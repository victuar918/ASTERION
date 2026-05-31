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
private const val MAX_CHUNK_KO = 120

// ── Config ──────────────────────────────────────────────────────────────────
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

// ── Engine ───────────────────────────────────────────────────────────────────
class SupertonicTtsEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE          = 44100
        const val CHUNK_COMPRESS       = 6
        const val BASE_CHUNK_SIZE      = 512
        const val LATENT_DIM           = 24
        const val STYLE_DIM            = 128
        const val LATENT_SIZE          = BASE_CHUNK_SIZE * CHUNK_COMPRESS   // 3072
        const val FULL_LATENT_DIM      = LATENT_DIM * CHUNK_COMPRESS        // 144
        const val NUM_INFERENCE_STEPS  = 10
    }

    private var env: OrtEnvironment? = null
    private var textEnc:  OrtSession? = null
    private var denoiser: OrtSession? = null
    private var decoder:  OrtSession? = null
    private var vocab: Map<String, Long> = emptyMap()   // tokenizer.json

    // ── 초기화 ──────────────────────────────────────────────────────────────
    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress)
        onProgress("🔧 ONNX Runtime 초기화...")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val p = dir.absolutePath
        textEnc  = env!!.createSession("$p/onnx/text_encoder.onnx",  opts)
        denoiser = env!!.createSession("$p/onnx/latent_denoiser.onnx", opts)
        decoder  = env!!.createSession("$p/onnx/voice_decoder.onnx",  opts)

        // 입출력 이름 로그 (처음 한 번만)
        logIO("text_encoder",   textEnc!!)
        logIO("latent_denoiser", denoiser!!)
        logIO("voice_decoder",   decoder!!)

        vocab = loadVocab(File(dir, "tokenizer.json"))
        onProgress("✅ Supertonic 2 준비 (ko지원, vocab=${vocab.size}, steps=$NUM_INFERENCE_STEPS)")
    }

    // ── 합성 ────────────────────────────────────────────────────────────────
    fun synthesize(text: String, voiceFile: String, speed: Float, outputFile: File): Boolean {
        if (textEnc == null) return false
        if (text.isBlank()) return false
        return try {
            val modelDir = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
            val style    = loadVoiceStyle(File(modelDir, "voices/${voiceFile}.bin"))
            val chunks   = chunkText(text, MAX_CHUNK_KO)
            val wavParts = mutableListOf<FloatArray>()
            val silSamples = (0.3f * SAMPLE_RATE).toInt()

            for ((idx, chunk) in chunks.withIndex()) {
                val wav = inferChunk(chunk, "ko", style, speed)
                if (idx > 0) wavParts.add(FloatArray(silSamples))
                wavParts.add(wav)
            }
            val flat = FloatArray(wavParts.sumOf { it.size })
            var off = 0; for (p in wavParts) { p.copyInto(flat, off); off += p.size }
            outputFile.writeBytes(float32ToWav(flat, SAMPLE_RATE))
            Log.i(TAG, "완료: ${outputFile.name} (${outputFile.length()/1024}KB)")
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
            val b = f.readBytes()
            val sr = ri(b,24); val ba = rs(b,32).toInt(); val ds = ri(b,40)
            if (sr>0 && ba>0) ds.toFloat()/(sr*ba) else 3.0f
        } catch(e: Exception) { 3.0f }
    }

    fun release() {
        textEnc?.close(); denoiser?.close(); decoder?.close(); env?.close()
        textEnc = null; denoiser = null; decoder = null; env = null
    }

    // ── 단일 청크 추론 ───────────────────────────────────────────────────────
    private fun inferChunk(text: String, lang: String, style: FloatArray, speed: Float): FloatArray {
        val env = env!!

        // 1. 토큰화 (NFKD + <ko>tag</ko> + vocab lookup)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val tagged     = "<$lang>$normalized</$lang>"
        val chars      = tagged.map { it.toString() }
        val inputIds   = LongArray(chars.size) { i -> vocab[chars[i]] ?: vocab[" "] ?: 0L }
        val attnMask   = FloatArray(chars.size) { 1.0f }
        val seqLen     = chars.size

        // style shape: [1, STYLE_DIM, 101]  총 12928 floats
        val styleShape = longArrayOf(1L, STYLE_DIM.toLong(), (style.size / STYLE_DIM).toLong())
        val styleTensor  = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), styleShape)

        val idsTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds),
            longArrayOf(1L, seqLen.toLong()))
        val maskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(attnMask),
            longArrayOf(1L, seqLen.toLong()))

        // 2. Text Encoder → last_hidden_state, raw_durations
        val teOut = textEnc!!.run(mapOf(
            textEnc!!.inputNames.toList()[0] to idsTensor,
            textEnc!!.inputNames.toList()[1] to maskTensor,
            textEnc!!.inputNames.toList()[2] to styleTensor
        ))
        val hiddenState = teOut[0] as OnnxTensor          // [1, seq, dim]
        val rawDurTensor = teOut[1] as OnnxTensor         // [1, seq] float32

        @Suppress("UNCHECKED_CAST")
        val rawDurations = (rawDurTensor.value as Array<FloatArray>)[0]

        // 3. Duration → latent 크기 계산
        // durations: 샘플 수 (int64)
        val durations = LongArray(rawDurations.size) { i ->
            max(0L, (rawDurations[i] / speed * SAMPLE_RATE).toLong())
        }
        val totalSamples = durations.sum()
        val latentLength = ((totalSamples + LATENT_SIZE - 1) / LATENT_SIZE).toInt()

        // 4. Noisy latents 초기화 (Box-Muller)
        val rng = java.util.Random()
        val latentFlat = FloatArray(1 * FULL_LATENT_DIM * latentLength)
        for (i in latentFlat.indices) {
            val u1 = maxOf(1e-10, rng.nextDouble())
            val u2 = rng.nextDouble()
            latentFlat[i] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
        }

        // latent_mask: int64 [1, latentLength] 전부 1
        val latentMaskFlat = LongArray(latentLength) { 1L }
        val latentMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(latentMaskFlat),
            longArrayOf(1L, latentLength.toLong()))

        // 5. Denoising loop
        val denoiserInputNames = denoiser!!.inputNames.toList()
        Log.d(TAG, "denoiser inputs: $denoiserInputNames")

        var latents = latentFlat.copyOf()
        for (step in 0 until NUM_INFERENCE_STEPS) {
            val timeStep = floatArrayOf(step.toFloat() / NUM_INFERENCE_STEPS)
            val latentsTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latents),
                longArrayOf(1L, FULL_LATENT_DIM.toLong(), latentLength.toLong()))
            val timeTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(timeStep), longArrayOf(1L))

            // 입력 이름 순서대로 맞추기
            val denoiserInputs = buildDenoiserInputs(
                denoiserInputNames, latentsTensor, timeTensor,
                hiddenState, maskTensor, latentMaskTensor, styleTensor)

            val denoiserOut = denoiser!!.run(denoiserInputs)
            val outTensor = denoiserOut[0] as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val outBatch = outTensor.value as Array<Array<FloatArray>>
            // flatten [1][FULL_LATENT_DIM][latentLength]
            val newLatents = FloatArray(FULL_LATENT_DIM * latentLength)
            var idx = 0
            for (d in 0 until FULL_LATENT_DIM)
                for (t in 0 until latentLength)
                    newLatents[idx++] = outBatch[0][d][t]
            latents = newLatents

            latentsTensor.close(); timeTensor.close(); denoiserOut.close()
        }

        // 6. Voice Decoder → waveform
        val finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latents),
            longArrayOf(1L, FULL_LATENT_DIM.toLong(), latentLength.toLong()))
        val decOut = decoder!!.run(mapOf(
            decoder!!.inputNames.toList()[0] to finalLatentTensor,
            decoder!!.inputNames.toList()[1] to latentMaskTensor
        ))
        val wavTensor = decOut[0] as OnnxTensor
        @Suppress("UNCHECKED_CAST")
        val wavBatch = wavTensor.value as Array<FloatArray>
        val wav = wavBatch[0].copyOfRange(0, minOf(totalSamples.toInt(), wavBatch[0].size))

        // cleanup
        idsTensor.close(); maskTensor.close(); styleTensor.close()
        hiddenState.close(); rawDurTensor.close(); teOut.close()
        latentMaskTensor.close(); finalLatentTensor.close(); decOut.close()

        return wav
    }

    // denoiser 입력 이름 매핑 (이름 기반으로 자동 매핑)
    private fun buildDenoiserInputs(
        names: List<String>,
        latentsTensor: OnnxTensor,
        timeTensor: OnnxTensor,
        hiddenState: OnnxTensor,
        textMask: OnnxTensor,
        latentMask: OnnxTensor,
        style: OnnxTensor
    ): Map<String, OnnxTensor> {
        val map = mutableMapOf<String, OnnxTensor>()
        for (name in names) {
            map[name] = when {
                name.contains("latent") && !name.contains("mask") -> latentsTensor
                name.contains("time") || name.contains("step")    -> timeTensor
                name.contains("hidden") || name.contains("text_emb") -> hiddenState
                name.contains("text_mask") || name.contains("attention") -> textMask
                name.contains("latent_mask")                       -> latentMask
                name.contains("style")                             -> style
                else -> { Log.w(TAG, "Unknown denoiser input: $name"); latentsTensor }
            }
        }
        return map
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────
    private fun loadVocab(f: File): Map<String, Long> {
        val j    = JSONObject(f.readText())
        val model = j.getJSONObject("model")
        val vocabJ = model.getJSONObject("vocab")
        val map  = mutableMapOf<String, Long>()
        for (key in vocabJ.keys()) {
            map[key] = vocabJ.getLong(key)
        }
        Log.i(TAG, "vocab size=${map.size}")
        return map
    }

    private fun loadVoiceStyle(f: File): FloatArray {
        val bytes = f.readBytes()
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
        // shape: [1, STYLE_DIM, size/STYLE_DIM] 은 styleTensor 생성 시 처리
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        val trimmed = text.trim().replace("\n", " ")
        if (trimmed.length <= maxLen) return listOf(trimmed)
        val chunks = mutableListOf<String>(); val buf = StringBuilder()
        for (s in trimmed.split(Regex("(?<=[。.!?！？])"))) {
            if (buf.length + s.length > maxLen && buf.isNotEmpty()) { chunks.add(buf.toString().trim()); buf.clear() }
            buf.append(s)
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks.filter { it.isNotBlank() }
    }

    private fun logIO(name: String, sess: OrtSession) {
        Log.i(TAG, "$name IN=${sess.inputNames.toList()} OUT=${sess.outputNames.toList()}")
    }

    // ── 모델 다운로드 (.ready_v3) ─────────────────────────────────────────
    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest   = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready_v3")   // v3 = onnx-community/Supertonic-TTS-2-ONNX
        if (marker.exists()) { onProgress("Supertonic 2 캐시 재사용 (ko모델)"); return@withContext dest }
        dest.deleteRecursively(); dest.mkdirs()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)  // 대용량: 600초
            .build()
        val base = "https://huggingface.co/onnx-community/Supertonic-TTS-2-ONNX/resolve/main"

        // onnx 모델 (외부데이터 포함)
        val modelFiles = listOf(
            "onnx/text_encoder.onnx",      "onnx/text_encoder.onnx_data",
            "onnx/latent_denoiser.onnx",   "onnx/latent_denoiser.onnx_data",
            "onnx/voice_decoder.onnx",     "onnx/voice_decoder.onnx_data"
        )
        // 설정 파일
        val configFiles = listOf("tokenizer.json", "config.json")
        // 화자 파일
        val voiceFiles = listOf(
            "voices/M1.bin","voices/M2.bin","voices/M3.bin","voices/M4.bin","voices/M5.bin",
            "voices/F1.bin","voices/F2.bin","voices/F3.bin","voices/F4.bin","voices/F5.bin"
        )

        for (path in modelFiles + configFiles + voiceFiles) {
            onProgress("⬇ $path")
            val out = File(dest, path).also { it.parentFile?.mkdirs() }
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("${resp.code}: $path")
                resp.body!!.byteStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        marker.createNewFile(); dest
    }

    // ── WAV ──────────────────────────────────────────────────────────────────
    private fun float32ToWav(s: FloatArray, sr: Int): ByteArray {
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
