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
import kotlin.math.*

private const val TAG = "SupertonicTtsEngine"
private const val MAX_CHUNK_KO = 120   // 한국어 최대 청크 길이

data class SpeakerConfig(val sid: Int, val speed: Float, val label: String, val voiceFile: String)
data class VoiceConfig(val speakers: Map<Int, SpeakerConfig>) {
    fun forSpeaker(num: Int) = speakers[num] ?: speakers[1]
        ?: SpeakerConfig(1, 1.0f, "Speaker$num", "M1.json")
    companion object {
        val DEFAULT = VoiceConfig(mapOf(
            1 to SpeakerConfig(1, 1.0f,  "아스터",   "M1.json"),
            2 to SpeakerConfig(2, 0.95f, "리언",     "F1.json"),
            3 to SpeakerConfig(3, 1.05f, "나레이터", "M2.json")
        ))
        val VOICE_FILES  = listOf("M1.json","M2.json","M3.json","M4.json","M5.json",
                                   "F1.json","F2.json","F3.json","F4.json","F5.json")
        val VOICE_LABELS = listOf("M1 (male1)","M2 (male2)","M3 (male3)","M4 (male4)","M5 (male5)",
                                   "F1 (female1)","F2 (female2)","F3 (female3)","F4 (female4)","F5 (female5)")
    }
}
data class VoiceStyle(val ttlFlat: FloatArray, val ttlShape: LongArray,
                      val dpFlat:  FloatArray, val dpShape:  LongArray)

// tts.json 구조
data class TtsConfig(val sampleRate: Int, val baseChunkSize: Int,
                     val chunkCompressFactor: Int, val latentDim: Int)

class SupertonicTtsEngine(private val context: Context) {
    private var env: OrtEnvironment? = null
    private var dpSess:   OrtSession? = null
    private var teSess:   OrtSession? = null
    private var veSess:   OrtSession? = null
    private var vocSess:  OrtSession? = null
    private var indexer:  LongArray   = LongArray(0)
    private var ttsConf:  TtsConfig?  = null
    private val totalSteps = 10

    // ── 초기화 ──
    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress)
        onProgress("🔧 ONNX Runtime 초기화...")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val p = dir.absolutePath
        dpSess  = env!!.createSession("$p/onnx/duration_predictor.onnx", opts)
        teSess  = env!!.createSession("$p/onnx/text_encoder.onnx",       opts)
        veSess  = env!!.createSession("$p/onnx/vector_estimator.onnx",   opts)
        vocSess = env!!.createSession("$p/onnx/vocoder.onnx",            opts)
        indexer = loadIndexer(File(dir, "onnx/unicode_indexer.json"))
        ttsConf = loadConfig(File(dir, "onnx/tts.json"))
        onProgress("✅ Supertonic 2 준비 (steps=$totalSteps, sr=${ttsConf?.sampleRate})")
    }

    // ── 합성 (한국어 자동 청크 + 침묵 이어붙이기) ──
    fun synthesize(text: String, voiceFile: String, speed: Float, outputFile: File): Boolean {
        if (dpSess == null || ttsConf == null) return false
        if (text.isBlank()) return false
        return try {
            val modelDir = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val style    = loadVoiceStyle(File(modelDir, "voice_styles/$voiceFile"))
            val chunks   = chunkText(text, MAX_CHUNK_KO)
            val wavParts = mutableListOf<FloatArray>()
            val silLen   = (0.3f * (ttsConf!!.sampleRate)).toInt()

            for ((idx, chunk) in chunks.withIndex()) {
                val wav = inferChunk(chunk, "ko", style, speed)
                if (idx > 0) wavParts.add(FloatArray(silLen) { 0f })
                wavParts.add(wav)
            }
            val flat = FloatArray(wavParts.sumOf { it.size })
            var off = 0
            for (p in wavParts) { p.copyInto(flat, off); off += p.size }
            outputFile.writeBytes(float32ToWav(flat, ttsConf!!.sampleRate))
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
        dpSess?.close(); teSess?.close(); veSess?.close(); vocSess?.close(); env?.close()
        dpSess = null; teSess = null; veSess = null; vocSess = null; env = null
    }

    // ── 단일 청크 추론 ──
    private fun inferChunk(text: String, lang: String, style: VoiceStyle, speed: Float): FloatArray {
        val env = env!!
        val conf = ttsConf!!
        val taggedText = "<$lang>$text</$lang>"

        // 1. 텍스트 → token IDs (2D [1][len])
        val codePoints = taggedText.codePoints().toArray()
        val maxLen = codePoints.size
        val textIds = Array(1) { LongArray(maxLen) { j -> indexer.getOrElse(codePoints[j]) { 0L } } }
        val textMask = Array(1) { Array(1) { FloatArray(maxLen) { 1.0f } } }

        val textIdsTensor = createLong2D(textIds, env)
        val textMaskTensor = createFloat3D(textMask, env)

        // 2. Duration Predictor
        val dpInputs = mapOf(
            "text_ids"  to textIdsTensor,
            "style_dp"  to OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dpFlat),  style.dpShape),
            "text_mask" to textMaskTensor
        )
        val dpResult = dpSess!!.run(dpInputs)
        val dpVal = dpResult[0].value
        var duration = when (dpVal) {
            is Array<*>  -> (dpVal as Array<FloatArray>)[0]
            is FloatArray -> dpVal
            else -> throw Exception("duration 타입 오류: ${dpVal?.javaClass}")
        }
        dpResult.close()

        // speed 적용 (ONNX 입력이 아니라 duration에 직접 나눔)
        duration = FloatArray(duration.size) { i -> duration[i] / speed }
        val totalDur = duration.sum()

        // 3. Text Encoder
        val teInputs = mapOf(
            "text_ids"  to textIdsTensor,
            "style_ttl" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttlFlat), style.ttlShape),
            "text_mask" to textMaskTensor
        )
        val teResult  = teSess!!.run(teInputs)
        val textEmb   = teResult[0] as OnnxTensor

        // 4. Noisy Latent 초기화 (Box-Muller)
        val sr = conf.sampleRate
        val wavLenMax = (totalDur * sr).toLong()
        val chunkSize = conf.baseChunkSize * conf.chunkCompressFactor
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
        val latentDim = conf.latentDim * conf.chunkCompressFactor
        val wavLen    = (totalDur * sr).toLong()
        val latentLength = ((wavLen + chunkSize - 1) / chunkSize).toInt()

        val rng = java.util.Random()
        var xt = Array(1) { Array(latentDim) { FloatArray(latentLen) { 0f } } }
        val latentMask = Array(1) { Array(1) { FloatArray(latentLen) { t -> if (t < latentLength) 1f else 0f } } }

        for (d in 0 until latentDim)
            for (t in 0 until latentLen) {
                val u1 = maxOf(1e-10, rng.nextDouble())
                val u2 = rng.nextDouble()
                val v  = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
                xt[0][d][t] = v * latentMask[0][0][t]
            }

        // 5. Vector Estimator 루프 (totalSteps번)
        val totalStepArr = FloatArray(1) { totalSteps.toFloat() }

        for (step in 0 until totalSteps) {
            val curStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
            val totalStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(totalStepArr), longArrayOf(1))
            val noisyLatentTensor = createFloat3D(xt, env)
            val latentMaskTensor  = createFloat3D(latentMask, env)
            val textMaskTensor2   = createFloat3D(textMask, env)

            val veInputs = mapOf(
                "noisy_latent" to noisyLatentTensor,
                "text_emb"     to textEmb,
                "style_ttl"    to OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttlFlat), style.ttlShape),
                "latent_mask"  to latentMaskTensor,
                "text_mask"    to textMaskTensor2,
                "current_step" to curStepTensor,
                "total_step"   to totalStepTensor
            )
            val veResult = veSess!!.run(veInputs)
            xt = veResult[0].value as Array<Array<FloatArray>>
            veResult.close()
            curStepTensor.close(); totalStepTensor.close()
            noisyLatentTensor.close(); latentMaskTensor.close(); textMaskTensor2.close()
        }

        // 6. Vocoder
        val finalLatent = createFloat3D(xt, env)
        val vocResult   = vocSess!!.run(mapOf("latent" to finalLatent))
        val wavBatch    = vocResult[0].value as Array<FloatArray>
        val actualLen   = (totalDur * sr).toInt()
        val wav = wavBatch[0].copyOfRange(0, minOf(actualLen, wavBatch[0].size))

        textIdsTensor.close(); textMaskTensor.close(); textEmb.close()
        teResult.close(); finalLatent.close(); vocResult.close()
        return wav
    }

    // ── 텍스트 → token ID 변환 (indexer는 codePoint → tokenId 배열) ──
    // 단, indexer 크기가 codePoint보다 작을 수 있으므로 범위 체크
    private fun LongArray.getOrElse(idx: Int, default: Long): Long =
        if (idx < 0 || idx >= this.size) default else this[idx]

    // ── 텍스트 청크 분할 ──
    private fun chunkText(text: String, maxLen: Int): List<String> {
        val trimmed = text.trim().replace("\n", " ")
        if (trimmed.length <= maxLen) return listOf(trimmed)
        val chunks = mutableListOf<String>()
        val sentences = trimmed.split(Regex("(?<=[。.!?！？])"))
        val buf = StringBuilder()
        for (s in sentences) {
            if (buf.length + s.length > maxLen && buf.isNotEmpty()) {
                chunks.add(buf.toString().trim()); buf.clear()
            }
            buf.append(s)
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks.filter { it.isNotBlank() }
    }

    // ── 텐서 생성 헬퍼 ──
    private fun createLong2D(arr: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
        val d0 = arr.size; val d1 = arr[0].size
        val flat = LongArray(d0 * d1) { i -> arr[i/d1][i%d1] }
        return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(d0.toLong(), d1.toLong()))
    }

    private fun createFloat3D(arr: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
        val d0 = arr.size; val d1 = arr[0].size; val d2 = arr[0][0].size
        val flat = FloatArray(d0*d1*d2)
        var idx = 0
        for (a in arr) for (b in a) for (c in b) flat[idx++] = c
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat),
            longArrayOf(d0.toLong(), d1.toLong(), d2.toLong()))
    }

    // ── 파일 로더 ──
    private fun loadIndexer(f: File): LongArray {
        val arr = JSONArray(f.readText())
        return LongArray(arr.length()) { i -> arr.getLong(i) }
    }

    private fun loadConfig(f: File): TtsConfig {
        val j = JSONObject(f.readText())
        val ae = j.getJSONObject("ae")
        val ttl = j.getJSONObject("ttl")
        return TtsConfig(
            sampleRate         = ae.getInt("sample_rate"),
            baseChunkSize      = ae.getInt("base_chunk_size"),
            chunkCompressFactor= ttl.getInt("chunk_compress_factor"),
            latentDim          = ttl.getInt("latent_dim")
        )
    }

    private fun loadVoiceStyle(f: File): VoiceStyle {
        val j = JSONObject(f.readText())
        fun flattenAndShape(key: String): Pair<FloatArray, LongArray> {
            val node = j.getJSONObject(key)
            val dims = node.getJSONArray("dims")
            val shape = LongArray(dims.length()) { i -> dims.getLong(i) }
            val list = mutableListOf<Float>()
            fun rec(a: JSONArray) { for (i in 0 until a.length()) when(val v = a.get(i)) { is JSONArray -> rec(v); is Number -> list.add(v.toFloat()) } }
            rec(node.getJSONArray("data"))
            return Pair(list.toFloatArray(), shape)
        }
        val (ttlFlat, ttlShape) = flattenAndShape("style_ttl")
        val (dpFlat,  dpShape)  = flattenAndShape("style_dp")
        return VoiceStyle(ttlFlat, ttlShape, dpFlat, dpShape)
    }

    // ── 모델 다운로드 ──
    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v2")
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
        val base = "https://huggingface.co/Supertone/supertonic/resolve/main"
        listOf("onnx/duration_predictor.onnx","onnx/text_encoder.onnx","onnx/vector_estimator.onnx",
               "onnx/vocoder.onnx","onnx/unicode_indexer.json","onnx/tts.json",
               "voice_styles/M1.json","voice_styles/M2.json","voice_styles/M3.json",
               "voice_styles/M4.json","voice_styles/M5.json",
               "voice_styles/F1.json","voice_styles/F2.json","voice_styles/F3.json",
               "voice_styles/F4.json","voice_styles/F5.json"
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

    // ── WAV 헬퍼 ──
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
