# Experiments Log

## OpenCV component 3 — reference-card detection self-test

- **Date:** 2026-07-28
- **Script:** `scripts/card_measure.py`
- **Test image:** `images/craft_with_card.png` (706x934, synthetic card composited onto a real damaged-box photo)
- **Ground truth:** card = 254 px wide, 8.56 cm real width -> cm_per_pixel = 0.0337
- **Result:** card detected at **257.0 px** (err +1.2%), cm_per_pixel = **0.03331** (err +1.2%), 100 px -> 3.33 cm (truth 3.37 cm)
- **Verdict: PASS** (tolerance ±5%)
- Rejected candidate confirmed working as intended: Jumpman-logo contour (380.5x248.9, ratio 1.529, quad=False, fill=0.208) correctly excluded by the fill-ratio gate, not by luck.
- **Caveat:** this only verifies code plumbing (detection runs, arithmetic is correct) on a pixel-flat synthetic composite. It does NOT validate real-world cm accuracy — that requires physical-card + ruler photos (see Timeline: "Capture physical card+ruler photos" phase).
