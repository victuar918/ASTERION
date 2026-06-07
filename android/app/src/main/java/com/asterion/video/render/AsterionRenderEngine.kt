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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.10
//
// [변경로그 v3.10]
//   - renderIntro: drawtext angle=-30 제거 (FFmpegKit 6.0-2.LTS 미지원)
//   - renderIntro: 실패 시 FFmpeg 에러 라인을 onProgress로 UI 출력
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

class AsterionRenderEngine(
    private val context: Context,
    private val ttsEngine: SupertonicTtsEngine
) {
    /** Activity에서 면책 TTS 합성 시 사용 */
    val ttsEnginePublic: SupertonicTtsEngine get() = ttsEngine

    private val subclipFiles      = mutableListOf<File>()
    private var totalDurationSecs = 0f

    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    /**
     * drawtext 용 폰트 절대경로
     * SELinux 접근 가능한 폰트를 lazy로 탐색
     */
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

    /** drawtext 옵션 필드용 fontfile 인수 */
    private val fontArg: String by lazy {
        if (fontPath.isNotEmpty()) ":fontfile='${fontPath}'" else ""
    }

    // ── 인트로 렌더링 v2 ──────────────────────────────────────────────────
    //
    // ① intro01 전체 재생 (BGV 실제 길이 사용, 중간 컷 없음)
    // ② Phase1 텍스트: 화면 상단 1/3, intro01 끝날 때 함께 사라짐
    // ③ xfade 1초로 intro01→intro02 크로스페이드
    // ④ intro02 전체 재생 + 순차 텍스트 (2초 간격, 마지막 후 6초 유지)
    // ⑤ 면책 TTS(disclaimerWav): introDurationSecs초에 시작

    suspend fun renderIntro(
        videoMeta: VideoMeta,
        disclaimerWav: File? = null,   // 면책 TTS WAV — introDurationSecs초에 시작
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) {
            onProgress("인트로 BGV 미설정 — 인트로 생략")
            return@withContext null
        }
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()

        val bgv1    = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2 = videoMeta.introBgv2.isNotBlank()
        val bgv2    = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else null

        // BGV 실제 길이 측정
        val bgv1Dur  = getBgvDurationSecs(bgv1).takeIf { it > 1f } ?: 8f
        val bgv2Dur  = if (bgv2 != null && bgv2.exists()) getBgvDurationSecs(bgv2).takeIf { it > 1f } ?: 15f else 0f
        val xfadeDur = if (hasBgv2) 1.0f else 0f
        val totalDur = if (hasBgv2) bgv1Dur + bgv2Dur - xfadeDur else bgv1Dur
        onProgress("인트로 측정: bgv1=${bgv1Dur.fmtUS(1)}s bgv2=${bgv2Dur.fmtUS(1)}s total=${totalDur.fmtUS(1)}s")

        // 타이밍
        val p1Start  = 2.0f
        val p1End    = (bgv1Dur - xfadeDur - 0.3f).coerceAtLeast(p1Start + 1f)
        val p2Base   = bgv1Dur - xfadeDur
        val t1 = p2Base + 2f; val t2 = p2Base + 4f
        val t3 = p2Base + 6f; val t4 = p2Base + 8f
        // ✅ FIX: tAllEnd가 totalDur을 초과하지 않도록 클램프
        val tAllEnd  = (t4 + 6f).coerceAtMost(totalDur - 0.5f)

        // 코인 타입별 텍스트
        val isXrp      = videoMeta.introType.trim().uppercase() == "XRP"
        val titleWord  = if (isXrp) "XRP 전망" else "크립토 갤러리"
        val rotWord    = if (isXrp) "예측하는" else "둘러보는"

        // 헬퍼
        fun esc(s: String) = s.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
        fun alpha(ts: Float, te: Float): String {
            val fi = (ts + 0.5f).fmtUS(2); val fo = (te - 0.5f).coerceAtLeast(ts + 0.6f).fmtUS(2)
            return "if(lt(t,$fi),(t-${ts.fmtUS(2)})/0.5,if(gt(t,$fo),(${te.fmtUS(2)}-t)/0.5,1))"
        }
        fun en(ts: Float, te: Float) = "between(t,${ts.fmtUS(2)},${te.fmtUS(2)})"

        val fp      = mutableListOf<String>()
        val fOpt    = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""

        // 비디오 전환: xfade 또는 단독
        if (hasBgv2 && bgv2 != null) {
            val xOff = (bgv1Dur - xfadeDur).coerceAtLeast(0.5f)
            fp += "[0:v]setpts=PTS-STARTPTS[v0]"
            fp += "[1:v]setpts=PTS-STARTPTS[v1]"
            fp += "[v0][v1]xfade=transition=fade:duration=${xfadeDur.fmtUS()}:offset=${xOff.fmtUS()}[bgv]"
        } else { fp += "[0:v]setpts=PTS-STARTPTS[bgv]" }

        var cur = "[bgv]"

        // Phase 1 텍스트 (상단 1/3)
        if (videoMeta.introText.isNotBlank()) {
            fp += "${cur}drawtext=${fOpt}text='${esc(videoMeta.introText)}':" +
                  "fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:" +
                  "x=(W-tw)/2:y=H/3-th/2:" +
                  "alpha='${alpha(p1Start,p1End)}':enable='${en(p1Start,p1End)}'[p1]"
            cur = "[p1]"
        }

        // Phase 2 순차 텍스트
        // ✅ FIX v3.10: angle=-30 제거 — FFmpegKit 6.0-2.LTS drawtext 미지원 파라미터
        if (hasBgv2) {
            val px100 = 80; val px70 = 56; val px40 = 32
            fp += "${cur}drawtext=${fOpt}text='베다점성술로':fontsize=$px70:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.15-th/2:alpha='${alpha(t1,tAllEnd)}':enable='${en(t1,tAllEnd)}'[t1]"; cur="[t1]"
            fp += "${cur}drawtext=${fOpt}text='${esc(rotWord)}':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.37-th/2:alpha='${alpha(t2,tAllEnd)}':enable='${en(t2,tAllEnd)}'[t2]"; cur="[t2]"
            fp += "${cur}drawtext=${fOpt}text='${esc(titleWord)}':fontsize=$px100:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.57-th/2:alpha='${alpha(t3,tAllEnd)}':enable='${en(t3,tAllEnd)}'[t3]"; cur="[t3]"
            fp += "${cur}drawtext=${fOpt}text='by ASTERION':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.77-th/2:alpha='${alpha(t4,tAllEnd)}':enable='${en(t4,tAllEnd)}'[t4]"; cur="[t4]"
        }
        fp += "${cur}format=yuv420p[vfinal]"

        // 오디오: 면책 TTS가 있으면 introDurationSecs초에 삽입, 없으면 무음
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
        Log.i(TAG, "renderIntro v2: $cmd")
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        val ok = outFile.exists() && outFile.length() > 0
        if (!ok) {
            // ✅ FIX v3.10: 에러 핵심 라인을 UI에도 출력 (logcat 전용에서 변경)
            val errLog  = rc.logsAsString
            val errLine = errLog.lines().lastOrNull {
                it.contains("Error",   ignoreCase = true) ||
                it.contains("Invalid", ignoreCase = true) ||
                it.contains("No such", ignoreCase = true) ||
                it.contains("failed",  ignoreCase = true) ||
                it.contains("Option",  ignoreCase = true)
            } ?: errLog.takeLast(200)
            Log.e(TAG, "인트로 실패:\n${errLog.takeLast(600)}")
            onProgress("⚠ 인트로 실패: ${errLine.takeLast(120)}")
            onProgress("→ 본 영상으로 진행")
            return@withContext null
        }
        subclipFiles.add(0, outFile)
        totalDurationSecs += totalDur
        onProgress("✅ 인트로 완료 (${totalDur.toInt()}초, ${outFile.length()/1024}KB)")
        outFile
    }

    // ── 씬 렌더링 ────────────────────────────────────────────────────

    /**
     * 이미 렌더링된 씬을 subclipFiles에 추가 (실제 렌더링 없이)
     * Status=DONE + 파일 존재 시 재렌더링 스킵에 사용
     */
    fun addExistingSubclip(file: File, onProgress: (String) -> Unit = {}) {
        val dur = try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            mmr.release()
            (ms / 1000.0f).coerceAtLeast(1.0f)
        } catch (e: Exception) { 3.0f }
        subclipFiles.add(file)
        totalDurationSecs += dur
        onProgress("⏭️ 캐시 재사용: ${file.name} (${file.length()/1024}KB, ${dur.fmtUS(1)}s)")
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        videoMeta: VideoMeta,
        voiceConfig: VoiceConfig,
        cacheDir: File? = null,   // null이면 .temp_scenes, 제공 시 해당 디렉토리에 저장
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()

        val sceneId = "scene_${row.rowIndex.toString().padStart(4, '0')}"
        onProgress("[$sceneId] ${row.section} / Speaker ${row.speaker}")
        try {
            val bgFile = AppConfig.resolveBgv(row.bgFileName)

            // BGV fallback 여부 UI 표시
            val requestedBgv = row.bgFileName.split("|").firstOrNull()?.trim() ?: ""
            if (bgFile.name != requestedBgv) {
                onProgress("[$sceneId] ⚠️ BGV 대체: '$requestedBgv' → '${bgFile.name}'")
                Log.w(TAG, "[$sceneId] BGV fallback: $requestedBgv → ${bgFile.name}")
            } else {
                onProgress("[$sceneId] BGV: ${bgFile.name} (${bgFile.length()/1024}KB)")
            }

            // bgFile 존재 여부 + 최소 크기 체크
            if (!bgFile.exists() || bgFile.length() < 10_000L) {
                Log.e(TAG, "[$sceneId] bgFile 문제: exist=${bgFile.exists()} size=${bgFile.length()} path=${bgFile.absolutePath}")
                onProgress("[$sceneId] ❌ bgFile 없음 또는 너무 작음(${bgFile.length()}B): ${bgFile.name}")
                return@withContext null
            }
            val ttsWavFile = File(sceneTempDir, "${sceneId}_tts.wav")
            val hasTts     = row.script.isNotBlank() && row.sectionType != SectionType.BUFFER

            if (hasTts) {
                val cfg = voiceConfig.forSpeaker(row.speaker)
                onProgress("[$sceneId] TTS: ${cfg.label}(sid=${cfg.sid} spd=${cfg.speed} steps=${cfg.numSteps})")
                synthesizeChunked(row.script, cfg.sid, cfg.speed, ttsWavFile, cfg.numSteps, sceneId, onProgress)
            }

            // WAV 진단 로그 + tTotal 계산
            val tTotal: Float
            if (ttsWavFile.exists() && ttsWavFile.length() > 1024L) {
                val dur = ttsEngine.estimateDurationFromFile(ttsWavFile)
                tTotal = dur.coerceAtLeast(1.0f)
                onProgress("[$sceneId] WAV: ${ttsWavFile.length()/1024}KB → tTotal=${tTotal.fmtUS(1)}s")
            } else {
                tTotal = 3.0f
                if (hasTts) onProgress("[$sceneId] ⚠️ WAV 없음 또는 1KB 미만 → tTotal=3.0s (TTS 실패 의심)")
            }
            totalDurationSecs += tTotal

            val pattern       = AnimationPattern.from(row.animation)
            val keyframes     = calcCardKeyframes(pattern, tTotal)
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f

            val outputFile = if (cacheDir != null) {
                cacheDir.mkdirs()
                File(cacheDir, "${sceneId}.mp4")
            } else {
                File(sceneTempDir, "${sceneId}.mp4")
            }
            val cmd = buildFfmpegCmd(
                bgFile, ttsWavFile.takeIf { it.exists() }, tTotal,
                CardStyle.from(row.cardStyle), GradientPreset.from(row.gradientPreset),
                keyframes, pattern, textStartSecs, row,
                CardExtraEffect.from(row.cardExtraEffect),
                row.bgEffectCode, BgTransition.from(row.bgTransition),
                row.bgTransitionDuration, outputFile
            )

            onProgress("[$sceneId] FFmpeg 인코딩...")
            Log.i(TAG, "[$sceneId] $cmd")
            val rc    = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            val fileOk = outputFile.exists() && outputFile.length() > 0
            if (!fileOk) {
                val errLog  = rc.logsAsString
                val errLine = errLog.lines().lastOrNull {
                    it.contains("Error",   ignoreCase = true) ||
                    it.contains("Invalid", ignoreCase = true) ||
                    it.contains("No such", ignoreCase = true) ||
                    it.contains("failed",  ignoreCase = true)
                } ?: errLog.takeLast(200)
                Log.e(TAG, "FFmpeg 실패 [$sceneId]:\n${errLog.takeLast(800)}")
                onProgress("[$sceneId] ❌ FFmpeg 오류: ${errLine.takeLast(120)}")
                return@withContext null
            }
            if (!rc.returnCode.isValueSuccess)
                Log.w(TAG, "[$sceneId] rc=${rc.returnCode.value}(경고) 파일 OK (${outputFile.length()/1024}KB)")

            ttsWavFile.delete()
            subclipFiles.add(outputFile)
            onProgress("[$sceneId] ✅ ${outputFile.length() / 1024}KB")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "renderScene 예외: $e")
            onProgress("[$sceneId] ❌ 예외: ${e.message}")
            null
        }
    }

    // ── 씬 합치기 ────────────────────────────────────────────────────

    suspend fun concatSubclips(
        outputName: String,
        bgmFileName: String,
        watermarkText: String = "",
        introDurSecs: Float = 21f,   // ✅ 워터마크/BGM 타이밍 기준 (인트로 종료 시점)
        onProgress: (String) -> Unit = {}
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
            val esc = escapeDrawtext(watermarkText)
            val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
            // ✅ 워터마크: introDurSecs 이후 ~ wmEnd
            filterParts += "[0:v]drawtext=${fontOpt}text='$esc':" +
                "fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:" +
                "x=30:y=40:enable='between(t\\,${introDurSecs.fmtUS(1)}\\,${wmEnd.fmtUS(1)})' [wm_pre]"
            filterParts += "[wm_pre]format=yuv420p[vout]"
            videoMapLabel = "[vout]"
        }

        if (bgmFile != null) {
            filterParts += "[0:a]volume=0.85[tts]"
            // ✅ BGM 덕킹: introDurSecs 기반 동적 계산
            val bgmFadeStart = (introDurSecs - 2f).fmtUS(1)
            val bgmFadeEnd   = introDurSecs.fmtUS(1)
            filterParts += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,$bgmFadeStart)\\,0.40\\,if(lt(t\\,$bgmFadeEnd)\\,0.40+(t-$bgmFadeStart)*(-0.175)\\,0.05))':eval=frame[bgm]"
            filterParts += "[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3:normalize=0[aout]"
            audioMapLabel = "[aout]"
        }

        val cmd = buildString {
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if (bgmFile != null) append("-stream_loop -1 -i ${bgmFile.absolutePath} ")
            if (filterParts.isNotEmpty()) append("-filter_complex \"${filterParts.joinToString(";")}\" ")
            append("-map \"$videoMapLabel\" -map \"$audioMapLabel\" ")
            // ✅ 항상 libx264 사용 (stream copy 시 워터마크 필터 미적용 문제 방지)
            append("-c:v libx264 -preset fast -crf 20 ")
            if (audioMapLabel.startsWith("[")) append("-c:a aac -b:a 192k ")
            else append("-c:a copy ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }

        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.filter { it.absolutePath.contains(TEMP_SUBDIR) }.forEach { it.delete() }
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

    // ── FFmpeg 명령어 조립 ──────────────────────────────────────────────

    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        pattern: AnimationPattern, textStartSecs: Float, row: ScriptDataRow,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {
        val effDur = transitionDur.coerceIn(0.3f, (tTotal * 0.45f).coerceAtLeast(0.3f))

        val vf = mutableListOf<String>()

        // ① PTS 정규화
        vf += "setpts=PTS-STARTPTS"

        // ② BG 전환 효과
        when (bgTransition) {
            BgTransition.FADE -> {
                val fadeOutSt = (tTotal - effDur).coerceAtLeast(0f)
                vf += "fade=t=in:st=0:d=${effDur.fmtUS()}"
                vf += "fade=t=out:st=${fadeOutSt.fmtUS()}:d=${effDur.fmtUS()}"
            }
            BgTransition.NONE -> { /* 전환 없음 */ }
            BgTransition.SLIDE_LEFT -> {
                val slideDur = effDur.coerceIn(0.3f, 1.0f)
                val slideX   = "iw*(1-min(t/${slideDur.fmtUS()}\\,1))"
                vf += "pad=w=2*iw:h=ih:x='${slideX}':y=0:color=black:eval=frame"
                vf += "crop=iw:ih:0:0"
            }
            else -> vf += "fade=t=in:st=0:d=${effDur.fmtUS()}"
        }

        // ③ BG 추가 효과
        when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> vf += "vignette=PI/4"
            "MOTION_BLUR" -> vf += "tmix=frames=3"
        }

        // ④ 카드 박스 (drawbox)
        if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            vf += "drawbox=x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340:" +
                  "color=0x${String.format(Locale.US, "%02X%02X%02X", r, g, b)}" +
                  "@${String.format(Locale.US, "%.2f", cardStyle.alpha)}:t=fill"
        }

        // ⑤ 카드 텍스트 (drawtext: Main / Sub / Desc)
        if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val pm = row.cardMain.trim()
            val ps = row.cardSub.trim()
            val pd = row.cardDesc.trim()

            if (pm.isNotBlank() || ps.isNotBlank() || pd.isNotBlank()) {
                val ts = textStartSecs.coerceAtLeast(0f)
                val fi = (ts + 0.8f).fmtUS(2)
                val fo = (tTotal - 0.6f).coerceAtLeast(ts + 1f).fmtUS(2)
                val tt = tTotal.fmtUS(2)
                val tsS = ts.fmtUS(2)
                val alphaExpr = "if(lt(t,${fi}),(t-${tsS})/0.8,if(gt(t,${fo}),(${tt}-t)/0.6,1))"
                val enableExpr = "between(t\\,${tsS}\\,${tt})"
                val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""

                val cardCenterX = kf.holdX.toInt() + 430
                val xExpr = "${cardCenterX}-tw/2"

                val mainLineCount = if (pm.isNotBlank()) splitToLines(pm, 12).size else 0
                val subLineCount  = if (ps.isNotBlank()) splitToLines(ps, 18).size else 0
                val descLineCount = if (pd.isNotBlank()) splitToLines(pd, 24).size else 0

                val mainH  = mainLineCount * 62
                val subH   = subLineCount  * 46
                val descH  = descLineCount * 40
                val gap12  = if (mainLineCount > 0 && subLineCount  > 0) 20 else 0
                val gap23  = if (subLineCount  > 0 && descLineCount > 0) 12 else 0
                val totalH = mainH + gap12 + subH + gap23 + descH

                val blockStartY = (kf.holdY + 170 - totalH / 2).toInt()
                val y1base = blockStartY
                val y2base = blockStartY + mainH + gap12
                val y3base = blockStartY + mainH + gap12 + subH + gap23

                splitToLines(pm, 12).forEachIndexed { i, line ->
                    val esc = escapeDrawtext(line)
                    val ly  = y1base + i * 62
                    vf += "drawtext=${fontOpt}text='${esc}':fontsize=52:fontcolor=white:" +
                          "borderw=2:bordercolor=black@0.8:" +
                          "x=${xExpr}:y=${ly}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
                splitToLines(ps, 18).forEachIndexed { i, line ->
                    val esc = escapeDrawtext(line)
                    val ly  = y2base + i * 46
                    vf += "drawtext=${fontOpt}text='${esc}':fontsize=38:fontcolor=0xCCCCCC:" +
                          "x=${xExpr}:y=${ly}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
                splitToLines(pd, 24).forEachIndexed { i, line ->
                    val esc = escapeDrawtext(line)
                    val ly  = y3base + i * 40
                    vf += "drawtext=${fontOpt}text='${esc}':fontsize=32:fontcolor=0xAAAAAA:" +
                          "x=${xExpr}:y=${ly}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
            }
        }

        // ⑥ BGV 루프 크로스페이드
        val bgvDur = getBgvDurationSecs(bgFile)
        val xFade  = 0.5f
        if (bgvDur >= 3.0f && tTotal > bgvDur + 1.0f) {
            val allPoints = mutableListOf<Float>()
            var lp = bgvDur
            while (lp + xFade < tTotal) {
                val fo = (lp - xFade).coerceAtLeast(0f)
                if (fo > effDur + 0.1f && lp < tTotal - effDur - xFade)
                    allPoints += lp
                lp += bgvDur
            }
            val maxPairs = 4
            val selected = if (allPoints.size <= maxPairs) allPoints
            else {
                val step = allPoints.size.toFloat() / maxPairs
                (0 until maxPairs).map { i -> allPoints[(i * step).toInt()] }
            }
            selected.forEach { p ->
                val fo    = (p - xFade).coerceAtLeast(0f)
                val fiEnd = p + xFade
                val foEn = "between(t\\,${fo.fmtUS()}\\,${p.fmtUS()})"
                val fiEn = "between(t\\,${p.fmtUS()}\\,${fiEnd.fmtUS()})"
                vf += "fade=t=out:st=${fo.fmtUS()}:d=${xFade.fmtUS()}:enable='${foEn}'"
                vf += "fade=t=in:st=${p.fmtUS()}:d=${xFade.fmtUS()}:enable='${fiEn}'"
            }
        }

        // ⑦ 픽셀 포맷 정규화
        vf += "scale=${VIDEO_W}:${VIDEO_H},format=yuv420p"

        return buildString {
            append("-y -stream_loop -1 -i ${bgFile.absolutePath} ")
            if (ttsWav != null) append("-i ${ttsWav.absolutePath} ")
            append("-vf \"${vf.joinToString(",")}\" ")
            append("-map 0:v ")
            if (ttsWav != null) append("-map 1:a -c:a aac -b:a 192k ")
            else                append("-an ")
            append("-t ${tTotal.fmtUS()} ")
            append("-c:v h264_mediacodec -b:v 4M -movflags +faststart ")
            append(outputFile.absolutePath)
        }
    }

    // ── BGV 지속 시간 측정 ───────────────────────────────────────────────

    private fun getBgvDurationSecs(bgFile: File): Float {
        if (!bgFile.exists()) return 0f
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(bgFile.absolutePath)
            val ms = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            mmr.release()
            ms / 1000.0f
        } catch (e: Exception) {
            Log.w(TAG, "BGV 길이 측정 실패: ${e.message}")
            0f
        }
    }

    // ── TTS 청크 합성 ──────────────────────────────────────────────────────────

    private fun synthesizeChunked(
        text: String, speakerId: Int, speed: Float,
        outputFile: File, numSteps: Int,
        sceneId: String, onProgress: (String) -> Unit
    ) {
        ttsEngine.synthesize(text, speakerId, speed, outputFile, numSteps)
        return
        @Suppress("UNREACHABLE_CODE")
        val MAX_CHUNK = 9999

        val chunks = text
            .replace("\\n", "\n").replace("\\N", "\n")
            .split(Regex("(?<=[.!?。\n])"))
            .flatMap { s ->
                val t = s.trim().replace("\n", " ")
                if (t.length > MAX_CHUNK) t.chunked(MAX_CHUNK) else listOf(t)
            }
            .filter { it.isNotBlank() }

        if (chunks.size <= 1 || text.length <= MAX_CHUNK) {
            ttsEngine.synthesize(text, speakerId, speed, outputFile, numSteps)
            return
        }

        onProgress("[$sceneId] TTS 청크 분할: ${chunks.size}개")

        val chunkFiles = chunks.mapIndexed { i, chunk ->
            val f = File(outputFile.parent, "${outputFile.nameWithoutExtension}_ck$i.wav")
            ttsEngine.synthesize(chunk, speakerId, speed, f, numSteps)
            f
        }.filter { it.exists() && it.length() > 0 }

        when {
            chunkFiles.isEmpty() -> return
            chunkFiles.size == 1 -> { chunkFiles[0].renameTo(outputFile); return }
        }

        val listFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_list.txt")
        listFile.writeText(chunkFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val concatCmd = "-y -f concat -safe 0 -i ${listFile.absolutePath} -ar 44100 -ac 1 ${outputFile.absolutePath}"
        com.arthenica.ffmpegkit.FFmpegKit.execute(concatCmd)
        listFile.delete()
        chunkFiles.forEach { it.delete() }

        onProgress("[$sceneId] WAV 청크 연결 완료 (${if (outputFile.exists()) outputFile.length()/1024 else 0}KB)")
    }

    // ── 키프레임 계산 ──────────────────────────────────────────────────

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

    // ── 유틸리티 ──────────────────────────────────────────────────────

    private fun escapeDrawtext(text: String): String = text
        .replace("\\", "\\\\")
        .replace("'",  "\\'")
        .replace(":",  "\\:")
        .replace(",",  "\\,")

    private fun splitToLines(text: String, max: Int): List<String> {
        val normalized = text
            .replace("\\N", "\n")
            .replace("\\n", "\n")
        return normalized.split("\n")
            .flatMap { line ->
                if (line.length > max) line.chunked(max) else listOf(line)
            }
            .filter { it.isNotBlank() }
    }

    private fun assTime(s: Float): String {
        val tc = (s * 100).toInt().coerceAtLeast(0)
        val cs = tc % 100; val ts = tc / 100; val ss = ts % 60
        val tm = ts / 60;  val mm = tm % 60;  val hh = tm / 60
        return "%d:%02d:%02d.%02d".format(hh, mm, ss, cs)
    }

    private fun detectHighlightColor(script: String, word: String): String {
        val rise   = listOf("상승","강화","기회","확장","대운","목성","금성")
        val fall   = listOf("주의","조심","손실","토성","라후","케투","역행")
        val planet = mapOf(
            "태양" to "00B7FF","달" to "FFC8C8","화성" to "4444FF",
            "수성" to "CCFF44","목성" to "00D7FF","금성" to "CC88FF",
            "토성" to "AAAAAA","라후" to "CC0066","케투" to "006688"
        )
        return planet.entries.firstOrNull { script.contains(it.key) }?.value
            ?: if (rise.any { script.contains(it) }) "4DDBFF"
            else if (fall.any { script.contains(it) }) "6666FF"
            else "4DDBFF"
    }
}

data class CardKeyframes(
    val inStartX: Float, val inStartY: Float,
    val holdX: Float,    val holdY: Float,
    val outType: CardOutType,
    val tIn: Float, val tHold: Float, val tOut: Float
)

enum class CardOutType { TOP, BOTTOM, LEFT, RIGHT, FADE, SCALE, ROTATE_SCALE }
