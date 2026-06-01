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
    // antonkarpenko 2.1.0은 native .so만 제공, Java API 미포함 → com.arthenica 사용
    // com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS: Maven Central 영구 아다트, Java API + native 세트 일치
    // full-gpl 포함: libass(ASS 자막), libx264, fontconfig, freetype, fribidi
    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS")
}
