package com.resellbox.ai.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.resellbox.ai.data.Prediction

/**
 * 원본 비트맵 위에 탐지 bbox 와 라벨을 그려 새 비트맵을 반환한다.
 * 탐지 좌표가 원본 비트맵의 픽셀 좌표계이므로 별도 스케일 변환이 필요 없다.
 */
object ResultRenderer {

    private fun colorFor(clazz: String): Int = when (clazz.lowercase()) {
        "dent" -> Color.rgb(0xFB, 0x8C, 0x00)            // 주황
        "surface_damage" -> Color.rgb(0xFD, 0xD8, 0x35)  // 노랑
        "tear" -> Color.rgb(0xE5, 0x39, 0x35)            // 빨강 (폴백)
        "scratch" -> Color.rgb(0x43, 0xA0, 0x47)         // 초록 (폴백)
        else -> Color.rgb(0x1E, 0x88, 0xE5)              // 파랑
    }

    fun draw(src: Bitmap, predictions: List<Prediction>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = (maxOf(out.width, out.height) * 0.006f).coerceAtLeast(3f)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (maxOf(out.width, out.height) * 0.03f).coerceAtLeast(24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        predictions.forEach { p ->
            val c = colorFor(p.clazz)
            boxPaint.color = c
            labelBg.color = c
            canvas.drawRect(p.left, p.top, p.right, p.bottom, boxPaint)

            val label = "${p.clazz} ${(p.confidence * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize
            val ty = (p.top - th * 0.4f).coerceAtLeast(th)
            canvas.drawRect(p.left, ty - th, p.left + tw + 16f, ty + 8f, labelBg)
            canvas.drawText(label, p.left + 8f, ty, textPaint)
        }
        return out
    }
}
