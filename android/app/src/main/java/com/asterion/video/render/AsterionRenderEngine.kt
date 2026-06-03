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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.1
// [버그①] ASS 헤더 \\n 리터럴 → 실제 줄바꿈 수정 (카드 불출력 원인)
// [버그②] concat 리스트 "\\n" → "\n" 수정
// [버그③] 카드 텍스트 위치 → 박스 내부 중앙 정렬
// [버그④] Pattern C → 화면 정중앙 배치
// [버그⑤] 워터마크 → concatSubclips 단계로 이전 (15초 등장)
// [버그⑥] BGM 덕킹 구현 (13→15초 0.35→0.10)
// [버그⑦] 임시파일 OUTPUT/.temp_scenes/ 격리 + concat 후 삭제
// [버그⑧] 9화자 VoiceConfig 반영
// [버그⑨] concatSubclips 시그니처 수정
// [버그⑩] BG 트림 입력측 -t 옵션으로 변경
// [신규] EDGE_GLOW 효과 / playPreviewDirect (AudioTrack 스트리밍)
// ============================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"

class AsterionRenderEngine(
    private val context: Context,
    private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT
) {
    private val ttsEngine      = SupertonicTtsEngine(context)
    private val subclipFiles   = mutableListOf<File>()
    private var totalDurationSecs: Float = 0f

    private val sceneTempDir: File by lazy {
        File(AppConfig.OUTPUT_DIR, TEMP_SUBDIR).also { it.mkdirs() }
    }

    suspend fun init(onProgress: (String) -> Unit = {}) {
        AppConfig.ensureDirs()
        sceneTempDir.mkdirs()
        onProgress("TTS 엔진 초기화 중...")
        ttsEngine.init(onProgress)
        onProgress("렌더 엔진 준비 완료")
        Log.i(TAG, "초기화 완료. BGV=${AppConfig.BGV_DIR}")
    }

    suspend fun renderScene(
        row: ScriptDataRow,
        videoMeta: VideoMeta,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val sceneId = "scene_${row.rowIndex.toString().padStart(4, '0')}"
        onProgress("[$sceneId] ${row.section} / Speaker ${row.speaker}")
        try {
            val bgFile     = AppConfig.resolveBgv(row.bgFileName)
            val ttsWavFile = File(sceneTempDir, "${sceneId}_tts.wav")
            val hasTts     = row.script.isNotBlank() && row.sectionType != SectionType.BUFFER
            if (hasTts) {
                val cfg = voiceConfig.forSpeaker(row.speaker)
                onProgress("[$sceneId] TTS: ${cfg.label}(sid=${cfg.sid},spd=${cfg.speed})")
                ttsEngine.synthesize(row.script, cfg.sid, cfg.speed, ttsWavFile)
            }
            val tTotal = if (ttsWavFile.exists()) ttsEngine.estimateDurationFromWav(ttsWavFile) else 3.0f
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
            ttsWavFile.delete(); assFile.delete()
            subclipFiles.add(outputFile)
            onProgress("[$sceneId] ✅ ${outputFile.length() / 1024}KB")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "renderScene 예외: $e"); null
        }
    }

    suspend fun concatSubclips(
        outputName: String,
        bgmFilename: String,
        watermarkText: String = "",
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (subclipFiles.isEmpty()) { onProgress("⚠️ 클립 없음"); return@withContext null }

        // [버그②] "\n" 사용 — "\\n"이면 FFmpeg concat 파서가 줄바꿈 인식 못 함
        val listFile = File(sceneTempDir, "concat_list.txt")
        listFile.writeText(subclipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val outputFile = File(AppConfig.OUTPUT_DIR, "$outputName.mp4")
        val bgmFile    = AppConfig.resolveBgm(bgmFilename)
        val duration   = totalDurationSecs
        val wmEnd      = (duration - 5f).coerceAtLeast(16f)

        onProgress("합치기: ${subclipFiles.size}개 / 총 ${duration.toInt()}초")

        val filterParts   = mutableListOf<String>()
        var videoMapLabel = "0:v"
        var audioMapLabel = "0:a"

        // [버그⑤] 워터마크 오버레이: 15초 등장, wmEnd초 소멸
        if (watermarkText.isNotBlank()) {
            val esc = watermarkText.replace("\\","\\\\").replace("'","\\'")
                .replace(":","\\:").replace(",","\\,")
            filterParts += "[0:v]drawtext=text='$esc'" +
                ":fontfile=/system/fonts/NotoSansCJK-Regular.ttc" +
                ":fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2" +
                ":x=30:y=40:enable='between(t\\,15\\,$wmEnd)'[vout]"
            videoMapLabel = "[vout]"
        }

        // [버그⑥] BGM 덕킹: 0~13초 0.35 / 13~15초 선형 감소 / 15초~ 0.10
        if (bgmFile != null) {
            filterParts += "[0:a]volume=1.0[tts]"
            filterParts += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,13)\\,0.35\\,if(lt(t\\,15)\\,0.35+(t-13)*(-0.125)\\,0.10))'" +
                ":eval=frame[bgm]"
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
        sceneTempDir.listFiles()?.forEach { it.delete() }

        return@withContext if (rc.returnCode.isValueSuccess) {
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length()/1024/1024}MB)")
            outputFile
        } else {
            Log.e(TAG, "concat 실패: ${rc.logsAsString.takeLast(400)}")
            onProgress("❌ concat 실패"); null
        }
    }

    // AudioTrack 직접 스트리밍 (테스트 플레이 — WAV 파일 미기록)
    suspend fun playPreviewDirect(
        text: String, sid: Int, speed: Float,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        try {
            val audio = ttsEngine.generateRaw(text, sid, speed)
                ?: run { onProgress("⚠️ TTS 생성 실패"); return@withContext }
            val sr = ttsEngine.sampleRate
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(audio.size * 4)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
            track.write(audio, 0, audio.size, android.media.AudioTrack.WRITE_BLOCKING)
            track.play()
            onProgress("🎙 재생 중...")
            Thread.sleep((audio.size.toLong() * 1000 / sr).coerceAtLeast(500))
            track.stop(); track.release()
            onProgress("재생 완료")
        } catch (e: Exception) { onProgress("❌ 재생 실패: ${e.message}") }
    }

    fun release() {
        ttsEngine.release(); subclipFiles.clear(); totalDurationSecs = 0f
        sceneTempDir.listFiles()?.forEach { it.delete() }
    }

    // [버그①③⑤] ASS 자막 생성
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
        fun prep(t: String) = t.replace("\\n","\\N").replace("\n","\\N")
        val fade = "{\\an5\\fad(800,600)}"

        out.writeText(buildString {
            // [버그①] appendLine 사용 — \\n 리터럴 절대 금지
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
            if (pm.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,$end,Main,,0,0,0,,${fade}{\\pos($cx,$y1)}${wrapAss(pm,12)}")
            if (ps.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,$end,Sub,,0,0,0,,${fade}{\\pos($cx,$y2)}${wrapAss(ps,18)}")
            if (pd.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,$end,Desc,,0,0,0,,${fade}{\\pos($cx,$y3)}${wrapAss(pd,24)}")
        }, Charsets.UTF_8)
    }

    // [버그⑩] 입력측 -t + EDGE_GLOW 추가
    private fun buildFfmpegCmd(
        bgFile: File, ttsWav: File?, tTotal: Float, assFile: File,
        cardStyle: CardStyle, gradient: GradientPreset, kf: CardKeyframes,
        extraEffect: CardExtraEffect, bgEffect: String, bgTransition: BgTransition,
        transitionDur: Float, outputFile: File
    ): String {
        val fp = mutableListOf<String>()
        fp += "[0:v]setpts=PTS-STARTPTS[bg0]"
        val bgFx = when (bgEffect.split(":")[0]) {
            "VIGNETTE"    -> { fp += "[bg0]vignette=PI/4[bgfx]"; "[bgfx]" }
            "MOTION_BLUR" -> { fp += "[bg0]tmix=frames=3[bgfx]"; "[bgfx]" }
            "EDGE_GLOW"   -> {
                val s = bgEffect.split(":").getOrElse(1){"0.4"}.toFloatOrNull() ?: 0.4f
                fp += "[bg0]split[bgo][bgs]"
                fp += "[bgs]unsharp=5:5:${String.format("%.2f",s*3f)}:0:0:0[bgsh]"
                fp += "[bgo][bgsh]blend=all_mode=screen:all_opacity=0.3[bgfx]"
                "[bgfx]"
            }
            else -> "[bg0]"
        }
        val card = if (cardStyle != CardStyle.NONE && cardStyle != CardStyle.MINIMAL) {
            val r = (gradient.topColor shr 16) and 0xFF
            val g = (gradient.topColor shr 8)  and 0xFF
            val b = gradient.topColor and 0xFF
            fp += "${bgFx}drawbox=x=${kf.holdX.toInt()}:y=${kf.holdY.toInt()}:w=860:h=340" +
                ":color=0x${String.format("%02X%02X%02X",r,g,b)}@${String.format("%.2f",cardStyle.alpha)}:t=fill[card]"
            "[card]"
        } else bgFx
        fp += "${card}subtitles='${assFile.absolutePath.replace("'","\\'")}' [final]"
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

    // [버그④] Pattern C → 정중앙
    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx = VIDEO_W / 2f - 430f; val cy = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f, VIDEO_H*.18f, VIDEO_W*.05f, VIDEO_H*.18f, CardOutType.TOP, 1f, tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H*.18f, VIDEO_W/2f-430f, VIDEO_H*.18f, CardOutType.BOTTOM, 1f, tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx, -340f, cx, cy, CardOutType.BOTTOM, 1f, tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx, cy, cx, cy, CardOutType.FADE, 1f, tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W*.05f, VIDEO_H*.68f, cx, cy, CardOutType.FADE, 1f, tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx, cy, cx, cy, CardOutType.SCALE, 0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f, VIDEO_H*.18f, VIDEO_W*.05f, VIDEO_H*.18f, CardOutType.ROTATE_SCALE, 1f, tOut, tTotal)
            else               -> CardKeyframes(cx, cy, cx, cy, CardOutType.FADE, 1f, tOut, tTotal)
        }
    }

    private fun assTime(s: Float): String {
        val tc = (s * 100).toInt().coerceAtLeast(0)
        val cs = tc % 100; val ts = tc / 100; val ss = ts % 60
        val tm = ts / 60; val mm = tm % 60; val hh = tm / 60
        return "%d:%02d:%02d.%02d".format(hh, mm, ss, cs)
    }

    private fun wrapAss(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.chunked(max).joinToString("\\N")
    }

    private fun detectHighlightColor(script: String, word: String): String {
        val rise   = listOf("상승","강화","기회","확장","대운","목성","금성")
        val fall   = listOf("주의","조심","손실","토성","라후","케투","역행")
        val planet = mapOf("태양" to "00B7FF","달" to "FFC8C8","화성" to "4444FF",
            "수성" to "CCFF44","목성" to "00D7FF","금성" to "CC88FF",
            "토성" to "AAAAAA","라후" to "CC0066","케투" to "006688")
        return planet.entries.firstOrNull { script.contains(it.key) }?.value
            ?: if (rise.any { script.contains(it) }) "4DDBFF"
            else if (fall.any { script.contains(it) }) "6666FF"
            else "4DDBFF"
    }
}

data class CardKeyframes(
    val inStartX: Float, val inStartY: Float,
    val holdX: Float, val holdY: Float,
    val outType: CardOutType,
    val tIn: Float, val tHold: Float, val tOut: Float
)

enum class CardOutType { TOP, BOTTOM, LEFT, RIGHT, FADE, SCALE, ROTATE_SCALE }