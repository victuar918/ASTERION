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
        versionCode = 15; versionName = "3.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { debug { isDebuggable = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // .onnx / .bin / .json 파일을 APK 내에서 압축하지 않음
    // (assets에 번들하는 경우 대비 — 현재는 런타임 다운로드 방식이므로 실질 무해)
    aaptOptions { noCompress("onnx", "bin", "json") }
    packaging {
        // sherpa-onnx AAR 과 FFmpegKit AAR 모두 libc++_shared.so 를 포함
        // 동일 SO 가 두 AARs 에 중복될 경우 첫 번째 것을 사용
        jniLibs {
            pickFirsts += listOf(
                "**/libc++_shared.so",
                "**/libonnxruntime.so"
            )
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── sherpa-onnx v1.13.2 ─────────────────────────────────────────────────
    // CI 워크플로에서 GitHub Releases로부터 직접 다운로드 후 android/app/libs/ 에 배치
    // JitPack 미사용: jitpack.yml에 버전이 v1.12.40으로 하드코딩되어 있어
    //                 v1.13.2 요청 시 잘못된 AAR이 설치되는 문제가 있음
    // 로컬 개발 시: 워크플로와 동일한 URL에서 수동으로 AAR을 libs/ 에 배치 필요
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Apache Commons Compress — 런타임 첫 실행 시 모델 tar.bz2 추출용
    // (Android 기본 java.util.zip은 bzip2 미지원)
    implementation("org.apache.commons:commons-compress:1.26.2")

    // FFmpegKit — 영상 렌더링 (변경 없음)
    // Appodeal Artifactory에서 Maven 해석 (settings.gradle.kts에 저장소 등록됨)
    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS")
    // FFmpegKit 런타임 필수 의존성 — POM transitive dep 미선언으로 명시 추가 필수
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
