plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.resellbox.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.resellbox.ai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // .tflite 모델을 압축하지 않아야 메모리 매핑(mmap)으로 빠르게 로드된다.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // ── 온디바이스 추론 (TensorFlow Lite / LiteRT) ──────────────
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // int8 양자화 모델은 NNAPI delegate 로 Qualcomm NPU(Hexagon) 가속.
    // GPU delegate 는 float 모델용이라 여기선 쓰지 않는다.
}
