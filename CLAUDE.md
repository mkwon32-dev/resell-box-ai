# CLAUDE.md (project root)

Single source of truth for project-wide decisions. If old docs, comments, or Roboflow
settings contradict this file, this file wins. Sub-folders have their own CLAUDE.md with
task-specific detail (e.g. `scripts/` for the OpenCV work) — read those too when working there.
Last revision: 2026-08-04.

---

## What this project is

ResellBox AI — a mobile app that photographs sneaker-box damage and measures each damage region in real centimeters, using a credit card in frame as a ruler. 
It **supplements, not replaces**, official authentication. Output is
always framed as an estimate, never a confirmed grading.

- **Scope: prototype** (portfolio / presentation), not production. Favor a working
  end-to-end demo over maximum accuracy.
- **Target device:** Samsung Galaxy S25 (Snapdragon 8 Elite), on-device NPU inference.
- **Deadline:** final presentation ≈ 2026-08-13. Deployment + app-integration phases are
  protected; earlier phases may not overrun into them.

---

## The pipeline — four components, one photo

Only **component 2 is a neural network.** The other three are ordinary code (classic CV /
plain logic). This split is the core architecture — keep them decoupled.

1. **Quality gate** (OpenCV, CPU) — reject blurry / dark / oblique photos before inference.
   Includes a card-aspect-ratio check (oblique shots break the cm math).
2. **Damage detector** (YOLO11s, NPU) — the only ML model. Finds each damage region, labels
   it `dent` or `surface_damage`, outputs bounding boxes. Multiple detections per photo are
   normal.
3. **Pixel→cm** (OpenCV, CPU) — find a standard ID/credit card in the frame, use its fixed
   width (8.56 cm) as a ruler to convert damage pixels to real centimeters.
4. **Risk scorer** (rules, CPU) — **DEFERRED, out of scope for this build.** Thresholds have
   no objective basis yet, so any Low/Caution/High assignment would be arbitrary. The
   deliverable ends at the measured cm value. Do not implement.

**Key consequence:** only the YOLO model is exported to TFLite and run on the NPU. OpenCV and
the rules are never converted — they run on the phone CPU. Editing the OpenCV logic never
requires re-exporting the model.

---

## Fixed decisions (do not reopen)

- **Two classes:** `dent`, `surface_damage`. No `normal` class — "normal" = absence of
  detections at the pipeline level, never a bounding-box label.
- **Model: YOLO11s, LOCKED.** RF-DETR A/B comparison was dropped for prototype scope.
- **Output = objective cm measurement.** Risk grading is deferred (no data-backed
  thresholds); revisit after collecting reseller judgments. Not a damaged/undamaged classifier.
- **Data: self-captured only.** Public datasets were all unusable (classification-style or
  whole-box labels; none label the damage *region*, which the cm pipeline needs). Don't retry them.

---

## Current status (2026-08-04)

- **DONE — Dataset:** self-captured sneaker-box images, 2 classes, labeled in Roboflow.
- **DONE — Model:** `box_ai_detection-14-yolo11s` (YOLO11s, COCO checkpoint, 640 letterbox).
  Test set: mAP@50 **64.6%**, Precision 72.1%, Recall 67.9%, F1 69.2%. Good enough for a
  prototype demo; accuracy improvement is a later task (analyze FP/FN → add targeted data).
- **Done — OpenCV card→cm** on PC. See `scripts/CLAUDE.md` for the detail. Decoupled
  from the model; needs no YOLO weights for the card-detection work itself.
- **In Progress — Edge conversion** (D): export `.pt` → TFLite → Qualcomm AI Hub compile / quantize
  (INT8→W8A16→FP16 ladder, re-measure mAP after each) / profile on a real S25 in the cloud.
  Only the model goes through this.
- **NEXT — Android app** (E): CameraX → quality gate → LiteRT+QNN-delegate inference (NPU) →
  OpenCV card measurement (CPU) → risk scorer → UI overlay. The PC OpenCV work is the
  reference logic + ground-truth values; the app re-implements it in Kotlin.

---

## How model files flow (important mental model)

- **PC (now):** use `best.pt` directly. No TFLite, no quantization. Python + `ultralytics` +
  `opencv-python`. This stage validates the pipeline logic and produces ground-truth values.
- **Phone (later):** `best.pt` → export TFLite → quantize → bundle in the app. Only the model
  is converted; OpenCV is re-written in Kotlin (OpenCV Android SDK), CPU-side.
- PC and phone use **different model files (.pt vs .tflite) and different languages
  (Python vs Kotlin)**, but the interface is the same: the model returns box coords
  `[x, y, w, h] + class`, and OpenCV consumes those. Logic + ground-truth values carry over;
  files do not.
- **Phase D folder rule:** conversion scripts in `export/`, artifacts in `export/models/`.
  Files under `scripts/` are NOT modified during Phase D. Verification always runs
  `python scripts/detect_and_measure.py --weights <tflite>` — never write a separate
  verification script.
---

## Environment & conventions

- Working dir: `~/projects/resell-box-ai` (outside OneDrive sync).
- OpenCV component: Python, `opencv-python` + `numpy`, prototype/debug in VSCode.
- **Phase D:** install `qai-hub` in a separate venv (`.venv-export`) — it can break the
  working ultralytics environment. Run `pip freeze > requirements_backup.txt` first.
- Log experiments in `docs/experiments.md`; log major decisions in `docs/decisions.md`.
  These feed the final presentation.
- **Session boundaries:** start a new Claude Code conversation per phase (OpenCV build →
  edge conversion → app → QA). This file re-establishes context each time. Use `claude --resume`
  to continue a specific session.

---

## Deadline note

Get the pipeline running end-to-end EARLY, even with an imperfect model. A running demo with
a mediocre model beats a great model with no demo. The model can be swapped in later.
