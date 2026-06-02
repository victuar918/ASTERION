pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Maven Central 실제 파일 서버
        maven { url = uri("https://repo1.maven.org/maven2/") }
        // ffmpeg-kit-full-gpl 6.0-2.LTS 배포 위치 (사용자 직접 검증)
        // com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS 가 repo1.maven.org, jitpack 에 없어 추가
        maven { url = uri("https://artifactory.appodeal.com/appodeal-public") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AsterionVideo"
include(":app")
