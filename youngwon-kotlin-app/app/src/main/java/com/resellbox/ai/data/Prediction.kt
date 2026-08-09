package com.resellbox.ai.data

/** 원본 이미지 픽셀 좌표계에서의 탐지 결과 (중심좌표 + 폭/높이). */
data class Prediction(
    val x: Float,        // bbox 중심 x (px)
    val y: Float,        // bbox 중심 y (px)
    val width: Float,    // px
    val height: Float,   // px
    val confidence: Float,
    val clazz: String
) {
    val left: Float get() = x - width / 2f
    val top: Float get() = y - height / 2f
    val right: Float get() = x + width / 2f
    val bottom: Float get() = y + height / 2f
    val longestSidePx: Float get() = maxOf(width, height)
}

/** 추론에 사용된 이미지 크기(px). 크기 추정 스케일 기준. */
data class ImageSize(val width: Int, val height: Int)
