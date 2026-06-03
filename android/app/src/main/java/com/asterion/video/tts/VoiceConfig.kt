package com.asterion.video.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// ASTERION TTS — VoiceConfig v3.1
// [버그⑧] 화자 1~3 → 9화자 전체 정의 (schema S-9 기준)
//   Speaker 4~9: SID=3 동일, speed 값만 다름
// ============================================================

private const val TAG = "VoiceConfig"

data class SpeakerConfig(
    val sid: Int,
    val speed: Float,
    val label: String,
    val numThreads: Int = 2
)

data class VoiceConfig(
    val speaker1: SpeakerConfig,
    val speaker2: SpeakerConfig,
    val speaker3: SpeakerConfig,
    val speaker4: SpeakerConfig,
    val speaker5: SpeakerConfig,
    val speaker6: SpeakerConfig,
    val speaker7: SpeakerConfig,
    val speaker8: SpeakerConfig,
    val speaker9: SpeakerConfig
) {
    fun forSpeaker(num: Int): SpeakerConfig = when (num) {
        2    -> speaker2
        3    -> speaker3
        4    -> speaker4
        5    -> speaker5
        6    -> speaker6
        7    -> speaker7
        8    -> speaker8
        9    -> speaker9
        else -> speaker1
    }

    companion object {
        val DEFAULT = VoiceConfig(
            speaker1 = SpeakerConfig(sid = 0, speed = 1.30f, label = "아스터",     numThreads = 2),
            speaker2 = SpeakerConfig(sid = 1, speed = 1.25f, label = "리언",       numThreads = 2),
            speaker3 = SpeakerConfig(sid = 2, speed = 1.15f, label = "나레이터",   numThreads = 2),
            speaker4 = SpeakerConfig(sid = 3, speed = 1.50f, label = "라후",       numThreads = 2),
            speaker5 = SpeakerConfig(sid = 3, speed = 1.00f, label = "케투",       numThreads = 2),
            speaker6 = SpeakerConfig(sid = 3, speed = 1.70f, label = "수성",       numThreads = 4),
            speaker7 = SpeakerConfig(sid = 3, speed = 1.15f, label = "달",         numThreads = 2),
            speaker8 = SpeakerConfig(sid = 3, speed = 1.30f, label = "태양",       numThreads = 2),
            speaker9 = SpeakerConfig(sid = 3, speed = 1.35f, label = "행성게스트", numThreads = 2)
        )
    }
}

class SupertonicTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    val sampleRate: Int get() = tts?.sampleRate() ?: 22050

    companion object {
        const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
        const val MODEL_DIR_NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private val REQUIRED_FILES = listOf("model.onnx", "lexicon.txt", "tokens.txt")
    }

    suspend fun init(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val modelDir = prepareModelDir(onProgress) ?: return@withContext
        onProgress("TTS 엔진 로딩 중...")
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model   = File(modelDir, "model.onnx").absolutePath,
                        lexicon = File(modelDir, "lexicon.txt").absolutePath,
                        tokens  = File(modelDir, "tokens.txt").absolutePath
                    ),
                    numThreads = 2, debug = false
                )
            )
            tts = OfflineTts(config = config)
            onProgress("TTS 엔진 준비 완료 (Supertonic-3, sampleRate=${tts?.sampleRate()})")
        } catch (e: Exception) {
            Log.e(TAG, "TTS 초기화 실패: $e")
            onProgress("❌ TTS 초기화 실패: ${e.message}")
        }
    }

    suspend fun synthesize(text: String, sid: Int, speed: Float, outputFile: File) =
        withContext(Dispatchers.IO) {
            val engine = tts ?: run { Log.e(TAG, "init() 먼저 호출 필요"); return@withContext }
            try {
                engine.generate(text = text, sid = sid, speed = speed).save(outputFile.absolutePath)
            } catch (e: Exception) { Log.e(TAG, "TTS 합성 실패: $e") }
        }

    /** AudioTrack 직접 재생용 (WAV 파일 미기록) */
    suspend fun generateRaw(text: String, sid: Int, speed: Float): FloatArray? =
        withContext(Dispatchers.IO) {
            val engine = tts ?: return@withContext null
            try { engine.generate(text = text, sid = sid, speed = speed).samples }
            catch (e: Exception) { Log.e(TAG, "generateRaw 실패: $e"); null }
        }

    fun estimateDurationFromWav(wavFile: File): Float {
        return try {
            val dataBytes = (wavFile.length() - 44L).coerceAtLeast(0L)
            (dataBytes.toFloat() / (sampleRate * 1 * 2) + 0.5f).coerceAtLeast(1.0f)
        } catch (e: Exception) { 3.0f }
    }

    private suspend fun prepareModelDir(onProgress: (String) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val ttsDir   = File(context.filesDir, "tts_model")
            val modelDir = File(ttsDir, MODEL_DIR_NAME)
            if (REQUIRED_FILES.all { File(modelDir, it).exists() }) return@withContext modelDir
            ttsDir.mkdirs()
            val archiveFile = File(ttsDir, "model.tar.bz2")
            try {
                onProgress("TTS 모델 다운로드 중...")
                downloadFile(MODEL_ARCHIVE_URL, archiveFile, onProgress)
                onProgress("압축 해제 중...")
                val result = ProcessBuilder("tar", "-xjf", archiveFile.absolutePath, "-C", ttsDir.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                archiveFile.delete()
                if (result != 0 || !REQUIRED_FILES.all { File(modelDir, it).exists() }) {
                    onProgress("❌ 압축 해제 실패 — 수동으로 $modelDir 에 모델 파일 복사 필요")
                    return@withContext null
                }
                onProgress("TTS 모델 설치 완료")
                modelDir
            } catch (e: Exception) {
                archiveFile.delete()
                onProgress("❌ 다운로드 실패: ${e.message}")
                null
            }
        }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (String) -> Unit) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.connect()
        val total = conn.contentLengthLong; var downloaded = 0L; var lastPct = -1
        FileOutputStream(dest).use { out ->
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192); var n: Int
                while (ins.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n); downloaded += n
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        if (pct != lastPct && pct % 10 == 0) { onProgress("다운로드 ${pct}%"); lastPct = pct }
                    }
                }
            }
        }
        conn.disconnect()
    }

    fun release() { tts = null }
}