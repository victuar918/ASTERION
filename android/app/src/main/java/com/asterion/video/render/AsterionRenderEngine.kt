package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "AsterionRenderEngine"

/**
 * v3.2 — FFmpegKit 실제 오류 메시지 UI 노출
 * renderScene(): WAV 생성 → BGV 합성 → 씬 MP4
 * concatSubclips(): 씬들 concat → BGM 오버레이 → 최종 MP4
 */
class AsterionRenderEngine(
    private val context: Context,
    private val tts: SupertonicTtsEngine
) {
    suspend fun init(onProgress: (String)->Unit = {}) {
        // 디렉토리 생성 확인 — 실패 시 명시적 경고
        val dirsOk = runCatching { AppConfig.ensureDirs() }.isSuccess
        val outputExists = AppConfig.OUTPUT_DIR.exists() && AppConfig.OUTPUT_DIR.canWrite()
        if (!outputExists) {
            onProgress("⚠ OUTPUT_DIR 접근 불가: ${AppConfig.OUTPUT_DIR.absolutePath}")
            Log.w(TAG, "OUTPUT_DIR not writable: ${AppConfig.OUTPUT_DIR.absolutePath}")
        } else {
            onProgress("✅ 렌더링 엔진 준비 (FFmpegKit 활성) — ${AppConfig.OUTPUT_DIR.absolutePath}")
        }
    }

    // ── 씬 렌더링 ──────────────────────────────────────────────────────────────
    suspend fun renderScene(
        row: ScriptDataRow,
        meta: VideoMeta,
        voiceConfig: VoiceConfig = VoiceConfig.DEFAULT,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val id  = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        val wav = File(AppConfig.OUTPUT_DIR, "${id}.wav")
        val mp4 = File(AppConfig.OUTPUT_DIR, "${id}.mp4")
        val cfg = voiceConfig.forSpeaker(row.speaker)

        // 1. TTS WAV 생성
        if (row.script.isBlank()) {
            onProgress("[$id] 스크립트 없음 — 건너뜀")
            return@withContext null
        }
        val ttsOk = tts.synthesize(row.script, cfg.voiceFile, cfg.speed, wav)
        if (!ttsOk) {
            onProgress("[$id] ❌ TTS 실패")
            return@withContext null
        }
        val durSec = tts.estimateDurationFromFile(wav)
        onProgress("[$id] 🎤 ${wav.length()/1024}KB [${cfg.label}] ${durSec}s")

        // 2. BGV 파일 결정
        val bgvName = row.bgFile.substringBefore("|").trim().ifBlank { "VedicEnergyByPlanet_XRP_MovingChart.mp4" }
        val bgvFile = AppConfig.resolveBgv(bgvName)

        // 3. FFmpeg: WAV + BGV → 씬 MP4
        val cmd = if (bgvFile.exists()) {
            // BGV 있음: 루프 + 오디오 길이에 맞춤
            "-stream_loop -1 -i ${bgvFile.absolutePath} " +
            "-i ${wav.absolutePath} " +
            "-map 0:v:0 -map 1:a:0 " +
            "-c:v libx264 -preset veryfast -crf 23 " +
            "-c:a aac -b:a 128k " +
            "-shortest -movflags +faststart " +
            "-y ${mp4.absolutePath}"
        } else {
            // BGV 없음: lavfi 검은 배경
            "-f lavfi -i color=c=black:size=1920x1080:rate=30 " +
            "-i ${wav.absolutePath} " +
            "-map 0:v:0 -map 1:a:0 " +
            "-c:v libx264 -preset veryfast -crf 23 " +
            "-c:a aac -b:a 128k " +
            "-shortest -movflags +faststart " +
            "-y ${mp4.absolutePath}"
        }
        onProgress("[$id] FFmpeg 시작 (BGV=${bgvFile.exists()})...")

        val (ok, ffLog) = ffmpegRun(cmd)
        if (ok && mp4.exists() && mp4.length() > 0) {
            onProgress("[$id] ✅ ${mp4.length()/1024}KB")
            mp4
        } else {
            // 실제 FFmpeg 오류를 UI에 노출 — 마지막 의미있는 줄 추출
            val errLine = ffLog.lines()
                .filter { it.isNotBlank() }
                .lastOrNull { it.contains("Error", ignoreCase=true)
                    || it.contains("Invalid", ignoreCase=true)
                    || it.contains("No such", ignoreCase=true)
                    || it.contains("Permission", ignoreCase=true)
                    || it.contains("failed", ignoreCase=true)
                    || it.contains("Unknown", ignoreCase=true) }
                ?: ffLog.lines().filter { it.isNotBlank() }.lastOrNull()
                ?: "FFmpeg 로그 없음"
            onProgress("[$id] ❌ FFmpeg 실패:\n${errLine.trim().takeLast(150)}")
            null
        }
    }

    // ── 전체 concat + BGM ─────────────────────────────────────────────────────
    suspend fun concatSubclips(
        outputName: String,
        bgmFileName: String,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val scenes = AppConfig.OUTPUT_DIR.listFiles()
            ?.filter { it.name.startsWith("scene_") && it.extension == "mp4" }
            ?.sortedBy { it.name } ?: emptyList()

        if (scenes.isEmpty()) {
            onProgress("⚠ 씬 MP4 없음 — 먼저 영상 제작 실행")
            return@withContext null
        }

        onProgress("🔗 씬 ${scenes.size}개 concat 시작...")

        val listFile = File(AppConfig.OUTPUT_DIR, "concat_list.txt")
        listFile.writeText(scenes.joinToString("\n") { "file '${it.absolutePath}'" })

        val rawOut   = File(AppConfig.OUTPUT_DIR, "${outputName}_raw.mp4")
        val finalOut = File(AppConfig.OUTPUT_DIR, "${outputName}.mp4")

        // 1단계: concat
        val concatCmd = "-f concat -safe 0 " +
            "-i ${listFile.absolutePath} " +
            "-c:v copy -c:a copy " +
            "-y ${rawOut.absolutePath}"
        val (concatOk, concatLog) = ffmpegRun(concatCmd)
        if (!concatOk) {
            val errLine = concatLog.lines().filter{it.isNotBlank()}.lastOrNull() ?: "로그 없음"
            onProgress("❌ concat 실패: ${errLine.trim().takeLast(120)}")
            return@withContext null
        }
        onProgress("✅ concat 완료 (${rawOut.length()/1024/1024}MB)")

        // 2단계: BGM 오버레이
        val bgmFile = AppConfig.resolveBgm(bgmFileName)
        if (bgmFile != null && bgmFile.exists()) {
            onProgress("🎵 BGM 오버레이...")
            val bgmCmd = "-i ${rawOut.absolutePath} " +
                "-stream_loop -1 -i ${bgmFile.absolutePath} " +
                "-filter_complex " +
                "\"[1:a]volume=0.25,afade=t=in:d=3,afade=t=out:st=$(getDurationSec(rawOut)-5):d=5[bgm];" +
                "[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=3[outa]\" " +
                "-map 0:v -map \"[outa]\" " +
                "-c:v copy -c:a aac -b:a 192k " +
                "-movflags +faststart " +
                "-y ${finalOut.absolutePath}"
            val (bgmOk, _) = ffmpegRun(bgmCmd)
            if (bgmOk) {
                rawOut.delete()
                onProgress("✅ 최종 완료: ${finalOut.name} (${finalOut.length()/1024/1024}MB)")
                finalOut
            } else {
                onProgress("⚠ BGM 오버레이 실패 — raw 파일 반환")
                rawOut.renameTo(finalOut); finalOut
            }
        } else {
            rawOut.renameTo(finalOut)
            onProgress("✅ 완료 (BGM 없음): ${finalOut.name} (${finalOut.length()/1024/1024}MB)")
            finalOut
        }
    }

    // ── FFmpegKit 코루틴 래퍼 — 성공여부 + 오류로그 반환 ──────────────────────
    private suspend fun ffmpegRun(cmd: String): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "FFmpeg cmd: $cmd")
            val session = FFmpegKit.executeAsync(cmd,
                { session ->
                    val ok   = ReturnCode.isSuccess(session.returnCode)
                    val logs = session.logsAsString ?: ""
                    if (!ok) Log.e(TAG, "FFmpeg fail rc=${session.returnCode}\n$logs")
                    if (cont.isActive) cont.resume(Pair(ok, logs))
                },
                { log -> Log.v(TAG, log.message ?: "") },
                null
            )
            cont.invokeOnCancellation { session.cancel() }
        }

    private fun getDurationSec(f: File): Long {
        if (!f.exists()) return 0L
        val session = FFmpegKit.execute(
            "-i ${f.absolutePath} -v quiet -show_entries format=duration -of csv=p=0"
        )
        return session.logsAsString.trim().toDoubleOrNull()?.toLong() ?: 0L
    }

    fun release() { /* tts는 Activity가 관리 */ }
}
