# ResellBox AI — Android 앱 (Kotlin, 온디바이스)

신발상자 사진을 찍으면 **손상 유형·위치·크기·리셀 위험도**를 추정해 보여주는 안드로이드 앱.
탐지 모델을 **기기 안에서 직접(온디바이스, TFLite int8)** 실행한다 — 인터넷/서버 불필요.
Galaxy S25 의 **Qualcomm NPU(NNAPI)** 가속을 시도하고, 안 되면 CPU 로 폴백한다.

> ⚠️ 참고용 도구입니다. KREAM/StockX/GOAT 공식 검수를 대체하지 않습니다.

## 준비 (모델 넣기)

1. 학습한 `best.pt` 를 int8 TFLite 로 변환 → **[CONVERT_MODEL.md](CONVERT_MODEL.md)**
2. 결과 파일을 `app/src/main/assets/model.tflite` 로 복사
3. `app/src/main/assets/labels.txt` 를 data.yaml 의 클래스 순서와 맞춤 (기본: scratch/dent/tear)

모델이 없어도 앱은 켜지고 UI·측정·위험도 로직은 확인 가능하다.

## 열기 / 실행

1. Android Studio → `File → Open` → 이 `android/` 폴더
2. Gradle Sync 완료 후, 상단 `app` 옆 ▶ Run
3. 실기기(권장) 또는 에뮬레이터에서 실행. 에뮬레이터는 NPU 가 없어 CPU 로 동작(정상).

## 구조

```
app/src/main/java/com/resellbox/ai/
├── MainActivity.kt          사진 선택/촬영 → 추론 → 결과 표시
├── data/
│   ├── Prediction.kt        탐지 결과 모델 (bbox, class, confidence)
│   └── TFLiteDetector.kt    ★ 온디바이스 YOLOv8 추론 (NNAPI/NPU, letterbox, NMS)
├── measure/BoxMeasurer.kt   상자=스케일 기준 → 손상 크기 cm 추정
├── risk/RiskScorer.kt       규칙 기반 위험도 Low/Caution/High
└── ui/ResultRenderer.kt     원본 위에 bbox·라벨 오버레이
app/src/main/assets/
├── model.tflite             (직접 넣기)
└── labels.txt               클래스 이름(순서 중요)
```

## 파이프라인 (README 대응)

| 단계 | 구현 | 상태 |
|---|---|---|
| ① 객체탐지 (scratch/dent/tear) | `TFLiteDetector` | ✅ 온디바이스 int8, NPU 가속 |
| ② 상자면 크기 측정 (cm) | `BoxMeasurer` | ⚠️ coarse(box_edge). OpenCV homography 는 TODO |
| ③ 규칙 기반 위험도 | `RiskScorer` | ✅ README cm 임계값 반영 |

## 남은 작업

- **크기 추정 정밀화**: 현재 "이미지 긴 변 = 35cm" 근사. `BoxMeasurer.findBoxLongEdgePx()`
  를 OpenCV 상자 실루엣 탐지로 교체하면 정확해진다(README Week 3, box_face 단계).
- **속도 측정**: S25 실기기에서 추론 시간(ms) 로그 확인 → 필요 시 imgsz 축소.
