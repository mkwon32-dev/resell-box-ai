# ResellBox AI

AI system for detecting sneaker box damage and estimating resale-risk from photos.

## Goal

ResellBox AI analyzes box damage from a user-uploaded photo and returns:

- damage type
- detected damage location
- estimated damage size
- risk level: `Low`, `Caution`, or `High`

This project is only a reference tool. It does **not** replace official resale inspection from platforms such as KREAM, StockX, or GOAT.

## Damage Types

We use four damage classes for object detection:

- `scratch`
- `dent`
- `tear`
- `stain`

`normal` images are used as negative examples with no bounding boxes.

## System Design

The system has three main parts:

1. **Roboflow / Object Detection**
   - Detects damage location with bounding boxes.
   - Classifies each detected damage as `scratch`, `dent`, `tear`, or `stain`.

2. **OpenCV Size Estimation**
   - The user places a standard card next to the damaged area.
   - OpenCV detects the card and converts pixels to centimeters.
   - The app estimates damage length, area, and count.

3. **Rule-Based Risk Scoring**
   - Risk is calculated from damage type, size, area, and count.
   - Risk is not manually labeled during training.

Example:

```text
tear + length >= 9 cm → High
small scratch → Low
medium dent → Caution
large stain → High
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
stain
```

Labeling rules:

- Draw a bounding box around each visible damage.
- If one image has multiple damage types, label each damage separately.
- Normal images should have no bounding box.
- Do not label `risk_label` in Roboflow.
- Risk is calculated later using OpenCV measurements and rules.

## Reference Object

For size estimation, the app uses a standard card as the fixed reference object.

User instruction:

```text
Place a standard card next to the damaged area before taking the photo.
```

The card size is assumed to be approximately:

```text
8.56 cm × 5.398 cm
```

OpenCV finds the card, calculates the pixel-to-centimeter ratio, and applies it to the detected damage bounding box.

## 5-Week Schedule

### Week 1: Scope, Dataset Setup, and Labeling Rules

- Finalize project direction: object detection + OpenCV size estimation.
- Define damage classes: `scratch`, `dent`, `tear`, `stain`.
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

- Implement card detection with OpenCV.
- Convert card pixel width to cm scale.
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
  - size estimation depends on card placement
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
- OpenCV for card detection and size estimation
- Rule-based Python or JavaScript logic for risk scoring
- TensorFlow Lite or another mobile-friendly model format for edge deployment
