# Galaxy S25 QNN Model Integration

This folder contains the ResellBox damage-detection models for Samsung Galaxy S25 on-device inference.

## Model Files

| File | Purpose |
|---|---|
| `resellbox_s25_context.bin` | Primary Galaxy S25 deployment model |
| `resellbox_s25_qnn.dlc` | Backup and debugging model |

Recommended model:

```text
resellbox_s25_context.bin
```

Validated target:

```text
Device: Samsung Galaxy S25
OS: Android 15
SoC: Snapdragon 8 Elite for Galaxy
Runtime: Qualcomm QNN / QAIRT
Accelerator: NPU / HTP
```

## Android Asset Location

Recommended location:

```text
app/src/main/assets/models/resellbox_s25_context.bin
```

## Input Specification

```text
Input name: images
Shape: [1, 3, 640, 640]
Type: float32
Layout: NCHW
Color order: RGB
Value range: 0.0–1.0
```

## Preprocessing

Use the following order:

```text
Image
→ Convert to RGB
→ Letterbox resize to 640×640
→ Padding value 114
→ Convert to float32
→ Divide by 255.0
→ Convert HWC to NCHW
→ Add batch dimension
```

Do not force-resize the image directly to `640×640`. Preserve the aspect ratio and add padding.

Keep these values for coordinate restoration:

```text
scale
left padding
top padding
original image width
original image height
```

## Output Specification

```text
Output name: output_0
Shape: [1, 6, 8400]
Type: float32
```

Each candidate contains:

```text
[center_x, center_y, width, height, dent_score, surface_damage_score]
```

Class mapping:

```text
0 = dent
1 = surface_damage
```

## Postprocessing

Recommended settings:

```text
Confidence threshold: 0.20
NMS IoU threshold: 0.45
NMS type: Class-aware
```

Required order:

```text
Raw output
→ Confidence filtering
→ xywh to xyxy conversion
→ Class-aware NMS
→ Remove letterbox padding
→ Divide coordinates by scale
→ Draw boxes on the original image
```

Coordinate conversion:

```text
x1 = center_x - width / 2
y1 = center_y - height / 2
x2 = center_x + width / 2
y2 = center_y + height / 2
```

Restore original coordinates:

```text
originalX1 = (x1 - leftPadding) / scale
originalY1 = (y1 - topPadding) / scale
originalX2 = (x2 - leftPadding) / scale
originalY2 = (y2 - topPadding) / scale
```

## Validation Results

The model was tested through Qualcomm AI Hub on a Samsung Galaxy S25.

```text
QNN DLC profile: Successful
QNN DLC inference: Successful
Context Binary inference: Successful
Execution unit: NPU
Minimum model inference time: 3.6 ms
```

DLC and Context Binary comparison:

```text
Output shape: identical
Mean absolute difference: 0.0
Maximum absolute difference: 0.0
```

Validated detection example:

```text
Class: surface_damage
Confidence: 0.2179
Candidates above threshold: 2
Final boxes after NMS: 1
```

## Android Team Responsibilities

The Android application must implement:

```text
Image loading
Letterbox preprocessing
QNN model loading
NPU inference
Confidence filtering
Class-aware NMS
Coordinate restoration
Bounding-box drawing
```

The model does not automatically perform NMS or draw final boxes.

## Reference

The validated Python implementation is available in:

```text
test_model.ipynb
```

