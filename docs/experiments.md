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

## detect_and_measure.py — new weights.pt verification + real-photo test

- **Date:** 2026-08-11
- **Weights:** `weights.pt` (overwritten with new training run)
- **Scripts:** `scripts/detect_and_measure.py` (card_measure.py's algorithm untouched)
- **Environment note:** `cv2.imread`/`imwrite` cannot open paths containing the repo's
  `문서` (non-ASCII) folder segment on this machine (Windows ANSI-fopen limitation in this
  OpenCV build — confirmed via a working `np.fromfile` + `cv2.imdecode` round-trip on the
  same bytes). All runs below were executed against a mirrored copy of `scripts/`,
  `images/`, and `weights.pt` in an ASCII-only scratch path; results and debug images were
  copied back into the real repo unchanged. This is exactly the failure mode the root
  CLAUDE.md's "Working dir: outside OneDrive sync" convention exists to avoid.

**1. `model.names` check** — new `weights.pt` reports `{0: 'dent', 1: 'surface_damage'}`,
identical class set/order to the existing two-class scheme (root CLAUDE.md). **Match confirmed.**

**2. Shared-array resize** — `detect_and_measure.py` now resizes once (long side -> 1280px)
right after `cv2.imread`, and passes that single array into both `run_detection()` (YOLO)
and `card_measure.measure()` (card + damage pixels), eliminating the risk of the two stages
disagreeing on scale.

**3. `--debug` step images** — added; saves `{stem}_01_canny.png`, `{stem}_02_contours.png`
(all raw contours pre-filtering), `{stem}_03_card_selection.png` (via card_measure.py's own
unmodified `draw_debug()`) to `debug/`.

**4. Regression gate (`images/craft_with_card.png`)** — run via `card_measure.py`'s own
self-test (native resolution, no resize, code path untouched): **257.0 px (+1.2%),
cm_per_pixel 0.03331 (+1.2%) — PASS**, identical to the 2026-07-28 baseline above. New
weights do not affect card detection (card_measure.py never loads `weights.pt`). Demo runs
proceeded only after this passed.

**5. Results table:**

| Image | Card px width | cm/px | Damage detections (YOLO) | Damage cm |
|---|---|---|---|---|
| `craft_with_card.png` (card-detection regression only, no YOLO run) | 257.0 px | 0.03331 | — | — |
| `demo_1.jpg` (real photo, 4000x3000 -> resized 1280 long side) | not found | not found | 13 (mostly `dent`, 1 `surface_damage`, conf 0.38-0.95) | size unavailable (all) |
| `demo_2.jpg` (real photo, 4000x3000 -> resized 1280 long side) | not found | not found | 5 (3 `dent`, 1 `surface_damage`, 1 more `dent`, conf 0.55-0.92) | size unavailable (all) |
| `demo_3.jpg` (real photo, 4000x3000 -> resized 1280 long side) | not found | not found | 7 (6 `dent`, 1 `surface_damage`, conf 0.73-0.94) | size unavailable (all) |

- **Finding — card not found on all three real photos.** `find_candidates()` (unmodified)
  returns only one candidate on each raw photo: the entire box outline (`demo_1`: 2056x932
  ratio 2.205 fill 0.207; `demo_3`: 2127.7x986.2 ratio 2.158 fill 0.344), rejected
  "not 4-sided" every time. The card is photographed lying flush on top of the box,
  touching/overlapping its printed edge — `MORPH_CLOSE`'s 15x15 kernel bridges the card's
  boundary into the box's outer contour before `findContours` runs, so the card never
  appears as its own external contour (`RETR_EXTERNAL` only returns the merged outer blob).
  This is a new variant of the failure modes already documented in `scripts/CLAUDE.md`
  (stripe-splits-the-card, logo-passes-gate, embossed-texture) — root cause is physical card
  placement, not a code bug, and reproduces identically at native resolution (verified via
  `find_candidates()` directly, before any resize, for all three photos). Confirms
  `card_measure.py` degrades to "not measurable" rather than crashing or reporting a wrong
  number, as designed. Not fixed here — algorithm logic was explicitly out of scope for this
  and the follow-up pass; flagging for the next capture round: card should sit on an empty
  patch of the same surface, not overlapping the box's own printed edge.
- **Also observed (model, not pipeline):** several low-confidence (0.38-0.45 on demo_1/2;
  demo_3 stayed >=0.73) `dent` detections cluster along crease/fold lines — a data/model
  precision note for the next training iteration, not a plumbing issue.
- Result images (`debug/demo_1_result.png`, `debug/demo_2_result.png`, `debug/demo_3_result.png`)
  confirm items 6-7: every detection box is drawn even when not measurable, labeled
  "size unavailable" on a semi-transparent backing plate, with the "NOT MEASURABLE" banner
  rendered the same way.

## Edge conversion — AI Hub compile + profile (Samsung Galaxy S25)

- **Date:** 2026-08-11
- **Script:** `export/profile_aihub.py`
- **Device:** Samsung Galaxy S25
- **Compile job:** https://workbench.aihub.qualcomm.com/jobs/jgnkw6dmg/ (no explicit target_runtime)
- **Profile job:** https://workbench.aihub.qualcomm.com/jobs/jp06xz8ep/
- **Output:** `export/models/weights_float.tflite`
- **Estimated inference time:** 3.61 ms

```
Estimated inference time: 3.61 ms
Layers: 341
Compute unit breakdown (by layer count):
         NPU:  341 layers (100.0%)
Compute unit breakdown (by execution time):
         NPU:   10.371 ms (100.0%)
```
