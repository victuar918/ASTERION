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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.8
//
// [변경로그 v3.8 — 카드 텍스트 복원]
//   subtitles(libass) → drawtext 전환
//   • ASS 파일 불필요 — buildFfmpegCmd 내에서 직접 생성
//   • Main/Sub/Desc 세 레이어, 페이드 인/아웃, 위치 좌표
//   • fontPath lazy: SELinux 접근 가능한 폰트만 선택
//   • buildFfmpegCmd 시그니처: assFile 제거, row+textStartSecs 추가
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

    /**
     * drawtext 용 폰트 절대경로
     * SELinux 접근 가능한 폰트를 lazy로 충돌
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

    /** drawtext 온션 필드용 fontfile 인수 (:없음) */
    private val fontArg: String by lazy {
        if (fontPath.isNotEmpty()) ":fontfile='${fontPath}'" else ""
    }

    // ── 인트로 렌더링 ──────────────────────────────────────────────────

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

        val dur       = videoMeta.introDurationSecs.coerceAtLeast(5f)
        val bgv1      = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2   = videoMeta.introBgv2.isNotBlank()
        val bgv2      = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else bgv1
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
        val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
        fp += "[bgv_f]drawtext=${fontOpt}text='${safeText}':" +
              "fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:" +
              "x=(W-tw)/2:y=(H-th)/2:" +
              "enable='between(t,${textStart.fmtUS(1)},${textEnd.fmtUS(1)})':" +
              "alpha='if(lt(t,${textFadeIn.fmtUS(1)}),(t-${textStart.fmtUS(1)})/1.0," +
              "if(gt(t,${textFadeOut.fmtUS(1)}),(${textEnd.fmtUS(1)}-t)/1.0,1))' [pre]"
        fp += "[pre]format=yuv420p[final]"

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
        val ok = outFile.exists() && outFile.length() > 0
        if (!ok) {
            Log.e(TAG, "인트로 실패: ${rc.logsAsString.takeLast(300)}")
            onProgress("⚠ 인트로 실패 — 본 영상으로 진행")
            return@withContext null
        }
        subclipFiles.add(0, outFile)
        totalDurationSecs += dur
        onProgress("✅ 인트로 완료 (${dur.toInt()}초, ${outFile.length()/1024}KB)")
        outFile
    }

    // ── 씬 렌더링 ────────────────────────────────────────────────────

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

            val tTotal        = if (ttsWavFile.exists()) ttsEngine.estimateDurationFromFile(ttsWavFile) else 3.0f
            totalDurationSecs += tTotal

            val pattern       = AnimationPattern.from(row.animation)
            val keyframes     = calcCardKeyframes(pattern, tTotal)
            // 모션 패턴: 카드 hold 위치 도상 후(tIn초) 텍스트 등장
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f

            val outputFile = File(sceneTempDir, "${sceneId}.mp4")
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
            val esc = escapeDrawtext(watermarkText)
            val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
            // 워터마크: 15초(인트로 종료) ~ wmEnd(영상 종료 5초 전)
            filterParts += "[0:v]drawtext=${fontOpt}text='$esc':" +
                "fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:" +
                "x=30:y=40:enable='between(t\\,15\\,${wmEnd.fmtUS(1)})' [wm_pre]"
            filterParts += "[wm_pre]format=yuv420p[vout]"
            videoMapLabel = "[vout]"
        }

        if (bgmFile != null) {
            filterParts += "[0:a]volume=1.0[tts]"
            filterParts += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,13)\\,0.35\\,if(lt(t\\,15)\\,0.35+(t-13)*(-0.125)\\,0.10))':eval=frame[bgm]"
            filterParts += "[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3[aout]"
            audioMapLabel = "[aout]"
        }

        val cmd = buildString {
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            // BGM: stream_loop -1 로 영상 종료 시까지 자동 반복
            if (bgmFile != null) append("-stream_loop -1 -i ${bgmFile.absolutePath} ")
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

    // ── FFmpeg 명령어 조립 ──────────────────────────────────────────────

    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        pattern: AnimationPattern, textStartSecs: Float, row: ScriptDataRow,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {
        val effDur = transitionDur.coerceIn(0.3f, (tTotal * 0.45f).coerceAtLeast(0.3f))

        // ── -vf 체인: filter_complex + stream_loop 호환 문제 회피 ──
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
            else -> vf += "fade=t=in:st=0:d=${effDur.fmtUS()}"  // SLIDE/ZOOM → fade 근사
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
                val fi = (ts + 0.8f).fmtUS(2)             // fade-in 완료
                val fo = (tTotal - 0.6f).coerceAtLeast(ts + 1f).fmtUS(2)  // fade-out 시작
                val tt = tTotal.fmtUS(2)
                val tsS = ts.fmtUS(2)
                // alpha: ts~fi 페이드인, fi~fo 보이는 중, fo~tt 페이드아웃
                val alphaExpr = "if(lt(t,${fi}),(t-${tsS})/0.8,if(gt(t,${fo}),(${tt}-t)/0.6,1))"
                val enableExpr = "between(t\\,${tsS}\\,${tt})"
                val fontOpt = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""

                val cx = kf.holdX.toInt()          // 카드 좌욱션
                val y1 = (kf.holdY + 80).toInt()   // Main y
                val y2 = (kf.holdY + 185).toInt()  // Sub y
                val y3 = (kf.holdY + 268).toInt()  // Desc y
                // 카드 박스 중앙 x: holdX + 430 (폭 860 절반)
                val cardCenterX = kf.holdX.toInt() + 430
                val xExpr = "${cardCenterX}-tw/2"  // 카드 박스 중앙 정렬

                // Main (흰색, 큰 글자, 외괭선)
                if (pm.isNotBlank()) {
                    val escaped = escapeDrawtext(wrapDrawtext(pm, 12))
                    vf += "drawtext=${fontOpt}text='${escaped}':fontsize=52:fontcolor=white:" +
                          "borderw=2:bordercolor=black@0.8:" +
                          "x=${xExpr}:y=${y1}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
                // Sub (회색, 중간 글자)
                if (ps.isNotBlank()) {
                    val escaped = escapeDrawtext(wrapDrawtext(ps, 18))
                    vf += "drawtext=${fontOpt}text='${escaped}':fontsize=38:fontcolor=0xCCCCCC:" +
                          "x=${xExpr}:y=${y2}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
                // Desc (연한 회색, 작은 글자)
                if (pd.isNotBlank()) {
                    val escaped = escapeDrawtext(wrapDrawtext(pd, 24))
                    vf += "drawtext=${fontOpt}text='${escaped}':fontsize=32:fontcolor=0xAAAAAA:" +
                          "x=${xExpr}:y=${y3}:" +
                          "alpha='${alphaExpr}':enable='${enableExpr}'"
                }
            }
        }

        // ⑥ BGV 루프 크로스페이드
        // BGV가 씬보다 짧을 때만 루프 포인트마다 0.5초 fade 삽입
        val bgvDur = getBgvDurationSecs(bgFile)
        val xFade = 0.5f
        if (bgvDur > 1.0f && tTotal > bgvDur + 1.0f) {
            var lp = bgvDur
            while (lp + xFade < tTotal) {
                val fo = (lp - xFade).coerceAtLeast(0f)
                // 기존 FADE 전환 페이드와 겹치면 스킵 (이중 취존 방지)
                if (fo > effDur + 0.1f && lp < tTotal - effDur - xFade) {
                    vf += "fade=t=out:st=${fo.fmtUS()}:d=${xFade.fmtUS()}"
                    vf += "fade=t=in:st=${lp.fmtUS()}:d=${xFade.fmtUS()}"
                }
                lp += bgvDur
            }
        }

        // ⑦ 픽셀 포맷 정규화
        vf += "scale=${VIDEO_W}:${VIDEO_H},format=yuv420p"

        // ── FFmpeg 명령 조립 ──
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

    /**
     * MediaMetadataRetriever로 BGV 파일 실제 길이(초) 측정
     * 파일 없음 / 측정 실패 시 0f 반환 → 그닥 루프 크로스페이드 스킵
     */
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

    /**
     * drawtext 텍스트 이스케이프
     * -vf 컨텍스트에서 콤론(개행)도 이스케이프 필요
     */
    private fun escapeDrawtext(text: String): String = text
        .replace("\\", "\\\\")
        .replace("'",  "\\'")
        .replace(":",  "\\:")
        .replace(",",  "\\,")
        .replace("\n", "\\n")
        .replace("\\N", "\\n")  // ASS 개행 태그 변환

    /**
     * drawtext 용 줄 바꿈
     * max 글자마다 \n 삽입 (화면 폭 초과 방지)
     */
    private fun wrapDrawtext(text: String, max: Int): String {
        val clean = text.replace("\\N", "\n").replace("\\n", "\n")
        if (clean.length <= max) return clean
        return clean.chunked(max).joinToString("\n")
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
