package edu.wisc.resellbox_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.resellbox.ai.data.DamageMeasurement
import com.resellbox.ai.data.TFLiteDetector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.resellbox.ai/qnn"
    }

    private var detector: TFLiteDetector? = null
    private val damageMeasurement = DamageMeasurement()

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
                    val predictions = detectionResult.predictions
                    val measurementResult = damageMeasurement.measure(
                        bitmap,
                        predictions
                    )

                    val measurementList = if (measurementResult is com.resellbox.ai.data.MeasurementResult.Success) {
                        measurementResult.measurements
                    } else {
                        emptyList()
                    }
                    Log.i("MainActivity", "Measurement handoff: predictionCount=${predictions.size} measurementCount=${measurementList.size} resultType=${measurementResult::class.java.simpleName}")

                    val predictionMaps = predictions.mapIndexed { index, prediction ->
                        val measured = measurementList.getOrNull(index)
                        val widthCm = measured?.widthCm?.toDouble()
                        val heightCm = measured?.heightCm?.toDouble()
                        val longestSideCm = measured?.longestSideCm?.toDouble()
                        Log.i("MainActivity", "Measurement pass-through: index=$index class=${prediction.clazz} widthCm=$widthCm heightCm=$heightCm longestSideCm=$longestSideCm")
                        mapOf(
                            "x" to prediction.x.toDouble(),
                            "y" to prediction.y.toDouble(),
                            "width" to prediction.width.toDouble(),
                            "height" to prediction.height.toDouble(),
                            "class" to prediction.clazz,
                            "confidence" to prediction.confidence.toDouble(),
                            "widthCm" to widthCm,
                            "heightCm" to heightCm,
                            "longestSideCm" to longestSideCm,
                            "width_cm" to widthCm,
                            "height_cm" to heightCm,
                            "longest_side_cm" to longestSideCm
                        )
                    }

                    val measurementPayload = measurementPayload(measurementResult)

                    val verdict = if (predictionMaps.isEmpty()) {
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
                            "predictions" to predictionMaps,
                            "verdict" to verdict,
                            "scale_source" to "none",
                            "accelerator" to currentDetector.acceleratorName,
                            "measurement" to measurementPayload
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

    private fun measurementPayload(measurementResult: com.resellbox.ai.data.MeasurementResult): Map<String, Any?> {
        return when (measurementResult) {
            is com.resellbox.ai.data.MeasurementResult.Success -> {
                mapOf(
                    "found" to true,
                    "damageCount" to measurementResult.damageCount,
                    "calibration" to mapOf(
                        "found" to true,
                        "corners" to measurementResult.calibration.corners.map { point ->
                            mapOf(
                                "x" to point.x,
                                "y" to point.y
                            )
                        },
                        "cardWidthPixels" to measurementResult.calibration.cardWidthPixels,
                        "cardHeightPixels" to measurementResult.calibration.cardHeightPixels,
                        "pixelsPerCmX" to measurementResult.calibration.pixelsPerCmX,
                        "pixelsPerCmY" to measurementResult.calibration.pixelsPerCmY,
                        "quality" to measurementResult.calibration.quality,
                        "score" to measurementResult.calibration.score,
                        "perspectiveCorrected" to measurementResult.calibration.perspectiveCorrected,
                        "warnings" to measurementResult.calibration.warnings
                    ),
                    "measurements" to measurementResult.measurements.map { damage ->
                        mapOf(
                            "class" to damage.clazz,
                            "className" to damage.className,
                            "confidence" to damage.confidence,
                            "x" to damage.x,
                            "y" to damage.y,
                            "widthPixels" to damage.widthPixels,
                            "heightPixels" to damage.heightPixels,
                            "widthCm" to damage.widthCm,
                            "heightCm" to damage.heightCm,
                            "longestSideCm" to damage.longestSideCm,
                            "width_cm" to damage.widthCm,
                            "height_cm" to damage.heightCm,
                            "longest_side_cm" to damage.longestSideCm
                        )
                    }
                )
            }

            is com.resellbox.ai.data.MeasurementResult.CardNotFound -> {
                mapOf(
                    "found" to false,
                    "message" to measurementResult.message,
                    "candidates" to measurementResult.candidates,
                    "damageCount" to 0
                )
            }

            is com.resellbox.ai.data.MeasurementResult.LowCalibrationQuality -> {
                mapOf(
                    "found" to true,
                    "qualityWarning" to measurementResult.message,
                    "pixelsPerCmX" to measurementResult.calibration.pixelsPerCmX,
                    "pixelsPerCmY" to measurementResult.calibration.pixelsPerCmY,
                    "calibration" to mapOf(
                        "found" to true,
                        "quality" to measurementResult.calibration.quality,
                        "score" to measurementResult.calibration.score,
                        "cardWidthPixels" to measurementResult.calibration.cardWidthPixels,
                        "cardHeightPixels" to measurementResult.calibration.cardHeightPixels,
                        "corners" to measurementResult.calibration.corners.map { point ->
                            mapOf(
                                "x" to point.x,
                                "y" to point.y
                            )
                        }
                    )
                )
            }
        }
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
