<img src="../docs/logo.png" alt="Box Damage AI" width="96">

# Box Damage AI

Box Damage AI is an Android app that detects visible sneaker box damage from uploaded or captured photos.

The app uses Flutter for the UI, Kotlin for the Android-native inference bridge, TensorFlow Lite for YOLO model execution, and a Qualcomm QNN TFLite Delegate attempt with automatic CPU fallback.

Current damage classes:

- `dent`
- `surface_damage`

## App Flow

```text
Photo capture / upload
        ↓
Flutter / Dart
        ↓
QnnAnalysisRepository
        ↓
QnnService
        ↓
MethodChannel
        ↓
MainActivity.kt
        ↓
TFLiteDetector.kt
        ↓
Image preprocessing
- decode image
- letterbox / resize to 640x640
- convert to FLOAT32 tensor
        ↓
TensorFlow Lite Interpreter
        ↓
Try QNN Delegate
   ├─ success → NPU / HTP
   └─ failure → CPU fallback
        ↓
model.tflite
        ↓
YOLO inference
        ↓
Postprocessing
- confidence filtering
- bounding-box decoding
- class mapping
- NMS
        ↓
Prediction.kt
        ↓
Kotlin → Flutter
        ↓
Damage detection result displayed

The verified inference path on the Galaxy S25 is currently TensorFlow Lite CPU execution. QNN delegate code is retained so the NPU path can be revisited later.

Important Files
app/
├── lib/
│   ├── providers.dart
│   ├── services/qnn_service.dart
│   ├── data/repositories/qnn_analysis_repository.dart
│   └── features/
│
├── android/app/
│   ├── libs/
│   │   └── qtld-release.aar
│   │
│   └── src/main/
│       ├── assets/
│       │   ├── model.tflite
│       │   └── labels.txt
│       │
│       └── kotlin/
│           ├── com/resellbox/ai/data/
│           │   ├── TFLiteDetector.kt
│           │   └── Prediction.kt
│           │
│           └── edu/wisc/resellbox_app/
│               └── MainActivity.kt
│
├── assets/
├── shaders/
├── native/sqlite/
├── pubspec.yaml
└── pubspec.lock

Main file roles
model.tflite — trained YOLO damage detection model
labels.txt — class names for model output
TFLiteDetector.kt — image preprocessing, TFLite inference, QNN attempt, CPU fallback, YOLO postprocessing, and NMS
Prediction.kt — stores final detection results such as class, confidence, and bounding box
MainActivity.kt — connects Flutter and Kotlin through MethodChannel
qnn_service.dart — Flutter-side native inference service
qnn_analysis_repository.dart — connects Flutter analysis flow to the native service
qtld-release.aar — Qualcomm QNN TFLite Delegate wrapper
assets/ — fonts and sample images used by the Flutter UI
shaders/scanline.frag — scan animation used on the analysis screen
native/sqlite/sqlite3.c — local SQLite source used by the app
