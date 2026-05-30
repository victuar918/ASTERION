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
        onProgress("✅ TTS 준비 — Supertonic 3 (${tts!!.sampleRate()}Hz)")
    }

    // ★ MP3로 저장 (FFmpeg Kit 활용)
    fun synthesize(text: String, sid: Int, speed: Float, outputFile: File): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        return try {
            val audio = engine.generateWithCallbackAndNumberOfThreads(
                text=text, sid=sid, speed=speed, callback=null, numThreads=4)

            // 먼저 WAV로 저장
            val wavFile = File(outputFile.parent, outputFile.nameWithoutExtension + "_tmp.wav")
            wavFile.writeBytes(float32ToWav(audio.samples, audio.sampleRate))

            // WAV → MP3 변환 (FFmpeg Kit)
            val mp3File = if (outputFile.extension == "mp3") outputFile
                          else File(outputFile.parent, outputFile.nameWithoutExtension + ".mp3")
            val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -i ${wavFile.absolutePath} -codec:a libmp3lame -b:a 128k ${mp3File.absolutePath}"
            )
            wavFile.delete()

            if (rc.returnCode.isValueSuccess) {
                // outputFile이 .mp3가 아니면 복사
                if (outputFile != mp3File) { mp3File.copyTo(outputFile, overwrite = true); mp3File.delete() }
                Log.i(TAG, "MP3 완료: ${outputFile.name} (${outputFile.length()/1024}KB)")
                true
            } else {
                Log.e(TAG, "MP3 변환 실패 — WAV fallback")
                // Fallback: WAV 사용
                float32ToWav(audio.samples, audio.sampleRate).also { outputFile.writeBytes(it) }
                true
            }
        } catch(e: Exception) { Log.e(TAG, "TTS: $e"); false }
    }

    fun estimateDurationFromFile(f: File): Float {
        if (!f.exists() || f.length() < 44) return 3.0f
        // MP3는 혁데마 데이터 파싱 생략 — WAV 헤더만 파싱
        return if (f.extension == "wav") estimateDurationFromWav(f) else estimateMp3Duration(f)
    }

    private fun estimateDurationFromWav(f: File): Float {
        return try {
            val b = f.readBytes()
            val sr = ri(b,24); val ba = rs(b,32).toInt(); val ds = ri(b,40)
            if (sr>0 && ba>0) ds.toFloat()/(sr*ba) else 3.0f
        } catch(e: Exception) { 3.0f }
    }

    private fun estimateMp3Duration(f: File): Float {
        // 가단한 추정: 파일안크기 ÷ (128kbps ÷ 8)
        return f.length().toFloat() / (128 * 1024 / 8)
    }

    fun release() { tts?.release(); tts = null }

    // 방식 3: HuggingFace 자동 다운로드
    private suspend fun prepareModelDir(onProgress: (String)->Unit): File = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, AppConfig.TTS_MODEL_SUBDIR)
        val marker = File(dest, ".ready")
        if (marker.exists() && File(dest, "model.onnx").exists()) {
            onProgress("TTS 모델 캐시 재사용 (Supertonic 3)")
            return@withContext dest
        }
        // 기존 모델 삭제 (v2 → v3 업그레이드)
        dest.deleteRecursively()
        dest.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
        AppConfig.TTS_MODEL_FILES.forEach { name ->
            onProgress("⬇ Supertonic-3: $name")
            downloadFile(client, "${AppConfig.TTS_HF_BASE}/$name", File(dest, name))
        }
        onProgress("⬇ espeak-ng-data.zip")
        val zip = File(dest, "espeak-ng-data.zip")
        downloadFile(client, AppConfig.TTS_ESPEAK_ZIP_URL, zip)
        onProgress("📦 압축 해제...")
        unzip(zip, dest); zip.delete()
        marker.createNewFile()
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
        val ds = s.size * 2
        return java.io.ByteArrayOutputStream(44 + ds).apply {
            fun wi(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun ws(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            write("RIFF".toByteArray()); wi(36 + ds)
            write("WAVEfmt ".toByteArray()); wi(16); ws(1); ws(1)
            wi(sr); wi(sr * 2); ws(2); ws(16)
            write("data".toByteArray()); wi(ds)
            for (x in s) { val v = (x.coerceIn(-1f, 1f) * 32767).toInt().toShort(); write(byteArrayOf(v.toByte(), (v.toInt() shr 8).toByte())) }
        }.toByteArray()
    }

    private fun ri(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun rs(b: ByteArray, o: Int): Short = ((b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)).toShort()
}
