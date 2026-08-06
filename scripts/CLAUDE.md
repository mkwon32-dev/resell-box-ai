# CLAUDE.md (scripts/ — OpenCV card measurement)

Task-specific guidance for the OpenCV component. The root `CLAUDE.md` has the project-wide
context; this file is the detail for card detection + pixel→cm conversion. Read both.

---

## Goal of this component

Find a standard ID/credit card in the photo and use it as a physical ruler, so that a
damage region's pixel size can be converted into real centimeters.

- Card is ISO/IEC 7810 ID-1: **8.56 cm × 5.398 cm, aspect ratio ≈ 1.586**.
- Core formula: `cm_per_pixel = 8.56 / card_pixel_width`, then
  `damage_cm = damage_pixel_length * cm_per_pixel`.
- This component is **fully decoupled from the YOLO model.** Card detection is classic
  geometry (no ML), so it needs no `.pt` file.

---

## Build order - COMPLETE (kept as rationale)

1. **Card detection + cm math only.** Feed **fake damage coords** (e.g. w=24, h=76) so the
   whole "detect card → cm_per_pixel → damage_cm" plumbing runs and can be verified in
   isolation. Validate against the prototype image (below).
2. **Only after step 1 is verified**, replace the fake coords with real model output
   (`YOLO("weights.pt")`). Wiring the model in first makes it impossible to tell a card-detection
   bug from a model-integration bug. One thing at a time.

Keep `weights.pt` at the project root for step 2, but do not load it during step 1.

---

## Prototype test image (the ground-truth harness)

`images/craft_with_card.png` — a synthetic card composited onto a real damaged-box photo,
for building/debugging without physical-card photos yet.

- **Ground truth:** card pixel width = **254 px**, real width = **8.56 cm**, so correct
  `cm_per_pixel` = **0.0337**. A 100px object must come out as **3.37 cm**. Use this as the
  pass/fail check (target within ±5%).
- **Limitation:** this verifies CODE PLUMBING only — does detection run, does the arithmetic
  flow. It does NOT verify real-world cm accuracy (the composite card is pixel-flat, not
  physically placed; the damage's true cm is unmeasured). Real accuracy validation needs
  physical-card photos + ruler measurements later.

Current result: `scripts/card_measure.py` measures **257px (+1.2%, within ±5%)** on this image.

---

## Card detection — the recipe that ACTUALLY works

The naive recipe (grayscale → Canny → findContours → approxPolyDP → keep 4-vertex contour
with aspect ≈ 1.586) does NOT work as-is. Four failure modes were found and fixed on the
prototype image. **Do not re-simplify these away** — each fix exists for a reason:

1. **Magnetic stripe splits the card contour.** The stripe is the strongest edge inside the
   card, so plain Canny→findContours returns only the lower white panel (251×119,
   ratio 2.109 → wrongly rejected).
   **Fix:** `MORPH_CLOSE` the edge map before `findContours` (kernel size not sensitive;
   9–31 all work).

2. **`approxPolyDP` eats ~3% of the width.** Rounded card corners make the polygon
   approximation chord inward (246px, −3.2%), burning most of the ±5% budget.
   **Fix:** use `approxPolyDP` only as a "is it 4-sided?" shape test; take the actual
   measurement from `minAreaRect` on the raw contour (257px, +1.2%).

3. **A printed logo (e.g. Jumpman) passes the aspect-ratio gate.** Its bounding contour
   (380×249, ratio 1.529) falls inside the 1.586 ± 10% window — every Jordan box has one,
   so vertex-count rejection is not reliable.
   **Fix:** add a **fill-ratio gate** (contour area / minAreaRect area). The card fills ~97%,
   the logo silhouette ~21%; require **≥ 85%**. Also pick the best aspect-ratio match, not
   the largest-area candidate.
   
 4. Heavily embossed/textured surfaces swallow the card contour. Canny fires on the texture everywhere; MORPH_CLOSE bridges the card edges into the surrounding pattern, so RETR_EXTERNAL never returns the card as its own contour. Observed on a crocodile-embossed lid (all lid placements failed; all smooth-floor placements passed). Fix at the UX level: card must sit on a smooth, low-texture surface. Same-plane principle still applies — prefer a smooth area of the box itself, else the adjacent floor.

Summary pipeline: grayscale → Canny → **MORPH_CLOSE** → findContours → for each contour:
approxPolyDP is-it-4-sided test + aspect ≈ 1.586 (±10%) + **fill-ratio ≥ 0.85** → among
survivors pick best aspect match → measure width with **minAreaRect** (not approxPolyDP).

---

## Damage length rule

- Axis-aligned damage: use the box's long side.
- Clearly diagonal damage: use the diagonal `sqrt(w^2 + h^2)`. The long side alone
  under-measures diagonal scratches by 20–30%.

---

## Coordinate conventions (bug traps)

- **Roboflow box format = CENTER (x, y) + width + height**, NOT top-left corner. To draw or
  to get corners: `x1 = x - w/2`, `y1 = y - h/2`. Forgetting the `/2` is the most common bug.
- **Coordinate systems (PC vs phone):** with local `ultralytics` (and equally via the
  Roboflow API), `boxes.xywh` is already in ORIGINAL-image pixels — the 640 letterbox is
  undone for you — so YOLO boxes and OpenCV card pixels share one coordinate system.
  This convenience DISAPPEARS with a raw TFLite model: padding must be removed and the
  scale undone by hand before combining with card pixels.
  **This surfaces in Phase D, not E** — it is the first thing to check if a TFLite run
  produces shifted boxes or wrong cm values. Solve it in Python under `export/` and hand
  the verified formula to the Android team.

---

## Geometric validity & fallbacks

- **Same-plane / perpendicular requirement:** the simple ratio is valid only when the card
  lies on the same surface as the damage and the camera is roughly perpendicular. Enforce via
  the aspect-ratio gate (card ratio far from 1.586 = oblique shot → reject / re-shoot).
  MVP = simple ratio + aspect gate. Stretch goal = homography (perspective correction) using
  the card's four corners. Never ship the simple ratio without the aspect gate.
- **Card not found:** do NOT crash. Return "size not measurable," still report the detected
  class, prompt a re-shoot.
- **Dark box + dark card:** if Canny can't find the card, the UX guidance is to place a
  light-colored card on a contrasting nearby surface (same plane as the damage).

---

## Environment

- Python, `opencv-python` + `numpy`. Debug in VSCode on PC.
- Verify every change against `images/craft_with_card.png` (target 254px ± 5%).
- Log runs/results in `docs/experiments.md`.
