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
        versionCode = 7; versionName = "1.6.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { debug { isDebuggable = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    aaptOptions { noCompress("onnx", "bin") }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // sherpa-onnx: 로컈 AAR (GitHub Actions tar.bz2 추출)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // FFmpeg Kit: Maven Central 배포 중단(없음) — 렌더링은 추후 네이티브 NDK로 충당
    // implementation("com.arthenica:ffmpeg-kit-full:6.0-2.LTS")
}
