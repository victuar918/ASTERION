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
 * v3.1 — ttsEngine을 외부에서 주입망밀
 * initCore()에서 init된 ttsEngine을 공유 → 이중 실행 방지
 */
class AsterionRenderEngine(
    private val context: Context,
    private val tts: SupertonicTtsEngine   // 외부 주입 (initCore에서 이미 init된 인스턴스)
) {
    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs()
        // tts는 이미 init되었으므로 여기서는 디렉토리만 수행
        onProgress("✅ 렌더링 엔진 준비")
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
            onProgress("[$id] ${if(ok) "✅ ${wav.length()/1024}KB [${cfg.label}/${cfg.voiceFile}]" else "❌ TTS 실패"} | stub")
        } else {
            onProgress("[$id] Script 비어있음 — 건너맜")
        }
        null // FFmpeg 커밋 후 영상 생성
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? {
        onProgress("🔧 렌더링 stub — FFmpeg AAR 커밋 후 활성화")
        return null
    }

    fun release() { /* tts는 Activity가 관리 */ }
}
