package com.asterion.video

import android.os.Environment
import java.io.File

object AppConfig {
    private val sdcard = Environment.getExternalStorageDirectory()
    val BGV_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/BGV")
    val BGM_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/bmg")
    val OUTPUT_DIR: File = File(sdcard, "Documents/work/ASTERION/YouTube/output")
    const val TTS_MODEL_SUBDIR = "tts_model"
    const val VIDEO_SS_ID = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"
    const val TTS_HF_BASE = "https://huggingface.co/Supertone/supertonic/resolve/main"
    const val TTS_ESPEAK_ZIP_URL = "$TTS_HF_BASE/espeak-ng-data.zip"
    val TTS_MODEL_FILES = listOf("model.onnx", "lexicon.txt", "tokens.txt")
    fun ensureDirs() { BGV_DIR.mkdirs(); BGM_DIR.mkdirs(); OUTPUT_DIR.mkdirs() }
    fun resolveBgv(f: String) = File(BGV_DIR, f).takeIf { it.exists() } ?: File(BGV_DIR, "default_bg.mp4")
    fun resolveBgm(f: String) = File(BGM_DIR, f).takeIf { it.exists() }
}
