package com.asterion.video.tts

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private const val TAG = "SupertonicTtsEngine"

data class SpeakerConfig(val sid: Int, val speed: Float, val label: String)
data class VoiceConfig(
    val speaker1: SpeakerConfig,
    val speaker2: SpeakerConfig,
    val speaker3: SpeakerConfig
) {
    fun forSpeaker(num: Int) = when(num) { 2->speaker2; 3->speaker3; else->speaker1 }
    companion object {
        val DEFAULT = VoiceConfig(
            SpeakerConfig(0, 1.0f,  "아스터"),
            SpeakerConfig(1, 0.95f, "리언"),
            SpeakerConfig(2, 1.05f, "나레이터")
        )
    }
}

class SupertonicTtsEngine(private val context: Context) {
    private var tts: OfflineTts? = null

    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model   = "$dir/model.onnx",
                    lexicon = "$dir/lexicon.txt",
                    tokens  = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data"
                ),
                numThreads = 4, debug = false, provider = "cpu"
            ),
            ruleFsts = "", maxNumSentences = 1
        )
        tts = OfflineTts(config)
        onProgress("✅ TTS 준비 (${tts!!.sampleRate()}Hz)")
    }

    fun synthesize(text: String, sid: Int, speed: Float, outputFile: File): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        return try {
            val audio = engine.generateWithCallbackAndNumberOfThreads(
                text=text, sid=sid, speed=speed, callback=null, numThreads=4)
            outputFile.writeBytes(float32ToWav(audio.samples, audio.sampleRate))
            true
        } catch(e: Exception) { Log.e(TAG, "TTS: $e"); false }
    }

    fun estimateDurationFromWav(f: File): Float {
        if (!f.exists() || f.length() < 44) return 3.0f
        return try {
            val b = f.readBytes()
            val sr = ri(b,24); val ba = rs(b,32).toInt(); val ds = ri(b,40)
            if (sr>0 && ba>0) ds.toFloat()/(sr*ba) else 3.0f
        } catch(e: Exception) { 3.0f }
    }

    fun release() { tts?.release(); tts=null }

    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        if (File(dest, ".ready").exists()) return@withContext dest
        dest.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
        AppConfig.TTS_MODEL_FILES.forEach { name ->
            onProgress("⬇ $name")
            downloadFile(client, "${AppConfig.TTS_HF_BASE}/$name", File(dest, name))
        }
        onProgress("⬇ espeak-ng-data.zip")
        val zip = File(dest, "espeak-ng-data.zip")
        downloadFile(client, AppConfig.TTS_ESPEAK_ZIP_URL, zip)
        onProgress("📦 압축 해제...")
        unzip(zip, dest); zip.delete()
        File(dest, ".ready").createNewFile()
        dest
    }

    private fun downloadFile(client: OkHttpClient, url: String, dest: File) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("${resp.code}: $url")
            resp.body!!.byteStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    private fun unzip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) } }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
    }

    private fun float32ToWav(s: FloatArray, sr: Int): ByteArray {
        val ds = s.size*2
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
