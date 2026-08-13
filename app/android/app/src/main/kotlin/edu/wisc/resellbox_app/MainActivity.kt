package edu.wisc.resellbox_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val damageMeasurement by lazy { DamageMeasurement(applicationContext) }

    // Single worker: analyses are serialized anyway (one detector instance),
    // and this keeps the heavy work off the main thread.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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

                    // Decode + inference + measurement take seconds. Method
                    // channel handlers run on the main thread, so doing this
                    // inline freezes the UI and risks an ANR; hop to a worker
                    // and post the reply back, since MethodChannel.Result must
                    // be answered on the main thread.
                    analysisExecutor.execute {
                        val reply = runAnalysis(imagePath, forceCpuOnly)
                        mainHandler.post { reply.deliver(result) }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    /** Outcome of a background analysis, replayed onto the main thread. */
    private sealed interface Reply {
        fun deliver(result: MethodChannel.Result)

        data class Ok(val payload: Map<String, Any?>) : Reply {
            override fun deliver(result: MethodChannel.Result) = result.success(payload)
        }

        data class Failure(val code: String, val message: String) : Reply {
            override fun deliver(result: MethodChannel.Result) =
                result.error(code, message, null)
        }
    }

    private fun runAnalysis(
        imagePath: String,
        forceCpuOnly: Boolean = false
    ): Reply {
        try {
            val currentDetector = resolveDetector(forceCpuOnly)
                ?: return Reply.Failure(
                    "DETECTOR_ERROR",
                    "TFLite detector is not initialized"
                )

            val bitmap = decodeBitmapWithExif(imagePath)
                ?: return Reply.Failure(
                    "IMAGE_ERROR",
                    "Failed to decode image: $imagePath"
                )

            return when (val detectionResult = currentDetector.detect(bitmap)) {

                is TFLiteDetector.Result.Ok -> {
                    val predictions = detectionResult.predictions
                    val outcome = damageMeasurement.measure(bitmap, predictions)
                    Log.i("MainActivity", "Measurement handoff: predictionCount=${predictions.size} scaleSource=${outcome.scaleSource}")

                    val predictionMaps = predictions.mapIndexed { index, prediction ->
                        val measured = outcome.measurements.getOrNull(index)
                        val widthCm = measured?.widthCm?.toDouble()
                        val heightCm = measured?.heightCm?.toDouble()
                        Log.i("MainActivity", "Measurement pass-through: index=$index class=${prediction.clazz} widthCm=$widthCm heightCm=$heightCm")
                        val map = mutableMapOf<String, Any?>(
                            "x" to prediction.x.toDouble(),
                            "y" to prediction.y.toDouble(),
                            "width" to prediction.width.toDouble(),
                            "height" to prediction.height.toDouble(),
                            "class" to prediction.clazz,
                            "confidence" to prediction.confidence.toDouble(),
                            "width_cm" to widthCm,
                            "height_cm" to heightCm
                        )
                        outcome.fallback?.measurements?.getOrNull(index)?.let { alt ->
                            map["fallback_width_cm"] = alt.widthCm?.toDouble()
                            map["fallback_height_cm"] = alt.heightCm?.toDouble()
                        }
                        map
                    }

                    // No verdict here: the Flutter side owns the risk rules and
                    // computes the verdict from classes + cm when it is absent.
                    val payload = mutableMapOf<String, Any?>(
                        "image" to mapOf(
                            "width" to detectionResult.image.width,
                            "height" to detectionResult.image.height
                        ),
                        "predictions" to predictionMaps,
                        "scale_source" to outcome.scaleSource,
                        "accelerator" to currentDetector.acceleratorName
                    )
                    // Detected card is a proposal: the UI draws the outline and
                    // asks the user; on rejection it swaps in the fallback cms.
                    if (outcome.scaleSource == "card" && outcome.cardCalibration != null) {
                        payload["card"] = mapOf(
                            "corners" to outcome.cardCalibration.corners.map {
                                mapOf("x" to it.x, "y" to it.y)
                            },
                            "fallback_scale_source" to (outcome.fallback?.scaleSource ?: "none")
                        )
                    }
                    Reply.Ok(payload)
                }

                is TFLiteDetector.Result.Error -> Reply.Failure(
                    "INFERENCE_ERROR",
                    detectionResult.message
                )
            }

        } catch (e: Exception) {
            return Reply.Failure(
                "INFERENCE_ERROR",
                e.message ?: "Unknown inference error"
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
        // Stop the worker before closing the detector: freeing the
        // interpreter under a running inference would crash native code.
        analysisExecutor.shutdown()
        val drained = try {
            analysisExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // teardown must not swallow it
            false
        }
        if (drained) {
            detector?.close()
        } else {
            // TFLite inference is uninterruptible native work, so shutdownNow
            // cannot guarantee the worker has stopped. Closing the interpreter
            // now would be the very crash this ordering exists to avoid; let
            // process teardown reclaim it instead.
            analysisExecutor.shutdownNow()
            Log.w("MainActivity", "Analysis still running at teardown; leaving detector to the process")
        }
        detector = null
        super.onDestroy()
    }
}
