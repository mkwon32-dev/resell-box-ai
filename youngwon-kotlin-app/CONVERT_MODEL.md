# best.pt → TFLite(int8) 변환 가이드

학습된 YOLO 가중치(`best.pt`)를 S25 NPU에서 돌릴 **int8 TFLite** 로 바꾸는 절차.
PC(맥/윈도우)에서 파이썬으로 한 번만 하면 된다.

## 1. 환경

```bash
python -m venv venv && source venv/bin/activate     # (윈도우: venv\Scripts\activate)
pip install ultralytics
```

## 2. int8 TFLite 로 내보내기

`data.yaml`(학습에 쓴 데이터셋 설정)이 있어야 int8 **보정(calibration)** 이 제대로 된다.
보정용 이미지로 양자화 범위를 잡기 때문이다.

```bash
yolo export model=best.pt format=tflite int8=True imgsz=640 data=data.yaml
```

끝나면 `best_saved_model/` 폴더에 여러 파일이 생긴다. 그중 **full integer quant** 파일을 쓴다:

```
best_saved_model/
├── best_full_integer_quant.tflite   ← ★ 이걸 사용 (int8, NPU용)
├── best_float32.tflite
└── best_float16.tflite
```

> 파일명이 `best_int8.tflite` 로 나오는 버전도 있다. int8 로 완전 양자화된 파일이면 된다.

## 3. 앱에 넣기

1. 위 파일을 **`model.tflite`** 로 이름 바꿔서
   `app/src/main/assets/model.tflite` 에 복사
2. `app/src/main/assets/labels.txt` 의 줄 순서를 **data.yaml 의 `names` 순서와 동일하게** 맞춘다
   ```yaml
   # data.yaml 예시
   names: [scratch, dent, tear]   # ← 이 순서 그대로 labels.txt 에
   ```
3. Android Studio 에서 다시 Run

앱은 모델 입력 크기(imgsz)를 자동으로 읽으므로 640이 아니어도 된다.
int8/float 입출력도 자동 판별한다.

## 4. 확인 / 트러블슈팅

| 증상 | 원인/해결 |
|---|---|
| "모델을 불러오지 못했습니다" | assets 에 `model.tflite` 없음 → 2단계 확인 |
| 손상 종류가 뒤바뀜(scratch↔tear) | `labels.txt` 순서가 data.yaml 과 다름 |
| 박스가 안 나오거나 이상함 | 신뢰도 임계값 조정: `TFLiteDetector(confThreshold=0.25f)` 로 낮춰 테스트 |
| NPU 가속 확인 | 결과 하단에 "NPU(NNAPI) 가속" 표시. CPU 로 뜨면 NNAPI 미지원 상황 → 동작은 함 |

## 참고: int8 vs float16
- **int8**(현재 선택): 가장 작고 NPU에서 가장 빠름. 정확도 소폭 하락 가능.
- 만약 int8 결과가 부정확하면 `float16` 파일(`best_float16.tflite`)로 바꿔 넣어 비교해보면
  변환 문제인지 모델 문제인지 구분된다. 앱 코드는 그대로 둬도 동작한다.
