package com.asterion.video.tts

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SupertonicTtsEngine"

// ─ 데이터 클래스 ─────────────────────────────────────────────────────────────

/**
 * 화자 설정
 *
 * @param sid   sherpa-onnx 화자 ID (Supertonic 3: 0~9)
 *              ※ 구버전의 'ASTERION 화자번호(1,2,3)'가 아님에 주의
 * @param speed 발화 속도 배율 (1.0 = 보통, 범위: 0.5~2.0)
 * @param label UI 표시명
 */
data class SpeakerConfig(val sid: Int, val speed: Float, val label: String)

/**
 * 전체 화자 구성
 *
 * key   = ASTERION 화자 번호 (1=아스터, 2=리언, 3=나레이터, ...)
 * value = SpeakerConfig — sid는 sherpa-onnx speaker ID (0~9)
 */
data class VoiceConfig(val speakers: Map<Int, SpeakerConfig>) {

    /** ASTERION 화자 번호 → SpeakerConfig 조회 */
    fun forSpeaker(num: Int): SpeakerConfig =
        speakers[num] ?: speakers[1] ?: SpeakerConfig(0, 1.0f, "Speaker$num")

    companion object {
        /**
         * 기본 화자 매핑
         * ASTERION 화자번호 → Supertonic 3 sid (0~9)
         * sid 0-4: 남성 계열 / sid 5-9: 여성 계열 (Supertonic 3 기준)
         */
        val DEFAULT = VoiceConfig(mapOf(
            1 to SpeakerConfig(sid = 0,  speed = 1.0f,  label = "아스터"),
            2 to SpeakerConfig(sid = 5,  speed = 0.95f, label = "리언"),
            3 to SpeakerConfig(sid = 1,  speed = 1.05f, label = "나레이터")
        ))

        /** Supertonic 3 화자 ID 목록 (0~9, 총 10명) */
        val SID_LIST: List<Int> = (0..9).toList()

        /**
         * UI 스피너용 라벨
         * 이름을 VOICE_LABELS로 유지 — AsterionVideoActivity 코드 변경 최소화
         */
        val VOICE_LABELS: List<String> = listOf(
            "sid-0 (남성①)", "sid-1 (남성②)", "sid-2 (남성③)",
            "sid-3 (남성④)", "sid-4 (남성⑤)",
            "sid-5 (여성①)", "sid-6 (여성②)", "sid-7 (여성③)",
            "sid-8 (여성④)", "sid-9 (여성⑤)"
        )

        /**
         * 하위 호환용 — AsterionVideoActivity 가 VOICE_FILES[position].toInt() 로
         * sherpa-onnx sid 를 얻을 수 있도록 문자열 정수 목록 유지
         */
        val VOICE_FILES: List<String> = SID_LIST.map { it.toString() }
    }
}

// ─ TTS 엔진 ──────────────────────────────────────────────────────────────────

class SupertonicTtsEngine(private val context: Context) {

    companion object {
        /** Supertonic 3 출력 샘플레이트 (24 kHz 고정) */
        const val SAMPLE_RATE = 24000

        /**
         * Diffusion 스텝 수 — 높을수록 품질↑ 속도↓
         * S24 Ultra 기준 권장값: 8 (문서 기본값)
         * 품질 우선 시 16, 속도 우선 시 4~6
         */
        const val NUM_STEPS = 8

        init {
            // sherpa-onnx AAR 내 JNI 네이티브 라이브러리 명시적 로드
            // AAR이 자동으로 로드하는 경우 중복 호출은 무해하게 무시됨
            try {
                System.loadLibrary("sherpa-onnx-jni")
            } catch (e: UnsatisfiedLinkError) {
                // AAR 자동 로드 시 정상적으로 도달하지 않음
                Log.w(TAG, "sherpa-onnx-jni 명시적 로드 시도 결과: ${e.message}")
            }
        }
    }

    private var tts: OfflineTts? = null

    // ── 초기화 ───────────────────────────────────────────────────────────────

    /**
     * TTS 엔진 초기화
     * 모델 파일이 없으면 GitHub Releases에서 tar.bz2를 다운로드 후 압축 해제
     * 이미 다운로드된 경우 캐시를 재사용
     */
    suspend fun init(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val modelDir = prepareModelDir(onProgress)
        onProgress("🔧 sherpa-onnx OfflineTts 초기화 중...")

        val p = modelDir.absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$p/duration_predictor.int8.onnx",
                    textEncoder       = "$p/text_encoder.int8.onnx",
                    vectorEstimator   = "$p/vector_estimator.int8.onnx",
                    vocoder           = "$p/vocoder.int8.onnx",
                    ttsJson           = "$p/tts.json",
                    unicodeIndexer    = "$p/unicode_indexer.bin",
                    voiceStyle        = "$p/voice.bin",
                ),
                numThreads = 4,
                debug      = false,
            ),
        )

        // assetManager = null: 파일 시스템 절대 경로 기반 모델 사용
        tts = OfflineTts(assetManager = null, config = config)

        // numSpeakers / sampleRate 는 버전에 따라 없을 수 있어 방어적으로 호출
        val numSpk = runCatching { tts!!.numSpeakers() }.getOrDefault(-1)
        val sr     = runCatching { tts!!.sampleRate()  }.getOrDefault(SAMPLE_RATE)
        onProgress("✅ Supertonic 3 준비 완료 (speakers=$numSpk sampleRate=$sr steps=$NUM_STEPS)")
    }

    // ── 음성 합성 ─────────────────────────────────────────────────────────────

    /**
     * 텍스트를 음성으로 합성하여 WAV 파일로 저장
     *
     * @param text       합성할 한국어 텍스트
     * @param sid        sherpa-onnx 화자 ID (0~9)
     * @param speed      발화 속도 배율 (0.5~2.0, 1.0 = 보통)
     * @param outputFile 저장할 WAV 파일 경로 (cacheDir 권장)
     * @return 성공 여부
     */
    fun synthesize(text: String, sid: Int, speed: Float, outputFile: File): Boolean {
        val engine = tts ?: run {
            Log.e(TAG, "synthesize: TTS 엔진이 초기화되지 않음")
            return false
        }
        if (text.isBlank()) {
            Log.w(TAG, "synthesize: 빈 텍스트 — 건너뜀")
            return false
        }
        return try {
            val genConfig = GenerationConfig(
                sid      = sid.coerceIn(0, 9),
                numSteps = NUM_STEPS,
                speed    = speed.coerceIn(0.5f, 2.0f),
                // ★ 한국어 발음을 위해 필수 — 없으면 영어식 발음으로 합성됨
                extra    = mapOf("lang" to "ko"),
            )
            val audio = engine.generateWithConfigAndCallback(
                text     = text,
                config   = genConfig,
                callback = { _: FloatArray -> 1 }  // 1 = 계속, 0 = 중단
            )
            val saved = audio.save(outputFile.absolutePath)
            if (saved) {
                Log.i(TAG, "✅ 합성 완료: ${outputFile.name} " +
                    "(${outputFile.length() / 1024}KB sid=$sid sr=${audio.sampleRate})")
            } else {
                Log.e(TAG, "❌ audio.save() 실패: ${outputFile.absolutePath}")
            }
            saved
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "synthesize 예외: $msg")
            Log.e(TAG, e.stackTraceToString().lines().take(10).joinToString("\n"))
            try {
                File(context.filesDir, "tts_error.txt")
                    .writeText("sid=$sid speed=$speed\n$msg\n" +
                        e.stackTraceToString().lines().take(12).joinToString("\n"))
            } catch (_: Exception) {}
            false
        }
    }

    // ── 유틸리티 ─────────────────────────────────────────────────────────────

    /**
     * WAV 헤더 파싱으로 오디오 길이(초) 추정
     * sherpa-onnx 출력 WAV(PCM 16-bit, 24kHz, mono)에서 정확히 동작
     * 샘플레이트가 44100 → 24000 으로 변경되었지만 헤더에서 직접 읽으므로 자동 대응
     */
    fun estimateDurationFromFile(f: File): Float {
        if (!f.exists() || f.length() < 44) return 3.0f
        return try {
            val b  = f.readBytes()
            val sr = ri(b, 24)          // byte 24-27: sample rate
            val ba = rs(b, 32).toInt()  // byte 32-33: block align (PCM 16-bit mono = 2)
            val ds = ri(b, 40)          // byte 40-43: data chunk size (bytes)
            if (sr > 0 && ba > 0) ds.toFloat() / (sr * ba) else 3.0f
        } catch (e: Exception) {
            Log.w(TAG, "estimateDuration 실패: ${e.message}")
            3.0f
        }
    }

    fun release() {
        tts?.release()
        tts = null
        Log.i(TAG, "TTS 엔진 해제 완료")
    }

    // ── 모델 다운로드 / 압축 해제 ────────────────────────────────────────────

    private suspend fun prepareModelDir(onProgress: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val marker = File(dest, AppConfig.TTS_READY_MARKER)

            if (marker.exists()) {
                onProgress("Supertonic 3 모델 캐시 재사용 (${dest.absolutePath})")
                return@withContext dest
            }

            // 구버전 (Supertonic 2) 잔여 파일 완전 삭제 후 재생성
            dest.deleteRecursively()
            dest.mkdirs()

            val tempTar = File(dest, "_download.tar.bz2")
            try {
                downloadTar(AppConfig.TTS_TAR_URL, tempTar, onProgress)
                onProgress("📦 압축 해제 중 (시간 소요)...")
                extractTarBz2(tempTar, dest, onProgress)
                tempTar.delete()
                validateModelFiles(dest)
                marker.createNewFile()
                onProgress("✅ Supertonic 3 모델 준비 완료")
            } catch (e: Exception) {
                // 실패 시 마커 없이 종료 — 다음 실행 시 자동 재시도
                tempTar.delete()
                throw Exception("모델 준비 실패: ${e.message}", e)
            }
            dest
        }

    private fun downloadTar(
        url: String,
        dest: File,
        onProgress: (String) -> Unit
    ) {
        onProgress("⬇ Supertonic 3 모델 다운로드 중 (~350MB)...")
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(1800, TimeUnit.SECONDS)  // 30분 (느린 네트워크 대응)
            .followRedirects(true)
            .addInterceptor { chain ->
                // GitHub releases 직접 다운로드에 User-Agent 필요
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "ASTERION-Android/3.2.0")
                        .build()
                )
            }
            .build()

        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: $url")
            val total = resp.body!!.contentLength()
            resp.body!!.byteStream().use { input ->
                dest.outputStream().use { output ->
                    var received = 0L
                    val buf = ByteArray(65536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        received += read
                        // 10 MB 마다 진행 상황 보고
                        if (total > 0 && received % (10 * 1024 * 1024L) < 65536L) {
                            val pct = received * 100 / total
                            onProgress("  ⬇ ${received / 1024 / 1024}MB / ${total / 1024 / 1024}MB ($pct%)")
                        }
                    }
                }
            }
        }
        onProgress("  ✅ 다운로드 완료 (${dest.length() / 1024 / 1024}MB)")
    }

    /**
     * tar.bz2 스트리밍 압축 해제
     * 최상위 디렉토리 (sherpa-onnx-supertonic-3-tts-int8-2026-05-11/) 를 제거하고
     * destDir 바로 아래에 파일 배치
     *
     * 메모리: BZip2CompressorInputStream + TarArchiveInputStream 모두 스트리밍 방식
     *         → ~350MB 파일도 RAM 과부하 없음
     */
    private fun extractTarBz2(
        archive: File,
        destDir: File,
        onProgress: (String) -> Unit
    ) {
        BZip2CompressorInputStream(archive.inputStream().buffered(65536)).use { bzip2 ->
            TarArchiveInputStream(bzip2).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (!tar.canReadEntryData(entry) || !entry.isFile) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    // "sherpa-onnx-xxx/tts.json"  →  "tts.json" (최상위 디렉토리 제거)
                    val name = entry.name
                        .substringAfter("/")
                        .trimStart('/')
                        .replace("\\", "/")  // Windows 경로 표기 방어

                    // 경로 순회 공격 방어
                    if (name.isBlank() || name.contains("..")) {
                        Log.w(TAG, "tar entry 스킵 (위험 경로): ${entry.name}")
                        entry = tar.nextTarEntry
                        continue
                    }

                    val out = File(destDir, name)
                    out.parentFile?.mkdirs()
                    onProgress("  📄 $name (${entry.size / 1024}KB)")
                    out.outputStream().use { tar.copyTo(it, bufferSize = 65536) }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    /** 필수 모델 파일 존재 및 크기 검증 */
    private fun validateModelFiles(dir: File) {
        val required = listOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin"
        )
        val missing = required.filter {
            val f = File(dir, it)
            !f.exists() || f.length() == 0L
        }
        if (missing.isNotEmpty()) {
            throw Exception("모델 파일 누락 또는 손상: $missing")
        }
        Log.i(TAG, "모델 파일 검증 완료: ${required.size}개 정상")
    }

    // ── WAV 헤더 파싱 유틸 ───────────────────────────────────────────────────

    private fun ri(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun rs(b: ByteArray, o: Int): Short =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort()
}
