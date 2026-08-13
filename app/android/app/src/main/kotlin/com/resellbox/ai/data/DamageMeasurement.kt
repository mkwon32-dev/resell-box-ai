package com.resellbox.ai.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android/OpenCV damage measurement pipeline layered on top of the existing
 * YOLO Prediction contract. DamageMeasurement accepts the original,
 * correctly-oriented Bitmap and a List<Prediction> that already live in original
 * image pixel coordinates.
 *
 * The intent is to first locate a reference card in the same physical image,
 * estimate a pixel-to-cm calibration, and then convert each YOLO bbox in the
 * original image to cm using that calibration.
 */
class DamageMeasurement(private val context: android.content.Context) {

    /**
     * Best-effort debug-image write to the app-private pictures dir
     * (Android/data/<pkg>/files/Pictures/resellbox_debug — needs no
     * permission on any Android version). Debug output must never fail an
     * analysis: the public Pictures dir is blocked by scoped storage and
     * throws EACCES in release builds.
     */
    private fun writeDebugBitmap(bitmap: Bitmap, fileName: String): String? = runCatching {
        // External files dir is null when external storage is unmounted;
        // fall back to internal storage so debug output still lands somewhere.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val dir = File(base, "resellbox_debug")
        dir.mkdirs()
        val outputFile = File(dir, fileName)
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        outputFile.absolutePath
    }.getOrElse {
        Log.w(TAG, "Debug image write failed: ${it.message}")
        null
    }

    companion object {
        private const val TAG = "DamageMeasurement"
        private var debugImageIndex = 0

        const val CARD_WIDTH_CM = 8.56f
        const val CARD_HEIGHT_CM = 5.398f
        const val CARD_ASPECT_RATIO = CARD_WIDTH_CM / CARD_HEIGHT_CM
        private const val CANONICAL_PX_PER_CM = 100.0

        const val MIN_CARD_AREA_RATIO = 0.003f
        const val MAX_CARD_AREA_RATIO = 0.35f

        // Wide, perspective-tolerant band: a card lying on a tilted box face
        // is foreshortened, so its apparent aspect can drift far from the
        // true 1.586 (the homography corrects the tilt when measuring).
        const val MIN_ASPECT_RATIO = 1.2f
        const val MAX_ASPECT_RATIO = 2.6f

        private const val ASPECT_SCORE_TOLERANCE = 0.18f

        // Acceptance gates lean on crispness rather than exact aspect: real
        // cards segment as clean convex 4-corner quads (rectangularity and
        // solidity near 1), while box art, tape and damage blobs do not. The
        // residual false positives (mostly card-shaped shoe-size labels) are
        // caught by the box-prior cross-check and the user confirmation step.
        private const val MIN_CARD_RECTANGULARITY = 0.85
        private const val MIN_CARD_SOLIDITY = 0.92
        private const val MIN_ACCEPT_SCORE = 0.60f

        // Hough-line quads are weaker evidence than contour quads, so they
        // stay pinned near the true card ratio.
        private const val MAX_LINE_QUAD_ASPECT_ERROR = 0.10

        // Cross-check: the card scale must imply a plausible sneaker-box size
        // when the box silhouette is also visible (real boxes are 33-37 cm).
        private const val IMPLIED_BOX_MIN_CM = 15.0
        private const val IMPLIED_BOX_MAX_CM = 60.0
    }

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCVLoader.initDebug() failed; preprocessing may still proceed if native libs are packaged")
        } else {
            Log.i(TAG, "OpenCV Android runtime initialized")
        }
    }

    private val boxFace = BoxFaceMeasurement()

    /**
     * Degradation ladder: a detected reference card is the best scale (exact
     * known dimensions); without one, fall back to the card-free box-face
     * pipeline (nominal box length), then its coarse box_edge tier, then none.
     */
    fun measure(
        bitmap: Bitmap,
        predictions: List<Prediction>
    ): MeasurementOutcome {
        val calibration = detectReferenceCard(bitmap)

        return when (calibration) {
            is CalibrationResult.Success -> {
                // Box-prior cross-check: with the card's px/cm, the visible box
                // silhouette must come out sneaker-box sized. A "card" that
                // implies an 8 cm or a 3 m box is a mis-detected rectangle
                // (box face, label) -- fall back to card-free measurement.
                val silhouetteLongPx = boxFace.silhouetteLongEdgeOriginalPx(bitmap)
                val impliedBoxCm = silhouetteLongPx?.let { it / calibration.pixelsPerCmLong }
                if (impliedBoxCm != null &&
                    (impliedBoxCm < IMPLIED_BOX_MIN_CM || impliedBoxCm > IMPLIED_BOX_MAX_CM)
                ) {
                    Log.w(TAG, "Card rejected by box-prior cross-check: impliedBoxCm=$impliedBoxCm (allowed $IMPLIED_BOX_MIN_CM..$IMPLIED_BOX_MAX_CM)")
                    val fallback = boxFace.measure(bitmap, predictions)
                    return MeasurementOutcome(fallback.scaleSource, fallback.measurements, null)
                }

                val homography = buildHomography(calibration.corners)
                val measurements = predictions.mapIndexed { index, prediction ->
                    val measurement = estimateDamageSize(prediction, calibration, homography)
                    Log.i(TAG, "Measurement per prediction: index=$index class=${measurement.className} widthCm=${measurement.widthCm} heightCm=${measurement.heightCm} longestSideCm=${measurement.longestSideCm}")
                    measurement
                }
                // The card still needs user confirmation in the UI, so also
                // compute the card-free measurements the app should fall back
                // to if the user rejects the detected card.
                val fallback = if (predictions.isEmpty()) null else boxFace.measure(bitmap, predictions)
                Log.i(TAG, "Measurement pipeline: scale_source=card measurementCount=${measurements.size} fallback=${fallback?.scaleSource}")
                MeasurementOutcome("card", measurements, calibration, fallback)
            }

            is CalibrationResult.CardNotFound,
            is CalibrationResult.LowCalibrationQuality -> {
                val reason = if (calibration is CalibrationResult.CardNotFound) {
                    "CardNotFound(${calibration.message})"
                } else {
                    "LowCalibrationQuality"
                }
                val fallback = boxFace.measure(bitmap, predictions)
                Log.i(TAG, "Measurement pipeline: card unusable ($reason), box-face fallback scale_source=${fallback.scaleSource}")
                MeasurementOutcome(fallback.scaleSource, fallback.measurements, null)
            }
        }
    }

    private fun estimateDamageSize(
        prediction: Prediction,
        calibration: CalibrationResult.Success,
        homography: Mat?
    ): DamageSize {
        val measurement = if (homography != null && !homography.empty()) {
            measurePredictionOnCardPlane(prediction, homography)
        } else {
            val pxPerCmLong = calibration.pixelsPerCmLong
            val pxPerCmShort = calibration.pixelsPerCmShort
            val angle = calibration.orientationAngleDegrees

            val longAxisHorizontal = abs(kotlin.math.cos(Math.toRadians(angle.toDouble()))) >= abs(kotlin.math.sin(Math.toRadians(angle.toDouble())))

            val widthScale = if (longAxisHorizontal) pxPerCmLong else pxPerCmShort
            val heightScale = if (longAxisHorizontal) pxPerCmShort else pxPerCmLong

            val widthCm = if (widthScale > 0f) {
                prediction.width / widthScale
            } else null

            val heightCm = if (heightScale > 0f) {
                prediction.height / heightScale
            } else null

            DamageSize(
                clazz = prediction.clazz,
                confidence = prediction.confidence,
                widthPixels = prediction.width,
                heightPixels = prediction.height,
                x = prediction.x,
                y = prediction.y,
                widthCm = widthCm,
                heightCm = heightCm,
                pixelsPerCmX = widthScale,
                pixelsPerCmY = heightScale,
            )
        }

        Log.i(
            TAG,
            "Damage measurement result: class=${measurement.className} confidence=${measurement.confidence} widthCm=${measurement.widthCm} heightCm=${measurement.heightCm} longestSideCm=${measurement.longestSideCm}"
        )
        return measurement
    }

    private fun detectReferenceCard(bitmap: Bitmap): CalibrationResult {
        val width = bitmap.width
        val height = bitmap.height

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val blurred = Mat()
        Imgproc.GaussianBlur(enhanced, blurred, Size(5.0, 5.0), 0.0)

        val filtered = Mat()
        Imgproc.bilateralFilter(blurred, filtered, 9, 60.0, 60.0)

        val edges = Mat()
        Imgproc.Canny(filtered, edges, 15.0, 70.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
        repeat(4) {
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        }
        saveDebugMat(edges, nextDebugFileName("card_edges_debug"))

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        Log.i(TAG, "Calibration: image=${width}x${height}, contourCount=${contours.size}")

        var candidateLogCount = 0
        val imageArea = width.toDouble() * height.toDouble()

        val edgeCandidates = evaluateCandidateContours(
            contours = contours,
            sourcePrefix = "EDGE",
            width = width,
            height = height,
            imageArea = imageArea,
            candidateLogCount = candidateLogCount
        )
        candidateLogCount = edgeCandidates.logCount

        val edgeMask = buildRegionMask(enhanced)
        saveDebugMat(edgeMask, nextDebugFileName("card_binary_debug"))
        val regionContours = mutableListOf<MatOfPoint>()
        val regionHierarchy = Mat()
        Imgproc.findContours(
            edgeMask,
            regionContours,
            regionHierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        val regionCandidates = evaluateCandidateContours(
            contours = regionContours,
            sourcePrefix = "REGION",
            width = width,
            height = height,
            imageArea = imageArea,
            candidateLogCount = candidateLogCount
        )
        candidateLogCount = regionCandidates.logCount

        val lineCandidates = evaluateLineQuadCandidates(
            edges = edges,
            width = width,
            height = height,
            imageArea = imageArea,
            candidateLogCount = candidateLogCount,
            bitmap = bitmap
        )
        candidateLogCount = lineCandidates.logCount

        val satCandidates = evaluateCandidateContours(
            contours = satMaskContours(src, width, height),
            sourcePrefix = "SAT",
            width = width,
            height = height,
            imageArea = imageArea,
            candidateLogCount = candidateLogCount
        )
        candidateLogCount = satCandidates.logCount

        Log.i(
            TAG,
            "Calibration candidate generation by source: edgeAccepted=${edgeCandidates.accepted.size} edgeEvaluated=${edgeCandidates.evaluated.size} regionAccepted=${regionCandidates.accepted.size} regionEvaluated=${regionCandidates.evaluated.size} lineAccepted=${lineCandidates.accepted.size} lineEvaluated=${lineCandidates.evaluated.size}"
        )

        val candidateResults = edgeCandidates.accepted.toMutableList()
        candidateResults.addAll(regionCandidates.accepted)
        candidateResults.addAll(lineCandidates.accepted)
        candidateResults.addAll(satCandidates.accepted)
        val evaluatedCandidates = edgeCandidates.evaluated.toMutableList()
        evaluatedCandidates.addAll(regionCandidates.evaluated)
        evaluatedCandidates.addAll(lineCandidates.evaluated)
        evaluatedCandidates.addAll(satCandidates.evaluated)
        val rejectedNearMisses = edgeCandidates.rejected.toMutableList()
        rejectedNearMisses.addAll(regionCandidates.rejected)
        rejectedNearMisses.addAll(lineCandidates.rejected)
        rejectedNearMisses.addAll(satCandidates.rejected)

        val mergedAccepted = deduplicateCandidates(candidateResults)
        val mergedEvaluated = deduplicateCandidates(evaluatedCandidates)

        Log.i(TAG, "Calibration candidate generation: edgeContours=${contours.size} edgeSeriousCandidates=${edgeCandidates.evaluated.size} regionContours=${regionContours.size} regionSeriousCandidates=${regionCandidates.evaluated.size} mergedCandidates=${mergedEvaluated.size}")

        val sorted = mergedAccepted.sortedByDescending { it.score }
        val rankedEvaluations = mergedEvaluated.sortedByDescending { it.score }
        val rankedRejected = rejectedNearMisses.sortedByDescending { it.rectangularity * it.solidity }
        rankedEvaluations.take(5).forEachIndexed { index, candidate ->
            val aspectError = abs(candidate.aspect.toDouble() - CARD_ASPECT_RATIO.toDouble()) / CARD_ASPECT_RATIO.toDouble()
            Log.i(
                TAG,
                "Calibration rank=${index + 1} source=${candidate.source} areaRatio=${candidate.areaRatio} aspectRatio=${candidate.aspect} aspectError=$aspectError rectangularity=${candidate.rectangularity} convexity=${candidate.convexity} approxPolygonPointCount=${candidate.approxPointCount} score=${candidate.score} corners=${candidate.points.map { "${it.x},${it.y}" }}"
            )
        }
        rankedRejected.take(5).forEachIndexed { index, candidate ->
            Log.i(
                TAG,
                "Calibration rejectedNearMiss rank=${index + 1} areaRatio=${candidate.areaRatio} aspectRatio=${candidate.aspect} rectangularity=${candidate.rectangularity} solidity=${candidate.solidity} approxPolygonPointCount=${candidate.approxPointCount} longSide=${candidate.longSide} shortSide=${candidate.shortSide} reason=${candidate.rejectionReason} corners=${candidate.points.map { "${it.x},${it.y}" }}"
            )
        }
        Log.i(TAG, "Calibration candidate summary: acceptedCount=${sorted.size} rejectedNearMissCount=${rejectedNearMisses.size}")
        Log.i(TAG, "Calibration: plausibleCardCandidates=${sorted.size}")

        val bestCandidateForDebug = sorted.firstOrNull()
        val candidateDebugImagePath = saveCardCandidatesDebugImage(
            bitmap,
            mergedEvaluated,
            rejectedNearMisses,
            bestCandidateForDebug,
            nextDebugFileName("card_candidates_debug")
        )
        Log.i(TAG, "Card candidate debug image saved to: $candidateDebugImagePath")
        Log.i(TAG, "Card candidate debug image contains ${evaluatedCandidates.size + rejectedNearMisses.size} evaluated candidates")

        if (sorted.isEmpty()) {
            Log.w(TAG, "Calibration: CardNotFound -> no plausible card contour found after preprocessing and scoring; contourCount=${contours.size}; imageArea=${imageArea}")
            return CalibrationResult.CardNotFound(
                message = "No plausible card contour found",
                candidates = 0
            )
        }

        val best = sorted.first()

        val corner = best.points
        val sideA = distance(corner[0], corner[1])
        val sideB = distance(corner[1], corner[2])
        val measuredSideA = sideA
        val measuredSideB = sideB
        val longSidePixels = max(measuredSideA, measuredSideB)
        val shortSidePixels = min(measuredSideA, measuredSideB)

        val longSidePixelsF = longSidePixels.toFloat()
        val shortSidePixelsF = shortSidePixels.toFloat()

        if (longSidePixels <= 0.0 || shortSidePixels <= 0.0) {
            Log.w(TAG, "Calibration selected candidate invalid: score=${best.score} areaRatio=${best.areaRatio} aspectRatio=${best.aspect} rectangularity=${best.rectangularity} convexity=${best.convexity} source=${best.source} approxPolygonPointCount=${best.approxPointCount}")
            return CalibrationResult.CardNotFound(
                message = "Selected card candidate has invalid dimension geometry",
                candidates = sorted.size
            )
        }

        val longSideAngle = atan2(corner[1].y - corner[0].y, corner[1].x - corner[0].x)
        val orientationAngleDegrees = Math.toDegrees(longSideAngle).toFloat()

        val pxPerCmLong = longSidePixelsF / CARD_WIDTH_CM
        val pxPerCmShort = shortSidePixelsF / CARD_HEIGHT_CM

        val scaleDiff = abs(pxPerCmLong.toDouble() - pxPerCmShort.toDouble()) / max(pxPerCmLong.toDouble(), pxPerCmShort.toDouble())
        val scaleDiffPercent = scaleDiff * 100.0
        val calibrationQuality = (clamp(
            (1.0f - scaleDiff.toFloat() * 3.0f),
            0.0f,
            1.0f
        ) * best.score).toFloat()

        val homography = buildHomography(corner)
        val homographySuccess = homography != null && !homography.empty()
        val canonicalWidthPx = CARD_WIDTH_CM * CANONICAL_PX_PER_CM
        val canonicalHeightPx = CARD_HEIGHT_CM * CANONICAL_PX_PER_CM
        val debugImagePath = saveSelectedCardDebugImage(bitmap, corner, best.source, best.aspect, calibrationQuality, orientationAngleDegrees)
        val finalCandidateDebugImagePath = saveCardCandidatesDebugImage(bitmap, evaluatedCandidates, rejectedNearMisses, best, nextDebugFileName("card_candidates_debug"))
        Log.i(TAG, "Card candidate debug image saved to: $finalCandidateDebugImagePath")
        Log.i(TAG, "Card candidate debug image contains ${evaluatedCandidates.size + rejectedNearMisses.size} evaluated candidates")
        val warpedImagePath = if (homographySuccess && homography != null) {
            saveWarpedCardDebugImage(bitmap, homography, canonicalWidthPx.toInt(), canonicalHeightPx.toInt())
        } else null

        Log.i(TAG, "Calibration dimension summary: measuredSideA=$measuredSideA measuredSideB=$measuredSideB longSidePixels=$longSidePixels shortSidePixels=$shortSidePixels orientationAngle=$orientationAngleDegrees resultingScales=pxPerCmLong=$pxPerCmLong pxPerCmShort=$pxPerCmShort")
        Log.i(TAG, "Calibration selected: score=${best.score}, areaRatio=${best.areaRatio}, aspectRatio=${best.aspect}, rectangularity=${best.rectangularity}, convexity=${best.convexity}, approxPolygonPointCount=${best.approxPointCount}, source=${best.source}, corners=${corner.map { "${it.x},${it.y}" }}, cardWidthPixels=$longSidePixelsF, cardHeightPixels=$shortSidePixelsF, pixelsPerCmX=$pxPerCmLong, pixelsPerCmY=$pxPerCmShort, scaleDiff=$scaleDiff, scaleDiffPercent=$scaleDiffPercent, quality=$calibrationQuality, canonicalWidthPx=$canonicalWidthPx, canonicalHeightPx=$canonicalHeightPx, homographyCreated=$homographySuccess, selectedCardDebugPath=$debugImagePath, cardCandidatesDebugPath=$finalCandidateDebugImagePath, selectedCardWarpedPath=$warpedImagePath")

        // No scaleDiff-based quality gate: pxPerCmLong/Short necessarily
        // disagree for a tilted card, and the homography measurement path
        // corrects tilt anyway. Bad quads are filtered by crispness gates,
        // the box-prior cross-check, and finally user confirmation.
        if (!homographySuccess || longSidePixels < 20.0 || shortSidePixels < 10.0) {
            return CalibrationResult.LowCalibrationQuality(
                message = "Reference card calibration quality is below threshold",
                calibration = CalibrationResult.Success(
                    found = true,
                    corners = corner,
                    cardWidthPixels = longSidePixelsF,
                    cardHeightPixels = shortSidePixelsF,
                    pixelsPerCmX = pxPerCmLong,
                    pixelsPerCmY = pxPerCmShort,
                    pixelsPerCmLong = pxPerCmLong,
                    pixelsPerCmShort = pxPerCmShort,
                    longSidePixels = longSidePixelsF,
                    shortSidePixels = shortSidePixelsF,
                    orientationAngleDegrees = orientationAngleDegrees,
                    score = best.score,
                    quality = calibrationQuality,
                    perspectiveCorrected = true,
                    warnings = listOf("Reference card detected but calibration quality is low")
                )
            )
        }

        Log.i(TAG, "Calibration accepted for measurement: quality=${calibrationQuality} threshold=0.15 predictionCount=${sorted.size}")

        return CalibrationResult.Success(
            found = true,
            corners = corner,
            cardWidthPixels = longSidePixelsF,
            cardHeightPixels = shortSidePixelsF,
            pixelsPerCmX = pxPerCmLong,
            pixelsPerCmY = pxPerCmShort,
            pixelsPerCmLong = pxPerCmLong,
            pixelsPerCmShort = pxPerCmShort,
            longSidePixels = longSidePixelsF,
            shortSidePixels = shortSidePixelsF,
            orientationAngleDegrees = orientationAngleDegrees,
            score = best.score,
            quality = calibrationQuality,
            perspectiveCorrected = true,
            warnings = emptyList()
        )
    }

    private fun buildHomography(corners: List<Point>): Mat? {
        if (corners.size < 4) return null

        val orderedCorners = reorderCornersForHomography(corners)
        if (orderedCorners.size < 4) return null

        val src = MatOfPoint2f(
            orderedCorners[0],
            orderedCorners[1],
            orderedCorners[2],
            orderedCorners[3]
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(CARD_WIDTH_CM * CANONICAL_PX_PER_CM.toDouble(), 0.0),
            Point(CARD_WIDTH_CM * CANONICAL_PX_PER_CM.toDouble(), CARD_HEIGHT_CM * CANONICAL_PX_PER_CM.toDouble()),
            Point(0.0, CARD_HEIGHT_CM * CANONICAL_PX_PER_CM.toDouble())
        )

        return Imgproc.getPerspectiveTransform(src, dst)
    }

    private fun reorderCornersForHomography(corners: List<Point>): List<Point> {
        val ordered = corners.toMutableList()
        if (ordered.size != 4) return ordered

        val edge01 = distance(ordered[0], ordered[1])
        val edge12 = distance(ordered[1], ordered[2])
        val edge23 = distance(ordered[2], ordered[3])
        val edge30 = distance(ordered[3], ordered[0])

        val longestEdge = maxOf(edge01, edge12, edge23, edge30)
        return when (longestEdge) {
            edge01 -> ordered
            edge12 -> listOf(ordered[1], ordered[2], ordered[3], ordered[0])
            edge23 -> listOf(ordered[2], ordered[3], ordered[0], ordered[1])
            else -> listOf(ordered[3], ordered[0], ordered[1], ordered[2])
        }
    }

    private fun transformPoints(points: Array<Point>, homography: Mat): Array<Point> {
        if (homography.empty()) return points
        val source = MatOfPoint2f(*points)
        val destination = MatOfPoint2f()
        Core.perspectiveTransform(source, destination, homography)
        return destination.toArray()
    }

    private fun measurePredictionOnCardPlane(prediction: Prediction, homography: Mat): DamageSize {
        val halfWidth = prediction.width / 2.0f
        val halfHeight = prediction.height / 2.0f
        val centerX = prediction.x
        val centerY = prediction.y

        val originalCorners = arrayOf(
            Point((centerX - halfWidth).toDouble(), (centerY - halfHeight).toDouble()),
            Point((centerX + halfWidth).toDouble(), (centerY - halfHeight).toDouble()),
            Point((centerX + halfWidth).toDouble(), (centerY + halfHeight).toDouble()),
            Point((centerX - halfWidth).toDouble(), (centerY + halfHeight).toDouble())
        )

        val transformedCorners = transformPoints(originalCorners, homography)
        if (transformedCorners.isEmpty()) {
            return DamageSize(
                clazz = prediction.clazz,
                confidence = prediction.confidence,
                widthPixels = prediction.width,
                heightPixels = prediction.height,
                x = prediction.x,
                y = prediction.y,
                widthCm = null,
                heightCm = null,
                pixelsPerCmX = null,
                pixelsPerCmY = null,
            )
        }

        val topEdgeLength = distance(transformedCorners[0], transformedCorners[1])
        val bottomEdgeLength = distance(transformedCorners[2], transformedCorners[3])
        val leftEdgeLength = distance(transformedCorners[3], transformedCorners[0])
        val rightEdgeLength = distance(transformedCorners[1], transformedCorners[2])
        val widthOnPlanePx = (topEdgeLength + bottomEdgeLength) / 2.0
        val heightOnPlanePx = (leftEdgeLength + rightEdgeLength) / 2.0

        val widthCm = (widthOnPlanePx / CANONICAL_PX_PER_CM).toFloat()
        val heightCm = (heightOnPlanePx / CANONICAL_PX_PER_CM).toFloat()

        // MVP measurement uses the YOLO bbox as the physical proxy for size; a contour-based
        // scratch-length measure is intentionally still out of scope for this pass.
        Log.i(
            TAG,
            "Damage measurement: class=${prediction.clazz} confidence=${prediction.confidence} originalBBox=${prediction.x},${prediction.y},${prediction.width},${prediction.height} transformedCorners=${transformedCorners.map { "${it.x},${it.y}" }} top=$topEdgeLength bottom=$bottomEdgeLength left=$leftEdgeLength right=$rightEdgeLength widthCm=$widthCm heightCm=$heightCm"
        )

        return DamageSize(
            clazz = prediction.clazz,
            confidence = prediction.confidence,
            widthPixels = prediction.width,
            heightPixels = prediction.height,
            x = prediction.x,
            y = prediction.y,
            widthCm = widthCm,
            heightCm = heightCm,
            pixelsPerCmX = null,
            pixelsPerCmY = null,
        )
    }

    private fun evaluateCandidateContours(
        contours: List<MatOfPoint>,
        sourcePrefix: String,
        width: Int,
        height: Int,
        imageArea: Double,
        candidateLogCount: Int
    ): CandidateEvaluationResult {
        val accepted = mutableListOf<CardCandidate>()
        val evaluated = mutableListOf<CardCandidate>()
        val rejected = mutableListOf<RejectedCandidate>()
        var logCount = candidateLogCount

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area <= 50.0) continue

            val rect = Imgproc.boundingRect(contour)
            val rectArea = rect.width.toDouble() * rect.height.toDouble()
            if (rectArea <= 0.0) continue

            val areaRatio = area / imageArea
            if (areaRatio < MIN_CARD_AREA_RATIO.toDouble() || areaRatio > MAX_CARD_AREA_RATIO.toDouble()) {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio outside allowed range min=${MIN_CARD_AREA_RATIO} max=${MAX_CARD_AREA_RATIO} source=$sourcePrefix")
                }
                continue
            }

            val contour2f = MatOfPoint2f()
            contour2f.fromArray(*contour.toArray())

            val box = Imgproc.minAreaRect(contour2f)
            val size = box.size
            val longSide = max(size.width, size.height)
            val shortSide = min(size.width, size.height)
            if (longSide <= 0.0 || shortSide <= 0.0) {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio invalid longSide=$longSide shortSide=$shortSide source=$sourcePrefix")
                }
                continue
            }

            val aspect = longSide / shortSide
            if (aspect < MIN_ASPECT_RATIO.toDouble() || aspect > MAX_ASPECT_RATIO.toDouble()) {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio aspectRatio=$aspect outside allowed range min=${MIN_ASPECT_RATIO} max=${MAX_ASPECT_RATIO} source=$sourcePrefix")
                }
                continue
            }

            val minAreaRectArea = widthToArea(box)
            val rectangularity = if (minAreaRectArea > 0.0) area / minAreaRectArea else 0.0
            if (rectangularity < 0.45) {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio rectangularity=$rectangularity below 0.45 source=$sourcePrefix")
                }
                continue
            }

            val hullIndices = MatOfInt()
            Imgproc.convexHull(contour, hullIndices)
            val contourPoints = contour.toArray()
            val hullPoints = hullIndices.toArray().map { contourPoints[it] }.toTypedArray()
            val hull = MatOfPoint()
            hull.fromArray(*hullPoints)
            val hullArea = Imgproc.contourArea(hull)
            val solidity = if (hullArea > 0.0) area / hullArea else 0.0
            if (solidity < 0.70) {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio solidity=$solidity below 0.70 source=$sourcePrefix")
                }
                continue
            }

            val approx = MatOfPoint2f()
            val perimeter = Imgproc.arcLength(contour2f, true)
            if (perimeter > 0.0) {
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
            }

            val points = approx.toArray()
            val approxPointCount = points.size
            val quadCandidate = approxPointCount == 4
            val candidateAspectError = abs(aspect - CARD_ASPECT_RATIO.toDouble()) / CARD_ASPECT_RATIO.toDouble()

            // Only crisp convex 4-corner quads may calibrate; blob and
            // minAreaRect fallbacks matched box faces and labels far too
            // often.
            val acceptable = quadCandidate &&
                rectangularity >= MIN_CARD_RECTANGULARITY &&
                solidity >= MIN_CARD_SOLIDITY &&
                longSide > 20.0 &&
                shortSide > 10.0

            if (!acceptable) {
                val rejectionReason = when {
                    !quadCandidate -> "not a 4-corner quad"
                    rectangularity < MIN_CARD_RECTANGULARITY -> "rectangularity below threshold"
                    solidity < MIN_CARD_SOLIDITY -> "solidity below threshold"
                    else -> "minAreaRect dimensions too small"
                }
                rejected.add(
                    RejectedCandidate(
                        areaRatio = areaRatio.toFloat(),
                        aspect = aspect.toFloat(),
                        rectangularity = rectangularity.toFloat(),
                        solidity = solidity.toFloat(),
                        approxPointCount = approxPointCount,
                        longSide = longSide.toFloat(),
                        shortSide = shortSide.toFloat(),
                        rejectionReason = "$sourcePrefix:$rejectionReason",
                        points = orderPoints(points)
                    )
                )
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio aspectRatio=$aspect aspectError=$candidateAspectError rectangularity=$rectangularity solidity=$solidity approxPolygonPointCount=$approxPointCount longSide=$longSide shortSide=$shortSide source=$sourcePrefix reason=$rejectionReason")
                }
                continue
            }

            if (areaRatio >= 0.005 && logCount < 30) {
                logCount++
                Log.i(
                    TAG,
                    "CandidateEvalLog#$logCount: source=$sourcePrefix areaRatio=$areaRatio aspectRatio=$aspect aspectError=$candidateAspectError rectangularity=$rectangularity solidity=$solidity approxPolygonPointCount=$approxPointCount longSide=$longSide shortSide=$shortSide"
                )
            }

            val longSideRatio = longSide / width.toDouble()
            val shortSideRatio = shortSide / height.toDouble()
            val sizeQuality = when {
                longSideRatio < 0.08 || shortSideRatio < 0.05 -> 0.0
                longSideRatio > 0.55 || shortSideRatio > 0.45 -> 0.0
                else -> 1.0
            }

            val aspectScore = clamp((1.0f - (candidateAspectError.toFloat() / ASPECT_SCORE_TOLERANCE)).toFloat(), 0.0f, 1.0f)
            val rectangularityScore = clamp(rectangularity.toFloat(), 0.0f, 1.0f).toDouble()
            val areaScore = clamp((areaRatio / 0.04).toFloat(), 0.0f, 1.0f).toDouble()
            val solidityScore = clamp(solidity.toFloat(), 0.0f, 1.0f).toDouble()

            val scoreRaw = (
                aspectScore * 0.42f +
                    rectangularityScore * 0.20 +
                    areaScore * 0.16 +
                    sizeQuality * 0.08 +
                    solidityScore * 0.12 +
                    0.22f
                )
            val score = scoreRaw.toFloat()

            val orderedPoints = orderPoints(points)

            val borderSensitive = sourcePrefix == "REGION" || sourcePrefix == "SAT"
            val regionBorderPadding = if (borderSensitive) max(24, min(width, height) / 40) else 0
            if (borderSensitive && isNearImageBorder(orderedPoints, width, height, regionBorderPadding)) {
                rejected.add(
                    RejectedCandidate(
                        areaRatio = areaRatio.toFloat(),
                        aspect = aspect.toFloat(),
                        rectangularity = rectangularity.toFloat(),
                        solidity = solidity.toFloat(),
                        approxPointCount = approxPointCount,
                        longSide = longSide.toFloat(),
                        shortSide = shortSide.toFloat(),
                        rejectionReason = "$sourcePrefix:border-touching",
                        points = orderedPoints
                    )
                )
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio source=$sourcePrefix region candidate touches image border width=$width height=$height")
                }
                continue
            }

            val candidate = CardCandidate(
                score = score,
                contourArea = area.toFloat(),
                areaRatio = areaRatio.toFloat(),
                aspect = aspect.toFloat(),
                rectangularity = rectangularity.toFloat(),
                convexity = solidity.toFloat(),
                points = orderedPoints,
                widthPixels = longSide.toFloat(),
                heightPixels = shortSide.toFloat(),
                source = "${sourcePrefix}_QUAD",
                approxPointCount = approxPointCount
            )

            evaluated.add(candidate)

            if (score > MIN_ACCEPT_SCORE) {
                accepted.add(candidate)
            } else {
                if (areaRatio >= 0.005 && logCount < 30) {
                    logCount++
                    Log.w(TAG, "Reject contour: areaRatio=$areaRatio low score=$score below $MIN_ACCEPT_SCORE source=$sourcePrefix")
                }
            }
        }

        return CandidateEvaluationResult(
            accepted = accepted,
            evaluated = evaluated,
            rejected = rejected,
            logCount = logCount
        )
    }

    /**
     * Low-saturation bright regions — a whitish card on a colored box face.
     * Separates card from lid by color where grayscale luminance cannot
     * (dark scenes, red/blue lids). Runs at a normalized resolution so the
     * morphology kernel sizes mean the same thing on every camera; contours
     * are scaled back to original pixels. Two variants:
     *   gentle  open9 + close9 — card clear of any white print
     *   split   close25 then open41 — card touching thin white print; the
     *           close solidifies the card over its own stripe/text, the big
     *           open cuts thin letter-stroke bridges to the box logo.
     */
    private fun satMaskContours(rgba: Mat, width: Int, height: Int): List<MatOfPoint> {
        val satScale = min(1.0, 1600.0 / max(width, height))
        val work = if (satScale < 1.0) {
            val resized = Mat()
            Imgproc.resize(
                rgba, resized,
                Size(width * satScale, height * satScale),
                0.0, 0.0, Imgproc.INTER_AREA
            )
            resized
        } else {
            rgba
        }
        val rgb = Mat()
        Imgproc.cvtColor(work, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 101.0), Scalar(180.0, 79.0, 255.0), mask)

        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val gentle = Mat()
        Imgproc.morphologyEx(mask, gentle, Imgproc.MORPH_OPEN, k9)
        Imgproc.morphologyEx(gentle, gentle, Imgproc.MORPH_CLOSE, k9)

        val k25 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(25.0, 25.0))
        val k41 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(41.0, 41.0))
        val split = Mat()
        Imgproc.morphologyEx(mask, split, Imgproc.MORPH_CLOSE, k25)
        Imgproc.morphologyEx(split, split, Imgproc.MORPH_OPEN, k41)

        val out = mutableListOf<MatOfPoint>()
        for (variant in listOf(gentle, split)) {
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                variant, contours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            for (contour in contours) {
                out.add(
                    if (satScale < 1.0) {
                        MatOfPoint(
                            *contour.toArray().map {
                                Point(it.x / satScale, it.y / satScale)
                            }.toTypedArray()
                        )
                    } else {
                        contour
                    }
                )
            }
        }
        return out
    }

    private fun buildRegionMask(gray: Mat): Mat {
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        return binary
    }

    private fun evaluateLineQuadCandidates(
        edges: Mat,
        width: Int,
        height: Int,
        imageArea: Double,
        candidateLogCount: Int,
        bitmap: Bitmap
    ): CandidateEvaluationResult {
        val accepted = mutableListOf<CardCandidate>()
        val evaluated = mutableListOf<CardCandidate>()
        val rejected = mutableListOf<RejectedCandidate>()
        var logCount = candidateLogCount

        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 50, 80.0, 30.0)
        val rawLineCount = if (lines.empty()) 0 else lines.rows()
        Log.i(TAG, "LINE_QUAD stage=rawHoughLines count=$rawLineCount")
        if (lines.empty()) {
            return CandidateEvaluationResult(accepted, evaluated, rejected, logCount)
        }

        val segments = mutableListOf<LineSegment>()
        for (i in 0 until lines.rows()) {
            val values = lines.get(i, 0)
            if (values.size < 4) continue
            val x1 = values[0]
            val y1 = values[1]
            val x2 = values[2]
            val y2 = values[3]
            val length = kotlin.math.hypot(x2 - x1, y2 - y1)
            if (length < 50.0) continue
            val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
            segments.add(LineSegment(x1, y1, x2, y2, length, angle))
        }
        Log.i(TAG, "LINE_QUAD stage=filteredLines count=${segments.size}")

        if (segments.isEmpty()) {
            return CandidateEvaluationResult(accepted, evaluated, rejected, logCount)
        }

        val groups = segments.groupBy { segment ->
            val angle = normalizeAngle(segment.angle)
            val bucket = Math.round((angle / (Math.PI / 18.0))) * (Math.PI / 18.0)
            bucket
        }

        val angleGroups = groups.values.filter { it.size >= 2 }.sortedByDescending { group -> group.sumOf { it.length } }
        Log.i(TAG, "LINE_QUAD stage=parallelLinePairs count=${angleGroups.size}")
        val quadCandidates = mutableListOf<List<Point>>()
        var localCompatiblePairs = 0
        var rejectedArea = 0
        var rejectedAspect = 0
        var rejectedAngle = 0
        var rejectedLocality = 0
        var rejectedSideSupport = 0
        var rejectedBorder = 0
        var quadHypothesesBeforeGeometry = 0
        var lineRejectedAverageSupport = 0
        var lineRejectedSingleSideSupport = 0
        var linePassedSupport = 0

        for (i in angleGroups.indices) {
            val groupA = angleGroups[i]
            for (j in (i + 1) until angleGroups.size) {
                val groupB = angleGroups[j]
                val mainAngleA = normalizeAngle(groupA.first().angle)
                val mainAngleB = normalizeAngle(groupB.first().angle)
                val angleDelta = angleDifference(mainAngleA, mainAngleB)
                val orthogonal = kotlin.math.abs((Math.PI / 2.0) - angleDelta) <= 0.75 || kotlin.math.abs((Math.PI / 2.0) - (Math.PI - angleDelta)) <= 0.75
                if (!orthogonal) continue

                val aSegments = groupA.sortedByDescending { it.length }.take(4)
                val bSegments = groupB.sortedByDescending { it.length }.take(4)
                for (a1 in aSegments) {
                    for (a2 in aSegments) {
                        if (a1 === a2) continue
                        for (b1 in bSegments) {
                            for (b2 in bSegments) {
                                if (b1 === b2) continue
                                localCompatiblePairs += 1
                                val corners = listOfNotNull(
                                    intersectSegments(a1, b1),
                                    intersectSegments(a1, b2),
                                    intersectSegments(a2, b1),
                                    intersectSegments(a2, b2)
                                )
                                if (corners.size != 4) continue
                                quadHypothesesBeforeGeometry += 1

                                val deduped = mutableListOf<Point>()
                                for (point in corners) {
                                    val tooClose = deduped.any { other -> distance(point, other) < 15.0 }
                                    if (!tooClose) deduped.add(point)
                                }
                                if (deduped.size != 4) continue

                                val ordered = orderPoints(deduped.toTypedArray())
                                if (ordered.size != 4) continue
                                if (!isConvexQuad(ordered)) continue

                                val edgeA = distance(ordered[0], ordered[1])
                                val edgeB = distance(ordered[1], ordered[2])
                                val edgeC = distance(ordered[2], ordered[3])
                                val edgeD = distance(ordered[3], ordered[0])
                                val longSide = maxOf(edgeA, edgeB, edgeC, edgeD)
                                val shortSide = minOf(edgeA, edgeB, edgeC, edgeD)
                                if (longSide <= 0.0 || shortSide <= 0.0) continue

                                val aspect = longSide / shortSide
                                val aspectError = abs(aspect - CARD_ASPECT_RATIO.toDouble()) / CARD_ASPECT_RATIO.toDouble()
                                if (aspectError > MAX_LINE_QUAD_ASPECT_ERROR) {
                                    rejectedAspect += 1
                                    continue
                                }
                                if (aspect < MIN_ASPECT_RATIO.toDouble() || aspect > MAX_ASPECT_RATIO.toDouble()) {
                                    rejectedAspect += 1
                                    continue
                                }

                                val polyArea = polygonArea(ordered)
                                val areaRatio = polyArea / imageArea
                                if (areaRatio < MIN_CARD_AREA_RATIO.toDouble() || areaRatio > 0.10) {
                                    rejectedArea += 1
                                    continue
                                }
                                if (longSide / max(width.toDouble(), 1.0) > 0.55 || shortSide / max(height.toDouble(), 1.0) > 0.45) {
                                    rejectedLocality += 1
                                    continue
                                }
                                if (ordered.any { it.x < 8.0 || it.y < 8.0 || it.x > (width - 8).toDouble() || it.y > (height - 8).toDouble() }) {
                                    rejectedBorder += 1
                                    continue
                                }
                                if (ordered.any { it.x < 16.0 || it.y < 16.0 || it.x > (width - 16).toDouble() || it.y > (height - 16).toDouble() }) {
                                    rejectedBorder += 1
                                    continue
                                }

                                val edgePairs = listOf(edgeA, edgeB, edgeC, edgeD)
                                val oppositeConsistency = max(
                                    abs(edgePairs[0] - edgePairs[2]) / maxOf(edgePairs[0], edgePairs[2]),
                                    abs(edgePairs[1] - edgePairs[3]) / maxOf(edgePairs[1], edgePairs[3])
                                )
                                if (oppositeConsistency > 0.25) continue

                                val edgeAngleA = angleBetweenPoints(ordered[0], ordered[1])
                                val edgeAngleB = angleBetweenPoints(ordered[1], ordered[2])
                                val edgeAngleC = angleBetweenPoints(ordered[2], ordered[3])
                                val edgeAngleD = angleBetweenPoints(ordered[3], ordered[0])
                                val angleChecks = listOf(
                                    angleDifference(normalizeAngle(edgeAngleA), normalizeAngle(edgeAngleB)),
                                    angleDifference(normalizeAngle(edgeAngleB), normalizeAngle(edgeAngleC)),
                                    angleDifference(normalizeAngle(edgeAngleC), normalizeAngle(edgeAngleD)),
                                    angleDifference(normalizeAngle(edgeAngleD), normalizeAngle(edgeAngleA))
                                )
                                if (angleChecks.any { kotlin.math.abs((Math.PI / 2.0) - it) > 1.10 }) {
                                    rejectedAngle += 1
                                    continue
                                }

                                val centers = listOf(
                                    midpoint(a1.x1, a1.y1, a1.x2, a1.y2),
                                    midpoint(a2.x1, a2.y1, a2.x2, a2.y2),
                                    midpoint(b1.x1, b1.y1, b1.x2, b1.y2),
                                    midpoint(b2.x1, b2.y1, b2.x2, b2.y2)
                                )
                                val supportCenter = averagePoint(centers)
                                val localCenterDistance = distance(supportCenter, averagePoint(ordered))
                                if (localCenterDistance > max(120.0, longSide * 0.75)) {
                                    rejectedLocality += 1
                                    continue
                                }

                                val sideSupport = listOf(
                                    sideEdgeSupport(edges, ordered[0], ordered[1], width, height),
                                    sideEdgeSupport(edges, ordered[1], ordered[2], width, height),
                                    sideEdgeSupport(edges, ordered[2], ordered[3], width, height),
                                    sideEdgeSupport(edges, ordered[3], ordered[0], width, height)
                                )
                                val averageSideSupport = sideSupport.average()
                                val hasSingleLowSide = sideSupport.any { it < 0.10 }
                                if (averageSideSupport < 0.15) {
                                    lineRejectedAverageSupport += 1
                                    rejectedSideSupport += 1
                                    Log.i(
                                        TAG,
                                        "LINE_QUAD sideSupportReject average<0.15 quad=${ordered.map { "${it.x},${it.y}" }} areaRatio=$areaRatio aspectRatio=$aspect sideSupport[0]=${sideSupport[0]} sideSupport[1]=${sideSupport[1]} sideSupport[2]=${sideSupport[2]} sideSupport[3]=${sideSupport[3]} averageSideSupport=$averageSideSupport exactRejectionReason=average < 0.15"
                                    )
                                    continue
                                }
                                if (hasSingleLowSide) {
                                    lineRejectedSingleSideSupport += 1
                                    rejectedSideSupport += 1
                                    Log.i(
                                        TAG,
                                        "LINE_QUAD sideSupportReject oneSide<0.10 quad=${ordered.map { "${it.x},${it.y}" }} areaRatio=$areaRatio aspectRatio=$aspect sideSupport[0]=${sideSupport[0]} sideSupport[1]=${sideSupport[1]} sideSupport[2]=${sideSupport[2]} sideSupport[3]=${sideSupport[3]} averageSideSupport=$averageSideSupport exactRejectionReason=one side < 0.10"
                                    )
                                    continue
                                }

                                linePassedSupport += 1
                                quadCandidates.add(ordered)
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "LINE_QUAD stage=localCompatiblePairs count=$localCompatiblePairs quadHypothesesBeforeGeometry=$quadHypothesesBeforeGeometry rejectedByArea=$rejectedArea rejectedByAspect=$rejectedAspect rejectedByAngle=$rejectedAngle rejectedByLocality=$rejectedLocality rejectedBySideSupport=$rejectedSideSupport rejectedByBorder=$rejectedBorder lineRejectedAverageSupport=$lineRejectedAverageSupport lineRejectedSingleSideSupport=$lineRejectedSingleSideSupport linePassedSupport=$linePassedSupport")
        if (quadCandidates.isEmpty()) {
            Log.i(TAG, "Calibration line-quad path produced no valid quadrilateral hypotheses")
            return CandidateEvaluationResult(accepted, evaluated, rejected, logCount)
        }

        val uniqueQuads = mutableListOf<List<Point>>()
        for (quad in quadCandidates.sortedBy { polygonArea(it) }) {
            var duplicate = false
            for (existing in uniqueQuads) {
                val centerDistance = distance(averagePoint(quad), averagePoint(existing))
                if (centerDistance < 60.0) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) uniqueQuads.add(quad)
        }

        val lineDebugQuads = mutableListOf<List<Point>>()
        for (quad in uniqueQuads.take(6)) {
            val corners = quad.toTypedArray()
            val edgeA = distance(corners[0], corners[1])
            val edgeB = distance(corners[1], corners[2])
            val edgeC = distance(corners[2], corners[3])
            val edgeD = distance(corners[3], corners[0])
            val longSide = maxOf(edgeA, edgeB, edgeC, edgeD)
            val shortSide = minOf(edgeA, edgeB, edgeC, edgeD)
            val aspect = longSide / shortSide
            val area = polygonArea(quad)
            val areaRatio = area / imageArea
            val aspectError = abs(aspect - CARD_ASPECT_RATIO.toDouble()) / CARD_ASPECT_RATIO.toDouble()
            val score = clamp(
                (1.0f - aspectError.toFloat() * 2.4f).coerceIn(0.0f, 1.0f) * 0.9f +
                    (if (areaRatio >= MIN_CARD_AREA_RATIO.toDouble() && areaRatio <= 0.10) 0.12f else 0.0f),
                0.0f,
                1.0f
            )
            val candidate = CardCandidate(
                score = score,
                contourArea = area.toFloat(),
                areaRatio = areaRatio.toFloat(),
                aspect = aspect.toFloat(),
                rectangularity = 0.82f,
                convexity = 0.86f,
                points = quad,
                widthPixels = longSide.toFloat(),
                heightPixels = shortSide.toFloat(),
                source = "LINE_QUAD",
                approxPointCount = 4
            )
            evaluated.add(candidate)
            if (score > MIN_ACCEPT_SCORE) accepted.add(candidate)
            lineDebugQuads.add(quad)
            if (areaRatio >= 0.005 && logCount < 30) {
                logCount++
                Log.i(
                    TAG,
                    "LineQuadCandidateLog#$logCount: areaRatio=$areaRatio aspectRatio=$aspect aspectError=$aspectError score=$score corners=${quad.map { "${it.x},${it.y}" }}"
                )
            }
        }

        saveCardLinesDebugImage(bitmap, segments, lineDebugQuads, nextDebugFileName("card_lines_debug"))
        return CandidateEvaluationResult(accepted, evaluated, rejected, logCount)
    }

    private fun saveCardLinesDebugImage(bitmap: Bitmap, segments: List<LineSegment>, quads: List<List<Point>>, fileName: String): String? {
        val debugBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)

        val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = 0xFF00E5FF.toInt()
        }
        val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            color = 0xFFFF0000.toInt()
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }

        for (segment in segments) {
            canvas.drawLine(segment.x1.toFloat(), segment.y1.toFloat(), segment.x2.toFloat(), segment.y2.toFloat(), segmentPaint)
        }

        quads.forEachIndexed { index, quad ->
            val path = Path()
            if (quad.size >= 4) {
                val points = quad.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                path.close()
                canvas.drawPath(path, quadPaint)
                canvas.drawText("Q${index + 1}", points[0].x + 12f, points[0].y - 12f, textPaint)
            }
        }

        return writeDebugBitmap(debugBitmap, "$fileName.png")
    }

    private fun isConvexQuad(points: List<Point>): Boolean {
        if (points.size != 4) return false
        // A convex polygon turns the same way at every vertex, so all cross
        // products share one sign. Mixed signs mean concave -- requiring both
        // signs (as this did) inverted the test and rejected every valid card
        // quad the line-quad path produced.
        var hasPositive = false
        var hasNegative = false
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            val p3 = points[(i + 2) % points.size]
            val cross = (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x)
            if (cross > 0.0) hasPositive = true
            else if (cross < 0.0) hasNegative = true
        }
        return hasPositive != hasNegative
    }

    private fun averagePoint(points: List<Point>): Point {
        if (points.isEmpty()) return Point()
        val x = points.map { it.x }.average()
        val y = points.map { it.y }.average()
        return Point(x, y)
    }

    private fun midpoint(x1: Double, y1: Double, x2: Double, y2: Double): Point {
        return Point((x1 + x2) / 2.0, (y1 + y2) / 2.0)
    }

    private fun isNearImageBorder(points: List<Point>, width: Int, height: Int, margin: Int): Boolean {
        if (points.isEmpty()) return false
        val border = max(12, margin)
        return points.any { point ->
            point.x <= border || point.y <= border || point.x >= (width - border).toDouble() || point.y >= (height - border).toDouble()
        }
    }

    private fun angleBetweenPoints(p1: Point, p2: Point): Double {
        return kotlin.math.atan2(p2.y - p1.y, p2.x - p1.x)
    }

    private fun sideEdgeSupport(edges: Mat, p1: Point, p2: Point, width: Int, height: Int): Double {
        val length = distance(p1, p2)
        if (length <= 0.0) return 0.0
        val sampleCount = max(6, min(18, (length / 12.0).toInt()))
        var hitCount = 0
        for (index in 0 until sampleCount) {
            val t = if (sampleCount == 1) 0.0 else index.toDouble() / (sampleCount - 1).toDouble()
            val x = p1.x + (p2.x - p1.x) * t
            val y = p1.y + (p2.y - p1.y) * t
            val px = x.toInt().coerceIn(0, width - 1)
            val py = y.toInt().coerceIn(0, height - 1)
            var hasSupport = false
            for (dy in -3..3) {
                val yy = (py + dy).coerceIn(0, height - 1)
                for (dx in -3..3) {
                    val xx = (px + dx).coerceIn(0, width - 1)
                    val value = edges.get(yy, xx)
                    if (value.isNotEmpty() && value[0] > 0.0) {
                        hasSupport = true
                        break
                    }
                }
                if (hasSupport) break
            }
            if (hasSupport) hitCount += 1
        }
        return hitCount.toDouble() / sampleCount.toDouble()
    }

    private fun normalizeAngle(angle: Double): Double {
        var normalized = angle
        while (normalized < -Math.PI / 2.0) normalized += Math.PI
        while (normalized > Math.PI / 2.0) normalized -= Math.PI
        return normalized
    }

    private fun angleDifference(a: Double, b: Double): Double {
        val diff = kotlin.math.abs(a - b)
        return min(diff, Math.PI - diff)
    }

    private fun polygonArea(points: List<Point>): Double {
        if (points.size < 3) return 0.0
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return kotlin.math.abs(area) / 2.0
    }

    private fun intersectSegments(a: LineSegment, b: LineSegment): Point? {
        val x1 = a.x1
        val y1 = a.y1
        val x2 = a.x2
        val y2 = a.y2
        val x3 = b.x1
        val y3 = b.y1
        val x4 = b.x2
        val y4 = b.y2

        val denominator = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (kotlin.math.abs(denominator) < 1e-6) return null

        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / denominator
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / denominator
        val point = Point(px, py)
        if (point.x.isNaN() || point.y.isNaN() || point.x.isInfinite() || point.y.isInfinite()) return null
        return point
    }

    private fun saveDebugMat(mat: Mat, fileName: String): String? {
        val output = Mat()
        mat.copyTo(output)
        val debugBitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(output, debugBitmap)
        return writeDebugBitmap(debugBitmap, fileName)
    }

    private fun nextDebugFileName(baseName: String): String {
        debugImageIndex += 1
        return if (debugImageIndex > 1) {
            "${baseName}_update${debugImageIndex}.png"
        } else {
            "${baseName}_update.png"
        }
    }

    private fun deduplicateCandidates(candidates: List<CardCandidate>): List<CardCandidate> {
        val unique = mutableListOf<CardCandidate>()
        for (candidate in candidates) {
            val duplicate = unique.any { other ->
                val centerDistance = kotlin.math.abs(candidate.points.firstOrNull()?.x ?: 0.0 - (other.points.firstOrNull()?.x ?: 0.0)) +
                    kotlin.math.abs(candidate.points.firstOrNull()?.y ?: 0.0 - (other.points.firstOrNull()?.y ?: 0.0))
                centerDistance < 40.0 && kotlin.math.abs(candidate.score - other.score) < 0.05f
            }
            if (!duplicate) unique.add(candidate)
        }
        return unique
    }

    private fun saveSelectedCardDebugImage(bitmap: Bitmap, corners: List<Point>, source: String, aspectRatio: Float, quality: Float, orientationAngle: Float): String? {
        val debugBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFF0000.toInt()
            strokeWidth = 16f
        }
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF0000.toInt()
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
        }

        val path = Path()
        val points = corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }

        if (points.size >= 4) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            path.close()
            canvas.drawPath(path, stroke)

            for (point in points) {
                canvas.drawCircle(point.x, point.y, 30f, marker)
            }

            val labels = listOf("TL", "TR", "BR", "BL")
            for (i in points.indices) {
                val label = labels.getOrElse(i) { "P$i" }
                canvas.drawText(label, points[i].x + 18f, points[i].y - 18f, textPaint)
            }

            val info = "src=$source aspect=${"%.3f".format(aspectRatio)} quality=${"%.3f".format(quality)} angle=${"%.2f".format(orientationAngle)}"
            canvas.drawText(info, 24f, 84f, textPaint)
        }

        return writeDebugBitmap(debugBitmap, "selected_card_debug.png")
    }

    private fun saveCardCandidatesDebugImage(
        bitmap: Bitmap,
        evaluatedCandidates: List<CardCandidate>,
        rejectedCandidates: List<RejectedCandidate>,
        selectedCandidate: CardCandidate?,
        fileName: String = "card_candidates_debug.png"
    ): String? {
        val debugBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)

        val acceptedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = 0xFF00A6FF.toInt()
        }
        val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16f
            color = 0xFFFF0000.toInt()
        }
        val acceptedMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF00A6FF.toInt()
        }
        val selectedMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF0000.toInt()
        }
        val rejectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = 0xFFFFC107.toInt()
        }
        val rejectedMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFC107.toInt()
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }

        evaluatedCandidates.sortedByDescending { it.score }.forEachIndexed { index, candidate ->
            val isSelected = selectedCandidate != null &&
                candidate.score == selectedCandidate.score &&
                candidate.source == selectedCandidate.source &&
                candidate.points == selectedCandidate.points
            val paint = if (isSelected) selectedPaint else acceptedOutlinePaint
            val marker = if (isSelected) selectedMarkerPaint else acceptedMarkerPaint
            val path = Path()
            val points = candidate.points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            if (points.size >= 4) {
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                path.close()
                canvas.drawPath(path, paint)
                for (point in points) {
                    canvas.drawCircle(point.x, point.y, 22f, marker)
                }
            }

            val label = "#${index + 1} ${candidate.source} a=${"%.2f".format(candidate.aspect)} r=${"%.2f".format(candidate.rectangularity)} s=${"%.2f".format(candidate.convexity)} sc=${"%.2f".format(candidate.score)}"
            val y = 80f + index * 52f
            canvas.drawText(label, 24f, y, textPaint)
        }

        rejectedCandidates.sortedByDescending { it.rectangularity * it.solidity }.forEachIndexed { index, candidate ->
            val paint = rejectedOutlinePaint
            val marker = rejectedMarkerPaint
            val path = Path()
            val points = candidate.points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            if (points.size >= 4) {
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
                path.close()
                canvas.drawPath(path, paint)
                for (point in points) {
                    canvas.drawCircle(point.x, point.y, 18f, marker)
                }
            }

            val label = "REJECTED #${index + 1} a=${"%.2f".format(candidate.aspect)} r=${"%.2f".format(candidate.rectangularity)} s=${"%.2f".format(candidate.solidity)} p=${candidate.approxPointCount} ${candidate.rejectionReason}"
            val y = 80f + (evaluatedCandidates.size + index) * 52f
            canvas.drawText(label, 24f, y, textPaint)
        }

        return writeDebugBitmap(debugBitmap, fileName)
    }

    private fun saveWarpedCardDebugImage(bitmap: Bitmap, homography: Mat, widthPx: Int, heightPx: Int): String? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, homography, Size(widthPx.toDouble(), heightPx.toDouble()))
        val warpedBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, warpedBitmap)
        return writeDebugBitmap(warpedBitmap, "selected_card_warped.png")
    }

    private fun orderPoints(points: Array<Point>): List<Point> {
        if (points.isEmpty()) return emptyList()
        val ordered = points.toList().sortedBy { it.x + it.y }
        val topLeft = ordered.minByOrNull { it.x + it.y } ?: points[0]
        val bottomRight = ordered.maxByOrNull { it.x + it.y } ?: points[0]
        val other = points.toList().filter { it != topLeft && it != bottomRight }
        val topRight = other.maxByOrNull { it.x } ?: other.firstOrNull() ?: points[0]
        val bottomLeft = other.minByOrNull { it.x } ?: other.firstOrNull() ?: points[0]
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun widthToArea(rotatedRect: org.opencv.core.RotatedRect): Double {
        val size = rotatedRect.size
        return size.width * size.height
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }
}

/**
 * The calibration result that the measurement layer can return after observing
 * the original image and its geometric content.
 */
sealed class CalibrationResult {
    data class Success(
        val found: Boolean,
        val corners: List<Point>,
        val cardWidthPixels: Float,
        val cardHeightPixels: Float,
        val pixelsPerCmX: Float,
        val pixelsPerCmY: Float,
        val pixelsPerCmLong: Float,
        val pixelsPerCmShort: Float,
        val longSidePixels: Float,
        val shortSidePixels: Float,
        val orientationAngleDegrees: Float,
        val score: Float,
        val quality: Float,
        val perspectiveCorrected: Boolean,
        val warnings: List<String>
    ) : CalibrationResult()

    data class CardNotFound(
        val message: String,
        val candidates: Int
    ) : CalibrationResult()

    data class LowCalibrationQuality(
        val message: String,
        val calibration: Success
    ) : CalibrationResult()
}

/**
 * Unified result of the measurement ladder. `measurements` is index-aligned
 * with the input predictions; cm fields are null for predictions the chosen
 * tier could not size. `cardCalibration` is present only when scaleSource is
 * "card".
 */
data class MeasurementOutcome(
    val scaleSource: String, // "card" | "box_face" | "box_edge" | "none"
    val measurements: List<DamageSize>,
    val cardCalibration: CalibrationResult.Success?,
    /** Card-free alternative, present when scaleSource is "card" and there is
     *  damage to size — used when the user rejects the detected card. */
    val fallback: BoxFaceMeasurement.Outcome? = null
)

/**
 * Physical-size estimate for one YOLO prediction in the original image space.
 */
data class DamageSize(
    val clazz: String,
    val confidence: Float,
    val widthPixels: Float,
    val heightPixels: Float,
    val x: Float,
    val y: Float,
    val widthCm: Float?,
    val heightCm: Float?,
    val pixelsPerCmX: Float?,
    val pixelsPerCmY: Float?
) {
    val className: String get() = clazz

    val longestSideCm: Float?
        get() = when {
            widthCm == null && heightCm == null -> null
            widthCm == null -> heightCm
            heightCm == null -> widthCm
            else -> max(widthCm!!, heightCm!!)
        }
}

private data class CandidateEvaluationResult(
    val accepted: MutableList<CardCandidate>,
    val evaluated: MutableList<CardCandidate>,
    val rejected: MutableList<RejectedCandidate>,
    val logCount: Int
)

private data class LineSegment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val length: Double,
    val angle: Double
)

private data class CardCandidate(
    val score: Float,
    val contourArea: Float,
    val areaRatio: Float,
    val aspect: Float,
    val rectangularity: Float,
    val convexity: Float,
    val points: List<Point>,
    val widthPixels: Float,
    val heightPixels: Float,
    val source: String,
    val approxPointCount: Int
)

private data class RejectedCandidate(
    val areaRatio: Float,
    val aspect: Float,
    val rectangularity: Float,
    val solidity: Float,
    val approxPointCount: Int,
    val longSide: Float,
    val shortSide: Float,
    val rejectionReason: String,
    val points: List<Point>
)
