package edu.wisc.resellbox_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.resellbox.ai.data.TFLiteDetector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.resellbox.ai/qnn"
    }

    private var detector: TFLiteDetector? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val forceCpuOnly = System.getProperty("resellbox.force_cpu", "0") == "1"
        detector = TFLiteDetector(
            applicationContext,
            forceCpuOnly = forceCpuOnly
        )

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {
                "analyzeImage" -> {
                    val imagePath = call.argument<String>("imagePath")
                    val forceCpuOnly = call.argument<Boolean>("forceCpuOnly") ?: false

                    if (imagePath.isNullOrBlank()) {
                        result.error(
                            "INVALID_ARGUMENT",
                            "imagePath is required",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    analyzeImage(imagePath, result, forceCpuOnly)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun analyzeImage(
        imagePath: String,
        result: MethodChannel.Result,
        forceCpuOnly: Boolean = false
    ) {
        try {
            val currentDetector = resolveDetector(forceCpuOnly)
            if (currentDetector == null) {
                result.error(
                    "DETECTOR_ERROR",
                    "TFLite detector is not initialized",
                    null
                )
                return
            }

            val bitmap = decodeBitmapWithExif(imagePath)

            if (bitmap == null) {
                result.error(
                    "IMAGE_ERROR",
                    "Failed to decode image: $imagePath",
                    null
                )
                return
            }

            when (val detectionResult = currentDetector.detect(bitmap)) {

                is TFLiteDetector.Result.Ok -> {
                    val predictions = detectionResult.predictions.map { prediction ->
                        mapOf(
                            "x" to prediction.x.toDouble(),
                            "y" to prediction.y.toDouble(),
                            "width" to prediction.width.toDouble(),
                            "height" to prediction.height.toDouble(),
                            "class" to prediction.clazz,
                            "confidence" to prediction.confidence.toDouble()
                        )
                    }

                    val verdict = if (predictions.isEmpty()) {
                        "low"
                    } else {
                        "caution"
                    }

                    result.success(
                        mapOf(
                            "image" to mapOf(
                                "width" to detectionResult.image.width,
                                "height" to detectionResult.image.height
                            ),
                            "predictions" to predictions,
                            "verdict" to verdict,
                            "scale_source" to "none",
                            "accelerator" to currentDetector.acceleratorName
                        )
                    )
                }

                is TFLiteDetector.Result.Error -> {
                    result.error(
                        "INFERENCE_ERROR",
                        detectionResult.message,
                        null
                    )
                }
            }

        } catch (e: Exception) {
            result.error(
                "INFERENCE_ERROR",
                e.message ?: "Unknown inference error",
                null
            )
        }
    }

    private fun resolveDetector(forceCpuOnly: Boolean): TFLiteDetector? {
        val requestedCpuOnly = forceCpuOnly || System.getProperty("resellbox.force_cpu", "0") == "1"
        if (requestedCpuOnly && (detector == null || detector?.acceleratorName != "CPU")) {
            detector?.close()
            detector = TFLiteDetector(
                applicationContext,
                forceCpuOnly = true
            )
        }
        return detector
    }

    private fun decodeBitmapWithExif(imagePath: String): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) return null

        return try {
            val exif = ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(90f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(270f)
                }
                else -> Unit
            }

            if (orientation == ExifInterface.ORIENTATION_NORMAL ||
                orientation == ExifInterface.ORIENTATION_UNDEFINED
            ) {
                bitmap
            } else {
                val rotated = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )
                if (rotated != bitmap) bitmap.recycle()
                rotated
            }
        } catch (e: Exception) {
            bitmap
        }
    }

    override fun onDestroy() {
        detector?.close()
        detector = null
        super.onDestroy()
    }
}
