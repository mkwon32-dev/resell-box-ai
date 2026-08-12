# ResellBox AI

AI system for detecting sneaker box damage and estimating resale-risk from photos.

**[⬇ Download the Android app (APK)](https://github.com/mkwon32-dev/resell-box-ai/releases/latest)** — install on any Android phone; no setup, talks to the hosted backend.

## Goal

ResellBox AI analyzes box damage from a user-uploaded photo and returns:

- damage type
- detected damage location
- estimated damage size
- risk level: `Low`, `Caution`, or `High`

This project is only a reference tool. It does **not** replace official resale inspection from platforms such as KREAM, StockX, or GOAT.

## Damage Types

We use three damage classes for object detection:

- `scratch`
- `dent`
- `tear`

`normal` images are used as negative examples with no bounding boxes.

## System Design

The system has three main parts:

1. **Roboflow / Object Detection**
   - Detects damage location with bounding boxes.
   - Classifies each detected damage as `scratch`, `dent`, or `tear`.

2. **OpenCV Damage Measurement**
   - If a reference card (8.56 × 5.398 cm) is in frame it is auto-detected
     and used for the most accurate px→cm scale.
   - Otherwise no reference object is needed: the box itself is the scale
     reference. OpenCV finds the box silhouette, splits it into visible face
     panels, and rectifies the damaged panel to fronto-parallel with a
     homography (this makes the measurement robust to weird camera angles).
   - Card-free damage is measured as a fraction of the panel, then converted
     to cm using a nominal sneaker-box length (33–37 cm; we assume 35 cm).
   - The app reports estimated damage dimensions and detected count.

3. **Rule-Based Risk Scoring**
   - Risk is calculated from damage type and longest measured side; the most
     severe detection wins. Area/count rules are planned but not implemented.
   - Risk is not manually labeled during training.

Example:

```text
tear + length >= 9 cm → High
small scratch → Low
medium dent → Caution
```

## Data Labeling Plan

Roboflow project type:

```text
Object Detection / Bounding Boxes
```

Labels:

```text
scratch
dent
tear
```

Labeling rules:

- Draw a bounding box around each visible damage.
- If one image has multiple damage types, label each damage separately.
- Normal images should have no bounding box.
- Do not label `risk_label` in Roboflow.
- Risk is calculated later using OpenCV measurements and rules.

## Size Estimation

A reference card in frame gives the best scale; without one, the box itself
is the scale reference — the user only has to keep the whole box in frame.

User instruction:

```text
Keep the whole box in frame. Optionally lay a credit-card-sized card
(8.56 × 5.398 cm) flat next to the damage for the most accurate sizing.
```

Measurement ladder (on-device: `DamageMeasurement.kt` + `BoxFaceMeasurement.kt`;
Python prototype of the card-free tiers:
`sneaker-box-dataset/measurement/measure_box_face.py`), from most to least
accurate — the card is auto-detected, no user toggle:

| Scene | Method | `scale_source` | Verdict behavior |
|---|---|---|---|
| Reference card found | card quad → homography → px/cm from known card dims | `card` | full cm rules |
| Box face found | face quad → homography → panel-relative → cm | `box_face` | full cm rules |
| Silhouette only | coarse scale: silhouette long edge = 35 cm | `box_edge` | full cm rules (coarse) |
| Close-up, no box edges | no size emitted | `none` | Caution floor |

The card tier wins when present because the card's dimensions are exactly
known, while the box tiers lean on a 33–37 cm length prior (about ±6% alone).
A card is only trustworthy when it is coplanar with the damage and detected
with high quality — low-quality card detections fall through to the box-face
tiers rather than poisoning the scale. For the card-free tiers, classical
face detection and assignment are the dominant error source: the current
synthetic benchmark has 37% median cross-face scale spread. Treat card-free
cm values as roughly ±30% estimates. Estimates are marked with `~`.

The cm thresholds below are equivalent to panel-relative fractions (at the
35 cm nominal): tear ≥ 9 cm ⇔ ≥ 0.26 of the panel's long dimension;
dent ≥ 12 / ≥ 4 cm ⇔ ≥ 0.34 / ≥ 0.11; scratch ≥ 10 cm ⇔ ≥ 0.29;
surface damage ≥ 8 cm ⇔ ≥ 0.23. Unmeasured damage (`none`) is floored at
`Caution` — it can never be cleared as `Low`.

## 5-Week Schedule

### Week 1: Scope, Dataset Setup, and Labeling Rules

- Finalize project direction: object detection + OpenCV size estimation.
- Define damage classes: `scratch`, `dent`, or `tear`.
- Treat `normal` as no-bbox negative examples.
- Create Roboflow project.
- Filter collected images and remove unclear examples.
- Start manual labeling for clear examples.

### Week 2: Data Labeling and Baseline Model

- Label damage regions with bounding boxes.
- Include multiple damage boxes if one image has multiple defects.
- Train the first baseline model.
- Check confusion matrix and weak classes.
- Add or clean data based on early model results.

### Week 3: OpenCV Measurement Module

- Implement box-silhouette + face-quad detection with OpenCV.
- Rectify the damaged panel with a homography; convert panel fraction to cm
  via the nominal box length.
- Use detected damage bbox to estimate length and area.
- Add simple image quality checks:
  - blur
  - brightness
  - visibility

### Week 4: Risk Scoring and Prototype

- Build rule-based risk scoring.
- Combine:
  - damage type
  - bbox count
  - estimated length
  - estimated area
- Create simple app/demo interface.
- Show result:
  - damage type
  - size estimate
  - risk level
  - short explanation

### Week 5: Testing, Edge Deployment, and Presentation

- Test with real sneaker box images.
- Convert model to mobile-friendly format if possible.
- Test inference speed on Galaxy S25 or a local/mobile demo.
- Prepare demo examples for `Low`, `Caution`, and `High`.
- Summarize limitations:
  - not official inspection
  - limited dataset
  - sizes are estimates from typical box dimensions (33–37 cm), shown with `~`
  - close-up photos without box edges are unsized and floored at `Caution`
  - model may struggle with unclear or mixed damage

## Final Output Example

```text
Damage Type: tear
Detected Count: 1
Estimated Length: 10.2 cm
Risk Level: High
Reason: Large tear detected over the high-risk length threshold.
```

## Tech Stack

- Roboflow for annotation and dataset management
- Object detection model such as YOLO or Roboflow Train
- OpenCV for box-face detection, homography rectification, and size estimation
- Rule-based Python or JavaScript logic for risk scoring
- TensorFlow Lite or another mobile-friendly model format for edge deployment
