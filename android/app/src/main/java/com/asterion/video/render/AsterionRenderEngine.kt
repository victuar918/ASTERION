package com.asterion.video.render

import android.content.Context
import android.media.MediaMetadataRetriever
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
 * v3.4
 * - BgTransition fade: bgFile 파이프 구분 두 번째 값으로 씬별 fade in/out 적용
 *   FFmpeg fade 필터: st=start_time(초), d=duration(초) — 프레임 단위 아님
 * - 기타 로직 완전 무변경 (Supertonic 3 cfg.sid 유지)
 */
class AsterionRenderEngine(
    private val context: Context,
    private val tts: SupertonicTtsEngine
) {
    suspend fun init(onProgress: (String)->Unit = {}) {
        val outputExists = AppConfig.OUTPUT_DIR.exists() && AppConfig.OUTPUT_DIR.canWrite()
        if (!outputExists) {
            onProgress("⚠ OUTPUT_DIR 접근 불가: ${AppConfig.OUTPUT_DIR.absolutePath}")
            Log.w(TAG, "OUTPUT_DIR not writable: ${AppConfig.OUTPUT_DIR.absolutePath}")
        } else {
            onProgress("✅ 렌더링 엔진 준비 — ${AppConfig.OUTPUT_DIR.absolutePath}")
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
        val wav = File(context.cacheDir, "${id}.wav")
        val mp4 = File(AppConfig.OUTPUT_DIR, "${id}.mp4")
        val cfg = voiceConfig.forSpeaker(row.speaker)

        if (row.script.isBlank()) {
            onProgress("[$id] 스크립트 없음 — 건너뜀")
            return@withContext null
        }

        val ttsOk = tts.synthesize(row.script, cfg.sid, cfg.speed, wav, cfg.numSteps)
        if (!ttsOk) {
            onProgress("[$id] ❌ TTS 실패")
            return@withContext null
        }
        val durSec = tts.estimateDurationFromFile(wav)
        onProgress("[$id] 🎤 ${wav.length()/1024}KB [${cfg.label} sid=${cfg.sid} steps=${cfg.numSteps}] ${durSec}s")

        // ── BgTransition 파싱 ─────────────────────────────────────────────────
        // bgFile 형식: "파일명|TRANSITION|초|효과코드"
        // 예) "intro01.mp4|FADE|0.5|...", "video.mp4|NONE|...", "video.mp4"
        val bgParts        = row.bgFile.split("|")
        val transitionType = bgParts.getOrNull(1)?.trim()?.uppercase() ?: "FADE"
        val fadeDurRaw     = bgParts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 0.5f
        val fadeDurSec     = fadeDurRaw.coerceIn(0.3f, 2.0f)

        // 씬 길이 계산 (fade in/out 겹침 방지)
        val sceneDurSec    = durSec.toString().toDoubleOrNull() ?: 5.0
        // fade in + fade out이 씬 길이를 초과하지 않도록 클램프
        val effectiveFade  = if (sceneDurSec < fadeDurSec * 2)
            (sceneDurSec / 2).toFloat() else fadeDurSec
        val fadeOutStart   = maxOf(0.0, sceneDurSec - effectiveFade)

        // vf 필터 문자열 — NONE이면 빈 문자열, 공백 없음(FFmpegKit 쿼팅 불필요)
        val vfFilter = when (transitionType) {
            "NONE" -> ""
            else   -> "fade=t=in:st=0:d=${String.format("%.3f", effectiveFade)}" +
                      ",fade=t=out:st=${String.format("%.3f", fadeOutStart)}" +
                      ":d=${String.format("%.3f", effectiveFade)}"
        }
        val vfOption = if (vfFilter.isNotBlank()) "-vf $vfFilter " else ""

        // ── BGV 파일 경로 결정 ────────────────────────────────────────────────
        val bgvName = bgParts[0].trim().ifBlank { "VedicEnergyByPlanet_XRP_MovingChart.mp4" }
        val bgvFile = AppConfig.resolveBgv(bgvName)

        // ── FFmpeg 명령 구성 ──────────────────────────────────────────────────
        val cmd = if (bgvFile.exists()) {
            "-stream_loop -1 -i ${bgvFile.absolutePath} " +
            "-i ${wav.absolutePath} " +
            "-map 0:v:0 -map 1:a:0 " +
            "-c:v libx264 -preset veryfast -crf 23 " +
            vfOption +
            "-c:a aac -b:a 128k " +
            "-shortest -movflags +faststart " +
            "-y ${mp4.absolutePath}"
        } else {
            "-f lavfi -i color=c=black:size=1920x1080:rate=30 " +
            "-i ${wav.absolutePath} " +
            "-map 0:v:0 -map 1:a:0 " +
            "-c:v libx264 -preset veryfast -crf 23 " +
            vfOption +
            "-c:a aac -b:a 128k " +
            "-shortest -movflags +faststart " +
            "-y ${mp4.absolutePath}"
        }
        onProgress("[$id] FFmpeg 시작 (BGV=${bgvFile.exists()}, fade=$transitionType ${String.format("%.1f",effectiveFade)}s)...")

        val (ok, ffLog) = ffmpegRun(cmd)
        return@withContext if (ok && mp4.exists() && mp4.length() > 0) {
            wav.delete()
            onProgress("[$id] ✅ ${mp4.length()/1024}KB")
            mp4
        } else {
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

    // ── 전체 concat + BGM (변경 없음) ────────────────────────────────────────
    suspend fun concatSubclips(
        outputName: String,
        bgmFileName: String,
        onProgress: (String)->Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val scenes = AppConfig.OUTPUT_DIR.listFiles()
            ?.filter { it.name.startsWith("scene_") && it.extension == "mp4" }
            ?.sortedBy { it.name } ?: emptyList()

        if (scenes.isEmpty()) {
            onProgress("⚠ 씬 MP4 없음")
            return@withContext null
        }
        onProgress("🔗 씬 ${scenes.size}개 concat 시작...")

        val listFile = File(AppConfig.OUTPUT_DIR, "concat_list.txt")
        listFile.writeText(scenes.joinToString("\n") { "file '${it.absolutePath}'" })

        val rawOut   = File(AppConfig.OUTPUT_DIR, "${outputName}_raw.mp4")
        val finalOut = File(AppConfig.OUTPUT_DIR, "${outputName}.mp4")

        val concatCmd = "-f concat -safe 0 " +
            "-i ${listFile.absolutePath} " +
            "-c:v copy -c:a copy " +
            "-y ${rawOut.absolutePath}"
        val (concatOk, concatLog) = ffmpegRun(concatCmd)
        if (!concatOk) {
            val errLine = concatLog.lines().filter { it.isNotBlank() }.lastOrNull() ?: "로그 없음"
            onProgress("❌ concat 실패: ${errLine.trim().takeLast(120)}")
            return@withContext null
        }
        onProgress("✅ concat 완료 (${rawOut.length()/1024/1024}MB)")

        val bgmFile = AppConfig.resolveBgm(bgmFileName)
        if (bgmFile != null && bgmFile.exists()) {
            onProgress("🎵 BGM 오버레이 (${bgmFile.name})...")
            val rawDurSec    = getDurationSec(rawOut)
            val fadeOutStart = if (rawDurSec > 8L) rawDurSec - 5L else maxOf(0L, rawDurSec - 1L)
            val bgmCmd = "-i ${rawOut.absolutePath} " +
                "-stream_loop -1 -i ${bgmFile.absolutePath} " +
                "-filter_complex " +
                "\"[1:a]volume=0.25,afade=t=in:d=3,afade=t=out:st=${fadeOutStart}:d=5[bgm];" +
                "[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=3[outa]\" " +
                "-map 0:v -map \"[outa]\" " +
                "-c:v copy -c:a aac -b:a 192k " +
                "-movflags +faststart " +
                "-y ${finalOut.absolutePath}"
            val (bgmOk, bgmLog) = ffmpegRun(bgmCmd)
            return@withContext if (bgmOk && finalOut.exists() && finalOut.length() > 0) {
                rawOut.delete()
                listFile.delete()
                onProgress("✅ 최종 완료: ${finalOut.name} (${finalOut.length()/1024/1024}MB)")
                finalOut
            } else {
                val errLine = bgmLog.lines().filter { it.isNotBlank() }.lastOrNull() ?: ""
                onProgress("⚠ BGM 오버레이 실패 — raw 반환\n${errLine.takeLast(100)}")
                listFile.delete()
                rawOut.renameTo(finalOut)
                finalOut
            }
        } else {
            listFile.delete()
            rawOut.renameTo(finalOut)
            onProgress("✅ 완료 (BGM 없음): ${finalOut.name} (${finalOut.length()/1024/1024}MB)")
            finalOut
        }
    }

    private fun getDurationSec(f: File): Long {
        if (!f.exists()) return 0L
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            ms / 1000L
        } catch (e: Exception) {
            Log.w(TAG, "getDurationSec 실패: ${e.message}")
            0L
        }
    }

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

    fun release() { /* tts는 Activity가 관리 */ }
}
