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

data class SpeakerConfig(
    val sid: Int,
    val speed: Float,
    val label: String,
    val voiceFile: String  // e.g. "M1.json", "F2.json"
)

data class VoiceConfig(
    val speakers: Map<Int, SpeakerConfig>  // key = 시트 Speaker 콌럼 값 (1,2,3...)
) {
    fun forSpeaker(num: Int): SpeakerConfig =
        speakers[num] ?: speakers[1] ?: SpeakerConfig(1, 1.0f, "Speaker$num", "M1.json")

    companion object {
        // 기본값 (시트 미로드 시 fallback)
        val DEFAULT = VoiceConfig(mapOf(
            1 to SpeakerConfig(1, 1.0f,  "아스터",   "M1.json"),
            2 to SpeakerConfig(2, 0.95f, "리언",     "F1.json"),
            3 to SpeakerConfig(3, 1.05f, "나레이터", "M2.json")
        ))

        val VOICE_FILES = listOf(
            "M1.json", "M2.json", "M3.json", "M4.json", "M5.json",
            "F1.json", "F2.json", "F3.json", "F4.json", "F5.json"
        )
        val VOICE_LABELS = listOf(
            "M1 (male 1)", "M2 (male 2)", "M3 (male 3)", "M4 (male 4)", "M5 (male 5)",
            "F1 (female 1)", "F2 (female 2)", "F3 (female 3)", "F4 (female 4)", "F5 (female 5)"
        )
    }
}

data class VoiceStyle(
    val styleTtl: FloatArray,
    val styleDp:  FloatArray
)

class SupertonicTtsEngine(private val context: Context) {
    private var env: OrtEnvironment? = null
    private var textEncoder: OrtSession? = null
    private var durationPredictor: OrtSession? = null
    private var vectorEstimator: OrtSession? = null
    private var vocoder: OrtSession? = null
    private var unicodeIndexer: Map<String, Long> = emptyMap()
    private var ttsConfig: JSONObject? = null
    private val totalSteps = 10  // 최고 품질 고정

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
        unicodeIndexer    = loadUnicodeIndexer(File(dir, "onnx/unicode_indexer.json"))
        ttsConfig         = JSONObject(File(dir, "onnx/tts.json").readText())
        onProgress("✅ Supertonic 2 준비 (steps=$totalSteps, ${unicodeIndexer.size}자 사전)")
    }

    fun synthesize(text: String, voiceFile: String, speed: Float, outputFile: File): Boolean {
        if (textEncoder == null) return false
        if (text.isBlank()) return false
        return try {
            val modelDir = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val style    = loadVoiceStyle(File(modelDir, "voice_styles/$voiceFile"))
            val tokenIds = tokenize(text)
            val encoded   = runTextEncoder(tokenIds)
            val durations = runDurationPredictor(encoded, style.styleDp, speed)
            val latent    = runVectorEstimator(encoded, durations, style.styleTtl)
            val samples   = runVocoder(latent)
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

    private fun runTextEncoder(tokenIds: LongArray): OnnxTensor {
        val shape = longArrayOf(1, tokenIds.size.toLong())
        val input = OnnxTensor.createTensor(env!!, LongBuffer.wrap(tokenIds), shape)
        val result = textEncoder!!.run(mapOf("token_ids" to input))
        input.close()
        return result[0].value as OnnxTensor
    }

    private fun runDurationPredictor(encoded: OnnxTensor, styleDp: FloatArray, speed: Float): OnnxTensor {
        val dpTensor = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(styleDp), longArrayOf(1, 8, 16))
        val spTensor = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        val result = durationPredictor!!.run(mapOf("encoded" to encoded, "style_dp" to dpTensor, "speed" to spTensor))
        dpTensor.close(); spTensor.close()
        return result[0].value as OnnxTensor
    }

    private fun runVectorEstimator(encoded: OnnxTensor, durations: OnnxTensor, styleTtl: FloatArray): OnnxTensor {
        val ttlTensor  = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256))
        val stepTensor = OnnxTensor.createTensor(env!!, LongBuffer.wrap(longArrayOf(totalSteps.toLong())), longArrayOf(1))
        val result = vectorEstimator!!.run(mapOf(
            "encoded" to encoded, "durations" to durations,
            "style_ttl" to ttlTensor, "total_step" to stepTensor))
        ttlTensor.close(); stepTensor.close()
        return result[0].value as OnnxTensor
    }

    private fun runVocoder(latent: OnnxTensor): FloatArray {
        val result = vocoder!!.run(mapOf("latent" to latent))
        val tensor = result[0].value as OnnxTensor
        val buf = tensor.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    private fun tokenize(text: String): LongArray =
        text.map { unicodeIndexer[it.toString()] ?: unicodeIndexer["<unk>"] ?: 0L }.toLongArray()

    private fun loadUnicodeIndexer(f: File): Map<String, Long> {
        val arr = JSONArray(f.readText())
        val map = mutableMapOf<String, Long>()
        for (i in 0 until arr.length()) {
            val id = arr.getLong(i)
            if (id >= 0) map[i.toChar().toString()] = id
        }
        return map
    }

    private fun loadVoiceStyle(f: File): VoiceStyle {
        val json = JSONObject(f.readText())
        fun flatten(arr: JSONArray): FloatArray {
            val list = mutableListOf<Float>()
            fun rec(a: JSONArray) { for (i in 0 until a.length()) when (val v = a.get(i)) { is JSONArray -> rec(v); is Number -> list.add(v.toFloat()) } }
            rec(arr); return list.toFloatArray()
        }
        return VoiceStyle(flatten(json.getJSONArray("style_ttl")), flatten(json.getJSONArray("style_dp")))
    }

    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v2")
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
        val base  = "https://huggingface.co/Supertone/supertonic/resolve/main"
        listOf("onnx/text_encoder.onnx","onnx/duration_predictor.onnx","onnx/vector_estimator.onnx",
               "onnx/vocoder.onnx","onnx/unicode_indexer.json","onnx/tts.json",
               "voice_styles/M1.json","voice_styles/M2.json","voice_styles/M3.json","voice_styles/M4.json","voice_styles/M5.json",
               "voice_styles/F1.json","voice_styles/F2.json","voice_styles/F3.json","voice_styles/F4.json","voice_styles/F5.json"
        ).forEach { path ->
            onProgress("⬇ $path")
            val out = File(dest, path).also { it.parentFile?.mkdirs() }
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("${resp.code}: $path")
                resp.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        marker.createNewFile(); dest
    }

    private fun float32ToWav(s: FloatArray, sr: Int): ByteArray {
        val ds = s.size * 2
        return java.io.ByteArrayOutputStream(44+ds).apply {
            fun wi(v: Int) = write(byteArrayOf(v.toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte()))
            fun ws(v: Int) = write(byteArrayOf(v.toByte(),(v shr 8).toByte()))
            write("RIFF".toByteArray()); wi(36+ds); write("WAVEfmt ".toByteArray()); wi(16); ws(1); ws(1)
            wi(sr); wi(sr*2); ws(2); ws(16); write("data".toByteArray()); wi(ds)
            for (x in s) { val v=(x.coerceIn(-1f,1f)*32767).toInt().toShort(); write(byteArrayOf(v.toByte(),(v.toInt() shr 8).toByte())) }
        }.toByteArray()
    }

    private fun ri(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun rs(b: ByteArray, o: Int): Short = ((b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)).toShort()
}
