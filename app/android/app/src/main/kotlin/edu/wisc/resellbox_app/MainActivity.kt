package edu.wisc.resellbox_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "com.resellbox.ai/qnn"

        // Coarse box_edge scale: the framed box's long edge is assumed to
        // fill the photo's long edge at the nominal 35 cm box length.
        private const val NOMINAL_BOX_CM = 35.0

        // Inference must fit in memory and NNAPI latency budgets; damage
        // detail below this resolution doesn't survive the 640px letterbox
        // anyway.
        private const val MAX_DECODE_DIM = 1536
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var detector: TFLiteDetector? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            if (call.method != "analyzeImage") {
                result.notImplemented()
                return@setMethodCallHandler
            }
            val imagePath = call.argument<String>("imagePath")
            if (imagePath.isNullOrBlank()) {
                result.error("INVALID_ARGUMENT", "imagePath is required", null)
                return@setMethodCallHandler
            }
            val conf = (call.argument<Number>("confidenceThreshold") ?: 0.60f).toFloat()
            val nms = (call.argument<Number>("nmsThreshold") ?: 0.50f).toFloat()

            executor.execute {
                val response = try {
                    analyze(imagePath, conf, nms)
                } catch (e: Exception) {
                    mainHandler.post {
                        result.error("INFERENCE_ERROR", e.message ?: e.javaClass.name, null)
                    }
                    return@execute
                }
                mainHandler.post { result.success(response) }
            }
        }
    }

    private fun analyze(imagePath: String, conf: Float, nms: Float): Map<String, Any> {
        val bitmap = decodeUpright(imagePath)
        val detector = this.detector
            ?: TFLiteDetector(applicationContext, confThreshold = conf, iouThreshold = nms)
                .also { this.detector = it }

        val predictions = detector.detect(bitmap)
        val cmPerPx = NOMINAL_BOX_CM / max(bitmap.width, bitmap.height)

        return mapOf(
            "image" to mapOf("width" to bitmap.width, "height" to bitmap.height),
            "predictions" to predictions.map { p ->
                mapOf(
                    "x" to p.x.toDouble(),
                    "y" to p.y.toDouble(),
                    "width" to p.width.toDouble(),
                    "height" to p.height.toDouble(),
                    "class" to p.clazz,
                    "confidence" to p.confidence.toDouble(),
                    "width_cm" to p.width * cmPerPx,
                    "height_cm" to p.height * cmPerPx,
                )
            },
            "scale_source" to "box_edge",
        )
    }

    /** Decode downscaled to [MAX_DECODE_DIM] and apply the EXIF rotation —
     *  camera JPEGs are stored sensor-oriented, and detection coordinates
     *  must match the upright image the app displays. */
    private fun decodeUpright(path: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Not a decodable image: $path")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_DIM) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalArgumentException("Not a decodable image: $path")

        val rotation = when (
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        executor.shutdown()
        detector?.close()
        detector = null
        super.onDestroy()
    }
}
