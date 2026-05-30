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
        versionCode = 4; versionName = "1.3.0"
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

    // sherpa-onnx: 로컈 AAR (GitHub Actions에서 다운로드, JitPack 우회)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // FFmpeg Kit
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2") {
        exclude(group = "com.arthenica", module = "smart-exception-java")
    }
}
