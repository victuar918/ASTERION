plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace   = "com.asterion.video"
    compileSdk  = 35
    defaultConfig {
        applicationId = "com.asterion.video"
        minSdk = 26; targetSdk = 35
        versionCode = 14; versionName = "3.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { debug { isDebuggable = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    aaptOptions { noCompress("onnx", "bin", "json") }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ONNX Runtime Android — Supertonic 2 온디바이스 TTS (변경 무)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // FFmpegKit — 영상 렌더링
    // Appodeal Artifactory에서 Maven 해석 (settings.gradle.kts에 저장소 등록됨)
    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS")
    // FFmpegKit 런타임 필수 의존성 — 누락 시 FFmpeg 실행 실패
    // Appodeal POM이 transitive dep 선언 안 함 → 명시 추가 필수
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
