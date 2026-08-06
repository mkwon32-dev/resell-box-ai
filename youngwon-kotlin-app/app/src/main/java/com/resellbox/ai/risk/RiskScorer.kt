package com.resellbox.ai.risk

import com.resellbox.ai.measure.BoxMeasurer

/**
 * 규칙 기반 위험도 산정 (README "Rule-Based Risk Scoring").
 *
 * 가장 심각한 탐지가 전체 위험도를 결정한다(most severe wins).
 * 측정 불가(none) 손상은 Low 로 내려갈 수 없고 Caution 이 하한이다.
 *
 * cm 임계값(35cm 공칭 상자 기준):
 *   dent            ≥ 12 → High,  ≥ 4 → Caution
 *   surface_damage  ≥ 8  → Caution        (긁힘/스커프 등 표면 손상)
 *   (tear/scratch 는 현재 모델에 없지만 폴백으로 유지)
 */
object RiskScorer {

    enum class Risk(val order: Int, val label: String) {
        LOW(0, "Low"), CAUTION(1, "Caution"), HIGH(2, "High")
    }

    data class Outcome(
        val risk: Risk,
        val count: Int,
        val topType: String?,
        val topLengthCm: Double?,
        val reason: String
    )

    fun score(measured: List<BoxMeasurer.Measured>): Outcome {
        if (measured.isEmpty()) {
            return Outcome(Risk.LOW, 0, null, null,
                "No damage detected. The box appears normal.")
        }

        var best = Risk.LOW
        var bestIdx = 0
        val perItemRisk = measured.mapIndexed { i, m ->
            val r = riskOf(m)
            if (r.order > best.order) { best = r; bestIdx = i }
            r
        }

        val top = measured[bestIdx]
        val reason = buildReason(best, top, perItemRisk[bestIdx])
        return Outcome(
            risk = best,
            count = measured.size,
            topType = top.prediction.clazz,
            topLengthCm = top.lengthCm,
            reason = reason
        )
    }

    private fun riskOf(m: BoxMeasurer.Measured): Risk {
        val cm = m.lengthCm
        // 미측정(none) → Caution 하한
        if (cm == null) return Risk.CAUTION

        val base = when (m.prediction.clazz.lowercase()) {
            "dent" -> when {
                cm >= 12 -> Risk.HIGH
                cm >= 4  -> Risk.CAUTION
                else     -> Risk.LOW
            }
            "surface_damage" -> if (cm >= 8) Risk.CAUTION else Risk.LOW
            // 아래는 현재 모델에 없는 클래스지만 폴백으로 유지
            "tear" -> if (cm >= 9) Risk.HIGH else Risk.CAUTION
            "scratch" -> if (cm >= 10) Risk.CAUTION else Risk.LOW
            else -> Risk.CAUTION // 알 수 없는 클래스는 보수적으로
        }

        // scale_source == NONE 인 경우 위에서 이미 걸러졌지만, 안전하게 한 번 더 하한 적용
        return if (m.scaleSource == BoxMeasurer.ScaleSource.NONE && base == Risk.LOW)
            Risk.CAUTION else base
    }

    private fun buildReason(
        risk: Risk,
        top: BoxMeasurer.Measured,
        @Suppress("UNUSED_PARAMETER") itemRisk: Risk
    ): String {
        val type = top.prediction.clazz
        val cmText = top.lengthCm?.let { "~%.1f cm".format(it) } ?: "not measured"
        return when (risk) {
            Risk.HIGH -> "Large $type damage ($cmText) exceeds the high-risk threshold."
            Risk.CAUTION -> if (top.lengthCm == null)
                "$type damage detected but size could not be measured (close-up); classified as Caution."
            else "$type damage ($cmText) is at a caution level."
            Risk.LOW -> "Small $type damage ($cmText) — low risk."
        }
    }
}
