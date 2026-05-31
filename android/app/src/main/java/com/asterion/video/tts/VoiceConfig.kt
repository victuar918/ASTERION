package com.asterion.video.tts

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.*

private const val TAG = "SupertonicTtsEngine"
private const val MAX_CHUNK = 100

data class SpeakerConfig(val sid: Int, val speed: Float, val label: String, val voiceFile: String)
data class VoiceConfig(val speakers: Map<Int, SpeakerConfig>) {
    fun forSpeaker(num: Int) = speakers[num] ?: speakers[1]
        ?: SpeakerConfig(1, 1.0f, "Speaker$num", "M1")
    companion object {
        val DEFAULT = VoiceConfig(mapOf(
            1 to SpeakerConfig(1, 1.0f,  "아스터",   "M1"),
            2 to SpeakerConfig(2, 0.95f, "리언",     "F1"),
            3 to SpeakerConfig(3, 1.05f, "나레이터", "M2")
        ))
        val VOICE_FILES  = listOf("M1","M2","M3","M4","M5","F1","F2","F3","F4","F5")
        val VOICE_LABELS = listOf(
            "M1 (male1)","M2 (male2)","M3 (male3)","M4 (male4)","M5 (male5)",
            "F1 (female1)","F2 (female2)","F3 (female3)","F4 (female4)","F5 (female5)")
    }
}

class SupertonicTtsEngine(private val context: Context) {
    // onnx-community/Supertonic-TTS-2-ONNX 상수 (config.json 기준)
    companion object {
        const val SAMPLE_RATE         = 44100
        const val CHUNK_COMPRESS      = 6
        const val BASE_CHUNK_SIZE     = 512
        const val LATENT_DIM          = 24
        const val STYLE_DIM           = 128
        const val LATENT_SIZE         = BASE_CHUNK_SIZE * CHUNK_COMPRESS    // 3072
        const val FULL_LATENT_DIM     = LATENT_DIM * CHUNK_COMPRESS         // 144
        const val NUM_STEPS           = 10
    }

    private var env: OrtEnvironment? = null
    private var textEnc:  OrtSession? = null
    private var denoiser: OrtSession? = null
    private var decoder:  OrtSession? = null
    private var vocab: Map<String, Long> = emptyMap()

    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress)
        onProgress("🔧 ONNX Runtime 초기화...")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val p = dir.absolutePath
        textEnc  = env!!.createSession("$p/onnx/text_encoder.onnx",   opts)
        denoiser = env!!.createSession("$p/onnx/latent_denoiser.onnx", opts)
        decoder  = env!!.createSession("$p/onnx/voice_decoder.onnx",   opts)
        vocab    = loadVocab(File(dir, "tokenizer.json"))
        // 입출력 이름 확인 로그
        Log.i(TAG, "text_encoder    IN=${textEnc!!.inputNames.toList()}")
        Log.i(TAG, "latent_denoiser IN=${denoiser!!.inputNames.toList()}")
        Log.i(TAG, "voice_decoder   IN=${decoder!!.inputNames.toList()}")
        onProgress("✅ Supertonic 2 준비 (ko지원 vocab=${vocab.size} steps=$NUM_STEPS)")
    }

    fun synthesize(text: String, voiceFile: String, speed: Float, outputFile: File): Boolean {
        if (textEnc == null) return false
        if (text.isBlank()) return false
        return try {
            val modelDir = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val style    = loadStyle(File(modelDir, "voices/${voiceFile}.bin"))
            val chunks   = chunkText(text, MAX_CHUNK)
            val wavParts = mutableListOf<FloatArray>()
            val silLen   = (0.25f * SAMPLE_RATE).toInt()
            for ((i, chunk) in chunks.withIndex()) {
                val wav = inferChunk(chunk, "ko", style, speed)
                if (i > 0) wavParts.add(FloatArray(silLen))
                wavParts.add(wav)
            }
            val flat = FloatArray(wavParts.sumOf { it.size })
            var off = 0; for (p in wavParts) { p.copyInto(flat, off); off += p.size }
            outputFile.writeBytes(toWav(flat, SAMPLE_RATE))
            Log.i(TAG, "완료 ${outputFile.name} ${outputFile.length()/1024}KB")
            true
        } catch(e: Exception) {
            Log.e(TAG, "synthesize: $e")
            try { File(outputFile.parent, "tts_error.txt").writeText("${e.javaClass.simpleName}: ${e.message}") } catch(_: Exception){}
            false
        }
    }

    fun estimateDurationFromFile(f: File): Float {
        if (!f.exists() || f.length() < 44) return 3.0f
        return try {
            val b = f.readBytes(); val sr = ri(b,24); val ba = rs(b,32).toInt(); val ds = ri(b,40)
            if (sr>0 && ba>0) ds.toFloat()/(sr*ba) else 3.0f
        } catch(e: Exception) { 3.0f }
    }

    fun release() {
        textEnc?.close(); denoiser?.close(); decoder?.close(); env?.close()
        textEnc = null; denoiser = null; decoder = null; env = null
    }

    // ── 추론 파이프라인 ─────────────────────────────────────────────────────
    private fun inferChunk(text: String, lang: String, style: FloatArray, speed: Float): FloatArray {
        val env = env!!

        // 1. 토큰화 (NFKD + 언어태그 + vocab)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val tagged     = "<$lang>$normalized</$lang>"
        val ids        = LongArray(tagged.length) { i -> vocab[tagged[i].toString()] ?: vocab[" "] ?: 0L }
        val seqLen     = ids.size

        // attention_mask: float32 [1, seqLen]
        val attnMask = FloatArray(seqLen) { 1.0f }

        // style: [1, STYLE_DIM, seq_len_style]  seq_len_style = style.size / STYLE_DIM
        val styleSeqLen  = style.size / STYLE_DIM
        val styleShape   = longArrayOf(1L, STYLE_DIM.toLong(), styleSeqLen.toLong())
        val styleTensor  = env.f32(style, styleShape)
        val idsTensor    = env.i64(ids, longArrayOf(1L, seqLen.toLong()))
        val attnTensor   = env.f32(attnMask, longArrayOf(1L, seqLen.toLong()))

        // 2. Text Encoder → last_hidden_state, raw_durations
        val teOut = textEnc!!.run(mapOf(
            "input_ids"      to idsTensor,
            "attention_mask" to attnTensor,
            "style"          to styleTensor
        ))
        val hiddenState  = teOut["last_hidden_state"].get() as OnnxTensor
        val rawDurTensor = teOut["raw_durations"].get() as OnnxTensor
        @Suppress("UNCHECKED_CAST")
        val rawDur = (rawDurTensor.value as Array<FloatArray>)[0]

        // 3. Duration → latent 크기
        val durations    = LongArray(rawDur.size) { i -> maxOf(0L, (rawDur[i] / speed * SAMPLE_RATE).toLong()) }
        val totalSamples = durations.sum()
        val latentLen    = maxOf(1, ((totalSamples + LATENT_SIZE - 1) / LATENT_SIZE).toInt())

        // 4. Noisy latents (Box-Muller)  shape [1, FULL_LATENT_DIM, latentLen]
        val rng = java.util.Random()
        var latents = FloatArray(FULL_LATENT_DIM * latentLen)
        var idx = 0
        while (idx < latents.size - 1) {
            val u1 = rng.nextDouble().coerceIn(1e-10, 1.0)
            val u2 = rng.nextDouble()
            val r  = sqrt(-2.0 * ln(u1)).toFloat()
            latents[idx]   = r * cos(2.0 * PI * u2).toFloat()
            latents[idx+1] = r * sin(2.0 * PI * u2).toFloat()
            idx += 2
        }
        val latentShape = longArrayOf(1L, FULL_LATENT_DIM.toLong(), latentLen.toLong())

        // latent_mask: float32 [1, 1, latentLen] — latent_denoiser에 float32로 전달
        val latentMaskF = FloatArray(latentLen) { 1.0f }
        val latentMask3d = longArrayOf(1L, 1L, latentLen.toLong())

        // 5. Denoising loop
        for (step in 0 until NUM_STEPS) {
            val timeStep    = floatArrayOf(step.toFloat() / NUM_STEPS)
            val latTensor   = env.f32(latents,     latentShape)
            val timeTensor  = env.f32(timeStep,    longArrayOf(1L))
            val lmTensor    = env.f32(latentMaskF, latentMask3d)
            val attn2Tensor = env.f32(attnMask,    longArrayOf(1L, seqLen.toLong()))
            val style2      = env.f32(style,       styleShape)

            val denoised = denoiser!!.run(mapOf(
                "latents"          to latTensor,
                "time_step"        to timeTensor,
                "last_hidden_state" to hiddenState,
                "attention_mask"   to attn2Tensor,
                "latent_mask"      to lmTensor,
                "style"            to style2
            ))
            val outTensor = denoised["latents"].get() as OnnxTensor
            val buf = outTensor.floatBuffer
            latents = FloatArray(buf.remaining()).also { buf.get(it) }
            latTensor.close(); timeTensor.close(); lmTensor.close()
            attn2Tensor.close(); style2.close(); denoised.close(); outTensor.close()
        }

        // 6. Voice Decoder — latents * latent_mask 적용 후 단일 "latent" 입력
        val maskedLatents = FloatArray(latents.size)
        for (d in 0 until FULL_LATENT_DIM)
            for (t in 0 until latentLen)
                maskedLatents[d * latentLen + t] = latents[d * latentLen + t] * latentMaskF[t]

        val finalLatent = env.f32(maskedLatents, latentShape)
        val decOut      = decoder!!.run(mapOf("latent" to finalLatent))
        val wavTensor   = decOut.values.first() as OnnxTensor
        val wavBuf      = wavTensor.floatBuffer
        val wavAll      = FloatArray(wavBuf.remaining()).also { wavBuf.get(it) }
        val wav         = wavAll.copyOfRange(0, minOf(totalSamples.toInt(), wavAll.size))

        idsTensor.close(); attnTensor.close(); styleTensor.close()
        hiddenState.close(); rawDurTensor.close(); teOut.close()
        finalLatent.close(); decOut.close()
        return wav
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────
    private fun OrtEnvironment.f32(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(this, FloatBuffer.wrap(data), shape)

    private fun OrtEnvironment.i64(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(this, LongBuffer.wrap(data), shape)

    private fun loadVocab(f: File): Map<String, Long> {
        val j = JSONObject(f.readText())
        val v = j.getJSONObject("model").getJSONObject("vocab")
        return buildMap { for (k in v.keys()) put(k, v.getLong(k)) }.also { Log.i(TAG, "vocab=${it.size}") }
    }

    private fun loadStyle(f: File): FloatArray {
        val bytes = f.readBytes()
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
        // shape → [1, STYLE_DIM, size/STYLE_DIM] 은 inferChunk에서 처리
    }

    private fun chunkText(text: String, max: Int): List<String> {
        val t = text.trim().replace("\n", " ")
        if (t.length <= max) return listOf(t)
        val chunks = mutableListOf<String>(); val buf = StringBuilder()
        for (s in t.split(Regex("(?<=[。.!?！？])"))) {
            if (buf.length + s.length > max && buf.isNotEmpty()) { chunks.add(buf.toString().trim()); buf.clear() }
            buf.append(s)
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks.filter { it.isNotBlank() }
    }

    // ── 모델 다운로드 (.ready_v3) ─────────────────────────────────────────
    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v3")
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS).build()
        val base = "https://huggingface.co/onnx-community/Supertonic-TTS-2-ONNX/resolve/main"
        val files = listOf(
            "onnx/text_encoder.onnx",      "onnx/text_encoder.onnx_data",
            "onnx/latent_denoiser.onnx",   "onnx/latent_denoiser.onnx_data",
            "onnx/voice_decoder.onnx",     "onnx/voice_decoder.onnx_data",
            "tokenizer.json", "config.json",
            "voices/M1.bin","voices/M2.bin","voices/M3.bin","voices/M4.bin","voices/M5.bin",
            "voices/F1.bin","voices/F2.bin","voices/F3.bin","voices/F4.bin","voices/F5.bin"
        )
        files.forEach { path ->
            onProgress("⬇ $path")
            val out = File(dest, path).also { it.parentFile?.mkdirs() }
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("${resp.code}: $path")
                resp.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        marker.createNewFile(); dest
    }

    // ── WAV ──────────────────────────────────────────────────────────────
    private fun toWav(s: FloatArray, sr: Int): ByteArray {
        val ds = s.size * 2
        return java.io.ByteArrayOutputStream(44+ds).apply {
            fun wi(v:Int)=write(byteArrayOf(v.toByte(),(v shr 8).toByte(),(v shr 16).toByte(),(v shr 24).toByte()))
            fun ws(v:Int)=write(byteArrayOf(v.toByte(),(v shr 8).toByte()))
            write("RIFF".toByteArray()); wi(36+ds); write("WAVEfmt ".toByteArray()); wi(16); ws(1); ws(1)
            wi(sr); wi(sr*2); ws(2); ws(16); write("data".toByteArray()); wi(ds)
            for(x in s){val v=(x.coerceIn(-1f,1f)*32767).toInt().toShort();write(byteArrayOf(v.toByte(),(v.toInt() shr 8).toByte()))}
        }.toByteArray()
    }

    private fun ri(b:ByteArray,o:Int)=(b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun rs(b:ByteArray,o:Int):Short=((b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)).toShort()
}
