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
    companion object {
        const val SAMPLE_RATE     = 44100
        const val CHUNK_COMPRESS  = 6
        const val BASE_CHUNK_SIZE = 512
        const val LATENT_DIM      = 24
        const val STYLE_DIM       = 128
        const val LATENT_SIZE     = BASE_CHUNK_SIZE * CHUNK_COMPRESS
        const val FULL_LATENT_DIM = LATENT_DIM * CHUNK_COMPRESS
        const val NUM_STEPS       = 10
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
        fun logIO(name: String, sess: OrtSession) {
            sess.inputInfo.forEach { (k, v) ->
                val t = if (v.info.toString().contains("INT64")) "INT64" else "FLOAT"
                val shape = v.info.toString().substringAfter("shape=[").substringBefore("]")
                Log.i(TAG, "  $name.$k = $t [$shape]")
                onProgress("  $k=$t[$shape]")
            }
        }
        onProgress("[text_encoder]");   logIO("te", textEnc!!)
        onProgress("[latent_denoiser]"); logIO("ld", denoiser!!)
        onProgress("[voice_decoder]");   logIO("vd", decoder!!)
        onProgress("✅ Supertonic 2 준비 (ko vocab=${vocab.size} steps=$NUM_STEPS)")
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
            try {
                File(context.filesDir, "tts_error.txt")
                    .writeText("${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTraceToString().lines().take(8).joinToString("\n"))
            } catch(_: Exception){}
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

    private fun OrtSession.Result.ortList(): List<OnnxValue> = this.map { it.value }.toList()

    private fun inferChunk(text: String, lang: String, style: FloatArray, speed: Float): FloatArray {
        val env = env!!
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val tagged     = "<$lang>$normalized</$lang>"
        val ids        = LongArray(tagged.length) { i -> vocab[tagged[i].toString()] ?: vocab[" "] ?: 0L }
        val seqLen     = ids.size
        val attnMaskI  = LongArray(seqLen) { 1L }

        val styleSeqLen = style.size / STYLE_DIM
        val styleShape  = longArrayOf(1L, styleSeqLen.toLong(), STYLE_DIM.toLong())
        val styleTensor = env.f32(style,     styleShape)
        val idsTensor   = env.i64(ids,       longArrayOf(1L, seqLen.toLong()))
        val attnTensor  = env.i64(attnMaskI, longArrayOf(1L, seqLen.toLong()))

        val teOut        = textEnc!!.run(mapOf(
            "input_ids"      to idsTensor,
            "attention_mask" to attnTensor,
            "style"          to styleTensor))
        val teList       = teOut.ortList()
        val encoderOut   = teList[0] as OnnxTensor
        val rawDurTensor = teList[1] as OnnxTensor

        val rawDur: FloatArray = when (val v = rawDurTensor.value) {
            is Array<*>   -> @Suppress("UNCHECKED_CAST") (v as Array<FloatArray>)[0]
            is FloatArray -> v
            else          -> throw Exception("rawDur type: ${v?.javaClass}")
        }

        val durations    = LongArray(rawDur.size) { i -> maxOf(0L, (rawDur[i] / speed * SAMPLE_RATE).toLong()) }
        val totalSamples = durations.sum()
        val latentLen    = maxOf(1, ((totalSamples + LATENT_SIZE - 1) / LATENT_SIZE).toInt())

        val rng = java.util.Random()
        var latents = FloatArray(FULL_LATENT_DIM * latentLen)
        var idx = 0
        while (idx < latents.size - 1) {
            val u1 = rng.nextDouble().coerceIn(1e-10, 1.0); val u2 = rng.nextDouble()
            val r  = sqrt(-2.0 * ln(u1)).toFloat()
            latents[idx]   = r * cos(2.0 * PI * u2).toFloat()
            latents[idx+1] = r * sin(2.0 * PI * u2).toFloat()
            idx += 2
        }
        val latentShape  = longArrayOf(1L, FULL_LATENT_DIM.toLong(), latentLen.toLong())

        // latent_mask: int64, 2D [1, latentLen]  ← 핵심 수정 (3D→2D)
        val latentMaskI  = LongArray(latentLen) { 1L }
        val latentMask2d = longArrayOf(1L, latentLen.toLong())   // 2D

        // denoiser attention_mask: int64
        val attnDenoiserI = LongArray(seqLen) { 1L }

        for (step in 0 until NUM_STEPS) {
            val noisyTensor    = env.f32(latents,          latentShape)
            val timestepTensor = env.i64(longArrayOf(step.toLong()),       longArrayOf(1L))
            val numStepsTensor = env.i64(longArrayOf(NUM_STEPS.toLong()),  longArrayOf(1L))
            val lmTensor       = env.i64(latentMaskI,      latentMask2d)   // 2D int64
            val attn2Tensor    = env.i64(attnDenoiserI,    longArrayOf(1L, seqLen.toLong()))
            val style2         = env.f32(style,             styleShape)
            val denoised       = denoiser!!.run(mapOf(
                "noisy_latents"       to noisyTensor,
                "encoder_outputs"     to encoderOut,
                "latent_mask"         to lmTensor,
                "attention_mask"      to attn2Tensor,
                "timestep"            to timestepTensor,
                "num_inference_steps" to numStepsTensor,
                "style"               to style2))
            val outTensor = denoised.ortList()[0] as OnnxTensor
            val buf = outTensor.floatBuffer
            latents = FloatArray(buf.remaining()).also { buf.get(it) }
            noisyTensor.close(); timestepTensor.close(); numStepsTensor.close()
            lmTensor.close(); attn2Tensor.close(); style2.close()
            denoised.close(); outTensor.close()
        }

        // masked latent: latent * latent_mask
        val masked = FloatArray(latents.size)
        for (d in 0 until FULL_LATENT_DIM)
            for (t in 0 until latentLen)
                masked[d * latentLen + t] = latents[d * latentLen + t] * latentMaskI[t].toFloat()

        val finalLatent = env.f32(masked, latentShape)
        val decOut      = decoder!!.run(mapOf("latent" to finalLatent))
        val wavTensor   = decOut.ortList()[0] as OnnxTensor
        val wavBuf      = wavTensor.floatBuffer
        val wavAll      = FloatArray(wavBuf.remaining()).also { wavBuf.get(it) }
        val wav         = wavAll.copyOfRange(0, minOf(totalSamples.toInt(), wavAll.size))

        idsTensor.close(); attnTensor.close(); styleTensor.close()
        encoderOut.close(); rawDurTensor.close(); teOut.close()
        finalLatent.close(); decOut.close()
        return wav
    }

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

    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v3")
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS).build()
        val base = "https://huggingface.co/onnx-community/Supertonic-TTS-2-ONNX/resolve/main"
        listOf(
            "onnx/text_encoder.onnx",    "onnx/text_encoder.onnx_data",
            "onnx/latent_denoiser.onnx", "onnx/latent_denoiser.onnx_data",
            "onnx/voice_decoder.onnx",   "onnx/voice_decoder.onnx_data",
            "tokenizer.json", "config.json",
            "voices/M1.bin","voices/M2.bin","voices/M3.bin","voices/M4.bin","voices/M5.bin",
            "voices/F1.bin","voices/F2.bin","voices/F3.bin","voices/F4.bin","voices/F5.bin"
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
