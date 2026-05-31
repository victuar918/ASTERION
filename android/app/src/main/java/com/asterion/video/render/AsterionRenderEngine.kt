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
 * v3.0.0 — Supertonic 2 온디바이스 TTS (ONNX Runtime 직접 실행)
 * 영상 합성: FFmpeg AAR 커밋 후 활성화
 */
class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val tts = SupertonicTtsEngine(context)

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
    }

    suspend fun renderScene(
        row: ScriptDataRow, meta: VideoMeta,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id  = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        val wav = File(AppConfig.OUTPUT_DIR, "${id}.wav")
        val cfg = voiceConfig.forSpeaker(row.speaker)
        if (row.script.isNotBlank()) {
            val ok = tts.synthesize(row.script, cfg.voiceFile, cfg.speed, wav)
            onProgress("[$id] ${if(ok) "✅ TTS ${wav.length()/1024}KB" else "❌ TTS"} | 렌더링 stub")
        }
        null // 영상 합성: FFmpeg 커밋 후 활성화
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? {
        onProgress("🔧 렌더링 stub — FFmpeg AAR 커밋 후 활성화")
        return null
    }

    fun release() { tts.release() }
}
