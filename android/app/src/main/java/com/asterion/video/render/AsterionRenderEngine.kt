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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.26
//
// [변경로그 v3.26] 동결 화면 원인 제거
//   ■ BGV Step1: 단일 소스에서 fade-in 제거
//     직접 원인: bgvBodyFile 시작에 fade=t=in:st=0:d=3 적용 →
//     바디 시작 시 3초 검은 화면 페이드인 → 사용자에게 ‘멈음’으로
//   ■ encodeVideo() GPU 헬퍼 제거 → libx264 직접 사용 (안정성 우선)
//   ■ 복수 소스: 소스별 fade-in/out 연결 (getMediaDurationSecs 활용)
// [변경로그 v3.25] WAV FFprobeKit 정밀측정 + BGM 13/15s + 면책15s + 실제 인트로길이
// [변경로그 v3.24] BGV WAV 완전 독립 레이어
// =================================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"

private val MOTION_PATTERNS = setOf(
    AnimationPattern.A, AnimationPattern.B, AnimationPattern.C,
    AnimationPattern.E, AnimationPattern.G
)

private fun Float.fmtUS(d: Int = 3): String = String.format(Locale.US, "%.${d}f", this)

data class ScenePrep(
    val row          : ScriptDataRow,
    val wavFile      : File?,
    val bgvCutFile   : File?,
    val bgFile       : File,
    val wavDuration  : Float,
    val startSecs    : Float,
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

    private val subclipFiles          = mutableListOf<File>()
    private var totalDurationSecs     = 0f
    var actualIntroDurationSecs: Float = 21f

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

    // WAV 길이: FFprobeKit → MMR → 파일크기 순서 fallback
    private fun measureWavDuration(file: File, sceneId: String, onProgress: (String) -> Unit): Float {
        try {
            val probe = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(file.absolutePath)
            val dur   = probe?.mediaInformation?.duration?.toFloatOrNull()
            if (dur != null && dur >= 0.1f) return dur
        } catch (e: Exception) { Log.w(TAG, "[$sceneId] FFprobe실패: ${e.message}") }
        val mmrDur = getMediaDurationSecs(file)
        if (mmrDur >= 0.5f) return mmrDur
        val fileDur = (file.length() - 44L).coerceAtLeast(0L) / 48000.0f
        onProgress("[$sceneId] WAV 파일크기 fallback: ${fileDur.fmtUS(1)}s")
        return fileDur.coerceAtLeast(0.5f)
    }

    // ── 인트로 렌더링 ────────────────────────────────────────────

    suspend fun renderIntro(
        videoMeta: VideoMeta,
        disclaimerWav: File? = null,
        onProgress: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) {
            onProgress("인트로 BGV 미설정 — 인트로 생략")
            return@withContext null
        }
        AppConfig.ensureDirs(); sceneTempDir.mkdirs()

        val bgv1    = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2 = videoMeta.introBgv2.isNotBlank()
        val bgv2    = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else null

        val bgv1Dur  = getMediaDurationSecs(bgv1).takeIf { it > 1f } ?: 8f
        val bgv2Dur  = if (bgv2 != null && bgv2.exists()) getMediaDurationSecs(bgv2).takeIf { it > 1f } ?: 15f else 0f
        val xfadeDur = if (hasBgv2) 1.0f else 0f
        val totalDur = if (hasBgv2) bgv1Dur + bgv2Dur - xfadeDur else bgv1Dur
        onProgress("인트로 측정: bgv1=${bgv1Dur.fmtUS(1)}s bgv2=${bgv2Dur.fmtUS(1)}s total=${totalDur.fmtUS(1)}s")

        val p1Start = 2.0f
        val p1End   = (bgv1Dur - xfadeDur - 0.3f).coerceAtLeast(p1Start + 1f)
        val p2Base  = bgv1Dur - xfadeDur
        val t1 = p2Base+2f; val t2 = p2Base+4f; val t3 = p2Base+6f; val t4 = p2Base+8f
        val tAllEnd = (t4 + 6f).coerceAtMost(totalDur - 0.5f)

        val isXrp     = videoMeta.introType.trim().uppercase() == "XRP"
        val titleWord = if (isXrp) "XRP 전망" else "크립토 갤러리"
        val rotWord   = if (isXrp) "예측하는" else "둘러보는"

        fun esc(s: String) = s.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
        fun alpha(ts: Float, te: Float): String {
            val fi = (ts+0.5f).fmtUS(2); val fo = (te-0.5f).coerceAtLeast(ts+0.6f).fmtUS(2)
            return "if(lt(t,$fi),(t-${ts.fmtUS(2)})/0.5,if(gt(t,$fo),(${te.fmtUS(2)}-t)/0.5,1))"
        }
        fun en(ts: Float, te: Float) = "between(t,${ts.fmtUS(2)},${te.fmtUS(2)})"

        val fp = mutableListOf<String>()
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
                  "x=(W-tw)/2:y=H/3-th/2:alpha='${alpha(p1Start,p1End)}':enable='${en(p1Start,p1End)}'[p1]"
            cur = "[p1]"
        }
        if (hasBgv2) {
            val px100=160; val px70=112; val px40=64
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
            val ds = "15.0"  // BGM 13-15s 페이드 완료 후 t=15s부터 면책문구
            fp += "[${silentIdx}:a]atrim=0:${ds},asetpts=PTS-STARTPTS[pre_sil]"
            fp += "[${numVid+1}:a]asetpts=PTS-STARTPTS[disc_a]"
            fp += "[pre_sil][disc_a]concat=n=2:v=0:a=1,apad=whole_dur=${(totalDur+1.0f).fmtUS(1)}[aout]"
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
            append("-t ${totalDur.fmtUS()} -c:v libx264 -preset ultrafast -crf 23 ")
            append("-movflags +faststart ${outFile.absolutePath}")
        }

        onProgress("🎬 인트로 렌더링 중 (${totalDur.toInt()}초, 면책=15s)...")
        Log.i(TAG, "renderIntro cmd: $cmd")
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if (!outFile.exists() || outFile.length() == 0L) {
            Log.e(TAG, "인트로 실패: ${rc.logsAsString.takeLast(600)}")
            onProgress("⚠ 인트로 실패 → 본 영상으로 진행")
            return@withContext null
        }
        subclipFiles.add(0, outFile)
        totalDurationSecs += totalDur
        actualIntroDurationSecs = totalDur  // 실제 인트로 길이 저장
        onProgress("✅ 인트로 완료 (${totalDur.toInt()}초)")
        outFile
    }

    // ── Phase 1: WAV 생성 ──────────────────────────────────────────

    suspend fun prepareScene(
        row        : ScriptDataRow,
        voiceConfig: VoiceConfig,
        startSecs  : Float,
        cacheDir   : File? = null,
        onProgress : (String) -> Unit = {}
    ): ScenePrep? = withContext(Dispatchers.IO) {
        val sceneId = "scene_${row.rowIndex.toString().padStart(4,'0')}"
        try {
            val hasTts = row.script.isNotBlank() && row.sectionType != SectionType.BUFFER
            val wavCache    = cacheDir?.let { File(it, "${sceneId}.wav") }
            val useWavCache = wavCache != null && wavCache.exists() && wavCache.length() > 1024L

            val ttsWavFile: File
            if (useWavCache) {
                ttsWavFile = wavCache!!
                onProgress("[$sceneId] WAV 캐시: ${wavCache.length()/1024}KB")
            } else {
                ttsWavFile = File(sceneTempDir, "${sceneId}_tts.wav")
                if (hasTts) {
                    val cfg = voiceConfig.forSpeaker(row.speaker)
                    onProgress("[$sceneId] TTS: ${cfg.label}(sid=${cfg.sid} steps=${cfg.numSteps})")
                    synthesizeChunked(row.script, cfg.sid, cfg.speed, ttsWavFile, cfg.numSteps, sceneId, onProgress)
                } else {
                    com.arthenica.ffmpegkit.FFmpegKit.execute(
                        "-y -f lavfi -i anullsrc=r=24000:cl=mono -t 3.0 -c:a pcm_s16le ${ttsWavFile.absolutePath}"
                    )
                }
                if (wavCache != null && ttsWavFile.exists() && ttsWavFile.length() > 1024L) {
                    try { ttsWavFile.copyTo(wavCache, overwrite = true) } catch (_: Exception) {}
                }
            }

            // v3.25: FFprobeKit 정밀 측정 → 누적 오차 제거
            val wavDuration = if (ttsWavFile.exists() && ttsWavFile.length() > 1024L)
                measureWavDuration(ttsWavFile, sceneId, onProgress)
            else 3.0f.also { if (hasTts) onProgress("[$sceneId] ⚠️ WAV 실패 → 3.0s") }

            onProgress("[$sceneId] WAV: ${ttsWavFile.length()/1024}KB → ${wavDuration.fmtUS(1)}s")

            val bgFile        = AppConfig.resolveBgv(row.bgFileName)
            val pattern       = AnimationPattern.from(row.animation)
            val keyframes     = calcCardKeyframes(pattern, wavDuration)
            val textStartSecs = if (pattern in MOTION_PATTERNS) keyframes.tIn else 0f

            ScenePrep(
                row=row, wavFile=ttsWavFile.takeIf { it.exists() && it.length()>1024L },
                bgvCutFile=null, bgFile=bgFile, wavDuration=wavDuration, startSecs=startSecs,
                cardStyle=CardStyle.from(row.cardStyle), gradient=GradientPreset.from(row.gradientPreset),
                keyframes=keyframes, textStartSecs=textStartSecs
            )
        } catch (e: Exception) {
            Log.e(TAG, "prepareScene [$sceneId]: $e")
            onProgress("[$sceneId] ❌ 예외: ${e.message}")
            null
        }
    }

    // ── Phase 2: 조립 ────────────────────────────────────────────
    //
    //   레이어 구조:
    //   [BGV 레이어] WAV 완전 독립, 단일소스 페이드 없음, 복수소스 소스별 fade
    //   [TTS 레이어] WAV concat → 오디오
    //   [카드 레이어] WAV 타임스탬프 enable= 조건

    suspend fun assembleBody(
        preps      : List<ScenePrep>,
        outputName : String,
        onProgress : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (preps.isEmpty()) { onProgress("⚠️ 준비된 씬 없음"); return@withContext null }
        sceneTempDir.mkdirs()

        val totalBodyDur = preps.sumOf { it.wavDuration.toDouble() }.toFloat()

        // ─ Step 1: BGV body (WAV 완전 독립) ────────────────────────
        // v3.26 핵심 수정:
        //   단일 소스: fade 없음 → 바디 시작 즉시 BGV 동작 (v3.25 fade-in 제거)
        //   복수 소스: 소스별 fade-in/fade-out (소스 간 전환 시만 사용)
        onProgress("📹 BGV 준비 중 (WAV 독립)...")
        val uniqueBgvList = preps.map { it.bgFile }.distinctBy { it.absolutePath }
        val vfNorm = "scale=${VIDEO_W}:${VIDEO_H}:force_original_aspect_ratio=decrease," +
            "pad=${VIDEO_W}:${VIDEO_H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p"
        val bgvBodyFile = File(sceneTempDir, "bgv_body.mp4")

        if (uniqueBgvList.size == 1) {
            // 단일 소스: fade 없이 직접 stream_loop
            // 페이드 제거: 바디 시작 시 검은 화면 페이드인이 발생했던 v3.25 문제 해결
            onProgress("  BGV 단일: ${uniqueBgvList[0].name}")
            val rc1 = com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -stream_loop -1 -i ${uniqueBgvList[0].absolutePath} " +
                "-an -vf $vfNorm -r 30 -c:v libx264 -preset ultrafast -crf 23 " +
                "-t ${totalBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
            )
            if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
                Log.e(TAG, "BGV 단일소스 실패: ${rc1.logsAsString.takeLast(300)}")
                onProgress("❌ BGV 준비 실패"); return@withContext null
            }
        } else {
            // 복수 소스: 소스별 fade-in(3s)+fade-out(3s) → concat → stream_loop
            // 소스 간 연결에만 페이드 적용, 실제로그 getMediaDurationSecs 활용
            val normFiles = uniqueBgvList.mapIndexed { idx, bgFile ->
                val bgvNatDur  = getMediaDurationSecs(bgFile).takeIf { it > 3.5f } ?: 30f
                val fadeOutSt  = (bgvNatDur - 3f).coerceAtLeast(3.1f)
                val fadeVf     = "$vfNorm,fade=t=in:st=0:d=3,fade=t=out:st=${fadeOutSt.fmtUS()}:d=3"
                val nf = File(sceneTempDir, "bgv_norm_${idx}.mp4")
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -i ${bgFile.absolutePath} -an -vf $fadeVf " +
                    "-r 30 -c:v libx264 -preset ultrafast -crf 23 ${nf.absolutePath}"
                )
                if (nf.exists() && nf.length() > 0L) { onProgress("  BGV[$idx]: ${bgFile.name}"); nf }
                else { Log.w(TAG, "BGV norm실패[$idx]"); bgFile }
            }
            val catList = File(sceneTempDir, "bgv_cat.txt")
            catList.writeText(normFiles.joinToString("\n") { "file '${it.absolutePath}'" })
            val catFile = File(sceneTempDir, "bgv_cat.mp4")
            com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -f concat -safe 0 -i ${catList.absolutePath} -c copy ${catFile.absolutePath}"
            )
            catList.delete(); normFiles.forEach { if (it.name.startsWith("bgv_norm_")) it.delete() }
            val rc2 = com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -stream_loop -1 -i ${catFile.absolutePath} " +
                "-an -r 30 -c:v libx264 -preset ultrafast -crf 23 " +
                "-t ${totalBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
            )
            catFile.delete()
            if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
                Log.e(TAG, "BGV 카탈로그 실패: ${rc2.logsAsString.takeLast(300)}")
                onProgress("❌ BGV 카탈로그 실패"); return@withContext null
            }
        }
        onProgress("✅ BGV: ${bgvBodyFile.length()/1024/1024}MB (${uniqueBgvList.size}소스)")

        // ─ Step 2: WAV concat (카드 타이밍 기준) ──────────────────
        onProgress("🎵 TTS 스트림 중...")
        val wavFiles = preps.map { prep ->
            val f = prep.wavFile?.takeIf { it.exists() && it.length() > 1024L }
            if (f != null) f
            else {
                val sil = File(sceneTempDir, "sil_${prep.row.rowIndex}.wav")
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -f lavfi -i anullsrc=r=24000:cl=mono -t ${prep.wavDuration.fmtUS()} -c:a pcm_s16le ${sil.absolutePath}"
                ); sil
            }
        }
        val wavList = File(sceneTempDir, "wav_list.txt")
        wavList.writeText(wavFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val ttsBody = File(sceneTempDir, "tts_body.wav")
        val wRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -f concat -safe 0 -i ${wavList.absolutePath} -ar 44100 -ac 2 ${ttsBody.absolutePath}"
        )
        if (!ttsBody.exists() || ttsBody.length() == 0L) {
            Log.e(TAG, "WAV concat 실패: ${wRc.logsAsString.takeLast(400)}")
            onProgress("❌ TTS 스트림 실패"); bgvBodyFile.delete(); wavList.delete(); return@withContext null
        }
        onProgress("✅ TTS: ${ttsBody.length()/1024}KB")

        // ─ Step 3: filter_complex_script (카드만 WAV 타임스탬프) ───
        val vfParts = mutableListOf<String>()
        vfParts += "[0:v]setpts=PTS-STARTPTS"
        for (prep in preps) {
            val t1 = prep.startSecs; val t2 = prep.startSecs + prep.wavDuration
            if (prep.cardStyle != CardStyle.NONE && prep.cardStyle != CardStyle.MINIMAL) {
                val r=(prep.gradient.topColor shr 16) and 0xFF
                val g=(prep.gradient.topColor shr 8)  and 0xFF
                val b= prep.gradient.topColor and 0xFF
                vfParts += "drawbox=x=${prep.keyframes.holdX.toInt()}:y=${prep.keyframes.holdY.toInt()}:w=860:h=340:" +
                    "color=0x${String.format(Locale.US,"%02X%02X%02X",r,g,b)}" +
                    "@${String.format(Locale.US,"%.2f",prep.cardStyle.alpha)}:t=fill:" +
                    "enable='between(t,${t1.fmtUS(3)},${t2.fmtUS(3)})'"
                val pm=prep.row.cardMain.trim(); val ps=prep.row.cardSub.trim(); val pd=prep.row.cardDesc.trim()
                if (pm.isNotBlank()||ps.isNotBlank()||pd.isNotBlank()) {
                    val ts =(prep.startSecs+prep.textStartSecs).coerceAtLeast(t1)
                    val fi =(ts+0.8f).fmtUS(3); val fo=(t2-0.6f).coerceAtLeast(ts+1f).fmtUS(3)
                    val alp="if(lt(t,$fi),(t-${ts.fmtUS(3)})/0.8,if(gt(t,$fo),(${t2.fmtUS(3)}-t)/0.6,1))"
                    val enb="between(t,${ts.fmtUS(3)},${t2.fmtUS(3)})"
                    val fo2=if(fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""
                    val cx=prep.keyframes.holdX.toInt()+430
                    val mainLines=if(pm.isNotBlank()) splitToLines(pm,12) else emptyList()
                    val subLines =if(ps.isNotBlank()) splitToLines(ps,18) else emptyList()
                    val descLines=if(pd.isNotBlank()) splitToLines(pd,24) else emptyList()
                    val mainH=mainLines.size*62; val subH=subLines.size*46
                    val gap12=if(mainLines.isNotEmpty()&&subLines.isNotEmpty()) 20 else 0
                    val gap23=if(subLines.isNotEmpty()&&descLines.isNotEmpty()) 12 else 0
                    val by=(prep.keyframes.holdY+170-(mainH+gap12+subH+gap23+descLines.size*40)/2).toInt()
                    mainLines.forEachIndexed  {i,l->vfParts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=52:fontcolor=white:borderw=2:bordercolor=black@0.8:x=${cx}-tw/2:y=${by+i*62}:alpha='$alp':enable='$enb'"}
                    subLines.forEachIndexed   {i,l->vfParts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=38:fontcolor=0xCCCCCC:x=${cx}-tw/2:y=${by+mainH+gap12+i*46}:alpha='$alp':enable='$enb'"}
                    descLines.forEachIndexed  {i,l->vfParts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=32:fontcolor=0xAAAAAA:x=${cx}-tw/2:y=${by+mainH+gap12+subH+gap23+i*40}:alpha='$alp':enable='$enb'"}
                }
            }
        }
        vfParts += "scale=${VIDEO_W}:${VIDEO_H},format=yuv420p[vout]"
        val filterFile = File(sceneTempDir, "body_vf.txt")
        filterFile.writeText(vfParts.joinToString(",\n"))

        // ─ Step 4: 단일 인코딩 ───────────────────────────────────────
        val bodyFile = File(AppConfig.OUTPUT_DIR, "${outputName}_body.mp4")
        onProgress("🎬 인코딩 (${totalBodyDur.toInt()}초, ${preps.size}씬)...")
        Log.i(TAG, "assembleBody v3.26: ${uniqueBgvList.size}소스, ${totalBodyDur.fmtUS(1)}s, ${vfParts.size}줄")
        val enc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -i ${bgvBodyFile.absolutePath} -i ${ttsBody.absolutePath} " +
            "-filter_complex_script ${filterFile.absolutePath} " +
            "-map [vout] -map 1:a " +
            "-c:v libx264 -preset ultrafast -crf 20 -c:a aac -b:a 192k " +
            "-t ${totalBodyDur.fmtUS()} -movflags +faststart ${bodyFile.absolutePath}"
        )
        bgvBodyFile.delete(); ttsBody.delete(); wavList.delete(); filterFile.delete()
        wavFiles.filter { it.absolutePath.contains("sil_") }.forEach { it.delete() }
        preps.forEach { it.wavFile?.let { f -> if (f.absolutePath.contains(TEMP_SUBDIR)) f.delete() } }

        if (!bodyFile.exists() || bodyFile.length() == 0L) {
            Log.e(TAG, "body 실패: ${enc.logsAsString.takeLast(400)}")
            onProgress("❌ body 인코딩 실패"); return@withContext null
        }
        totalDurationSecs += totalBodyDur
        subclipFiles.add(bodyFile)
        onProgress("✅ body: ${bodyFile.length()/1024/1024}MB (${totalBodyDur.toInt()}초)")
        bodyFile
    }

    // ── 합치기 + BGM + 워터마크 ─────────────────────────────────

    suspend fun concatSubclips(
        outputName   : String,
        bgmFileName  : String,
        watermarkText: String = "",
        introDurSecs : Float  = 21f,
        onProgress   : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (subclipFiles.isEmpty()) { onProgress("⚠️ 클립 없음"); return@withContext null }
        val listFile = File(sceneTempDir, "concat_list.txt")
        listFile.writeText(subclipFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val outputFile = File(AppConfig.OUTPUT_DIR, "$outputName.mp4")
        val bgmFile    = AppConfig.resolveBgm(bgmFileName)
        val duration   = totalDurationSecs
        val wmEnd      = (duration - 5f).coerceAtLeast(introDurSecs + 1f)
        onProgress("합치기: ${subclipFiles.size}개 / ${duration.toInt()}초")

        val fp = mutableListOf<String>()
        var vMap = "0:v"; var aMap = "0:a"
        if (watermarkText.isNotBlank()) {
            val esc = escapeDrawtext(watermarkText)
            val fo  = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
            fp += "[0:v]drawtext=${fo}text='$esc':fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:x=30:y=40:enable='between(t\\,${introDurSecs.fmtUS(1)}\\,${wmEnd.fmtUS(1)})'[wm]"
            fp += "[wm]format=yuv420p[vout]"; vMap = "[vout]"
        }
        if (bgmFile != null) {
            fp += "[0:a]volume=0.85[tts]"
            // v3.25: BGM 페이드 13s~15s (면책문구 15s 시작점 기준)
            fp += "[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,13.0)\\,0.40\\,if(lt(t\\,15.0)\\,0.40+(t-13.0)*(-0.195)\\,0.01))':eval=frame[bgm]"
            fp += "[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3:normalize=0[aout]"
            aMap = "[aout]"
        }
        val cmd = buildString {
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if (bgmFile != null) append("-stream_loop -1 -i ${bgmFile.absolutePath} ")
            if (fp.isNotEmpty()) append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"$vMap\" -map \"$aMap\" ")
            append("-c:v libx264 -preset fast -crf 20 -c:a aac -b:a 192k ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }
        val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.filter { it.name.endsWith("_body.mp4") }.forEach { it.delete() }
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }
        return@withContext if (outputFile.exists() && outputFile.length() > 0) {
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length()/1024/1024}MB)"); outputFile
        } else {
            Log.e(TAG, "concat 실패: ${rc.logsAsString.takeLast(400)}"); onProgress("❌ concat 실패"); null
        }
    }

    fun release() {
        subclipFiles.clear(); totalDurationSecs=0f; actualIntroDurationSecs=21f
        runCatching { sceneTempDir.listFiles()?.forEach { it.delete() } }
    }

    fun getMediaDurationSecs(file: File): Float {
        if (!file.exists()) return 0f
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release(); ms / 1000.0f
        } catch (e: Exception) { Log.w(TAG, "재생길이 실패 [${file.name}]: ${e.message}"); 0f }
    }

    private fun getBgvDurationSecs(bgFile: File): Float = getMediaDurationSecs(bgFile)

    private fun synthesizeChunked(text: String, speakerId: Int, speed: Float, outputFile: File, numSteps: Int, sceneId: String, onProgress: (String) -> Unit) {
        ttsEngine.synthesize(text, speakerId, speed, outputFile, numSteps)
    }

    private fun calcCardKeyframes(pattern: AnimationPattern, tTotal: Float): CardKeyframes {
        val tOut = (tTotal - 1f).coerceAtLeast(1.1f)
        val cx = VIDEO_W / 2f - 430f; val cy = VIDEO_H / 2f - 170f
        return when (pattern) {
            AnimationPattern.A -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f,     VIDEO_H*.18f, CardOutType.TOP,          1f, tOut, tTotal)
            AnimationPattern.B -> CardKeyframes(VIDEO_W.toFloat(), VIDEO_H*.18f, VIDEO_W/2f-430f, VIDEO_H*.18f, CardOutType.BOTTOM,       1f, tOut, tTotal)
            AnimationPattern.C -> CardKeyframes(cx,               -340f,         cx,              cy,            CardOutType.BOTTOM,       1f, tOut, tTotal)
            AnimationPattern.D -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.FADE,         1f, tOut, tTotal)
            AnimationPattern.E -> CardKeyframes(VIDEO_W*.05f,     VIDEO_H*.68f,  cx,              cy,            CardOutType.FADE,         1f, tOut, tTotal)
            AnimationPattern.F -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.SCALE,       0.5f, tOut, tTotal)
            AnimationPattern.G -> CardKeyframes(-860f,             VIDEO_H*.18f, VIDEO_W*.05f,     VIDEO_H*.18f, CardOutType.ROTATE_SCALE, 1f, tOut, tTotal)
            else               -> CardKeyframes(cx,                cy,           cx,              cy,            CardOutType.FADE,         1f, tOut, tTotal)
        }
    }

    private fun escapeDrawtext(text: String): String =
        text.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")

    private fun splitToLines(text: String, max: Int): List<String> =
        text.replace("\\N","\n").replace("\\n","\n").split("\n")
            .flatMap { if (it.length > max) it.chunked(max) else listOf(it) }.filter { it.isNotBlank() }
}

data class CardKeyframes(
    val inStartX: Float, val inStartY: Float, val holdX: Float, val holdY: Float,
    val outType: CardOutType, val tIn: Float, val tHold: Float, val tOut: Float
)
enum class CardOutType { TOP, BOTTOM, LEFT, RIGHT, FADE, SCALE, ROTATE_SCALE }
