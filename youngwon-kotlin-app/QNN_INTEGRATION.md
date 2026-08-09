# QNN / QAIRT 최적화 통합 가이드 (Galaxy S25)

모델링 담당이 만든 **QNN Context Binary** (`resellbox_s25_context.bin`) 를
Galaxy S25 NPU(HTP) 에서 돌리기 위한 통합 문서. (근거: 팀 `models/README.md`)

검증됨: **NPU 추론 3.6ms**, DLC=Context Binary 출력 완전 동일(오차 0.0),
surface_damage 탐지 확인(conf 0.2179 → NMS 후 1박스).

## 무엇을 새로 만들고, 무엇을 재사용하나
`models/README.md` 의 "Android Team Responsibilities" 8개 중 **6개는 이 앱에 이미 구현**돼 있다.

| 책임 | 상태 | 위치 |
|---|---|---|
| Image loading | ✅ | MainActivity |
| Letterbox preprocessing (114 pad) | ✅ | TFLiteDetector.detect |
| **QNN model loading** | ❌ 새로 | QnnDetector (아래) |
| **NPU inference** | ❌ 새로 | QnnDetector (아래) |
| Confidence filtering (0.20) | ✅ | TFLiteDetector.decodeYolo |
| Class-aware NMS (IoU 0.45) | ✅ | TFLiteDetector.nms |
| Coordinate restoration | ✅ | decodeYolo 의 letterbox 역변환 |
| Bounding-box drawing | ✅ | ResultRenderer |

→ **새로 짤 건 "QNN 로드 + NPU 추론" 딱 2개.** 전처리·디코딩·NMS·좌표복원·그리기는 재사용.

---

## 방식 선택
`.bin` 을 돌리는 런타임은 **QAIRT(Qualcomm AI Runtime)** 다. 두 가지 통합법:

### A. QAIRT 네이티브 (context binary 직접) — 문서의 기본
- `.bin` 을 `QnnContext_createFromBinary` 로 로드, HTP 백엔드로 실행.
- **가장 빠름(3.6ms), 하지만 JNI/C++ + QAIRT `.so` 필요.**
- S25 + QAIRT SDK 보유자(모델링 담당)가 구현 적합.

### B. QNN delegate (대안, 더 쉬움)
- `.bin` 대신 우리 `.tflite` + `libQnnTFLiteDelegate.so` 로 HTP 가속.
- 코드 변화 적음(TFLiteDetector 에 delegate 추가). 단 최고속은 아님.
- 자세한 건 이 파일 하단 "부록: QNN delegate" 참고.

아래는 **A(네이티브)** 기준.

## A. QAIRT 네이티브 통합 절차

### 1) 모델·라이브러리 배치
```
app/src/main/assets/models/resellbox_s25_context.bin      ← .bin (팀 리포에서)
app/src/main/jniLibs/arm64-v8a/                            ← QAIRT SDK 의 .so 들
    libQnnHtp.so, libQnnHtpV79Stub.so, libQnnHtpV79Skel.so,
    libQnnSystem.so, libQnnHtpPrepare.so, (QAIRT 런타임 .so)
```
> `V79` 숫자는 S25 SoC(Snapdragon 8 Elite) 의 Hexagon 버전. SDK 실제 파일명 사용.

### 2) 입력 스펙 — ⚠️ 우리 TFLite 와 레이아웃이 다름
| | TFLite (지금) | QNN `.bin` |
|---|---|---|
| 이름 | (자동) | `images` |
| shape | NHWC `[1,640,640,3]` | **NCHW `[1,3,640,640]`** |
| 값 | float32, /255 | 동일 |
- letterbox(비율유지+114패딩) 는 **동일**. 마지막 버퍼 채울 때만 **NCHW(채널 먼저)** 로 배치.
- 좌표 복원용 `scale, leftPad, topPad, origW, origH` 는 우리 코드가 이미 유지 중.

### 3) 출력 → 후처리는 그대로 재사용
- 출력 `output_0` `[1,6,8400]` = `[cx,cy,w,h,dent,surface_damage]`, 0=dent 1=surface_damage.
- 이 FloatArray 를 우리 `decodeYolo`/`nms` 에 그대로 넣으면 됨(형식 동일).

### 4) 코드 구조
`Detector` 인터페이스로 분리 후 `QnnDetector` 를 추가하고,
`MainActivity` 에서 `TFLiteDetector` → `QnnDetector` 한 줄만 교체.
(이 앱은 pre/post 가 이미 함수로 분리돼 있어 재사용 쉬움.)

### 5) 확인
- S25 실행 → 같은 테스트 이미지로 `surface_damage` 박스 1개(NMS 후) 재현되면 성공.
- TFLite(우리)와 **같은 박스**가 나와야 정상(같은 모델).

## B. QNN delegate — 연결 코드 이미 내장됨 ⭐ (팀원은 .so 만 추가)
`.tflite` 를 그대로 쓰는 경로. **연결 코드는 `TFLiteDetector` 에 이미 들어있다**
(리플렉션 방식이라 QNN 없이도 컴파일·실행됨 — 에뮬레이터는 자동으로 NNAPI/CPU 폴백).

**팀원이 할 일은 라이브러리 추가 2가지뿐:**
1. `app/src/main/jniLibs/arm64-v8a/` 에 QNN `.so` 복사
   ```
   libQnnTFLiteDelegate.so, libQnnHtp.so, libQnnHtpV79Stub.so,
   libQnnHtpV79Skel.so, libQnnSystem.so   (SDK 실제 파일명)
   ```
2. `app/build.gradle.kts` 에 delegate AAR + jniLibs 패키징:
   ```kotlin
   android { packaging { jniLibs { useLegacyPackaging = true } } }
   dependencies { implementation(files("libs/QnnDelegate.aar")) }  // SDK 제공 AAR
   ```

이러면 앱 실행 시 `TFLiteDetector.tryAddQnnDelegate()` 가 런타임에 QnnDelegate 를
자동으로 찾아 **HTP(NPU) 백엔드**로 붙인다. 코드 수정 불필요.

**SDK 버전이 달라 클래스/메서드명이 다르면** — `TFLiteDetector` 하단 companion 의
`QNN_DELEGATE_CLASS` / `QNN_OPTIONS_CLASS` 두 상수, 또는 `tryAddQnnDelegate()` 안의
`"setBackendType"` 등 메서드명 문자열만 실제 API 에 맞게 바꾸면 된다.

확인: S25 실행 시 `TFLiteDetector.usingQnn == true` 면 QNN 활성. 같은 이미지로
TFLite(NNAPI) 때와 동일한 박스가 나오면 성공.

---
## 공통 주의
- **에뮬레이터·비-Qualcomm 기기에서는 QNN 이 안 돈다.** 우리 앱은 그 경우 NNAPI/CPU 로
  자동 폴백하므로, 당신은 계속 에뮬레이터로 TFLite 를 테스트하고 GitHub 에 올리면 된다.
- 검증 기준 코드: 팀 리포 `test_model.ipynb`.
