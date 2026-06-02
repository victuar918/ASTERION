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
 * v3.3
 * - WAV를 context.cacheDir에 저장 (외부저장소 권한 불필요)
 * - 씬 MP4 성공 시 WAV 즉시 삭제 → 동시 WAV 최대 1개
 * - getDurationSec: FFmpegKit → MediaMetadataRetriever (ffprobe 플래그 오작동 수정)
 * - concatSubclips BGM afade: $(함수호출) 리터럴 버그 → ${변수} 올바른 보간으로 수정
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
        // WAV: 앱 내부 cacheDir — 외부저장소 권한 불필요, 성공 시 즉시 삭제
        val wav = File(context.cacheDir, "${id}.wav")
        val mp4 = File(AppConfig.OUTPUT_DIR, "${id}.mp4")
        val cfg = voiceConfig.forSpeaker(row.speaker)

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

        val bgvName = row.bgFile.substringBefore("|").trim().ifBlank { "VedicEnergyByPlanet_XRP_MovingChart.mp4" }
        val bgvFile = AppConfig.resolveBgv(bgvName)

        val cmd = if (bgvFile.exists()) {
            "-stream_loop -1 -i ${bgvFile.absolutePath} " +
            "-i ${wav.absolutePath} " +
            "-map 0:v:0 -map 1:a:0 " +
            "-c:v libx264 -preset veryfast -crf 23 " +
            "-c:a aac -b:a 128k " +
            "-shortest -movflags +faststart " +
            "-y ${mp4.absolutePath}"
        } else {
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
        return@withContext if (ok && mp4.exists() && mp4.length() > 0) {
            wav.delete()  // 성공 즉시 삭제 — cacheDir 공간 확보
            onProgress("[$id] ✅ ${mp4.length()/1024}KB")
            mp4
        } else {
            // WAV는 실패 시 보존 (디버그용, 다음 실행 시 덮어씀)
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
            onProgress("⚠ 씬 MP4 없음")
            return@withContext null
        }
        onProgress("🔗 씬 ${scenes.size}개 concat 시작...")

        val listFile = File(AppConfig.OUTPUT_DIR, "concat_list.txt")
        listFile.writeText(scenes.joinToString("\n") { "file '${it.absolutePath}'" })

        val rawOut   = File(AppConfig.OUTPUT_DIR, "${outputName}_raw.mp4")
        val finalOut = File(AppConfig.OUTPUT_DIR, "${outputName}.mp4")

        // 1단계: concat (스트림 복사 — 재인코딩 없음)
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

        // 2단계: BGM 오버레이
        val bgmFile = AppConfig.resolveBgm(bgmFileName)
        if (bgmFile != null && bgmFile.exists()) {
            onProgress("🎵 BGM 오버레이 (${bgmFile.name})...")

            // getDurationSec으로 페이드아웃 시작점 계산
            // 영상이 8초 이하면 페이드아웃 생략 (st=0 이 되면 처음부터 페이드 → 어색)
            val rawDurSec   = getDurationSec(rawOut)
            val fadeOutStart = if (rawDurSec > 8L) rawDurSec - 5L else maxOf(0L, rawDurSec - 1L)

            // filter_complex: FFmpegKit은 쉘 없이 직접 실행 → "" 토큰 처리 지원
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

    // ── 영상 길이 조회 — MediaMetadataRetriever 사용 ──────────────────────────
    // FFmpegKit.execute()는 ffmpeg 실행 → ffprobe 플래그(-show_entries 등) 미지원
    // MediaMetadataRetriever는 Android 네이티브, 추가 의존성 없음
    private fun getDurationSec(f: File): Long {
        if (!f.exists()) return 0L
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            ms / 1000L  // ms → 초
        } catch (e: Exception) {
            Log.w(TAG, "getDurationSec 실패: ${e.message}")
            0L
        }
    }

    // ── FFmpegKit 코루틴 래퍼 ─────────────────────────────────────────────────
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
