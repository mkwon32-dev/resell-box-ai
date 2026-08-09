package com.resellbox.ai.measure

import com.resellbox.ai.data.ImageSize
import com.resellbox.ai.data.Prediction

/**
 * 손상 크기를 cm 로 추정한다 (카드/자 없이 상자 자체를 스케일 기준으로).
 *
 * README 의 "measurement ladder" 중 현재 구현된 단계:
 *   - box_edge : 상자 실루엣의 긴 변 = 35cm 로 가정한 coarse 스케일.
 *                (사용자가 상자 전체를 프레임에 담았다는 전제 → 이미지 긴 변 ≈ 상자 긴 변)
 *   - none     : 이미지 크기를 모르면 크기 미측정 → 위험도는 Caution 하한.
 *
 * TODO(Week 3): OpenCV 로 상자 실루엣/면 사각형을 찾아 homography 로 정면화한 뒤
 *               패널 비율 기반으로 측정하면 정확도가 올라간다(scale_source = box_face).
 *               지금은 [findBoxLongEdgePx] 가 이미지 긴 변으로 대체(fallback)한다.
 */
class BoxMeasurer(private val nominalBoxCm: Double = 35.0) {

    enum class ScaleSource { BOX_FACE, BOX_EDGE, NONE }

    data class Measured(
        val prediction: Prediction,
        val lengthCm: Double?,      // null = 미측정
        val scaleSource: ScaleSource
    )

    fun measure(predictions: List<Prediction>, image: ImageSize?): List<Measured> {
        val longEdgePx = findBoxLongEdgePx(image)
        return predictions.map { p ->
            if (longEdgePx == null || longEdgePx <= 0f) {
                Measured(p, null, ScaleSource.NONE)
            } else {
                val cmPerPx = nominalBoxCm / longEdgePx
                Measured(p, p.longestSidePx * cmPerPx, ScaleSource.BOX_EDGE)
            }
        }
    }

    /**
     * 상자 실루엣의 긴 변(px)을 찾는다.
     * 현재는 OpenCV 미구현이라 이미지 긴 변으로 근사한다(coarse box_edge).
     */
    private fun findBoxLongEdgePx(image: ImageSize?): Float? {
        if (image == null || image.width <= 0 || image.height <= 0) return null
        return maxOf(image.width, image.height).toFloat()
    }
}
