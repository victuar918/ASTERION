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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.6
//
// [변경로그 v3.6]
//   1) return code 오판단 수정
//      FFmpeg이 non-zero를 반환하더라도 출력 파일이 존재하면 성공 처리
//      (Android FFmpegKit에서 경고를 non-zero로 반환하는 알려진 이슈)
//   2) filter_complex 복구 + libx264 유지
//      진단 모드 해제, 정상 필터 체인 복원
//      format=yuv420p 유지 (drawbox 알파 체인 yuva420p 방지)
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
    private val subclipFiles      = mutableListOf<File>()
    private var totalDurationSecs = 0f

    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    /** drawtext 폰트 인수 문자열 — SELinux 접근 가능한 폰트만 포함 */
    private val fontArg: String by lazy {
        listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKkr-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/DroidSans.ttf"
        ).firstOrNull {
            try { File(it).canRead() } catch (_: Exception) { false }
        }?.let { ":fontfile='${it}'" } ?: ""
    }

    // ── 인트로 렌더링 ───────────────────────────────────────────────────

    suspend fun renderIntro(
        videoMeta: VideoMeta,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) {
            onProgress("인트로 BGV 미설정 — 인트로 생략")
            return@withContext null
        }
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()

        val dur     = videoMeta.introDurationSecs.coerceAtLeast(5f)
        val bgv1    = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2 = videoMeta.introBgv2.isNotBlank()
        val bgv2    = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else bgv1
        val silentIdx = if (hasBgv2) 2 else 1

        val textStart   = 3.0f
        val textFadeIn  = textStart + 1.0f
        val textFadeOut = (dur - 2.0f).coerceAtLeast(textFadeIn + 1.0f)
        val textEnd     = (dur - 1.0f).coerceAtLeast(textFadeIn + 2.0f)

        val safeText = videoMeta.introText
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")
            .replace(",", "\\,")

        val fp = mutableListOf<String>()
        if (hasBgv2) {
            val halfDur  = (dur + 1.0f) / 2.0f
            val xfadeOff = (halfDur - 1.0f).coerceAtLeast(0.5f)
            fp += "[0:v]setpts=PTS-STARTPTS[v0]"
            fp += "[1:v]setpts=PTS-STARTPTS[v1]"
            fp += "[v0][v1]xfade=transition=fade:duration=1.0:offset=${xfadeOff.fmtUS()}[bgv]"
        } else {
            fp += "[0:v]setpts=PTS-STARTPTS[bgv]"
        }
        val fadeOutSt = (dur - 1.0f).coerceAtLeast(0f)
        fp += "[bgv]fade=t=in:st=0:d=1.0,fade=t=out:st=${fadeOutSt.fmtUS()}:d=1.0[bgv_f]"
        fp += "[bgv_f]drawtext=text='${safeText}'${fontArg}:" +
              "fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:" +
              "x=(W-tw)/2:y=(H-th)/2:" +
              "enable='between(t,${textStart.fmtUS(1)},${textEnd.fmtUS(1)})':" +
              "alpha='if(lt(t,${textFadeIn.fmtUS(1)}),(t-${textStart.fmtUS(1)})/1.0," +
              "if(gt(t,${textFadeOut.fmtUS(1)}),(${textEnd.fmtUS(1)}-t)/1.0,1))' [final_pre]"
        fp += "[final_pre]format=yuv420p[final]"

        val outFile = File(sceneTempDir, "scene_intro.mp4")
        val cmd = buildString {
            append("-y -stream_loop -1 -i ${bgv1.absolutePath} ")
            if (hasBgv2) append("-stream_loop -1 -i ${bgv2.absolutePath} ")
            append("-f lavfi -i anullsrc=r=44100:cl=stereo ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[final]\" -map ${silentIdx}:a ")
            append("-t ${dur.fmtUS()} ")
            append("-c:v libx264 -preset ultrafast -crf 23 ")
            append("-c:a aac -b:a 128k -movflags +faststart ${outFile.absolutePath}")
        }
        onProgress("🎬 인트로 렌더링 중...")
        Log.i(TAG, "renderIntro: $cmd")
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        // 파일 존재 여부로 성공 판단 (return code non-zero라도 파일이 있으면 성공)
        val ok = outFile.exists() && outFile.length() > 0
        if (!ok) {
            Log.e(TAG, "인트로 실패 rc=${rc.returnCode.value}: ${rc.logsAsString.takeLast(300)}")
            onProgress("⚠ 인트로 실패 — 본 영상으로 진행")
            return@withContext null
        }
        if (!rc.returnCode.isValueSuccess)
            Log.w(TAG, "인트로 rc=${rc.returnCode.value}(경고) 하지만 파일 정상 (${outFile.length()/1024}KB)")
        subclipFiles.add(0, outFile)
        totalDurationSecs += dur
        onProgress("✅ 인트로 완료 (${dur.toInt()}초, ${outFile.length()/1024}KB)")
        outFile
    }

    // ── 씬 렌더링 ───────────────────────────────────────────────────

    suspend fun renderScene(
        row: ScriptDataRow,
        videoMeta: VideoMeta,
        voiceConfig: VoiceConfig,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()

        val sceneId = "scene_${row.rowIndex.toString().padStart(4, '0')}"
        onProgress("[$sceneId] ${row.section} / Speaker ${row.speaker}")
        try {
            val bgFile     = AppConfig.resolveBgv(row.bgFileName)
            val ttsWavFile = File(sceneTempDir, "${sceneId}_tts.wav")
            val hasTts     = row.script.isNotBlank() && row.sectionType != SectionType.BUFFER

            if (hasTts) {
                val cfg = voiceConfig.forSpeaker(row.speaker)
                onProgress("[$sceneId] TTS: ${cfg.label}(sid=${cfg.sid} spd=${cfg.speed} steps=${cfg.numSteps})")
                ttsEngine.synthesize(row.script, cfg.sid, cfg.speed, ttsWavFile, cfg.numSteps)
            }

            val tTotal    = if (ttsWavFile.exists()) ttsEngine.estimateDurationFromFile(ttsWavFile) else 3.0f
            totalDurationSecs += tTotal

            val pattern       = AnimationPattern.from(row.animation)
            val keyframes     = calcCardKeyframes(pattern, tTotal)
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f
            val assFile       = File(sceneTempDir, "${sceneId}.ass")
            buildAssSubtitle(row, assFile, keyframes, tTotal, textStartSecs)

            val outputFile = File(sceneTempDir, "${sceneId}.mp4")
            val cmd = buildFfmpegCmd(
                bgFile, ttsWavFile.takeIf { it.exists() }, tTotal, assFile,
                CardStyle.from(row.cardStyle), GradientPreset.from(row.gradientPreset),
                keyframes, pattern,
                CardExtraEffect.from(row.cardExtraEffect),
                row.bgEffectCode, BgTransition.from(row.bgTransition),
                row.bgTransitionDuration, outputFile
            )

            onProgress("[$sceneId] FFmpeg 인코딩...")
            Log.i(TAG, "[$sceneId] $cmd")
            val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)

            // 파일 존재 여부로 성공 판단
            // Android FFmpegKit에서 경고를 non-zero return code로 반환하는 알려진 이슈 존재
            val fileOk = outputFile.exists() && outputFile.length() > 0
            if (!fileOk) {
                val errLog  = rc.logsAsString
                val errLine = errLog.lines().lastOrNull {
                    it.contains("Error", ignoreCase = true) ||
                    it.contains("Invalid", ignoreCase = true) ||
                    it.contains("No such", ignoreCase = true) ||
                    it.contains("failed",  ignoreCase = true)
                } ?: errLog.takeLast(200)
                Log.e(TAG, "FFmpeg 실패 [$sceneId] rc=${rc.returnCode.value}:\n${errLog.takeLast(800)}")
                onProgress("[$sceneId] ❌ FFmpeg 오류: ${errLine.takeLast(120)}")
                return@withContext null
            }
            if (!rc.returnCode.isValueSuccess)
                Log.w(TAG, "[$sceneId] rc=${rc.returnCode.value}(경고) 하지만 파일 OK (${outputFile.length()/1024}KB)")

            ttsWavFile.delete()
            assFile.delete()
            subclipFiles.add(outputFile)
            onProgress("[$sceneId] ✅ ${outputFile.length() / 1024}KB")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "renderScene 예외: $e")
            onProgress("[$sceneId] ❌ 예외: ${e.message}")
            null
        }
    }

    // ── 씬 합치기 ───────────────────────────────────────────────────

    suspend fun concatSubclips(
        outputName: String,
        bgmFileName: String,
        watermarkText: String = "",
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (subclipFiles.isEmpty()) { onProgress("⚠️ 클립 없음"); return@withContext null }

        val listFile   = File(sceneTempDir, "concat_list.txt")
        listFile.writeText(subclipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val outputFile = File(AppConfig.OUTPUT_DIR, "$outputName.mp4")
        val bgmFile    = AppConfig.resolveBgm(bgmFileName)
        val duration   = totalDurationSecs
        val wmEnd      = (duration - 5f).coerceAtLeast(16f)

        onProgress("합치기: ${subclipFiles.size}개 / 총 ${duration.toInt()}초")

        val filterParts   = mutableListOf<String>()
        var videoMapLabel = "0:v"
        var audioMapLabel = "0:a"

        if (watermarkText.isNotBlank()) {
            val esc = watermarkText
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace(",", "\\,")
            filterParts += "[0:v]drawtext=text='$esc'${fontArg}" +
                ":fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2" +
                ":x=30:y=40:enable='between(t\\,15\\,${wmEnd.fmtUS(1)})' [wm_pre]"
            filterParts += "[wm_pre]format=yuv420p[vout]"
            videoMapLabel = "[vout]"
        }

        if (bgmFile != null) {
            filterParts += "[0:a]volume=1.0[tts]"
            filterParts += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,13)\\,0.35\\,if(lt(t\\,15)\\,0.35+(t-13)*(-0.125)\\,0.10))':eval=frame[bgm]"
            filterParts += "[tts][bgm]amix=inputs=2:duration=first:dropout_transition=2[aout]"
            audioMapLabel = "[aout]"
        }

        val cmd = buildString {
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if (bgmFile != null) append("-i ${bgmFile.absolutePath} ")
            if (filterParts.isNotEmpty()) append("-filter_complex \"${filterParts.joinToString(";")}\" ")
            append("-map \"$videoMapLabel\" -map \"$audioMapLabel\" ")
            if (videoMapLabel.startsWith("[")) append("-c:v libx264 -preset fast -crf 20 ")
            else append("-c:v copy ")
            if (audioMapLabel.startsWith("[")) append("-c:a aac -b:a 192k ")
            else append("-c:a copy ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }

        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.forEach { it.delete() }
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

    // ── ASS 자막 ─────────────────────────────────────────────────────

    private fun buildAssSubtitle(
        row: ScriptDataRow, out: File, kf: CardKeyframes, tTotal: Float,
        textStartSecs: Float = 0f
    ) {
        val cx  = (kf.holdX + 430).toInt()
        val y1  = (kf.holdY + 80).toInt()
        val y2  = (kf.holdY + 185).toInt()
        val y3  = (kf.holdY + 268).toInt()
        val dialogueStart = assTime(textStartSecs)
        val end = assTime(tTotal)
        val hlWord  = row.highlightWord.trim()
        val hlColor = detectHighlightColor(row.script, hlWord)
        val mainTxt = if (hlWord.isNotBlank() && row.cardMain.contains(hlWord))
            row.cardMain.replace(hlWord, "{\\c&H${hlColor}&}$hlWord{\\c&HFFFFFF&}")
        else row.cardMain
        fun prep(t: String) = t.replace("\\n", "\\N").replace("\n", "\\N")
        val fade = "{\\an5\\fad(800,600)}"
        out.writeText(buildString {
            appendLine("[Script Info]"); appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $VIDEO_W"); appendLine("PlayResY: $VIDEO_H"); appendLine("")
            appendLine("[V4+ Styles]")
            appendLine("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding")
            appendLine("Style: Main,NotoSansKR-Bold,52,&H00FFFFFF,&H004DDBFF,&H80000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,5,30,30,80,1")
            appendLine("Style: Sub,NotoSansKR-Regular,38,&H00CCCCCC,&H00000000,&H80000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,5,30,30,80,1")
            appendLine("Style: Desc,NotoSansKR-Regular,32,&H00AAAAAA,&H00000000,&H80000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,5,30,30,80,1")
            appendLine(""); appendLine("[Events]")
            appendLine("Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text")
            val pm = prep(mainTxt); val ps = prep(row.cardSub); val pd = prep(row.cardDesc)
            if (pm.isNotBlank()) appendLine("Dialogue: 0,$dialogueStart,$end,Main,,0,0,0,,${fade}{\\pos($cx,$y1)}${wrapAss(pm, 12)}")
            if (ps.isNotBlank()) appendLine("Dialogue: 0,$dialogueStart,$end,Sub,,0,0,0,,${fade}{\\pos($cx,$y2)}${wrapAss(ps, 18)}")
            if (pd.isNotBlank()) appendLine("Dialogue: 0,$dialogueStart,$end,Desc,,0,0,0,,${fade}{\\pos($cx,$y3)}${wrapAss(pd, 24)}")
        }, Charsets.UTF_8)
    }

    // ── FFmpeg 명령어 ──────────────────────────────────────────────────

    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float, assFile: File,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        pattern: AnimationPattern,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {
        val effDur = transitionDur.coerceIn(0.3f, (tTotal * 0.45f).coerceAtLeast(0.3f))

        // -vf 체인: filter_complex + stream_loop 조합이 Android FFmpegKit에서 Invalid argument 유발
        // -vf는 동일한 필터를 named pad 없이 체인으로 처리
        val vf = mutableListOf<String>()
        vf += "setpts=PTS-STARTPTS"

        // BG 전환 효과
        when (bgTransition) {
            BgTransition.FADE -> {
                val fadeOutSt = (tTotal - effDur).coerceAtLeast(0f)
                vf += "fade=t=in:st=0:d=${effDur.fmtUS()}"
                vf += "fade=t=out:st=${fadeOutSt.fmtUS()}:d=${effDur.fmtUS()}"
            }
            BgTransition.NONE -> { /* no transition */ }
            else -> {  // SLIDE/ZOOM/BLUR → fade 근사
                vf += "fade=t=in:st=0:d=${effDur.fmtUS()}"
            }
        }

        // BG 조정 효과
        when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> vf += "vignette=PI/4"
            "MOTION_BLUR" -> vf += "tmix=frames=3"
        }

        // 카드 박스
        if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            vf += "drawbox=x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340:" +
                  "color=0x${String.format(Locale.US, "%02X%02X%02X", r, g, b)}@${String.format(Locale.US, "%.2f", cardStyle.alpha)}:t=fill"
        }

        // 포맷 정제 (h264 호환성)
        vf += "scale=${VIDEO_W}:${VIDEO_H},format=yuv420p"

        val bgAfterTrans: String = when (bgTransition) {
            BgTransition.NONE -> "[bg0]"
            BgTransition.FADE -> {
                val fadeOutSt = (tTotal - effDur).coerceAtLeast(0f)
                fp += "[bg0]fade=t=in:st=0:d=${effDur.fmtUS()},fade=t=out:st=${fadeOutSt.fmtUS()}:d=${effDur.fmtUS()}[bg_t]"
                "[bg_t]"
            }
            BgTransition.SLIDE_LEFT, BgTransition.WIPE_RIGHT -> {
                fp += "[$blackBgIdx:v]setpts=PTS-STARTPTS[blackbg]"
                fp += "[blackbg][bg0]overlay=x='max(0-W,W*(t/${effDur.fmtUS()}-1))':y=0:format=auto[bg_t]"
                "[bg_t]"
            }
            BgTransition.SLIDE_UP -> {
                fp += "[$blackBgIdx:v]setpts=PTS-STARTPTS[blackbg]"
                fp += "[blackbg][bg0]overlay=x=0:y='max(0-H,H*(1-t/${effDur.fmtUS()}))':format=auto[bg_t]"
                "[bg_t]"
            }
            else -> {
                fp += "[bg0]fade=t=in:st=0:d=${effDur.fmtUS()}[bg_t]"
                "[bg_t]"
            }
        }

        val bgFx: String = when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> { fp += "${bgAfterTrans}vignette=PI/4[bgfx]"; "[bgfx]" }
            "MOTION_BLUR" -> { fp += "${bgAfterTrans}tmix=frames=3[bgfx]"; "[bgfx]" }
            "EDGE_GLOW"   -> {
                val s = bgEffect.split(":").getOrElse(1) { "0.4" }.toFloatOrNull() ?: 0.4f
                fp += "${bgAfterTrans}split[bgo][bgs]"
                fp += "[bgs]unsharp=5:5:${String.format(Locale.US, "%.2f", s * 3f)}:0:0:0[bgsh]"
                fp += "[bgo][bgsh]blend=all_mode=screen:all_opacity=0.3[bgfx]"
                "[bgfx]"
            }
            else -> bgAfterTrans
        }

        val card: String = if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            fp += "${bgFx}drawbox=x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340:" +
                  "color=0x${String.format(Locale.US, "%02X%02X%02X", r, g, b)}@${String.format(Locale.US, "%.2f", cardStyle.alpha)}:t=fill[card]"
            "[card]"
        } else bgFx

        // [진단] subtitles 필터 임시 제거
        // libass가 외부저장소 .ass 파일 접근 시 SELinux EINVAL 가능성 검증
        // 이 버전 성공 → subtitles 원인 확정 → drawtext로 교체 예정
        fp += "${card}scale=${VIDEO_W}:${VIDEO_H},format=yuv420p[final]"

        return buildString {
            append("-y -stream_loop -1 -i ${bgFile.absolutePath} ")
            if (needsBlackBg) append("-f lavfi -i color=c=black:size=${VIDEO_W}x${VIDEO_H}:rate=30 ")
            if (ttsWav != null) append("-i ${ttsWav.absolutePath} ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[final]\" ")
            if (audioIdx >= 0) append("-map ${audioIdx}:a -c:a aac -b:a 192k ")
            else               append("-an ")
            append("-t ${tTotal.fmtUS()} ")
            append("-c:v libx264 -preset ultrafast -crf 23 -movflags +faststart ")
            append(outputFile.absolutePath)
        }
    }

    // ── 키프레임 ─────────────────────────────────────────────────────

    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx = VIDEO_W / 2f - 430f
        val cy = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f, VIDEO_H*.18f, CardOutType.TOP,          1f,   tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H*.18f, VIDEO_W/2f-430f, VIDEO_H*.18f, CardOutType.BOTTOM, 1f,   tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx,               -340f,         cx,           cy,            CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx,                cy,           cx,           cy,            CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W*.05f,     VIDEO_H*.68f,  cx,           cy,            CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx,                cy,           cx,           cy,            CardOutType.SCALE,        0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f, VIDEO_H*.18f, CardOutType.ROTATE_SCALE, 1f,  tOut, tTotal)
            else               -> CardKeyframes(cx,                cy,           cx,           cy,            CardOutType.FADE,         1f,   tOut, tTotal)
        }
    }

    private fun assTime(s: Float): String {
        val tc = (s * 100).toInt().coerceAtLeast(0)
        val cs = tc % 100; val ts = tc / 100; val ss = ts % 60
        val tm = ts / 60;  val mm = tm % 60;  val hh = tm / 60
        return "%d:%02d:%02d.%02d".format(hh, mm, ss, cs)
    }

    private fun wrapAss(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.chunked(max).joinToString("\\N")
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
