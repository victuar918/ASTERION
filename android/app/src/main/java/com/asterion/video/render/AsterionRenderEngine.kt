package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// =================================================================
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.19
//
// [변경로그 v3.19] — 아키텍처 전면 재설계
//   ▶ 핵심 문제: 씬별 MP4 중간파일 방식의 타이밍 누적 오차
//     buildFfmpegCmd의 -stream_loop + -shortest 조합이
//     v3.17/v3.18 두 번 모두 NOT_FOUND로 미적용됨
//   ■ 새 아키텍처 (2단계):
//     Phase 1: prepareScene — TTS합성 + BGV pre-cut만 수행 (MP4 인코딩 없음)
//     Phase 2: assembleBody — 모든 씬을 단일 FFmpeg 호출로 조립
//       - BGV 스트림 = 씬별 pre-cut BGV concat
//       - TTS 스트림 = 씬별 WAV concat
//       - 카드/텍스트 = 절대시간 enable= 조건부 filter_complex_script
//       → 씬별 MP4 없음 → 타이밍 불일치 근본 해결
//   ■ BGM 볼륨 0.05 → 0.02 (TTS 방해 감소)
// [변경로그 v3.18] 이전 버전
// =================================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"

private val MOTION_PATTERNS = setOf(
    AnimationPattern.A, AnimationPattern.B, AnimationPattern.C,
    AnimationPattern.E, AnimationPattern.G
)

/** Locale.US 고정 Float 포맷 헬퍼 */
private fun Float.fmtUS(d: Int = 3): String = String.format(Locale.US, "%.${d}f", this)

// ─────────────────────────────────────────────────────────────────
// ScenePrep: Phase 1에서 생성되는 씬별 준비 데이터
// ─────────────────────────────────────────────────────────────────
data class ScenePrep(
    val row          : ScriptDataRow,
    val wavFile      : File?,          // TTS WAV (null = 생성 실패)
    val bgvCutFile   : File?,          // pre-cut BGV (null = 생성 실패 → bgFile 사용)
    val bgFile       : File,           // 원본 BGV (fallback)
    val wavDuration  : Float,          // 실제 재생 길이
    val startSecs    : Float,          // body 내 누적 시작 시각
    val cardStyle    : CardStyle,
    val gradient     : GradientPreset,
    val keyframes    : CardKeyframes,
    val textStartSecs: Float
)

class AsterionRenderEngine(
    private val context: Context,
    private val ttsEngine: SupertonicTtsEngine
) {
    val ttsEnginePublic: SupertonicTtsEngine get() = ttsEngine

    private val subclipFiles      = mutableListOf<File>()
    private var totalDurationSecs = 0f

    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    private val fontPath: String by lazy {
        listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKkr-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/DroidSans.ttf"
        ).firstOrNull {
            try { File(it).canRead() } catch (_: Exception) { false }
        } ?: ""
    }

    // ── 인트로 렌더링 ────────────────────────────────────────────────

    suspend fun renderIntro(
        videoMeta: VideoMeta,
        disclaimerWav: File? = null,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) {
            onProgress("인트로 BGV 미설정 — 인트로 생략")
            return@withContext null
        }
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()

        val bgv1   = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2 = videoMeta.introBgv2.isNotBlank()
        val bgv2   = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else null

        val bgv1Dur  = getMediaDurationSecs(bgv1).takeIf { it > 1f } ?: 8f
        val bgv2Dur  = if (bgv2 != null && bgv2.exists()) getMediaDurationSecs(bgv2).takeIf { it > 1f } ?: 15f else 0f
        val xfadeDur = if (hasBgv2) 1.0f else 0f
        val totalDur = if (hasBgv2) bgv1Dur + bgv2Dur - xfadeDur else bgv1Dur
        onProgress("인트로 측정: bgv1=${bgv1Dur.fmtUS(1)}s bgv2=${bgv2Dur.fmtUS(1)}s total=${totalDur.fmtUS(1)}s")

        val p1Start = 2.0f
        val p1End   = (bgv1Dur - xfadeDur - 0.3f).coerceAtLeast(p1Start + 1f)
        val p2Base  = bgv1Dur - xfadeDur
        val t1 = p2Base + 2f; val t2 = p2Base + 4f
        val t3 = p2Base + 6f; val t4 = p2Base + 8f
        val tAllEnd = (t4 + 6f).coerceAtMost(totalDur - 0.5f)

        val isXrp     = videoMeta.introType.trim().uppercase() == "XRP"
        val titleWord = if (isXrp) "XRP 전망" else "크립토 갤러리"
        val rotWord   = if (isXrp) "예측하는" else "둘러보는"

        fun esc(s: String) = s.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
        fun alpha(ts: Float, te: Float): String {
            val fi = (ts + 0.5f).fmtUS(2); val fo = (te - 0.5f).coerceAtLeast(ts + 0.6f).fmtUS(2)
            return "if(lt(t,$fi),(t-${ts.fmtUS(2)})/0.5,if(gt(t,$fo),(${te.fmtUS(2)}-t)/0.5,1))"
        }
        fun en(ts: Float, te: Float) = "between(t,${ts.fmtUS(2)},${te.fmtUS(2)})"

        val fp   = mutableListOf<String>()
        val fOpt = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""

        if (hasBgv2 && bgv2 != null) {
            val xOff = (bgv1Dur - xfadeDur).coerceAtLeast(0.5f)
            fp += "[0:v]setpts=PTS-STARTPTS[v0]"
            fp += "[1:v]setpts=PTS-STARTPTS[v1]"
            fp += "[v0][v1]xfade=transition=fade:duration=${xfadeDur.fmtUS()}:offset=${xOff.fmtUS()}[bgv]"
        } else { fp += "[0:v]setpts=PTS-STARTPTS[bgv]" }

        var cur = "[bgv]"
        if (videoMeta.introText.isNotBlank()) {
            fp += "${cur}drawtext=${fOpt}text='${esc(videoMeta.introText)}':" +
                  "fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:" +
                  "x=(W-tw)/2:y=H/3-th/2:" +
                  "alpha='${alpha(p1Start,p1End)}':enable='${en(p1Start,p1End)}'[p1]"
            cur = "[p1]"
        }
        if (hasBgv2) {
            val px100 = 160; val px70 = 112; val px40 = 64
            fp += "${cur}drawtext=${fOpt}text='베다점성술로':fontsize=$px70:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.15-th/2:alpha='${alpha(t1,tAllEnd)}':enable='${en(t1,tAllEnd)}'[t1]"; cur="[t1]"
            fp += "${cur}drawtext=${fOpt}text='${esc(rotWord)}':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.37-th/2:alpha='${alpha(t2,tAllEnd)}':enable='${en(t2,tAllEnd)}'[t2]"; cur="[t2]"
            fp += "${cur}drawtext=${fOpt}text='${esc(titleWord)}':fontsize=$px100:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.57-th/2:alpha='${alpha(t3,tAllEnd)}':enable='${en(t3,tAllEnd)}'[t3]"; cur="[t3]"
            fp += "${cur}drawtext=${fOpt}text='by ASTERION':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.77-th/2:alpha='${alpha(t4,tAllEnd)}':enable='${en(t4,tAllEnd)}'[t4]"; cur="[t4]"
        }
        fp += "${cur}format=yuv420p[vfinal]"

        val numVid    = if (hasBgv2) 2 else 1
        val silentIdx = numVid
        val hasDisc   = disclaimerWav != null && disclaimerWav.exists()
        if (hasDisc) {
            val ds = videoMeta.introDurationSecs.fmtUS(1)
            fp += "[${silentIdx}:a]atrim=0:${ds},asetpts=PTS-STARTPTS[pre_sil]"
            fp += "[${numVid+1}:a]asetpts=PTS-STARTPTS[disc_a]"
            fp += "[pre_sil][disc_a]concat=n=2:v=0:a=1[aout]"
        }

        val outFile = File(sceneTempDir, "scene_intro.mp4")
        val cmd = buildString {
            append("-y ")
            append("-stream_loop -1 -i ${bgv1.absolutePath} ")
            if (hasBgv2 && bgv2 != null) append("-stream_loop -1 -i ${bgv2.absolutePath} ")
            append("-f lavfi -i anullsrc=r=44100:cl=stereo ")
            if (hasDisc) append("-i ${disclaimerWav!!.absolutePath} ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[vfinal]\" ")
            if (hasDisc) append("-map \"[aout]\" -c:a aac -b:a 128k ")
            else         append("-map ${silentIdx}:a ")
            append("-t ${totalDur.fmtUS()} ")
            append("-c:v libx264 -preset ultrafast -crf 23 ")
            append("-movflags +faststart ${outFile.absolutePath}")
        }

        onProgress("🎬 인트로 렌더링 중 (${totalDur.toInt()}초)...")
        Log.i(TAG, "renderIntro: $cmd")
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        val ok = outFile.exists() && outFile.length() > 0
        if (!ok) {
            Log.e(TAG, "인트로 실패: ${rc.logsAsString.takeLast(600)}")
            onProgress("⚠ 인트로 실패 → 본 영상으로 진행")
            return@withContext null
        }
        subclipFiles.add(0, outFile)
        totalDurationSecs += totalDur
        onProgress("✅ 인트로 완료 (${totalDur.toInt()}초, ${outFile.length()/1024}KB)")
        outFile
    }

    // ── Phase 1: 씬 준비 (TTS + BGV pre-cut) ───────────────────────
    //   MP4 인코딩 없음 — WAV와 BGV 파일만 생성
    //   BGV pre-cut: libx264로 정확히 WAV 길이만큼 인코딩

    suspend fun prepareScene(
        row      : ScriptDataRow,
        voiceConfig: VoiceConfig,
        startSecs: Float,
        cacheDir : File? = null,
        onProgress: (String) -> Unit = {}
    ): ScenePrep? = withContext(Dispatchers.IO) {
        val sceneId = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        try {
            val hasTts = row.script.isNotBlank() && row.sectionType != SectionType.BUFFER

            // ── WAV: 캐시 확인 또는 합성 ──────────────────────────────
            val wavCache = cacheDir?.let { File(it, "${sceneId}.wav") }
            val useWavCache = wavCache != null && wavCache.exists() && wavCache.length() > 1024L

            val ttsWavFile: File
            if (useWavCache) {
                ttsWavFile = wavCache!!
                onProgress("[$sceneId] WAV 캐시 사용: ${wavCache.length()/1024}KB")
            } else {
                ttsWavFile = File(sceneTempDir, "${sceneId}_tts.wav")
                if (hasTts) {
                    val cfg = voiceConfig.forSpeaker(row.speaker)
                    onProgress("[$sceneId] TTS 합성: ${cfg.label}(sid=${cfg.sid} steps=${cfg.numSteps})")
                    synthesizeChunked(row.script, cfg.sid, cfg.speed, ttsWavFile, cfg.numSteps, sceneId, onProgress)
                } else {
                    // BUFFER/빈 스크립트: 3초 묵음 WAV
                    com.arthenica.ffmpegkit.FFmpegKit.execute(
                        "-y -f lavfi -i anullsrc=r=24000:cl=mono " +
                        "-t 3.0 -c:a pcm_s16le ${ttsWavFile.absolutePath}"
                    )
                }
                // 캐시에 복사
                if (wavCache != null && ttsWavFile.exists() && ttsWavFile.length() > 1024L) {
                    try { ttsWavFile.copyTo(wavCache, overwrite = true) } catch (_: Exception) {}
                }
            }

            // v3.21: WAV 길이 측정 개선 — 이것이 "카드가 항상 BGV와 동기화"되던 근본 원인
            //   MediaMetadataRetriever: Supertonic WAV에서 0ms 반환 가능
            //   → 0일 때 wavDuration=1.0f 고정 → BGV pre-cut도 1초 → 카드도 1초 간격
            //   → BGV와 카드가 릌다 동일한 잘못된 1초 기준으로 돌아감
            //   Fix: 파일크기 기반 측정으로 fallback (Supertonic = 24kHz mono 16-bit = 48000 bytes/sec)
            val wavDuration = if (ttsWavFile.exists() && ttsWavFile.length() > 1024L) {
                val mmrDur = getMediaDurationSecs(ttsWavFile)
                if (mmrDur >= 0.5f) {
                    mmrDur
                } else {
                    val dataBytes = (ttsWavFile.length() - 44L).coerceAtLeast(0L)
                    val fileDur   = dataBytes / 48000.0f  // 24000Hz × 1ch × 2bytes
                    onProgress("[$sceneId] WAV MMR=0 → 파일크기 기반: ${fileDur.fmtUS(1)}s")
                    fileDur.coerceAtLeast(1.0f)
                }
            } else 3.0f.also { if (hasTts) onProgress("[$sceneId] ⚠️ WAV 생성 실패 → 3.0s 기본값") }

            onProgress("[$sceneId] WAV: ${ttsWavFile.length()/1024}KB → ${wavDuration.fmtUS(1)}s")

            // ── BGV pre-cut: 캐시 확인 또는 생성 ──────────────────────
            //   libx264 + scale 정규화 → 모든 BGV 1920x1080 30fps yuv420p로 통일
            //   -t wavDuration: 정확히 WAV 길이에 맞춤 (frame 단위 정밀)
            val bgFile   = AppConfig.resolveBgv(row.bgFileName)
            val bgvCache = cacheDir?.let { File(it, "${sceneId}_bgv.mp4") }
            val useBgvCache = bgvCache != null && bgvCache.exists() && bgvCache.length() > 0L

            val bgvCutFile: File
            if (useBgvCache) {
                bgvCutFile = bgvCache!!
                onProgress("[$sceneId] BGV 캐시 사용: ${bgvCache.length()/1024}KB")
            } else {
                bgvCutFile = File(sceneTempDir, "${sceneId}_bgv.mp4")
                val cutCmd = "-y -stream_loop -1 -i ${bgFile.absolutePath} " +
                    "-an -vf \"scale=${VIDEO_W}:${VIDEO_H}:force_original_aspect_ratio=decrease," +
                    "pad=${VIDEO_W}:${VIDEO_H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p\" " +
                    "-r 30 -c:v libx264 -preset ultrafast -crf 23 " +
                    "-t ${wavDuration.fmtUS()} ${bgvCutFile.absolutePath}"
                val cutRc = com.arthenica.ffmpegkit.FFmpegKit.execute(cutCmd)
                if (!bgvCutFile.exists() || bgvCutFile.length() == 0L) {
                    Log.w(TAG, "[$sceneId] BGV pre-cut 실패: ${cutRc.logsAsString.takeLast(200)}")
                    onProgress("[$sceneId] ⚠️ BGV pre-cut 실패 → 원본 사용")
                } else {
                    onProgress("[$sceneId] BGV cut: ${bgvCutFile.length()/1024}KB (${wavDuration.fmtUS(1)}s)")
                    if (bgvCache != null) try { bgvCutFile.copyTo(bgvCache, overwrite = true) } catch (_: Exception) {}
                }
            }

            // ── 카드 메타 계산 ────────────────────────────────────────
            val pattern       = AnimationPattern.from(row.animation)
            val keyframes     = calcCardKeyframes(pattern, wavDuration)
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f

            ScenePrep(
                row           = row,
                wavFile       = ttsWavFile.takeIf { it.exists() && it.length() > 1024L },
                bgvCutFile    = bgvCutFile.takeIf { it.exists() && it.length() > 0L },
                bgFile        = bgFile,
                wavDuration   = wavDuration,
                startSecs     = startSecs,
                cardStyle     = CardStyle.from(row.cardStyle),
                gradient      = GradientPreset.from(row.gradientPreset),
                keyframes     = keyframes,
                textStartSecs = textStartSecs
            )
        } catch (e: Exception) {
            Log.e(TAG, "prepareScene 예외 [$sceneId]: $e")
            onProgress("[$sceneId] ❌ 예외: ${e.message}")
            null
        }
    }

    // ── Phase 2: 단일 인코딩 조립 ────────────────────────────────────
    //   BGV cuts concat + WAV concat → filter_complex_script → body.mp4
    //   씬별 MP4 없음 → 절대시간 기반 카드/텍스트 → 타이밍 완전 보장

    suspend fun assembleBody(
        preps      : List<ScenePrep>,
        outputName : String,
        onProgress : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (preps.isEmpty()) { onProgress("⚠️ 준비된 씬 없음"); return@withContext null }

        sceneTempDir.mkdirs()

        // ─ Step 1: BGV cuts concat ─────────────────────────────────
        onProgress("📹 BGV 스트림 구성 중 (${preps.size}개)...")
        val bgvListFile = File(sceneTempDir, "bgv_list.txt")
        bgvListFile.writeText(preps.joinToString("\n") {
            val f = it.bgvCutFile?.takeIf { f -> f.exists() && f.length() > 0L } ?: it.bgFile
            "file '${f.absolutePath}'"
        })
        val bgvBodyFile = File(sceneTempDir, "bgv_body.mp4")
        val bgvRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -f concat -safe 0 -i ${bgvListFile.absolutePath} -c copy ${bgvBodyFile.absolutePath}"
        )
        if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
            Log.e(TAG, "BGV concat 실패: ${bgvRc.logsAsString.takeLast(400)}")
            onProgress("❌ BGV 스트림 실패")
            bgvListFile.delete()
            return@withContext null
        }
        onProgress("✅ BGV 스트림: ${bgvBodyFile.length()/1024/1024}MB")

        // ─ Step 2: WAV concat ──────────────────────────────────────
        onProgress("🎵 TTS 스트림 구성 중...")
        // 묵음 WAV가 필요한 씬 처리
        val wavFiles = preps.map { prep ->
            val f = prep.wavFile?.takeIf { it.exists() && it.length() > 1024L }
            if (f != null) f
            else {
                val silFile = File(sceneTempDir, "sil_${prep.row.rowIndex}.wav")
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -f lavfi -i anullsrc=r=24000:cl=mono " +
                    "-t ${prep.wavDuration.fmtUS()} -c:a pcm_s16le ${silFile.absolutePath}"
                )
                silFile
            }
        }
        val wavListFile = File(sceneTempDir, "wav_list.txt")
        wavListFile.writeText(wavFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val ttsBodyFile = File(sceneTempDir, "tts_body.wav")
        // v3.20: 44100Hz 스테레오로 정규화
        //   intro.mp4 오디오(44100Hz 스테레오)와 포맷 통일 → concat 시 불일치 방지
        val wavRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -f concat -safe 0 -i ${wavListFile.absolutePath} -ar 44100 -ac 2 ${ttsBodyFile.absolutePath}"
        )
        if (!ttsBodyFile.exists() || ttsBodyFile.length() == 0L) {
            Log.e(TAG, "WAV concat 실패: ${wavRc.logsAsString.takeLast(400)}")
            onProgress("❌ TTS 스트림 실패")
            bgvBodyFile.delete(); bgvListFile.delete(); wavListFile.delete()
            return@withContext null
        }
        onProgress("✅ TTS 스트림: ${ttsBodyFile.length()/1024}KB")

        // ─ Step 3: filter_complex_script 생성 ──────────────────────
        //   절대시간 enable='between(t,start,end)' 으로 각 씬 카드/텍스트 배치
        //   파일로 저장 → 커맨드라인 길이 제한 없음
        val vfParts = mutableListOf<String>()
        vfParts += "[0:v]setpts=PTS-STARTPTS"

        for (prep in preps) {
            val t1 = prep.startSecs
            val t2 = prep.startSecs + prep.wavDuration

            if (prep.cardStyle != CardStyle.NONE && prep.cardStyle != CardStyle.MINIMAL) {
                val r = (prep.gradient.topColor shr 16) and 0xFF
                val g = (prep.gradient.topColor shr 8)  and 0xFF
                val b = prep.gradient.topColor and 0xFF
                // drawbox — enable= 절대시간
                vfParts += "drawbox=" +
                    "x=${prep.keyframes.holdX.toInt()}:y=${prep.keyframes.holdY.toInt()}:w=860:h=340:" +
                    "color=0x${String.format(Locale.US, "%02X%02X%02X", r, g, b)}" +
                    "@${String.format(Locale.US, "%.2f", prep.cardStyle.alpha)}:t=fill:" +
                    "enable='between(t,${t1.fmtUS(3)},${t2.fmtUS(3)})'"

                val pm = prep.row.cardMain.trim()
                val ps = prep.row.cardSub.trim()
                val pd = prep.row.cardDesc.trim()

                if (pm.isNotBlank() || ps.isNotBlank() || pd.isNotBlank()) {
                    val ts  = (prep.startSecs + prep.textStartSecs).coerceAtLeast(t1)
                    val fi  = (ts + 0.8f).fmtUS(3)
                    val fo  = (t2 - 0.6f).coerceAtLeast(ts + 1f).fmtUS(3)
                    val alp = "if(lt(t,$fi),(t-${ts.fmtUS(3)})/0.8,if(gt(t,$fo),(${t2.fmtUS(3)}-t)/0.6,1))"
                    val enb = "between(t,${ts.fmtUS(3)},${t2.fmtUS(3)})"
                    val fo2 = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""
                    val cx  = prep.keyframes.holdX.toInt() + 430

                    val mainLines = if (pm.isNotBlank()) splitToLines(pm, 12) else emptyList()
                    val subLines  = if (ps.isNotBlank()) splitToLines(ps, 18) else emptyList()
                    val descLines = if (pd.isNotBlank()) splitToLines(pd, 24) else emptyList()

                    val mainH = mainLines.size * 62
                    val subH  = subLines.size  * 46
                    val gap12 = if (mainLines.isNotEmpty() && subLines.isNotEmpty()) 20 else 0
                    val gap23 = if (subLines.isNotEmpty()  && descLines.isNotEmpty()) 12 else 0
                    val totalH = mainH + gap12 + subH + gap23 + descLines.size * 40
                    val by = (prep.keyframes.holdY + 170 - totalH / 2).toInt()

                    mainLines.forEachIndexed { i, line ->
                        vfParts += "drawtext=${fo2}text='${escapeDrawtext(line)}':" +
                            "fontsize=52:fontcolor=white:borderw=2:bordercolor=black@0.8:" +
                            "x=${cx}-tw/2:y=${by + i*62}:alpha='$alp':enable='$enb'"
                    }
                    subLines.forEachIndexed { i, line ->
                        vfParts += "drawtext=${fo2}text='${escapeDrawtext(line)}':" +
                            "fontsize=38:fontcolor=0xCCCCCC:" +
                            "x=${cx}-tw/2:y=${by + mainH + gap12 + i*46}:alpha='$alp':enable='$enb'"
                    }
                    descLines.forEachIndexed { i, line ->
                        vfParts += "drawtext=${fo2}text='${escapeDrawtext(line)}':" +
                            "fontsize=32:fontcolor=0xAAAAAA:" +
                            "x=${cx}-tw/2:y=${by + mainH + gap12 + subH + gap23 + i*40}:alpha='$alp':enable='$enb'"
                    }
                }
            }
        }
        vfParts += "scale=${VIDEO_W}:${VIDEO_H},format=yuv420p[vout]"

        val filterScriptFile = File(sceneTempDir, "body_vf.txt")
        filterScriptFile.writeText(vfParts.joinToString(",\n"))

        // ─ Step 4: 단일 FFmpeg 인코딩 ──────────────────────────────
        val totalBodyDur = preps.sumOf { it.wavDuration.toDouble() }.toFloat()
        val bodyFile = File(AppConfig.OUTPUT_DIR, "${outputName}_body.mp4")

        onProgress("🎬 단일 인코딩 시작 (${totalBodyDur.toInt()}초, ${preps.size}씬)...")
        Log.i(TAG, "assembleBody filter lines: ${vfParts.size}, dur=${totalBodyDur.fmtUS(1)}s")

        val cmd = "-y " +
            "-i ${bgvBodyFile.absolutePath} " +
            "-i ${ttsBodyFile.absolutePath} " +
            "-filter_complex_script ${filterScriptFile.absolutePath} " +
            "-map [vout] -map 1:a " +
            "-c:v libx264 -preset ultrafast -crf 20 " +
            "-c:a aac -b:a 192k " +  // intro와 동일한 비트레이트
            "-t ${totalBodyDur.fmtUS()} " +
            "-movflags +faststart " +
            bodyFile.absolutePath

        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)

        // 임시 파일 정리
        bgvBodyFile.delete(); ttsBodyFile.delete()
        bgvListFile.delete(); wavListFile.delete(); filterScriptFile.delete()
        wavFiles.filter { it.absolutePath.contains("sil_") }.forEach { it.delete() }
        preps.forEach { prep ->
            prep.bgvCutFile?.let { if (it.absolutePath.contains(TEMP_SUBDIR)) it.delete() }
            prep.wavFile?.let    { if (it.absolutePath.contains(TEMP_SUBDIR)) it.delete() }
        }

        if (!bodyFile.exists() || bodyFile.length() == 0L) {
            Log.e(TAG, "assembleBody 실패: ${rc.logsAsString.takeLast(400)}")
            onProgress("❌ body 인코딩 실패\n${rc.logsAsString.lines().lastOrNull { it.contains("Error",true) || it.contains("Invalid",true) } ?: ""}")
            return@withContext null
        }

        totalDurationSecs += totalBodyDur
        subclipFiles.add(bodyFile)
        onProgress("✅ body 완성: ${bodyFile.length()/1024/1024}MB (${totalBodyDur.toInt()}초)")
        bodyFile
    }

    // ── 씬 합치기 (intro + body) + BGM + 워터마크 ───────────────────

    suspend fun concatSubclips(
        outputName   : String,
        bgmFileName  : String,
        watermarkText: String = "",
        introDurSecs : Float  = 21f,
        onProgress   : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (subclipFiles.isEmpty()) { onProgress("⚠️ 클립 없음"); return@withContext null }

        val listFile   = File(sceneTempDir, "concat_list.txt")
        listFile.writeText(subclipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val outputFile = File(AppConfig.OUTPUT_DIR, "$outputName.mp4")
        val bgmFile    = AppConfig.resolveBgm(bgmFileName)
        val duration   = totalDurationSecs
        val wmEnd      = (duration - 5f).coerceAtLeast(introDurSecs + 1f)

        onProgress("합치기: ${subclipFiles.size}개 / 총 ${duration.toInt()}초")

        val filterParts   = mutableListOf<String>()
        var videoMapLabel = "0:v"
        var audioMapLabel = "0:a"

        if (watermarkText.isNotBlank()) {
            val esc     = escapeDrawtext(watermarkText)
            val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
            filterParts += "[0:v]drawtext=${fontOpt}text='$esc':" +
                "fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:" +
                "x=30:y=40:enable='between(t\\,${introDurSecs.fmtUS(1)}\\,${wmEnd.fmtUS(1)})' [wm_pre]"
            filterParts += "[wm_pre]format=yuv420p[vout]"
            videoMapLabel = "[vout]"
        }

        if (bgmFile != null) {
            filterParts += "[0:a]volume=0.85[tts]"
            // BGM: 인트로 구간(0~introDurSecs)은 0.40으로 존재감 있게
            // 이후 body 구간은 0.02로 매우 작게 (TTS 방해 최소화)
            val bgmFadeStart = (introDurSecs - 2f).fmtUS(1)
            val bgmFadeEnd   = introDurSecs.fmtUS(1)
            filterParts += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,$bgmFadeStart)\\,0.40\\,if(lt(t\\,$bgmFadeEnd)\\,0.40+(t-$bgmFadeStart)*(-0.19)\\,0.02))':eval=frame[bgm]"
            filterParts += "[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3:normalize=0[aout]"
            audioMapLabel = "[aout]"
        }

        val cmd = buildString {
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if (bgmFile != null) append("-stream_loop -1 -i ${bgmFile.absolutePath} ")
            if (filterParts.isNotEmpty()) append("-filter_complex \"${filterParts.joinToString(";")}\" ")
            append("-map \"$videoMapLabel\" -map \"$audioMapLabel\" ")
            append("-c:v libx264 -preset fast -crf 20 ")
            // v3.20: 항상 오디오 재인코딩 (-c:a copy 제거)
            //   -c:a copy: intro(44100Hz) + body(24kHz) 포맷 다르면 20MB 불완전 파일 생성
            append("-c:a aac -b:a 192k ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }

        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.filter { it.name.endsWith("_body.mp4") }.forEach { it.delete() }
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }

        val ok = outputFile.exists() && outputFile.length() > 0
        return@withContext if (ok) {
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length()/1024/1024}MB)")
            outputFile
        } else {
            Log.e(TAG, "concat 실패: ${rc.logsAsString.takeLast(400)}")
            onProgress("❌ concat 실패")
            null
        }
    }

    fun release() {
        subclipFiles.clear()
        totalDurationSecs = 0f
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }
    }

    // ── 재생 길이 측정 ────────────────────────────────────────────

    fun getMediaDurationSecs(file: File): Float {
        if (!file.exists()) return 0f
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            mmr.release()
            ms / 1000.0f
        } catch (e: Exception) {
            Log.w(TAG, "재생길이 측정 실패 [${file.name}]: ${e.message}")
            0f
        }
    }

    private fun getBgvDurationSecs(bgFile: File): Float = getMediaDurationSecs(bgFile)

    // ── TTS 청크 합성 ────────────────────────────────────────────

    private fun synthesizeChunked(
        text: String, speakerId: Int, speed: Float,
        outputFile: File, numSteps: Int,
        sceneId: String, onProgress: (String) -> Unit
    ) {
        ttsEngine.synthesize(text, speakerId, speed, outputFile, numSteps)
    }

    // ── 키프레임 계산 ────────────────────────────────────────────

    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx   = VIDEO_W / 2f - 430f
        val cy   = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f,     VIDEO_H*.18f, CardOutType.TOP,          1f,   tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H*.18f, VIDEO_W/2f-430f, VIDEO_H*.18f, CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx,               -340f,         cx,              cy,            CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W*.05f,     VIDEO_H*.68f,  cx,              cy,            CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.SCALE,        0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f,     VIDEO_H*.18f, CardOutType.ROTATE_SCALE, 1f,  tOut, tTotal)
            else               -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.FADE,         1f,   tOut, tTotal)
        }
    }

    // ── 유틸리티 ────────────────────────────────────────────────

    private fun escapeDrawtext(text: String): String = text
        .replace("\\", "\\\\")
        .replace("'",  "\\'")
        .replace(":",  "\\:")
        .replace(",",  "\\,")

    private fun splitToLines(text: String, max: Int): List<String> =
        text.replace("\\N","\n").replace("\\n","\n")
            .split("\n")
            .flatMap { if (it.length > max) it.chunked(max) else listOf(it) }
            .filter { it.isNotBlank() }
}

data class CardKeyframes(
    val inStartX: Float, val inStartY: Float,
    val holdX: Float,    val holdY: Float,
    val outType: CardOutType,
    val tIn: Float, val tHold: Float, val tOut: Float
)

enum class CardOutType { TOP, BOTTOM, LEFT, RIGHT, FADE, SCALE, ROTATE_SCALE }
