package com.asterion.video

import android.os.Environment
import java.io.File

object AppConfig {
    private val sdcard = Environment.getExternalStorageDirectory()

    val BGV_DIR: File    = File(sdcard, "Documents/work/ASTERION/YouTube/BGV")
    val BGM_DIR: File    = File(sdcard, "Documents/work/ASTERION/YouTube/bgm")
    val OUTPUT_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/output")

    const val TTS_MODEL_SUBDIR = "tts_model"
    const val LOTTIE_SUBDIR    = "lottie"
    const val VIDEO_SS_ID      = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"

    // ── Supertonic 3 모델 — sherpa-onnx int8 최적화 ──────────────────────────
    // 배포 방식: GitHub Releases의 'tts-models' 태그 아래 tar.bz2 단일 파일
    // 최초 실행 시 앱 내부 filesDir/tts_model/ 에 다운로드 + 압축 해제
    // 총 크기: 약 350MB (압축) / 약 700MB (해제)
    const val TTS_TAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    // 다운로드 완료 마커 파일명
    // 이 이름을 바꾸면 다음 실행 시 모델을 강제 재다운로드함
    const val TTS_READY_MARKER = ".ready_supertonic3_int8"

    fun ensureDirs() { BGV_DIR.mkdirs(); BGM_DIR.mkdirs(); OUTPUT_DIR.mkdirs() }
    fun resolveBgv(f: String) = File(BGV_DIR, f).takeIf { it.exists() } ?: File(BGV_DIR, "default_bg.mp4")
    fun resolveBgm(f: String) = File(BGM_DIR, f).takeIf { it.exists() }
    fun listBgvFiles() = BGV_DIR.listFiles()?.filter { it.extension in listOf("mp4","mov","mkv") }?.map { it.name } ?: emptyList()
    fun listBgmFiles() = BGM_DIR.listFiles()?.filter { it.extension in listOf("mp3","aac","m4a","wav") }?.map { it.name } ?: emptyList()
}
