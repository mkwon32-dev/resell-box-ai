여기에 변환한 모델 파일을 넣으세요.

  model.tflite   ← `yolo export format=tflite int8=True` 로 만든 파일 (이 이름 그대로)
  labels.txt     ← 클래스 이름을 학습 때 data.yaml 의 순서 그대로 (이미 있음: scratch/dent/tear)

⚠️ labels.txt 의 줄 순서는 반드시 학습 시 클래스 인덱스 순서와 같아야 합니다.
   (data.yaml 의 names 순서를 그대로 복사하세요. 순서가 어긋나면 손상 종류가 뒤바뀝니다.)

model.tflite 가 없으면 앱은 켜지지만 "모델을 불러오지 못했습니다" 안내가 뜹니다.
