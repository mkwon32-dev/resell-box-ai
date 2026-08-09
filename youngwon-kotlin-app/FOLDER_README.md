# youngwon-kotlin-app — ResellBox 안드로이드 앱 (네이티브 Kotlin)

**팀이 채택한 안드로이드 앱**(네이티브 Kotlin). 신발상자 사진을 찍으면
**기기에서 직접(on-device)** 손상을 탐지하고 리셀 위험도를 알려준다.

## 무엇을 하나
- 사진 선택/촬영 → 온디바이스 YOLO11 추론 → 손상 탐지 → 위험도 카드
- 클래스: `dent`, `surface_damage`
- 실기기에서 QNN(NPU)/NNAPI 가속, 에뮬레이터는 CPU (자동 판별·폴백)
- 인터넷 불필요 (백엔드 없이 폰 안에서 완결)

## 팀 스펙과 정렬 (models/README.md 기준)
- 전처리: letterbox 640, 114 padding
- 후처리: **confidence 0.20**, **class-aware NMS (IoU 0.45)**, 좌표복원
- 출력 디코딩: `[1,6,8400]` = `[cx,cy,w,h,dent,surface_damage]`
- 모델: `app/src/main/assets/model.tflite` (weights.pt → TFLite float16)

## 실행
Android Studio 에서 이 폴더를 열고 ▶ Run. 모델은 assets 에 포함됨.

## QNN(S25 NPU) 최적화 — 연결 코드 이미 내장됨
`TFLiteDetector` 에 QNN delegate 연결 코드가 리플렉션으로 들어있다.
**팀원은 QNN SDK 의 `.so` + AAR 만 추가하면** S25 에서 NPU 가속이 자동 활성화된다.
(없으면 NNAPI/CPU 로 자동 폴백 → 어디서든 동작.) 절차: `QNN_INTEGRATION.md`.

## 핵심 파일
```
app/src/main/java/com/resellbox/ai/
├── MainActivity.kt              화면·흐름
├── data/TFLiteDetector.kt       온디바이스 추론 + 디코딩 + NMS + QNN 연결
├── measure/BoxMeasurer.kt       크기 추정(coarse, 상자=35cm 가정)
├── risk/RiskScorer.kt           규칙 기반 위험도
└── ui/ResultRenderer.kt         bbox 오버레이
```

## 남은 개선 (선택)
- 모델 정확도: 실제 신발상자 손상 사진으로 재학습
- 크기 측정 정밀화(현재 coarse) → OpenCV/카드 기준
- 히스토리·설정 등 화면 보강
