// Android AppConfig.kt — Supertonic 3 URL 업그레이드
package com.asterion.video

import android.os.Environment
import java.io.File

object AppConfig {
    private val sdcard = Environment.getExternalStorageDirectory()

    val BGV_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/BGV")
    val BGM_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/bgm")  // fix: bmg → bgm
    val OUTPUT_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/output")

    const val TTS_MODEL_SUBDIR = "tts_model"
    const val LOTTIE_SUBDIR = "lottie"
    const val VIDEO_SS_ID = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"

    // ★ Supertonic 3 (31개 언어, v2 호환 ONNX)
    const val TTS_HF_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main"
    const val TTS_ESPEAK_ZIP_URL = "$TTS_HF_BASE/espeak-ng-data.zip"
    val TTS_MODEL_FILES = listOf("model.onnx", "lexicon.txt", "tokens.txt")

    fun ensureDirs() { BGV_DIR.mkdirs(); BGM_DIR.mkdirs(); OUTPUT_DIR.mkdirs() }
    fun resolveBgv(f: String) = File(BGV_DIR, f).takeIf { it.exists() } ?: File(BGV_DIR, "default_bg.mp4")
    fun resolveBgm(f: String) = File(BGM_DIR, f).takeIf { it.exists() }
    fun listBgvFiles() = BGV_DIR.listFiles()?.filter { it.extension in listOf("mp4","mov","mkv") }?.map { it.name } ?: emptyList()
    fun listBgmFiles() = BGM_DIR.listFiles()?.filter { it.extension in listOf("mp3","aac","m4a","wav") }?.map { it.name } ?: emptyList()
}
