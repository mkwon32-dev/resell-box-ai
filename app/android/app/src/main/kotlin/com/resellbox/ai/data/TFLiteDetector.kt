package com.resellbox.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
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

/**
 * On-device YOLOv8 TFLite detector.
 *
 * Delegate priority:
 * 1. Qualcomm QNN HTP delegate
 * 2. Android NNAPI
 * 3. CPU
 *
 * The detector supports FLOAT32, INT8, and UINT8 input/output tensors.
 * Preprocessing uses YOLO-style letterboxing.
 * Postprocessing restores coordinates and applies class-aware NMS.
 */
class TFLiteDetector(
    context: Context,
    modelAsset: String = "model.tflite",
    labelsAsset: String = "labels.txt",
    private val confThreshold: Float = 0.20f,
    private val iouThreshold: Float = 0.45f
) : AutoCloseable {

    sealed class Result {
        data class Ok(
            val predictions: List<Prediction>,
            val image: ImageSize
        ) : Result()

        data class Error(
            val message: String
        ) : Result()
    }

    private val labels: List<String> =
        context.assets.open(labelsAsset)
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private var nnapi: NnApiDelegate? = null
    private var qnnDelegate: Any? = null

    private val interpreter: Interpreter

    private val inW: Int
    private val inH: Int
    private val inType: DataType
    private val inScale: Float
    private val inZero: Int

    init {
        val model = loadModel(context, modelAsset)
        val options = Interpreter.Options()

        configureDelegate(context, options)

        interpreter = try {
            val createdInterpreter = Interpreter(model, options)

            when {
                qnnDelegate != null -> {
                    Log.i(
                        TAG,
                        "TFLite Interpreter created with QNN/HTP delegate"
                    )
                }

                nnapi != null -> {
                    Log.i(
                        TAG,
                        "TFLite Interpreter created with NNAPI delegate"
                    )
                }

                else -> {
                    Log.i(
                        TAG,
                        "TFLite Interpreter created with CPU"
                    )
                }
            }

            createdInterpreter
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "Interpreter creation with delegate failed. Falling back to CPU.",
                e
            )

            nnapi?.close()
            nnapi = null

            (qnnDelegate as? AutoCloseable)?.let {
                runCatching { it.close() }
            }
            qnnDelegate = null

            Interpreter(
                model,
                Interpreter.Options().setNumThreads(4)
            )
        }

        val inT = interpreter.getInputTensor(0)
        val shape = inT.shape()

        inH = shape[1]
        inW = shape[2]
        inType = inT.dataType()

        val q = inT.quantizationParams()

        inScale =
            if (q.scale == 0f) 1f
            else q.scale

        inZero = q.zeroPoint

        Log.i(
            TAG,
            "Detector ready: input=${inW}x${inH}, type=$inType, accelerator=$acceleratorName"
        )
    }

    fun detect(bitmap: Bitmap): Result {
        return try {
            val origW = bitmap.width
            val origH = bitmap.height

            // YOLO letterbox preprocessing.
            val scale = min(
                inW / origW.toFloat(),
                inH / origH.toFloat()
            )

            val newW = (origW * scale).roundToInt()
            val newH = (origH * scale).roundToInt()

            val padX = (inW - newW) / 2f
            val padY = (inH - newH) / 2f

            val resized = Bitmap.createScaledBitmap(
                bitmap,
                newW,
                newH,
                true
            )

            val canvasBmp = Bitmap.createBitmap(
                inW,
                inH,
                Bitmap.Config.ARGB_8888
            )

            android.graphics.Canvas(canvasBmp).apply {
                drawColor(
                    android.graphics.Color.rgb(
                        114,
                        114,
                        114
                    )
                )

                drawBitmap(
                    resized,
                    padX,
                    padY,
                    null
                )
            }

            val input = buildInputBuffer(canvasBmp)

            val outT = interpreter.getOutputTensor(0)

            val outBuf = ByteBuffer
                .allocateDirect(outT.numBytes())
                .order(ByteOrder.nativeOrder())

            interpreter.run(input, outBuf)

            val flat = readOutput(
                outBuf,
                outT
            )

            val preds = decodeYolo(
                flat = flat,
                shape = outT.shape(),
                scale = scale,
                padX = padX,
                padY = padY,
                origW = origW,
                origH = origH
            )

            val finalPredictions = nms(preds)

            Log.i(
                TAG,
                "Inference completed: accelerator=$acceleratorName, detections=${finalPredictions.size}"
            )

            Result.Ok(
                finalPredictions,
                ImageSize(origW, origH)
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Inference failed",
                e
            )

            Result.Error(
                "Inference error: ${e.message}"
            )
        }
    }

    private fun buildInputBuffer(
        bmp: Bitmap
    ): ByteBuffer {
        val pixels = IntArray(
            inW * inH
        )

        bmp.getPixels(
            pixels,
            0,
            inW,
            0,
            0,
            inW,
            inH
        )

        val buf = ByteBuffer
            .allocateDirect(
                interpreter
                    .getInputTensor(0)
                    .numBytes()
            )
            .order(ByteOrder.nativeOrder())

        for (p in pixels) {
            val r =
                ((p shr 16) and 0xFF) / 255f

            val g =
                ((p shr 8) and 0xFF) / 255f

            val b =
                (p and 0xFF) / 255f

            putValue(buf, r)
            putValue(buf, g)
            putValue(buf, b)
        }

        buf.rewind()

        return buf
    }

    private fun putValue(
        buf: ByteBuffer,
        real: Float
    ) {
        when (inType) {
            DataType.FLOAT32 -> {
                buf.putFloat(real)
            }

            DataType.UINT8 -> {
                val value =
                    (real / inScale + inZero)
                        .roundToInt()
                        .coerceIn(0, 255)

                buf.put(value.toByte())
            }

            DataType.INT8 -> {
                val value =
                    (real / inScale + inZero)
                        .roundToInt()
                        .coerceIn(-128, 127)

                buf.put(value.toByte())
            }

            else -> {
                buf.putFloat(real)
            }
        }
    }

    private fun readOutput(
        buf: ByteBuffer,
        tensor: org.tensorflow.lite.Tensor
    ): FloatArray {
        buf.rewind()

        val n = tensor.numElements()
        val out = FloatArray(n)

        val q = tensor.quantizationParams()

        when (tensor.dataType()) {
            DataType.FLOAT32 -> {
                for (i in 0 until n) {
                    out[i] = buf.float
                }
            }

            DataType.INT8 -> {
                val scale =
                    if (q.scale == 0f) 1f
                    else q.scale

                for (i in 0 until n) {
                    out[i] =
                        (buf.get().toInt() - q.zeroPoint) * scale
                }
            }

            DataType.UINT8 -> {
                val scale =
                    if (q.scale == 0f) 1f
                    else q.scale

                for (i in 0 until n) {
                    out[i] =
                        ((buf.get().toInt() and 0xFF) - q.zeroPoint) * scale
                }
            }

            else -> {
                for (i in 0 until n) {
                    out[i] = buf.float
                }
            }
        }

        return out
    }

    /**
     * Decodes YOLOv8 output.
     *
     * Supported layouts:
     * [1, 4 + classes, anchors]
     * [1, anchors, 4 + classes]
     */
    private fun decodeYolo(
        flat: FloatArray,
        shape: IntArray,
        scale: Float,
        padX: Float,
        padY: Float,
        origW: Int,
        origH: Int
    ): List<Prediction> {
        val attrs = 4 + labels.size

        val d1 = shape[1]
        val d2 = shape[2]

        val channelsFirst =
            d1 == attrs

        val anchors =
            if (channelsFirst) d2
            else d1

        fun at(
            anchor: Int,
            attr: Int
        ): Float {
            return if (channelsFirst) {
                flat[attr * anchors + anchor]
            } else {
                flat[anchor * attrs + attr]
            }
        }

        val result =
            ArrayList<Prediction>()

        for (a in 0 until anchors) {
            var bestClass = 0
            var bestScore = 0f

            for (c in labels.indices) {
                val score =
                    at(a, 4 + c)

                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore < confThreshold) {
                continue
            }

            var cx = at(a, 0)
            var cy = at(a, 1)
            var w = at(a, 2)
            var h = at(a, 3)

            if (
                cx <= 1.5f &&
                cy <= 1.5f &&
                w <= 1.5f &&
                h <= 1.5f
            ) {
                cx *= inW
                cy *= inH
                w *= inW
                h *= inH
            }

            val ox =
                (cx - padX) / scale

            val oy =
                (cy - padY) / scale

            val ow =
                w / scale

            val oh =
                h / scale

            if (
                ow <= 1f ||
                oh <= 1f
            ) {
                continue
            }

            result.add(
                Prediction(
                    x = ox.coerceIn(
                        0f,
                        origW.toFloat()
                    ),
                    y = oy.coerceIn(
                        0f,
                        origH.toFloat()
                    ),
                    width = ow,
                    height = oh,
                    confidence = bestScore,
                    clazz = labels[bestClass]
                )
            )
        }

        return result
    }

    /**
     * Class-aware Non-Maximum Suppression.
     */
    private fun nms(
        preds: List<Prediction>
    ): List<Prediction> {
        val sorted =
            preds
                .sortedByDescending {
                    it.confidence
                }
                .toMutableList()

        val keep =
            ArrayList<Prediction>()

        while (sorted.isNotEmpty()) {
            val best =
                sorted.removeAt(0)

            keep.add(best)

            sorted.removeAll {
                it.clazz == best.clazz &&
                    iou(best, it) > iouThreshold
            }
        }

        return keep
    }

    private fun iou(
        a: Prediction,
        b: Prediction
    ): Float {
        val x1 =
            max(a.left, b.left)

        val y1 =
            max(a.top, b.top)

        val x2 =
            min(a.right, b.right)

        val y2 =
            min(a.bottom, b.bottom)

        val inter =
            max(0f, x2 - x1) *
                max(0f, y2 - y1)

        val union =
            a.width * a.height +
                b.width * b.height -
                inter

        return if (union <= 0f) {
            0f
        } else {
            inter / union
        }
    }

    private fun loadModel(
        context: Context,
        asset: String
    ): MappedByteBuffer {
        val afd =
            context.assets.openFd(asset)

        FileInputStream(
            afd.fileDescriptor
        ).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    /**
     * Delegate priority:
     * 1. Qualcomm QNN HTP
     * 2. Android NNAPI
     * 3. CPU
     */
    private fun configureDelegate(
        context: Context,
        options: Interpreter.Options
    ) {
        if (
            tryAddQnnDelegate(
                context,
                options
            )
        ) {
            return
        }

        Log.w(
            TAG,
            "QNN delegate unavailable. Trying NNAPI."
        )

        if (!isEmulator()) {
            try {
                nnapi =
                    NnApiDelegate()

                options.addDelegate(
                    nnapi
                )

                Log.i(
                    TAG,
                    "NNAPI delegate added"
                )
            } catch (e: Throwable) {
                Log.e(
                    TAG,
                    "NNAPI delegate initialization failed. Using CPU.",
                    e
                )

                nnapi = null

                options.setNumThreads(4)
            }
        } else {
            Log.i(
                TAG,
                "Emulator detected. Using CPU."
            )

            options.setNumThreads(4)
        }
    }

    /**
     * Attempts to attach Qualcomm QNN TFLite Delegate
     * using the Java wrapper provided by qtld-release.aar.
     */
    private fun tryAddQnnDelegate(
        context: Context,
        options: Interpreter.Options
    ): Boolean {
        return try {
            Log.i(
                TAG,
                "Attempting to initialize QNN HTP delegate"
            )

            val delegateCls =
                Class.forName(
                    QNN_DELEGATE_CLASS
                )

            val optionsCls =
                Class.forName(
                    QNN_OPTIONS_CLASS
                )

            val opts =
                optionsCls
                    .getConstructor()
                    .newInstance()

            // Select Qualcomm HTP backend.
            setEnumOption(
                optionsCls = optionsCls,
                opts = opts,
                method = "setBackendType",
                enumClassName =
                    "$QNN_OPTIONS_CLASS\$BackendType",
                enumValue = "HTP_BACKEND"
            )

            // Run FLOAT32 model using FP16 precision on HTP when supported.
            setEnumOption(
                optionsCls = optionsCls,
                opts = opts,
                method = "setHtpPrecision",
                enumClassName =
                    "$QNN_OPTIONS_CLASS\$HtpPrecision",
                enumValue = "HTP_PRECISION_FP16"
            )

            // Tell QNN where packaged HTP Skel libraries are located.
            setStringOption(
                optionsCls = optionsCls,
                opts = opts,
                method = "setSkelLibraryDir",
                value =
                    context.applicationInfo.nativeLibraryDir
            )

            // QAIRT 2.48 exposes the HTP process-domain selector in the
            // delegate options API. The local docs keep the signed/unsigned
            // runtime semantics separate, and the SDK is packaged with an
            // unsigned HTP skel directory. Use the unsigned HTP PD session
            // for the current app-side deployment to match the unsigned skel
            // runtime packaging.
            setEnumOption(
                optionsCls = optionsCls,
                opts = opts,
                method = "setHtpPdSession",
                enumClassName =
                    "$QNN_OPTIONS_CLASS\$HtpPdSession",
                enumValue = "HTP_PD_SESSION_UNSIGNED"
            )

            val delegate =
                delegateCls
                    .getConstructor(
                        optionsCls
                    )
                    .newInstance(opts)

            options.addDelegate(
                delegate as org.tensorflow.lite.Delegate
            )

            qnnDelegate =
                delegate

            Log.i(
                TAG,
                "QNN delegate initialized successfully"
            )

            true
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "QNN delegate initialization failed",
                e
            )

            qnnDelegate = null

            false
        }
    }

    /**
     * Invokes an enum-based delegate option if available.
     */
    private fun setEnumOption(
        optionsCls: Class<*>,
        opts: Any,
        method: String,
        enumClassName: String,
        enumValue: String
    ) {
        try {
            val enumCls =
                Class.forName(
                    enumClassName
                )

            val value =
                enumCls
                    .getMethod(
                        "valueOf",
                        String::class.java
                    )
                    .invoke(
                        null,
                        enumValue
                    )

            optionsCls
                .getMethod(
                    method,
                    enumCls
                )
                .invoke(
                    opts,
                    value
                )

            Log.i(
                TAG,
                "QNN option configured: $method=$enumValue"
            )
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "QNN option skipped: $method (${e.javaClass.simpleName})"
            )
        }
    }

    /**
     * Invokes a String-based delegate option if available.
     */
    private fun setStringOption(
        optionsCls: Class<*>,
        opts: Any,
        method: String,
        value: String
    ) {
        try {
            optionsCls
                .getMethod(
                    method,
                    String::class.java
                )
                .invoke(
                    opts,
                    value
                )

            Log.i(
                TAG,
                "$method configured: $value"
            )
        } catch (e: Throwable) {
            Log.w(
                TAG,
                "QNN option skipped: $method (${e.javaClass.simpleName})"
            )
        }
    }

    private fun isEmulator(): Boolean {
        val fp =
            Build.FINGERPRINT ?: ""

        return fp.startsWith("generic") ||
            fp.contains(
                "emulator",
                true
            ) ||
            Build.MODEL.contains(
                "sdk_gphone",
                true
            ) ||
            Build.MODEL.contains(
                "Emulator",
                true
            ) ||
            Build.HARDWARE.contains(
                "ranchu",
                true
            ) ||
            Build.HARDWARE.contains(
                "goldfish",
                true
            ) ||
            Build.PRODUCT.contains(
                "sdk",
                true
            )
    }

    val usingNnapi: Boolean
        get() = nnapi != null

    val usingQnn: Boolean
        get() = qnnDelegate != null

    val acceleratorName: String
        get() = when {
            usingQnn -> "QNN/HTP"
            usingNnapi -> "NNAPI"
            else -> "CPU"
        }

    override fun close() {
        interpreter.close()

        nnapi?.close()

        (qnnDelegate as? AutoCloseable)?.let {
            runCatching {
                it.close()
            }
        }

        qnnDelegate = null
    }

    companion object {
        private const val TAG =
            "ResellBoxQNN"

        private const val QNN_DELEGATE_CLASS =
            "com.qualcomm.qti.QnnDelegate"

        private const val QNN_OPTIONS_CLASS =
            "com.qualcomm.qti.QnnDelegate\$Options"
    }
}
