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

// ============================================================
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.2
//
// [아키텍처]
//   Activity 가 SupertonicTtsEngine.init() 완료 후 주입
//   → 350MB 모델 이중 로드 없음, tts 항상 초기화 상태 보장
//   → voiceConfig 는 renderScene() 호출마다 전달 (UI 설정 실시간 반영)
// ============================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"

class AsterionRenderEngine(
    private val context: Context,
    private val ttsEngine: SupertonicTtsEngine   // Activity 에서 init() 완료 후 주입
) {
    private val subclipFiles      = mutableListOf<File>()
    private var totalDurationSecs = 0f

    /** 임시 씬 파일 저장 폴더 — OUTPUT_DIR/.temp_scenes/ */
    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    // ── 씬 렌더링 ────────────────────────────────────────────────────────────

    /**
     * 씬 한 개 렌더링
     *
     * @param row        Script_Data 한 행
     * @param videoMeta  Video_Meta (제목·워터마크 등)
     * @param voiceConfig Activity 의 buildVoiceConfig() 결과 — sid/speed/numSteps 실제 반영
     * @return 임시 MP4 파일, 실패 시 null
     */
    suspend fun renderScene(
        row: ScriptDataRow,
        videoMeta: VideoMeta,
        voiceConfig: VoiceConfig,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // 디렉토리 확보 (mkdirs 는 멱등적)
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
                // numSteps 를 synthesize 에 전달 — UI 품질 SeekBar 설정 실제 반영
                ttsEngine.synthesize(row.script, cfg.sid, cfg.speed, ttsWavFile, cfg.numSteps)
            }

            // WAV 헤더 파싱으로 씬 길이 확정 (sampleRate 자동 대응)
            val tTotal = if (ttsWavFile.exists()) ttsEngine.estimateDurationFromFile(ttsWavFile) else 3.0f
            totalDurationSecs += tTotal

            val keyframes  = calcCardKeyframes(AnimationPattern.from(row.animation), tTotal)
            val assFile    = File(sceneTempDir, "${sceneId}.ass")
            buildAssSubtitle(row, assFile, keyframes, tTotal)

            val outputFile = File(sceneTempDir, "${sceneId}.mp4")
            val cmd = buildFfmpegCmd(
                bgFile, ttsWavFile.takeIf { it.exists() }, tTotal, assFile,
                CardStyle.from(row.cardStyle), GradientPreset.from(row.gradientPreset),
                keyframes, CardExtraEffect.from(row.cardExtraEffect),
                row.bgEffectCode, BgTransition.from(row.bgTransition),
                row.bgTransitionDuration, outputFile
            )

            onProgress("[$sceneId] FFmpeg 인코딩...")
            val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            if (!rc.returnCode.isValueSuccess) {
                Log.e(TAG, "FFmpeg 실패: ${rc.logsAsString.takeLast(500)}")
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

    // ── 씬 합치기 ────────────────────────────────────────────────────────────

    /**
     * 모든 씬 MP4 를 이어붙이고 BGM + 워터마크 오버레이
     *
     * @param outputName    최종 파일명 (확장자 제외)
     * @param bgmFileName   bgm 폴더 기준 BGM 파일명 (Video_Meta.Main_BGM)
     * @param watermarkText 상단 워터마크 문자열 (Video_Meta.Top_Watermark)
     */
    suspend fun concatSubclips(
        outputName: String,
        bgmFileName: String,
        watermarkText: String = "",
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (subclipFiles.isEmpty()) { onProgress("⚠️ 클립 없음"); return@withContext null }

        val listFile = File(sceneTempDir, "concat_list.txt")
        listFile.writeText(subclipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val outputFile = File(AppConfig.OUTPUT_DIR, "$outputName.mp4")
        val bgmFile    = AppConfig.resolveBgm(bgmFileName)
        val duration   = totalDurationSecs
        // 워터마크 소멸 시각 = 총 길이 - 5초 (최소 16초 — 등장(15초) 보다 1초 이상 표시 보장)
        val wmEnd      = (duration - 5f).coerceAtLeast(16f)

        onProgress("합치기: ${subclipFiles.size}개 / 총 ${duration.toInt()}초")

        val filterParts   = mutableListOf<String>()
        var videoMapLabel = "0:v"
        var audioMapLabel = "0:a"

        // 워터마크: 15초 등장, wmEnd 초 소멸 (영상 종료 5초 전)
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

        // BGM 덕킹: 0~13초 0.35 / 13~15초 선형 감소 / 15초~ 0.10
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

    // ── 상태 초기화 ──────────────────────────────────────────────────────────

    /**
     * 렌더링 상태 초기화 — 재렌더링 시 반드시 호출
     * TTS 엔진 생명주기는 Activity 가 관리하므로 ttsEngine.release() 미호출
     */
    fun release() {
        subclipFiles.clear()
        totalDurationSecs = 0f
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }
    }

    // ── ASS 자막 생성 ────────────────────────────────────────────────────────

    private fun buildAssSubtitle(row: ScriptDataRow, out: File, kf: CardKeyframes, tTotal: Float) {
        val cx  = (kf.holdX + 430).toInt()
        val y1  = (kf.holdY + 80).toInt()
        val y2  = (kf.holdY + 185).toInt()
        val y3  = (kf.holdY + 268).toInt()
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
                appendLine("Dialogue: 0,0:00:00.00,$end,Main,,0,0,0,,${fade}{\\pos($cx,$y1)}${wrapAss(pm, 12)}")
            if (ps.isNotBlank())
                appendLine("Dialogue: 0,0:00:00.00,$end,Sub,,0,0,0,,${fade}{\\pos($cx,$y2)}${wrapAss(ps, 18)}")
            if (pd.isNotBlank())
                appendLine("Dialogue: 0,0:00:00.00,$end,Desc,,0,0,0,,${fade}{\\pos($cx,$y3)}${wrapAss(pd, 24)}")
        }, Charsets.UTF_8)
    }

    // ── FFmpeg 명령어 조립 ────────────────────────────────────────────────────

    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float, assFile: File,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {
        val fp = mutableListOf<String>()
        // BGV 루프 시 PTS 불연속 방지:
        // 1) setpts=PTS-STARTPTS  → 첫 프레임 PTS=0 기준화
        // 2) trim=duration=X      → 정확히 X초에서 스트림 절단
        // 3) setpts=PTS-STARTPTS  → trim 후 PTS를 다시 0-base로 재설정
        //    → h264_mediacodec 에 전달되는 스트림은 완전한 단조증가 PTS 보장
        fp += "[0:v]setpts=PTS-STARTPTS,trim=duration=${String.format("%.3f", tTotal)},setpts=PTS-STARTPTS[bg0]"

        val bgFx = when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> { fp += "[bg0]vignette=PI/4[bgfx]"; "[bgfx]" }
            "MOTION_BLUR" -> { fp += "[bg0]tmix=frames=3[bgfx]"; "[bgfx]" }
            "EDGE_GLOW"   -> {
                val s = bgEffect.split(":").getOrElse(1) { "0.4" }.toFloatOrNull() ?: 0.4f
                fp += "[bg0]split[bgo][bgs]"
                fp += "[bgs]unsharp=5:5:${String.format("%.2f", s * 3f)}:0:0:0[bgsh]"
                fp += "[bgo][bgsh]blend=all_mode=screen:all_opacity=0.3[bgfx]"
                "[bgfx]"
            }
            else -> "[bg0]"
        }

        val card = if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            fp += "${bgFx}drawbox=" +
                "x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340:" +
                "color=0x${String.format("%02X%02X%02X", r, g, b)}@${String.format("%.2f", cardStyle.alpha)}" +
                ":t=fill[card]"
            "[card]"
        } else bgFx

        // fontsdir: libass가 Android 시스템 폴트를 인식하도록 명시
        // 공백 제거: '[final]' 앞의 공백은 필터 패드명 파싱 오류 가능성 충제
        fp += "${card}subtitles='${assFile.absolutePath.replace("'", "\\'")}':fontsdir=/system/fonts[final]"

        return buildString {
            append("ffmpeg -y -t $tTotal -stream_loop -1 -i ${bgFile.absolutePath} ")
            if (ttsWav != null) append("-i ${ttsWav.absolutePath} ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[final]\" ")
            if (ttsWav != null) append("-map 1:a -c:a aac -b:a 192k ") else append("-an ")
            append("-c:v h264_mediacodec -b:v 8M -maxrate 8M -bufsize 16M -movflags +faststart ")
            append(outputFile.absolutePath)
        }
    }

    // ── 키프레임 계산 ────────────────────────────────────────────────────────

    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx = VIDEO_W / 2f - 430f
        val cy = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f,           VIDEO_H * .18f, VIDEO_W * .05f, VIDEO_H * .18f, CardOutType.TOP,          1f,   tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H * .18f, VIDEO_W / 2f - 430f, VIDEO_H * .18f, CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx,               -340f,          cx,            cy,             CardOutType.BOTTOM,       1f,   tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx,               cy,             cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W * .05f,  VIDEO_H * .68f, cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx,               cy,             cx,            cy,             CardOutType.SCALE,        0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f,           VIDEO_H * .18f, VIDEO_W * .05f, VIDEO_H * .18f, CardOutType.ROTATE_SCALE, 1f,   tOut, tTotal)
            else               -> CardKeyframes(cx,               cy,             cx,            cy,             CardOutType.FADE,         1f,   tOut, tTotal)
        }
    }

    // ── 유틸리티 ────────────────────────────────────────────────────────────

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
