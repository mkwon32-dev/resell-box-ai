# youngwon-kotlin-app — 네이티브 Kotlin 온디바이스 앱 (Youngwon)

팀 Flutter 앱(`app/`)과는 **별개**인, 네이티브 **Android(Kotlin)** 프로토타입.
`weights.pt` 를 **TFLite(float16)** 로 변환해 **기기에서 직접 추론**하는 파이프라인을
검증하려고 만들었다. Flutter 에 on-device 를 이식할 때 **참고 구현**으로 쓸 수 있다.

## 무엇을 하나
- 사진 선택/촬영 → 온디바이스 YOLO11 추론 → 손상 탐지 → 위험도 카드
- 클래스: `dent`, `surface_damage` (모델과 동일)
- 실기기에서 NNAPI(NPU) 가속, 에뮬레이터는 CPU

## 팀 스펙과 정렬된 부분 (models/README.md 기준)
- 전처리: letterbox 640, 114 padding
- 후처리: **confidence 0.20**, **class-aware NMS (IoU 0.45)**, 좌표복원
- 출력 디코딩: `[1,6,8400]` = `[cx,cy,w,h,dent,surface_damage]`

## 실행
Android Studio 에서 이 폴더를 열고 ▶ Run.
모델 파일은 `app/src/main/assets/model.tflite` 에 포함돼 있음.

## QNN 통합
S25 QNN(.bin) 으로 바꾸는 방법은 `QNN_INTEGRATION.md` 참고.
전처리/디코딩/NMS/좌표복원 로직은 그대로 재사용 가능(입력만 NCHW 로).

## 핵심 파일
```
app/src/main/java/com/resellbox/ai/
├── MainActivity.kt              화면·흐름
├── data/TFLiteDetector.kt       온디바이스 추론 + 디코딩 + NMS
├── measure/BoxMeasurer.kt       크기 추정(coarse, 상자=35cm 가정)
├── risk/RiskScorer.kt           규칙 기반 위험도
└── ui/ResultRenderer.kt         bbox 오버레이
```

> 이 앱은 팀 Flutter 앱을 대체하지 않는다. on-device 추론 검증·참고용.
