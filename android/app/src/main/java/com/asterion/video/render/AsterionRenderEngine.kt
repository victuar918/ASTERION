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
 * 렌더링 엔진 — v1.6 stub
 * FFmpeg Kit이 Maven Central에 배포 중단되어 NDK 네이티브 빌드로 충당 예정
 * 현재: TTS(sherpa-onnx) 동작 확인용 stub
 */
class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val tts = SupertonicTtsEngine(context)

    suspend fun init(onProgress: (String) -> Unit = {}) {
        AppConfig.ensureDirs()
        tts.init(onProgress)
        onProgress("✅ TTS 준비 완료 — 렌더링 엔진 stub")
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        meta: VideoMeta,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        onProgress("[$id] TTS 생성 중...")

        // TTS 생성
        val mp3 = File(AppConfig.OUTPUT_DIR, "${id}.mp3")
        val cfg = voiceConfig.forSpeaker(row.speaker)
        val ok = tts.synthesize(row.script, cfg.sid, cfg.speed, mp3)

        if (ok) {
            onProgress("[$id] ✅ TTS 완료 (${mp3.length()/1024}KB) — 렌더링 stub")
        } else {
            onProgress("[$id] ❌ TTS 실패")
        }
        // 렌더링 stub: null 리턴 (영상 파일 없음)
        null
    }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String) -> Unit = {}): File? {
        onProgress("렌더링 stub — FFmpeg NDK 충당 후 활성화")
        return null
    }

    fun release() { tts.release() }
}
