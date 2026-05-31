package com.asterion.video.render

import android.content.Context
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AsterionRenderEngine(
    private val context: Context
) {
    private val tts = SupertonicTtsEngine(context)

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        meta: VideoMeta,
        voiceConfig: VoiceConfig = VoiceConfig.DEFAULT,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id  = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        val wav = File(AppConfig.OUTPUT_DIR, "${id}.wav")
        val cfg = voiceConfig.forSpeaker(row.speaker)
        if (row.script.isNotBlank()) {
            val ok = tts.synthesize(row.script, cfg.voiceFile, cfg.speed, wav)
            onProgress("[$id] ${if(ok) "✅ ${wav.length()/1024}KB [${cfg.label}/${cfg.voiceFile}]" else "❌ TTS"} | stub")
        }
        null
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? {
        onProgress("🔧 렌더링 stub — FFmpeg 커밋 후 활성화")
        return null
    }

    fun release() { tts.release() }
}
