package com.resellbox.ai.data

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Card-free damage sizing, ported from the Python prototype
 * (sneaker-box-dataset/measurement/measure_box_face.py).
 *
 * Degradation ladder:
 *   box_face  face quad found; damage rectified via homography and sized as a
 *             fraction of the panel, cm via nominal box length (top/front long
 *             dim = 35 cm, side long dim = 25 cm).
 *   box_edge  silhouette found but no clean face split; coarse scale from the
 *             minAreaRect long edge = 35 cm (no rectification).
 *   none      box edges not in frame (close-up); no cm emitted.
 */
class BoxFaceMeasurement {

    companion object {
        private const val TAG = "BoxFaceMeasurement"

        // Nominal box prior (cm). Sneaker boxes ~33-37 long; use the middle.
        private const val NOMINAL_LONG_CM = 35.0
        private const val NOMINAL_SIDE_LONG_CM = 25.0 // side panel long dim = box width

        // Sanity range for a rectified panel aspect (long/short); perspective
        // foreshortening pulls real aspects toward 1, so the range is wide.
        private const val ASPECT_SANE_MIN = 1.0
        private const val ASPECT_SANE_MAX = 3.8

        private const val WORK_LONG_SIDE = 600.0
        private const val GRABCUT_LONG_SIDE = 300.0
        private const val MIN_HULL_FRAC = 0.12
        private const val MAX_HULL_SPAN = 0.96
        private const val BG_FG_MIN_DIST = 0.25
        private const val MIN_EDGE_SUPPORT = 0.40
        private const val RECTIFIED_LONG_SIDE = 300.0
        private const val MIN_RECT_FILL = 0.70
    }

    data class Outcome(
        val scaleSource: String, // "box_face" | "box_edge" | "none"
        val measurements: List<DamageSize>,
    )

    /**
     * Long edge of the box silhouette's minAreaRect in original-image px, or
     * null when no silhouette is found. Used to sanity-check a detected
     * reference card: the card's px/cm must imply a plausible box size.
     */
    fun silhouetteLongEdgeOriginalPx(bitmap: Bitmap): Double? {
        val (work, scale) = toWorkMat(bitmap)
        val (hull, _) = findSilhouette(work) ?: return null
        val rect = Imgproc.minAreaRect(MatOfPoint2f(*hull.toArray()))
        return max(rect.size.width, rect.size.height) / scale
    }

    private fun toWorkMat(bitmap: Bitmap): Pair<Mat, Double> {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        val scale = WORK_LONG_SIDE / max(bitmap.width, bitmap.height).toDouble()
        val work = Mat()
        Imgproc.resize(
            bgr, work,
            Size((bitmap.width * scale).roundToInt().toDouble(), (bitmap.height * scale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_CUBIC
        )
        return work to scale
    }

    fun measure(bitmap: Bitmap, predictions: List<Prediction>): Outcome {
        val (work, scale) = toWorkMat(bitmap)

        val silhouette = findSilhouette(work)
        if (silhouette == null) {
            Log.i(TAG, "No box silhouette (close-up); scale_source=none")
            return Outcome("none", predictions.map { unsized(it) })
        }
        val (hull, hullSource) = silhouette

        var (faces, kind) = splitFaces(hull)

        // A cropped panel is useless for scale: its true extent is unknown. The
        // signature is a clipped edge running along a frame border (two or more
        // vertices on the same border); a single grazing vertex is tolerated.
        val ww = work.cols()
        val wh = work.rows()
        val borderMargin = 0.006 * max(ww, wh)
        faces = faces.filter { quad -> !isCropped(quad, ww, wh, borderMargin) }

        if (kind == "quad" && faces.isNotEmpty()) {
            // A lone 4-vertex quad has no structural validation (a GrabCut blob
            // on flat texture reduces to one too) -- demand visible edge support.
            val edgesDilated = Mat()
            Imgproc.dilate(
                edgeMap(work), edgesDilated,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            )
            faces = faces.filter { quad -> edgeSupport(quad, edgesDilated) >= MIN_EDGE_SUPPORT }
        }

        val assigned = assignFaces(faces)

        // box_edge fallback scale: only from an edge-based hull that looks
        // box-shaped; a GrabCut blob, an irregular hull, or one running off
        // every frame border is weak evidence (close-up texture).
        val edgePxPerCm = boxEdgePxPerCm(hull, hullSource, ww, wh)

        var anyFace = false
        var anyEdge = false
        val measurements = predictions.map { prediction ->
            val faceMeasured = measureOnFace(prediction, assigned, scale)
            when {
                faceMeasured != null -> {
                    anyFace = true
                    faceMeasured
                }
                edgePxPerCm != null -> {
                    anyEdge = true
                    sized(
                        prediction,
                        widthCm = prediction.width * scale / edgePxPerCm,
                        heightCm = prediction.height * scale / edgePxPerCm,
                    )
                }
                else -> unsized(prediction)
            }
        }

        val source = when {
            anyFace -> "box_face"
            anyEdge -> "box_edge"
            else -> "none"
        }
        Log.i(TAG, "Box-face ladder: hullSource=$hullSource splitKind=$kind faces=${assigned.size} scale_source=$source")
        return Outcome(source, measurements)
    }

    // ------------------------------------------------------------------
    // Silhouette
    // ------------------------------------------------------------------

    /** Median-auto Canny edges of a bilateral-filtered grey image. */
    private fun edgeMap(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.bilateralFilter(gray, blur, 9, 60.0, 60.0)
        val med = matMedian(blur)
        val edges = Mat()
        Imgproc.Canny(blur, edges, max(10.0, 0.66 * med), min(255.0, 1.33 * med + 40))
        return edges
    }

    private fun matMedian(gray: Mat): Double {
        val buf = ByteArray(gray.rows() * gray.cols())
        gray.get(0, 0, buf)
        val values = IntArray(buf.size) { buf[it].toInt() and 0xFF }
        values.sort()
        return values[values.size / 2].toDouble()
    }

    /**
     * Box silhouette as (convex hull, source), or null (close-up / no box).
     * Attempt 1: strict edge-based largest contour ("edge"). Attempt 2:
     * GrabCut seeded with a central rect ("grabcut"), accepted only when the
     * segmented foreground's colour differs from the border background.
     */
    private fun findSilhouette(bgr: Mat): Pair<MatOfPoint, String>? {
        val w = bgr.cols()
        val h = bgr.rows()

        val closed = Mat()
        Imgproc.morphologyEx(
            edgeMap(bgr), closed, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        )
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(closed, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isNotEmpty()) {
            val hull = convexHullOf(contours.maxBy { Imgproc.contourArea(it) })
            if (hullValid(hull, w, h)) return hull to "edge"
        }

        // GrabCut attempt at reduced resolution (it dominates runtime).
        val gcScale = GRABCUT_LONG_SIDE / max(w, h).toDouble()
        val small = Mat()
        Imgproc.resize(
            bgr, small,
            Size((w * gcScale).roundToInt().toDouble(), (h * gcScale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        val sw = small.cols()
        val sh = small.rows()
        val marginX = (0.06 * sw).roundToInt()
        val marginY = (0.06 * sh).roundToInt()
        val rect = Rect(marginX, marginY, sw - 2 * marginX, sh - 2 * marginY)
        val mask = Mat.zeros(sh, sw, CvType.CV_8UC1)
        try {
            Imgproc.grabCut(
                small, mask, rect,
                Mat.zeros(1, 65, CvType.CV_64FC1), Mat.zeros(1, 65, CvType.CV_64FC1),
                3, Imgproc.GC_INIT_WITH_RECT
            )
        } catch (e: Exception) {
            Log.w(TAG, "grabCut failed: ${e.message}")
            return null
        }
        val fg = Mat.zeros(sh, sw, CvType.CV_8UC1)
        val maskBuf = ByteArray(sh * sw)
        mask.get(0, 0, maskBuf)
        val fgBuf = ByteArray(sh * sw)
        for (i in maskBuf.indices) {
            val v = maskBuf[i].toInt()
            if (v == Imgproc.GC_FGD || v == Imgproc.GC_PR_FGD) fgBuf[i] = 255.toByte()
        }
        fg.put(0, 0, fgBuf)
        Imgproc.morphologyEx(
            fg, fg, Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        )
        val fgContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(fg, fgContours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        if (fgContours.isEmpty()) return null
        val largest = fgContours.maxBy { Imgproc.contourArea(it) }
        val upscaled = MatOfPoint(
            *largest.toArray().map { Point(it.x / gcScale, it.y / gcScale) }.toTypedArray()
        )
        val hull = convexHullOf(upscaled)
        if (!hullValid(hull, w, h)) return null

        // Colour check: foreground must be distinguishable from the border
        // ring, otherwise this is a uniform close-up GrabCut couldn't split.
        val bigMarginX = (0.06 * w).roundToInt()
        val bigMarginY = (0.06 * h).roundToInt()
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val hullMask = Mat.zeros(h, w, CvType.CV_8UC1)
        Imgproc.fillConvexPoly(hullMask, hull, Scalar(255.0))
        val bgMask = Mat()
        Core.bitwise_not(hullMask, bgMask)
        bgMask.submat(bigMarginY, h - bigMarginY, bigMarginX, w - bigMarginX).setTo(Scalar(0.0))
        if (Core.countNonZero(bgMask) < 500) return null
        val dist = Imgproc.compareHist(
            labHist(lab, hullMask), labHist(lab, bgMask), Imgproc.HISTCMP_BHATTACHARYYA
        )
        if (dist < BG_FG_MIN_DIST) return null
        return hull to "grabcut"
    }

    private fun labHist(lab: Mat, mask: Mat): Mat {
        val hist = Mat()
        Imgproc.calcHist(
            listOf(lab), MatOfInt(1, 2), mask, hist,
            MatOfInt(24, 24), MatOfFloat(0f, 256f, 0f, 256f)
        )
        Core.normalize(hist, hist)
        return hist
    }

    private fun convexHullOf(contour: MatOfPoint): MatOfPoint {
        val indices = MatOfInt()
        Imgproc.convexHull(contour, indices)
        val points = contour.toArray()
        return MatOfPoint(*indices.toArray().map { points[it] }.toTypedArray())
    }

    private fun hullValid(hull: MatOfPoint, w: Int, h: Int): Boolean {
        if (Imgproc.contourArea(hull) < MIN_HULL_FRAC * w * h) return false
        val bounds = Imgproc.boundingRect(hull)
        // fills the frame -> close-up texture, not a box outline
        return !(bounds.width >= MAX_HULL_SPAN * w && bounds.height >= MAX_HULL_SPAN * h)
    }

    // ------------------------------------------------------------------
    // Face split
    // ------------------------------------------------------------------

    /**
     * Hull -> (face quads, kind). kind "hex": 3 faces from a hexagonal 3/4
     * view, structurally validated by junction consistency. kind "quad": one
     * 4-vertex face (frontal view) -- weak evidence, callers must corroborate
     * with edge support. Hexagon splits are preferred.
     */
    private fun splitFaces(hull: MatOfPoint): Pair<List<Array<Point>>, String> {
        var single: Array<Point>? = null
        for (approx in hexagonCandidates(hull)) {
            if (approx.size == 4) {
                if (single == null && isConvexQuad(approx)) single = approx
                continue
            }
            val faces = hexagonFaces(approx)
            if (faces.isNotEmpty()) return faces to "hex"
        }
        single?.let { return listOf(it) to "quad" }
        return emptyList<Array<Point>>() to "none"
    }

    /**
     * 4/6-vertex reductions of the hull, finest first (7-gons reduced by
     * dropping the vertex whose removal loses the least area).
     */
    private fun hexagonCandidates(hull: MatOfPoint): List<Array<Point>> {
        val hull2f = MatOfPoint2f(*hull.toArray())
        val peri = Imgproc.arcLength(hull2f, true)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Array<Point>>()
        val epsSteps = 14
        for (step in 0 until epsSteps) {
            val eps = 0.008 + (0.06 - 0.008) * step / (epsSteps - 1)
            val approx2f = MatOfPoint2f()
            Imgproc.approxPolyDP(hull2f, approx2f, eps * peri, true)
            var points = approx2f.toArray()
            if (points.size == 7) {
                var bestLoss = Double.MAX_VALUE
                var bestDrop = 0
                val fullArea = polygonArea(points)
                for (i in points.indices) {
                    val reduced = points.filterIndexed { idx, _ -> idx != i }.toTypedArray()
                    val loss = kotlin.math.abs(fullArea - polygonArea(reduced))
                    if (loss < bestLoss) {
                        bestLoss = loss
                        bestDrop = i
                    }
                }
                points = points.filterIndexed { idx, _ -> idx != bestDrop }.toTypedArray()
            }
            if (points.size != 4 && points.size != 6) continue
            val key = points.joinToString("|") { "${it.x.roundToInt()},${it.y.roundToInt()}" }
            if (!seen.add(key)) continue
            out.add(points)
        }
        return out
    }

    /**
     * Split a hexagonal box silhouette into visible face quads via
     * parallelogram completion: the interior 3-face junction p satisfies
     * p = v[i-1] + v[i+1] - v[i] for the alternating vertex parity. Try both
     * parities, keep the one whose three estimates agree and land inside.
     */
    private fun hexagonFaces(hexa: Array<Point>): List<Array<Point>> {
        val xs = hexa.map { it.x }
        val ys = hexa.map { it.y }
        val diam = hypot(xs.max() - xs.min(), ys.max() - ys.min())
        var best = emptyList<Array<Point>>()
        var bestSpread: Double? = null
        for (k in 0..1) {
            val est = (0 until 3).map { j ->
                val a = hexa[(2 * j + k) % 6]
                val b = hexa[(2 * j + k + 2) % 6]
                val c = hexa[(2 * j + k + 1) % 6]
                Point(a.x + b.x - c.x, a.y + b.y - c.y)
            }
            val median = Point(median3(est.map { it.x }), median3(est.map { it.y }))
            val spread = est.maxOf { hypot(it.x - median.x, it.y - median.y) }
            if (spread > 0.30 * diam) continue
            if (Imgproc.pointPolygonTest(MatOfPoint2f(*hexa), median, false) < 0) continue
            val faces = mutableListOf<Array<Point>>()
            var ok = true
            for (j in 0 until 3) {
                // Already polygon-ordered: junction -> 3 consecutive hull verts.
                val quad = arrayOf(
                    median,
                    hexa[(2 * j + k) % 6],
                    hexa[(2 * j + k + 1) % 6],
                    hexa[(2 * j + k + 2) % 6],
                )
                if (!isConvexQuad(quad)) {
                    ok = false
                    break
                }
                faces.add(quad)
            }
            if (ok && (bestSpread == null || spread < bestSpread!!)) {
                best = faces
                bestSpread = spread
            }
        }
        return best
    }

    private fun median3(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun isConvexQuad(quad: Array<Point>): Boolean {
        if (quad.size != 4) return false
        var signSum = 0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            signSum += if (cross > 0) 1 else if (cross < 0) -1 else 0
        }
        return kotlin.math.abs(signSum) == 4
    }

    private fun polygonArea(points: Array<Point>): Double {
        if (points.size < 3) return 0.0
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return kotlin.math.abs(area) / 2.0
    }

    /**
     * Fraction of points sampled along the quad's sides that land on an
     * (already dilated) edge pixel. Real box faces sit on visible edges; quads
     * fitted to segmentation blobs on flat texture do not.
     */
    private fun edgeSupport(quad: Array<Point>, edgesDilated: Mat): Double {
        val h = edgesDilated.rows()
        val w = edgesDilated.cols()
        var hits = 0
        var total = 0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val n = max(8, (hypot(b.x - a.x, b.y - a.y) / 4).toInt())
            for (s in 0 until n) {
                val t = 0.05 + (0.95 - 0.05) * s / (n - 1).coerceAtLeast(1)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x in 0 until w && y in 0 until h) {
                    total++
                    if (edgesDilated.get(y, x)[0] > 0.0) hits++
                }
            }
        }
        return if (total > 0) hits.toDouble() / total else 0.0
    }

    private fun isCropped(quad: Array<Point>, w: Int, h: Int, margin: Double): Boolean {
        return quad.count { it.x < margin } >= 2 ||
            quad.count { it.x > w - margin } >= 2 ||
            quad.count { it.y < margin } >= 2 ||
            quad.count { it.y > h - margin } >= 2
    }

    // ------------------------------------------------------------------
    // Face assignment + measurement
    // ------------------------------------------------------------------

    private data class AssignedFace(val quad: Array<Point>, val name: String, val longCm: Double)

    /**
     * Positional face assignment. Topmost centroid = top face; of the
     * remaining, larger area = front. Aspect bands are only a sanity gate.
     * Top and front long dim = box length; side long dim = box width.
     */
    private fun assignFaces(faces: List<Array<Point>>): List<AssignedFace> {
        val sane = faces.filter { quad ->
            val (aspect, _, _) = quadAspect(quad)
            aspect in ASPECT_SANE_MIN..ASPECT_SANE_MAX
        }
        if (sane.isEmpty()) return emptyList()
        if (sane.size == 1) return listOf(AssignedFace(sane[0], "panel", NOMINAL_LONG_CM))
        val byY = sane.sortedBy { quad -> quad.sumOf { it.y } / quad.size }
        val top = byY.first()
        val rest = byY.drop(1).sortedByDescending { polygonArea(it) }
        val out = mutableListOf(
            AssignedFace(top, "top", NOMINAL_LONG_CM),
            AssignedFace(rest[0], "front", NOMINAL_LONG_CM),
        )
        if (rest.size > 1) out.add(AssignedFace(rest[1], "side", NOMINAL_SIDE_LONG_CM))
        return out
    }

    /** (aspect long/short, w_px, h_px) from averaged opposite sides. */
    private fun quadAspect(quad: Array<Point>): Triple<Double, Double, Double> {
        val (a, b, c, d) = quad
        val w = (hypot(b.x - a.x, b.y - a.y) + hypot(c.x - d.x, c.y - d.y)) / 2
        val h = (hypot(c.x - b.x, c.y - b.y) + hypot(d.x - a.x, d.y - a.y)) / 2
        if (min(w, h) < 1) return Triple(0.0, w, h)
        return Triple(max(w, h) / min(w, h), w, h)
    }

    /**
     * Measure one prediction against the assigned faces: pick the face with
     * the largest bbox overlap, rectify only the overlap polygon (transforming
     * the full bbox after a tiny overlap extrapolates off-panel corners and
     * can grossly inflate the reported size), and convert via the face's
     * nominal long dimension. Null when the bbox overlaps no face.
     */
    private fun measureOnFace(
        prediction: Prediction,
        faces: List<AssignedFace>,
        scale: Double,
    ): DamageSize? {
        if (faces.isEmpty()) return null
        val box = MatOfPoint2f(
            Point((prediction.left * scale).toDouble(), (prediction.top * scale).toDouble()),
            Point((prediction.right * scale).toDouble(), (prediction.top * scale).toDouble()),
            Point((prediction.right * scale).toDouble(), (prediction.bottom * scale).toDouble()),
            Point((prediction.left * scale).toDouble(), (prediction.bottom * scale).toDouble()),
        )

        var bestArea = 0.0
        var bestFace: AssignedFace? = null
        var bestOverlap: Array<Point>? = null
        for (face in faces) {
            val overlap = MatOfPoint2f()
            val area = Imgproc.intersectConvexConvex(MatOfPoint2f(*face.quad), box, overlap, true)
            if (area > bestArea && overlap.toArray().size >= 3) {
                bestArea = area.toDouble()
                bestFace = face
                bestOverlap = overlap.toArray()
            }
        }
        if (bestFace == null || bestOverlap == null) return null

        // Rectify preserving the quad's own side proportions; the dst rect is
        // in the same polygon order as the quad, so sides map 1:1.
        val (_, wPx, hPx) = quadAspect(bestFace.quad)
        val f = RECTIFIED_LONG_SIDE / max(wPx, hPx)
        val rectW = wPx * f
        val rectH = hPx * f
        val src = MatOfPoint2f(*bestFace.quad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(rectW, 0.0), Point(rectW, rectH), Point(0.0, rectH)
        )
        val homography = Imgproc.getPerspectiveTransform(src, dst)
        val warped = MatOfPoint2f()
        Core.perspectiveTransform(MatOfPoint2f(*bestOverlap), warped, homography)
        val warpedPoints = warped.toArray()
        val dmgW = warpedPoints.maxOf { it.x } - warpedPoints.minOf { it.x }
        val dmgH = warpedPoints.maxOf { it.y } - warpedPoints.minOf { it.y }
        val pxPerCm = max(rectW, rectH) / bestFace.longCm

        Log.i(
            TAG,
            "box_face measurement: class=${prediction.clazz} face=${bestFace.name} " +
                "overlapArea=$bestArea widthCm=${dmgW / pxPerCm} heightCm=${dmgH / pxPerCm}"
        )
        return sized(prediction, widthCm = dmgW / pxPerCm, heightCm = dmgH / pxPerCm)
    }

    /** Coarse whole-silhouette scale, or null when the hull is weak evidence. */
    private fun boxEdgePxPerCm(hull: MatOfPoint, hullSource: String, w: Int, h: Int): Double? {
        if (hullSource != "edge") return null
        val rect = Imgproc.minAreaRect(MatOfPoint2f(*hull.toArray()))
        val rectArea = rect.size.width * rect.size.height
        if (rectArea <= 0.0) return null
        if (Imgproc.contourArea(hull) / rectArea < MIN_RECT_FILL) return null
        if (borderTouches(hull, w, h) >= 4) return null
        val longEdge = max(rect.size.width, rect.size.height)
        if (longEdge < 10.0) return null
        return longEdge / NOMINAL_LONG_CM
    }

    private fun borderTouches(hull: MatOfPoint, w: Int, h: Int): Int {
        val m = 0.02 * max(w, h)
        val points = hull.toArray()
        var touches = 0
        if (points.any { it.x < m }) touches++
        if (points.any { it.x > w - m }) touches++
        if (points.any { it.y < m }) touches++
        if (points.any { it.y > h - m }) touches++
        return touches
    }

    private fun sized(prediction: Prediction, widthCm: Double, heightCm: Double) = DamageSize(
        clazz = prediction.clazz,
        confidence = prediction.confidence,
        widthPixels = prediction.width,
        heightPixels = prediction.height,
        x = prediction.x,
        y = prediction.y,
        widthCm = widthCm.toFloat(),
        heightCm = heightCm.toFloat(),
        pixelsPerCmX = null,
        pixelsPerCmY = null,
    )

    private fun unsized(prediction: Prediction) = DamageSize(
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

private operator fun Array<Point>.component1(): Point = this[0]
private operator fun Array<Point>.component2(): Point = this[1]
private operator fun Array<Point>.component3(): Point = this[2]
private operator fun Array<Point>.component4(): Point = this[3]
