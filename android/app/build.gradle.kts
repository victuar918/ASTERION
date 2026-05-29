plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace   = "com.asterion.video"
    compileSdk  = 35

    defaultConfig {
        applicationId = "com.asterion.video"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug   { isDebuggable = true }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    aaptOptions { noCompress("onnx", "bin") }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // sherpa-onnx (Supertonic-TTS-2 온디바이스)
    implementation("com.github.k2-fsa:sherpa-onnx-android:1.10.31")

    // FFmpeg Kit
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2") {
        exclude(group = "com.arthenica", module = "smart-exception-java")
    }

    // OkHttp (Sheets API + HuggingFace 다운로드)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}
