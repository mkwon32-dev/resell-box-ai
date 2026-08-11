# CLAUDE.md (export/ — Phase D: edge conversion)

Task-specific guidance for converting `weights.pt` (YOLO11s) to TFLite and profiling it on
the target device. The root `CLAUDE.md` has project-wide context; read it first. This file
is the detail for Phase D only.

---

## Scope & folder rule

- Conversion scripts live in `export/`. Artifacts (`.tflite`, `.onnx`, benchmark logs) live
  in `export/models/`.
- Files under `scripts/` are **not modified** during Phase D. `scripts/detect_and_measure.py`
  and `scripts/card_measure.py` stay exactly as they are.
- Verification always runs the existing harness:
  `python scripts/detect_and_measure.py --weights <exported model path>`
  Never write a separate verification script for this phase.

---

## Target device

- **Samsung Galaxy S25**, Snapdragon 8 Elite, on-device NPU inference via the
  QNN delegate (per root `CLAUDE.md`).
- **Use the concrete device name, not `(Family)`.** `hub.Device("Samsung Galaxy S25
  (Family)")` fails at compile job creation with an unclear error (`No devices match the
  given OS name, version, and attributes`), even though the same name resolves fine via
  `hub.get_devices()`. `export/profile_aihub.py` targets `"Samsung Galaxy S25"` instead.

---

## Deliverables

1. Float TFLite export of `weights.pt`.
2. Real S25 latency measurement (via Qualcomm AI Hub cloud profiling).
3. Accuracy comparison before/after quantization (mAP re-measured at each ladder step).

## Quantization ladder

INT8 → W8A16 → FP16. Re-measure mAP after **each** step — do not skip a rung to save time;
the point of the ladder is knowing which step cost the accuracy, not just the final number.

---

## Windows blocker: no direct TFLite export → ONNX path instead

`export/to_tflite.py` was tried first and failed: ultralytics 8.4.115 replaced the native
TFLite exporter with a unified "LiteRT" export that hard-asserts to **Linux x86 or macOS
only** (`AssertionError: LiteRT export only supported on Linux x86 and macOS`). This machine
is Windows, so direct `.pt → .tflite` is not available here.

**Path used instead:** `.pt` → **ONNX** (`export/to_onnx.py`) → **Qualcomm AI Hub compile
job** → `.tflite`. AI Hub's compile step (not the local machine) produces the actual
`.tflite`, so the "float TFLite export" deliverable above is completed via AI Hub, not via a
local ultralytics export. Do not re-attempt a local TFLite export on this machine — it is a
platform limitation, not a transient error.

## Model output shape (no built-in NMS)

The exported ONNX graph outputs shape **`(1, 6, 8400)`**:
- `6` = 4 bbox coords + 2 class scores (`dent`, `surface_damage` — see class index order
  below).
- `8400` = anchor points (640/8² + 640/16² + 640/32² grid cells summed across the 3
  detection heads).
- **NMS is NOT included in the graph.** Unlike the local `ultralytics` `.pt` inference path
  (which runs decoding + NMS for you inside `model.predict()`), consumers of the raw ONNX
  (or the downstream TFLite from AI Hub) must implement box decoding and NMS themselves.
  This applies to the Android app's Kotlin inference path — plan for it there.

---

## Class index order (from `model.names`)

```
{0: 'dent', 1: 'surface_damage'}
```

Confirmed by `export/to_onnx.py` (prints `model.names` before exporting) on 2026-08-05 —
first observed from the earlier (failed) `export/to_tflite.py` attempt, since the class
order comes from the loaded `.pt` graph, not from the export step itself. This is what the
`6 = 4 + 2` class-score dimension in the ONNX/TFLite output (see above) maps to: index 0 =
`dent`, index 1 = `surface_damage`.

---

## Known landmine: letterbox / coordinate system (from scripts/CLAUDE.md)

- With local `ultralytics` (`.pt`, PC), `boxes.xywh` is already in ORIGINAL-image pixels —
  the 640 letterbox is undone for you.
- **This convenience disappears with a raw TFLite model.** The raw output is in
  letterboxed-640 space; padding must be stripped and the scale undone by hand before the
  boxes can be combined with `card_measure.py`'s card pixels.
- If a TFLite run produces shifted boxes or wrong cm values, **check the letterbox
  inverse-transform first** — it is the most likely cause, not the model itself.
- Solve this in Python here under `export/` (so it can be verified against known-good `.pt`
  output on the same image), then hand the verified formula to the Android team for the
  Kotlin re-implementation.

---

## Environment

- Export scripts run in the working `ultralytics` environment used elsewhere in this repo.
- `qai-hub` (needed for Qualcomm AI Hub compile/quantize/profile steps) installs into a
  **separate venv**, `.venv-export`, because it can conflict with the working `ultralytics`
  environment. Before creating it: `pip freeze > requirements_backup.txt` from the main
  environment first, so it can be restored if something breaks.

---

## On export failure

If an export step fails, report the full error text verbatim and stop. Do not silently try
an alternate conversion path (different format flag, different opset, downgrading a
package, etc.) — surface the failure so the root cause can be decided on, not guessed at.
