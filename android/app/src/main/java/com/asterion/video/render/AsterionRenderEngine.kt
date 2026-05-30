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

private const val TAG = "AsterionRenderEngine"

/**
 * 렌더링 엔진 v1.8 — TTS는 실제 동작, 영상 합성은 stub
 * FFmpeg AAR를 app/libs/에 직접 커밋하면 자동 활성화
 */
class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val tts = SupertonicTtsEngine(context)

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
        onProgress("✅ TTS(sherpa-onnx) 준비 | 렌더링 stub")
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        meta: VideoMeta,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        onProgress("[$id] TTS 생성 중...")
        val mp3 = File(AppConfig.OUTPUT_DIR, "${id}.mp3")
        val cfg = voiceConfig.forSpeaker(row.speaker)
        val ok  = if (row.script.isNotBlank()) tts.synthesize(row.script, cfg.sid, cfg.speed, mp3) else false
        onProgress("[$id] ${if(ok) "✅ TTS ${mp3.length()/1024}KB" else "⚠ TTS 실패"} | 렌더링 stub")
        null // 영상 파일: FFmpeg AAR 커밋 시 생성
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? {
        onProgress("🔧 렌더링 stub — FFmpeg AAR 커밋 후 활성화")
        return null
    }

    fun release() { tts.release() }
}
