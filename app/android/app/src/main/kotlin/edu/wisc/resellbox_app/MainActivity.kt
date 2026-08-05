package edu.wisc.resellbox_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "com.resellbox.ai/qnn"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "analyzeImage" -> {
                    val imagePath = call.argument<String>("imagePath")

                    if (imagePath.isNullOrBlank()) {
                        result.error(
                            "INVALID_ARGUMENT",
                            "imagePath is required",
                            null,
                        )
                        return@setMethodCallHandler
                    }

                    result.success(
                        mapOf(
                            "image" to mapOf(
                                "width" to 1200,
                                "height" to 900,
                            ),
                            "predictions" to listOf(
                                mapOf(
                                    "x" to 420.0,
                                    "y" to 360.0,
                                    "width" to 240.0,
                                    "height" to 140.0,
                                    "class" to "dent",
                                    "confidence" to 0.92,
                                ),
                                mapOf(
                                    "x" to 820.0,
                                    "y" to 520.0,
                                    "width" to 210.0,
                                    "height" to 90.0,
                                    "class" to "surface_damage",
                                    "confidence" to 0.74,
                                ),
                            ),
                            "verdict" to "caution",
                            "scale_source" to "none",
                        ),
                    )
                }

                else -> result.notImplemented()
            }
        }
    }
}
