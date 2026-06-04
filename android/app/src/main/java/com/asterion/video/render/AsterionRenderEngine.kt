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

// =================================================================
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.4
//
// [변경로그 v3.4 — 렌더링 안정화]
//   • trim 필터 제거 → -t tTotal OUTPUT 옵션으로 대체
//     trim + stream_loop 조합이 Android FFmpegKit에서 안정적이지 않아 렌더링 실패 유발
//   • fontsdir=/system/fonts 제거 → SELinux/권한 제한으로 libass 초기화 실패
//   • BgTransition.NONE 코드 정상화 (열거형에 NONE 추가)
// =================================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"

// 모션 패턴: 카드가 이동하는 패턴 (tIn 후 텍스트 등장)
private val MOTION_PATTERNS = setOf(
    AnimationPattern.A, AnimationPattern.B, AnimationPattern.C,
    AnimationPattern.E, AnimationPattern.G
)

class AsterionRenderEngine(
    private val context: Context,
    private val ttsEngine: SupertonicTtsEngine  // Activity에서 init() 완료 후 주입
) {
    private val subclipFiles      = mutableListOf<File>()
    private var totalDurationSecs = 0f

    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    // ── 인트로 렌더링 ──────────────────────────────────────────────────────

    /**
     * 인트로 15초 렌더링
     * - Video_Meta의 Intro_BGV1/2 두 배경영상을 xfade로 연결
     * - 중앙에 introText를 부드럽게 페이드 인/아웃
     * - 완성된 인트로 파일을 subclipFiles 맨 앞에 삽입
     */
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
            // 두 BGV를 xfade 연결
            // trim 없음: -t dur OUTPUT 옵션으로 제한
            val halfDur  = (dur + 1.0f) / 2.0f
            val xfadeOff = (halfDur - 1.0f).coerceAtLeast(0.5f)
            fp += "[0:v]setpts=PTS-STARTPTS[v0]"
            fp += "[1:v]setpts=PTS-STARTPTS[v1]"
            fp += "[v0][v1]xfade=transition=fade:duration=1.0:offset=${String.format("%.3f", xfadeOff)}[bgv]"
        } else {
            fp += "[0:v]setpts=PTS-STARTPTS[bgv]"
        }

        // BGV 시작/종료 페이드
        val fadeOutSt = (dur - 1.0f).coerceAtLeast(0f)
        fp += "[bgv]fade=t=in:st=0:d=1.0,fade=t=out:st=${String.format("%.3f", fadeOutSt)}:d=1.0[bgv_f]"

        // 중앙 텍스트 (drawtext)
        fp += "[bgv_f]drawtext=" +
              "fontfile=/system/fonts/NotoSansCJK-Regular.ttc:" +
              "text='${safeText}':" +
              "fontsize=64:fontcolor=white:" +
              "borderw=3:bordercolor=black@0.8:" +
              "x=(W-tw)/2:y=(H-th)/2:" +
              "enable='between(t,${String.format("%.1f", textStart)},${String.format("%.1f", textEnd)})':" +
              "alpha='if(lt(t,${String.format("%.1f", textFadeIn)})," +
              "(t-${String.format("%.1f", textStart)})/1.0," +
              "if(gt(t,${String.format("%.1f", textFadeOut)})," +
              "(${String.format("%.1f", textEnd)}-t)/1.0,1))'[final]"

        val outFile = File(sceneTempDir, "scene_intro.mp4")
        val cmd = buildString {
            append("ffmpeg -y -stream_loop -1 -i ${bgv1.absolutePath} ")
            if (hasBgv2) append("-stream_loop -1 -i ${bgv2.absolutePath} ")
            append("-f lavfi -i anullsrc=r=44100:cl=stereo ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[final]\" -map ${silentIdx}:a ")
            // -t OUTPUT 옵션으로 인트로 길이 제한 (trim 사용 안 함)
            append("-t ${String.format("%.3f", dur)} ")
            append("-c:v h264_mediacodec -b:v 8M -maxrate 8M -bufsize 16M ")
            append("-c:a aac -b:a 128k ")
            append("-movflags +faststart ${outFile.absolutePath}")
        }

        onProgress("🎬 인트로 렌더링 중 (${dur.toInt()}초)...")
        Log.i(TAG, "renderIntro cmd: $cmd")
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if (!rc.returnCode.isValueSuccess) {
            Log.e(TAG, "인트로 렌더링 실패: ${rc.logsAsString.takeLast(400)}")
            onProgress("⚠ 인트로 렌더링 실패 — 본 영상으로 진행")
            return@withContext null
        }

        subclipFiles.add(0, outFile)
        totalDurationSecs += dur
        onProgress("✅ 인트로 완료 (${dur.toInt()}초)")
        outFile
    }

    // ── 씬 렌더링 ───────────────────────────────────────────────────────

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

            val pattern   = AnimationPattern.from(row.animation)
            val keyframes = calcCardKeyframes(pattern, tTotal)
            // 모션 패턴: 카드가 hold 위치에 도달한 후(tIn초) 텍스트 등장
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f

            val assFile    = File(sceneTempDir, "${sceneId}.ass")
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
            val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            if (!rc.returnCode.isValueSuccess) {
                Log.e(TAG, "FFmpeg 실패 [$sceneId]: ${rc.logsAsString.takeLast(500)}")
                onProgress("[$sceneId] ❌ 인코딩 실패")
                return@withContext null
            }
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

    // ── 씬 합치기 ────────────────────────────────────────────────────

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
            filterParts += "[0:v]drawtext=text='$esc'" +
                ":fontfile=/system/fonts/NotoSansCJK-Regular.ttc" +
                ":fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2" +
                ":x=30:y=40:enable='between(t\\,15\\,$wmEnd)'[vout]"
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
            if (videoMapLabel.startsWith("[")) append("-c:v h264_mediacodec -b:v 8M -maxrate 8M -bufsize 16M ")
            else append("-c:v copy ")
            if (audioMapLabel.startsWith("[")) append("-c:a aac -b:a 192k ")
            else append("-c:a copy ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }

        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.forEach { it.delete() }
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }

        return@withContext if (rc.returnCode.isValueSuccess) {
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length() / 1024 / 1024}MB)")
            outputFile
        } else {
            Log.e(TAG, "concat 실패: ${rc.logsAsString.takeLast(400)}")
            onProgress("❌ concat 실패")
            null
        }
    }

    // ── 상태 초기화 ────────────────────────────────────────────────────

    fun release() {
        subclipFiles.clear()
        totalDurationSecs = 0f
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }
    }

    // ── ASS 자막 생성 ────────────────────────────────────────────────────

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
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $VIDEO_W")
            appendLine("PlayResY: $VIDEO_H")
            appendLine("")
            appendLine("[V4+ Styles]")
            appendLine("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour," +
                "OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut," +
                "ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow," +
                "Alignment,MarginL,MarginR,MarginV,Encoding")
            appendLine("Style: Main,NotoSansKR-Bold,52,&H00FFFFFF,&H004DDBFF,&H80000000,&H00000000," +
                "-1,0,0,0,100,100,0,0,1,2,0,5,30,30,80,1")
            appendLine("Style: Sub,NotoSansKR-Regular,38,&H00CCCCCC,&H00000000,&H80000000,&H00000000," +
                "0,0,0,0,100,100,0,0,1,1,0,5,30,30,80,1")
            appendLine("Style: Desc,NotoSansKR-Regular,32,&H00AAAAAA,&H00000000,&H80000000,&H00000000," +
                "0,0,0,0,100,100,0,0,1,1,0,5,30,30,80,1")
            appendLine("")
            appendLine("[Events]")
            appendLine("Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text")

            val pm = prep(mainTxt)
            val ps = prep(row.cardSub)
            val pd = prep(row.cardDesc)
            if (pm.isNotBlank())
                appendLine("Dialogue: 0,$dialogueStart,$end,Main,,0,0,0,,${fade}{\\pos($cx,$y1)}${wrapAss(pm, 12)}")
            if (ps.isNotBlank())
                appendLine("Dialogue: 0,$dialogueStart,$end,Sub,,0,0,0,,${fade}{\\pos($cx,$y2)}${wrapAss(ps, 18)}")
            if (pd.isNotBlank())
                appendLine("Dialogue: 0,$dialogueStart,$end,Desc,,0,0,0,,${fade}{\\pos($cx,$y3)}${wrapAss(pd, 24)}")
        }, Charsets.UTF_8)
    }

    // ── FFmpeg 명령어 조립 ───────────────────────────────────────────────

    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float, assFile: File,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        pattern: AnimationPattern,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {

        val needsBlackBg = bgTransition in setOf(
            BgTransition.SLIDE_LEFT, BgTransition.SLIDE_UP, BgTransition.WIPE_RIGHT
        )
        val blackBgIdx = if (needsBlackBg) 1 else -1
        val audioIdx   = when {
            ttsWav == null -> -1
            needsBlackBg   -> 2
            else           -> 1
        }
        val effDur = transitionDur.coerceIn(0.3f, (tTotal * 0.45f).coerceAtLeast(0.3f))

        val fp = mutableListOf<String>()

        // ① BGV: PTS 리셋만 수행 (trim 제거)
        // 실제 요소 제한은 명령 내 -t OUTPUT 옵션으로 명시
        fp += "[0:v]setpts=PTS-STARTPTS[bg0]"

        // ② BG 전환 효과
        val bgAfterTrans: String = when (bgTransition) {
            BgTransition.NONE -> "[bg0]"

            BgTransition.FADE -> {
                val fadeOutSt = (tTotal - effDur).coerceAtLeast(0f)
                fp += "[bg0]fade=t=in:st=0:d=${String.format("%.3f", effDur)}," +
                      "fade=t=out:st=${String.format("%.3f", fadeOutSt)}:d=${String.format("%.3f", effDur)}[bg_t]"
                "[bg_t]"
            }

            BgTransition.SLIDE_LEFT, BgTransition.WIPE_RIGHT -> {
                fp += "[$blackBgIdx:v]setpts=PTS-STARTPTS[blackbg]"
                fp += "[blackbg][bg0]overlay=" +
                      "x='max(0-W,W*(t/${String.format("%.3f", effDur)}-1))':y=0:format=auto[bg_t]"
                "[bg_t]"
            }

            BgTransition.SLIDE_UP -> {
                fp += "[$blackBgIdx:v]setpts=PTS-STARTPTS[blackbg]"
                fp += "[blackbg][bg0]overlay=" +
                      "x=0:y='max(0-H,H*(1-t/${String.format("%.3f", effDur)}))':format=auto[bg_t]"
                "[bg_t]"
            }

            else -> {  // ZOOM_IN, ZOOM_OUT, BLUR_FADE → 페이드로 근사
                fp += "[bg0]fade=t=in:st=0:d=${String.format("%.3f", effDur)}[bg_t]"
                "[bg_t]"
            }
        }

        // ③ BG 조정 효과
        val bgFx: String = when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> { fp += "${bgAfterTrans}vignette=PI/4[bgfx]"; "[bgfx]" }
            "MOTION_BLUR" -> { fp += "${bgAfterTrans}tmix=frames=3[bgfx]"; "[bgfx]" }
            "EDGE_GLOW"   -> {
                val s = bgEffect.split(":").getOrElse(1) { "0.4" }.toFloatOrNull() ?: 0.4f
                fp += "${bgAfterTrans}split[bgo][bgs]"
                fp += "[bgs]unsharp=5:5:${String.format("%.2f", s * 3f)}:0:0:0[bgsh]"
                fp += "[bgo][bgsh]blend=all_mode=screen:all_opacity=0.3[bgfx]"
                "[bgfx]"
            }
            else -> bgAfterTrans
        }

        // ④ 카드 박스
        val card: String = if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            fp += "${bgFx}drawbox=" +
                  "x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340:" +
                  "color=0x${String.format("%02X%02X%02X", r, g, b)}@${String.format("%.2f", cardStyle.alpha)}" +
                  ":t=fill[card]"
            "[card]"
        } else bgFx

        // ⑤ ASS 자막 (fontsdir 제거 — Android SELinux에서 /system/fonts 접근 차단 시 libass 실패)
        fp += "${card}subtitles='${assFile.absolutePath.replace("'", "\\'")}' [final]"

        // ⑥ FFmpeg 명령 조립
        return buildString {
            append("ffmpeg -y -stream_loop -1 -i ${bgFile.absolutePath} ")
            if (needsBlackBg) {
                // SLIDE 전환용 블랙 배경 (duration 없음: -t OUTPUT에서 제한)
                append("-f lavfi -i color=c=black:size=${VIDEO_W}x${VIDEO_H}:rate=30 ")
            }
            if (ttsWav != null) append("-i ${ttsWav.absolutePath} ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[final]\" ")
            if (audioIdx >= 0) append("-map ${audioIdx}:a -c:a aac -b:a 192k ")
            else               append("-an ")
            // -t OUTPUT 옵션: 입력 측에 두지 않고 출력 인코딩 시간을 제한
            // (trim 필터 대체 — stream_loop + trim 조합이 Android FFmpegKit에서 불안정)
            append("-t ${String.format("%.3f", tTotal)} ")
            append("-c:v h264_mediacodec -b:v 8M -maxrate 8M -bufsize 16M -movflags +faststart ")
            append(outputFile.absolutePath)
        }
    }

    // ── 키프레임 계산 ───────────────────────────────────────────────────

    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx = VIDEO_W / 2f - 430f
        val cy = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f,             VIDEO_H * .18f, VIDEO_W * .05f, VIDEO_H * .18f, CardOutType.TOP,          1f,   tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H * .18f, VIDEO_W / 2f - 430f, VIDEO_H * .18f, CardOutType.BOTTOM, 1f,   tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx,               -340f,          cx,            cy,             CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx,                cy,            cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W * .05f,   VIDEO_H * .68f, cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx,                cy,            cx,            cy,             CardOutType.SCALE,        0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f,             VIDEO_H * .18f, VIDEO_W * .05f, VIDEO_H * .18f, CardOutType.ROTATE_SCALE, 1f,  tOut, tTotal)
            else               -> CardKeyframes(cx,                cy,            cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
        }
    }

    // ── 유틸리티 ───────────────────────────────────────────────────────

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
        val rise   = listOf("상승", "강화", "기회", "확장", "대운", "목성", "금성")
        val fall   = listOf("주의", "조심", "손실", "토성", "라후", "케투", "역행")
        val planet = mapOf(
            "태양" to "00B7FF", "달"   to "FFC8C8", "화성" to "4444FF",
            "수성" to "CCFF44", "목성" to "00D7FF", "금성" to "CC88FF",
            "토성" to "AAAAAA", "라후" to "CC0066", "케투" to "006688"
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
