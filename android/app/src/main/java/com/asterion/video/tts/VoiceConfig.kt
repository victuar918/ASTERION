package com.asterion.video.tts

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.TimeUnit

private const val TAG = "SupertonicTtsEngine"

data class SpeakerConfig(val sid: Int, val speed: Float, val label: String, val voiceFile: String)
data class VoiceConfig(
    val speaker1: SpeakerConfig,
    val speaker2: SpeakerConfig,
    val speaker3: SpeakerConfig
) {
    fun forSpeaker(num: Int) = when(num) { 2->speaker2; 3->speaker3; else->speaker1 }
    companion object {
        val DEFAULT = VoiceConfig(
            SpeakerConfig(0, 1.0f,  "아스터",   "M1.json"),
            SpeakerConfig(1, 0.95f, "리언",     "F1.json"),
            SpeakerConfig(2, 1.05f, "나레이터", "M2.json")
        )
    }
}

class SupertonicTtsEngine(private val context: Context) {
    private var env: OrtEnvironment? = null
    private var textEncoder: OrtSession? = null
    private var durationPredictor: OrtSession? = null
    private var vectorEstimator: OrtSession? = null
    private var vocoder: OrtSession? = null
    private var unicodeIndexer: Map<String, Long> = emptyMap()
    private var ttsConfig: JSONObject? = null
    private val totalSteps = 4

    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress)
        onProgress("🔧 ONNX Runtime 초기화...")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val p = dir.absolutePath
        textEncoder       = env!!.createSession("$p/onnx/text_encoder.onnx",       opts)
        durationPredictor = env!!.createSession("$p/onnx/duration_predictor.onnx", opts)
        vectorEstimator   = env!!.createSession("$p/onnx/vector_estimator.onnx",   opts)
        vocoder           = env!!.createSession("$p/onnx/vocoder.onnx",            opts)
        // unicode_indexer.json: 배열 형식 [tokenId, ...] — 인덱스 = Unicode 코드 포인트
        unicodeIndexer = loadUnicodeIndexer(File(dir, "onnx/unicode_indexer.json"))
        ttsConfig      = JSONObject(File(dir, "onnx/tts.json").readText())
        onProgress("✅ Supertonic 2 온디바이스 준비 완료 (${unicodeIndexer.size}자 사전)")
    }

    fun synthesize(text: String, voiceFile: String, speed: Float, outputFile: File): Boolean {
        if (textEncoder == null) return false
        if (text.isBlank()) return false
        return try {
            val modelDir   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val voiceStyle = loadVoiceStyle(File(modelDir, "voice_styles/$voiceFile"))
            val tokenIds   = tokenize(text)
            val encoded    = runTextEncoder(tokenIds)
            val durations  = runDurationPredictor(encoded, voiceStyle, speed)
            val latent     = runVectorEstimator(encoded, durations, voiceStyle)
            val samples    = runVocoder(latent)
            encoded.close(); durations.close(); latent.close()
            val sr = ttsConfig?.optInt("sample_rate", 44100) ?: 44100
            outputFile.writeBytes(float32ToWav(samples, sr))
            Log.i(TAG, "완료: ${outputFile.name} (${outputFile.length()/1024}KB)")
            true
        } catch(e: Exception) { Log.e(TAG, "synthesize: $e"); false }
    }

    fun estimateDurationFromFile(f: File): Float {
        if (!f.exists() || f.length() < 44) return 3.0f
        return try {
            val b = f.readBytes()
            val sr = ri(b,24); val ba = rs(b,32).toInt(); val ds = ri(b,40)
            if (sr>0 && ba>0) ds.toFloat()/(sr*ba) else 3.0f
        } catch(e: Exception) { 3.0f }
    }

    fun release() {
        textEncoder?.close(); durationPredictor?.close()
        vectorEstimator?.close(); vocoder?.close(); env?.close()
        textEncoder = null; durationPredictor = null
        vectorEstimator = null; vocoder = null; env = null
    }

    // ── ONNX 실행 ──
    private fun runTextEncoder(tokenIds: LongArray): OnnxTensor {
        val shape = longArrayOf(1, tokenIds.size.toLong())
        val input = OnnxTensor.createTensor(env!!, LongBuffer.wrap(tokenIds), shape)
        val result = textEncoder!!.run(mapOf("token_ids" to input))
        input.close()
        return result[0].value as OnnxTensor
    }

    private fun runDurationPredictor(encoded: OnnxTensor, voiceStyle: FloatArray, speed: Float): OnnxTensor {
        val ss = longArrayOf(1, voiceStyle.size.toLong())
        val st = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(voiceStyle), ss)
        val sp = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        val result = durationPredictor!!.run(mapOf("encoded" to encoded, "style" to st, "speed" to sp))
        st.close(); sp.close()
        return result[0].value as OnnxTensor
    }

    private fun runVectorEstimator(encoded: OnnxTensor, durations: OnnxTensor, voiceStyle: FloatArray): OnnxTensor {
        val ss = longArrayOf(1, voiceStyle.size.toLong())
        val st = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(voiceStyle), ss)
        val ts = OnnxTensor.createTensor(env!!, LongBuffer.wrap(longArrayOf(totalSteps.toLong())), longArrayOf(1))
        val result = vectorEstimator!!.run(mapOf(
            "encoded" to encoded, "durations" to durations, "style" to st, "total_step" to ts))
        st.close(); ts.close()
        return result[0].value as OnnxTensor
    }

    private fun runVocoder(latent: OnnxTensor): FloatArray {
        val result = vocoder!!.run(mapOf("latent" to latent))
        val tensor = result[0].value as OnnxTensor
        val buf = tensor.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    // ── 텍스트 전처리 ──
    private fun tokenize(text: String): LongArray =
        text.map { unicodeIndexer[it.toString()] ?: unicodeIndexer["<unk>"] ?: 0L }.toLongArray()

    /**
     * unicode_indexer.json 형식: JSON 배열
     * 인덱스 = Unicode 코드 포인트, 값 = 토큰 ID (-1이면 미등록)
     * 예: [-1,-1,...,0,1,2,...] → ' '(32)→0, '0'(48)→1, ...
     */
    private fun loadUnicodeIndexer(f: File): Map<String, Long> {
        val arr = JSONArray(f.readText())
        val map = mutableMapOf<String, Long>()
        for (i in 0 until arr.length()) {
            val tokenId = arr.getLong(i)
            if (tokenId >= 0) {
                map[i.toChar().toString()] = tokenId
            }
        }
        Log.i(TAG, "unicode_indexer 로드: ${map.size}자 등록")
        return map
    }

    private fun loadVoiceStyle(f: File): FloatArray {
        val json = JSONObject(f.readText())
        val arr  = json.getJSONArray("style")
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }

    // ── 모델 다운로드 ──
    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v2")
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
        val base  = "https://huggingface.co/Supertone/supertonic/resolve/main"
        val files = listOf(
            "onnx/text_encoder.onnx", "onnx/duration_predictor.onnx",
            "onnx/vector_estimator.onnx", "onnx/vocoder.onnx",
            "onnx/unicode_indexer.json", "onnx/tts.json",
            "voice_styles/M1.json", "voice_styles/M2.json", "voice_styles/M3.json",
            "voice_styles/F1.json", "voice_styles/F2.json", "voice_styles/F3.json"
        )
        files.forEach { path ->
            onProgress("⬇ $path")
            val out = File(dest, path).also { it.parentFile?.mkdirs() }
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("${resp.code}: $path")
                resp.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        marker.createNewFile()
        dest
    }

    // ── WAV 헬퍼 ──
    private fun float32ToWav(s: FloatArray, sr: Int): ByteArray {
        val ds = s.size * 2
        return java.io.ByteArrayOutputStream(44+ds).apply {
            fun wi(v: Int) = write(byteArrayOf(v.toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte()))
            fun ws(v: Int) = write(byteArrayOf(v.toByte(),(v shr 8).toByte()))
            write("RIFF".toByteArray()); wi(36+ds)
            write("WAVEfmt ".toByteArray()); wi(16); ws(1); ws(1)
            wi(sr); wi(sr*2); ws(2); ws(16)
            write("data".toByteArray()); wi(ds)
            for (x in s) { val v=(x.coerceIn(-1f,1f)*32767).toInt().toShort(); write(byteArrayOf(v.toByte(),(v.toInt() shr 8).toByte())) }
        }.toByteArray()
    }

    private fun ri(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun rs(b: ByteArray, o: Int): Short = ((b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)).toShort()
}
