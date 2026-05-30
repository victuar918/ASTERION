package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// TODO: FFmpeg Kit 의존성 추가 후 완전 구현
// 현재는 빌드 확인용 stub

private const val TAG = "AsterionRenderEngine"

class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val tts = SupertonicTtsEngine(context)
    private val clips = mutableListOf<File>()

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
        onProgress("렌더 엔진 준비 완료 (stub)")
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        meta: VideoMeta,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        onProgress("[${row.section}] stub 렌더링 — FFmpeg 추후 구현")
        null
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? = null

    fun release() { tts.release(); clips.clear() }
}
