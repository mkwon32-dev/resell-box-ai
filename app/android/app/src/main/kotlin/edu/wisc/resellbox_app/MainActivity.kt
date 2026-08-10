package edu.wisc.resellbox_app

import android.graphics.BitmapFactory
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

        detector = TFLiteDetector(applicationContext)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {
                "analyzeImage" -> {
                    val imagePath = call.argument<String>("imagePath")

                    if (imagePath.isNullOrBlank()) {
                        result.error(
                            "INVALID_ARGUMENT",
                            "imagePath is required",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    analyzeImage(imagePath, result)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun analyzeImage(
        imagePath: String,
        result: MethodChannel.Result
    ) {
        try {
            val bitmap = BitmapFactory.decodeFile(imagePath)

            if (bitmap == null) {
                result.error(
                    "IMAGE_ERROR",
                    "Failed to decode image: $imagePath",
                    null
                )
                return
            }

            val currentDetector = detector

            if (currentDetector == null) {
                result.error(
                    "DETECTOR_ERROR",
                    "TFLite detector is not initialized",
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
                            "accelerator" to if (currentDetector.usingNnapi) {
                                "NNAPI"
                            } else {
                                "CPU"
                            }
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

    override fun onDestroy() {
        detector?.close()
        detector = null
        super.onDestroy()
    }
}
