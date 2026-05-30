package com.asterion.video.render

import android.content.Context
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.0.0 — TTS 실제 동작, 영상 합성 stub
 * FFmpeg AAR를 app/libs/에 커밋하면 영상 합성 자동 활성화
 */
class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val tts = SupertonicTtsEngine(context)

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
        onProgress("✅ v2.0.0 | TTS 준비 | 렌더링 stub")
    }

    suspend fun renderScene(
        row: ScriptDataRow, meta: VideoMeta,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id  = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        val mp3 = File(AppConfig.OUTPUT_DIR, "${id}.mp3")
        val cfg = voiceConfig.forSpeaker(row.speaker)
        if (row.script.isNotBlank()) {
            val ok = tts.synthesize(row.script, cfg.sid, cfg.speed, mp3)
            onProgress("[$id] ${if(ok) "✅ ${mp3.length()/1024}KB" else "❌ TTS"} | stub")
        }
        null
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? {
        onProgress("🔧 stub | FFmpeg AAR 커밋 후 활성화")
        return null
    }

    fun release() { tts.release() }
}
