package edu.wisc.resellbox_app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One detection in original-image pixel coordinates (center x/y + size). */
data class Prediction(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val clazz: String,
) {
    val left: Float get() = x - width / 2f
    val top: Float get() = y - height / 2f
    val right: Float get() = x + width / 2f
    val bottom: Float get() = y + height / 2f
}

/**
 * On-device YOLO inference (ported from youngwon-kotlin-app/TFLiteDetector).
 *
 * - int8-quantized models get NNAPI (NPU) acceleration on real devices;
 *   emulators and delegate failures fall back to 4-thread CPU.
 * - float32 / int8 / uint8 tensors are handled by reading quantization
 *   params off the tensors themselves.
 * - Preprocessing is YOLO letterbox (aspect-preserving resize + gray pad);
 *   postprocessing restores original coordinates and runs class-aware NMS.
 *
 * Expects assets/model.tflite and assets/labels.txt (one class per line,
 * in the training data.yaml order).
 */
class TFLiteDetector(
    context: Context,
    modelAsset: String = "model.tflite",
    labelsAsset: String = "labels.txt",
    private val confThreshold: Float = 0.20f,
    private val iouThreshold: Float = 0.45f,
) : AutoCloseable {

    private val labels: List<String> =
        context.assets.open(labelsAsset).bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }

    private var nnapi: NnApiDelegate? = null
    private val interpreter: Interpreter

    private val inW: Int
    private val inH: Int
    private val inType: DataType
    private val inScale: Float
    private val inZero: Int

    init {
        val model = loadModel(context, modelAsset)
        val options = Interpreter.Options()
        if (!isEmulator()) {
            try {
                nnapi = NnApiDelegate()
                options.addDelegate(nnapi)
            } catch (_: Throwable) {
                nnapi = null
                options.setNumThreads(4)
            }
        } else {
            options.setNumThreads(4)
        }
        interpreter = try {
            Interpreter(model, options)
        } catch (_: Throwable) {
            nnapi?.close(); nnapi = null
            Interpreter(model, Interpreter.Options().setNumThreads(4))
        }

        val inT = interpreter.getInputTensor(0)
        val shape = inT.shape() // [1, H, W, 3]
        inH = shape[1]; inW = shape[2]
        inType = inT.dataType()
        val q = inT.quantizationParams()
        inScale = if (q.scale == 0f) 1f else q.scale
        inZero = q.zeroPoint
    }

    fun detect(bitmap: Bitmap): List<Prediction> {
        val origW = bitmap.width
        val origH = bitmap.height

        val scale = min(inW / origW.toFloat(), inH / origH.toFloat())
        val newW = (origW * scale).roundToInt()
        val newH = (origH * scale).roundToInt()
        val padX = (inW - newW) / 2f
        val padY = (inH - newH) / 2f

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val canvasBmp = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(canvasBmp).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            drawBitmap(resized, padX, padY, null)
        }

        val input = buildInputBuffer(canvasBmp)

        val outT = interpreter.getOutputTensor(0)
        val outBuf = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(input, outBuf)

        val flat = readOutput(outBuf, outT)
        val preds = decodeYolo(flat, outT.shape(), scale, padX, padY, origW, origH)
        return nms(preds)
    }

    private fun buildInputBuffer(bmp: Bitmap): ByteBuffer {
        val pixels = IntArray(inW * inH)
        bmp.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        val buf = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            putValue(buf, r); putValue(buf, g); putValue(buf, b)
        }
        buf.rewind()
        return buf
    }

    private fun putValue(buf: ByteBuffer, real: Float) {
        when (inType) {
            DataType.FLOAT32 -> buf.putFloat(real)
            DataType.UINT8 ->
                buf.put(((real / inScale + inZero).roundToInt().coerceIn(0, 255)).toByte())
            DataType.INT8 ->
                buf.put(((real / inScale + inZero).roundToInt().coerceIn(-128, 127)).toByte())
            else -> buf.putFloat(real)
        }
    }

    private fun readOutput(buf: ByteBuffer, tensor: org.tensorflow.lite.Tensor): FloatArray {
        buf.rewind()
        val n = tensor.numElements()
        val out = FloatArray(n)
        val q = tensor.quantizationParams()
        when (tensor.dataType()) {
            DataType.FLOAT32 -> for (i in 0 until n) out[i] = buf.float
            DataType.INT8 -> {
                val s = if (q.scale == 0f) 1f else q.scale
                for (i in 0 until n) out[i] = (buf.get().toInt() - q.zeroPoint) * s
            }
            DataType.UINT8 -> {
                val s = if (q.scale == 0f) 1f else q.scale
                for (i in 0 until n) out[i] = ((buf.get().toInt() and 0xFF) - q.zeroPoint) * s
            }
            else -> for (i in 0 until n) out[i] = buf.float
        }
        return out
    }

    /**
     * YOLO output decode; shape is [1, 4+nc, A] or [1, A, 4+nc], coordinates
     * either normalized 0..1 or already in input-size pixels.
     */
    private fun decodeYolo(
        flat: FloatArray, shape: IntArray,
        scale: Float, padX: Float, padY: Float,
        origW: Int, origH: Int,
    ): List<Prediction> {
        val attrs = 4 + labels.size
        val d1 = shape[1]; val d2 = shape[2]
        val channelsFirst = (d1 == attrs)
        val anchors = if (channelsFirst) d2 else d1

        fun at(anchor: Int, attr: Int): Float =
            if (channelsFirst) flat[attr * anchors + anchor] else flat[anchor * attrs + attr]

        val result = ArrayList<Prediction>()
        for (a in 0 until anchors) {
            var bestC = 0; var bestS = 0f
            for (c in labels.indices) {
                val s = at(a, 4 + c)
                if (s > bestS) { bestS = s; bestC = c }
            }
            if (bestS < confThreshold) continue

            var cx = at(a, 0); var cy = at(a, 1)
            var w = at(a, 2); var h = at(a, 3)
            if (cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f) {
                cx *= inW; cy *= inH; w *= inW; h *= inH
            }
            val ox = (cx - padX) / scale
            val oy = (cy - padY) / scale
            val ow = w / scale
            val oh = h / scale
            if (ow <= 1f || oh <= 1f) continue

            result.add(
                Prediction(
                    x = ox.coerceIn(0f, origW.toFloat()),
                    y = oy.coerceIn(0f, origH.toFloat()),
                    width = ow, height = oh,
                    confidence = bestS.coerceIn(0f, 1f),
                    clazz = labels[bestC],
                )
            )
        }
        return result
    }

    /** Class-aware NMS: only suppress overlaps within the same class. */
    private fun nms(preds: List<Prediction>): List<Prediction> {
        val sorted = preds.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<Prediction>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { it.clazz == best.clazz && iou(best, it) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: Prediction, b: Prediction): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun loadModel(context: Context, asset: String): MappedByteBuffer {
        val afd = context.assets.openFd(asset)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT ?: ""
        return fp.startsWith("generic") || fp.contains("emulator", true) ||
            Build.MODEL.contains("sdk_gphone", true) || Build.MODEL.contains("Emulator", true) ||
            Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true) ||
            Build.PRODUCT.contains("sdk", true)
    }

    override fun close() {
        interpreter.close()
        nnapi?.close()
    }
}
