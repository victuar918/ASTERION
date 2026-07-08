package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// =================================================================
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.30
//
// [변경로그 v3.30]
//   ■ BG_Effect 구현: 시트 M열(BG_Effect) 효과 코드를 BGV 세그먼트별로 적용
//        - VIGNETTE:x    → 가장자리 어둡게 (시선 집중)
//        - EDGE_GLOW:x   → 윤곽 선명/발광 느낌
//        - MOTION_BLUR:x → 배경 부드럽게 (가벼운 가우시안 블러, 모바일 효율 우선)
//        - 그룹화 키를 (BGV파일 + 효과)로 변경 → 같은 파일이라도 효과 다르면 분리
//        - 효과 없음(NONE/빈값)은 기존과 100% 동일 동작 → 시트 호환성 보장
//   ■ 메타불일치 경고를 codec/해상도만 비교하도록 수정 (프레임레이트 문자열 오탐 제거)
// [변경로그 v3.29.1]
//   ■ [Fix6] resolveBgv() fallback 발생 시 onProgress로 즉시 가시화 + 누적 카운터
//          - BG_File에 letter code가 잘못 들어가 전 행이 DEFAULT_BGV로 묶이는 사고 가시화
// [변경로그 v3.29]
//   ■ BGV 씬별 개별 적용: 연속 동일 BGV 그룹화 → 세그먼트 병렬 인코딩 → concat
//   ■ [Fix5] BGV 그룹화 시 BUFFER/빈BG_File 행 안정 처리 (이전 유효 BGV 재사용)
//   ■ [Fix4] isDirectory 체크 + absolutePath 전체 출력
// [변경로그 v3.28] 병렬 카드 인코딩: Semaphore(3) / GPU 우선 + libx264 폴백
// [변경로그 v3.27] assembleBody 영상 재설계
// =================================================================

private const val TAG         = "AsterionRenderEngine"
private const val VIDEO_W     = 1920
private const val VIDEO_H     = 1080
private const val TEMP_SUBDIR = ".temp_scenes"
private const val SEAMLESS_BGV_LOOP = true   // v3.34: 배경 loop 이음매 크로스페이드 (false=기존 loop cut)
private const val INTRO_BODY_XFADE  = 2.0f   // v3.36: 인트로↔바디 크로스페이드 길이(초), 0=하드컷

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
    private var bgvFallbackCount      = 0

    @Volatile private var useHwEnc: Boolean = android.os.Build.VERSION.SDK_INT >= 21

    private fun vc(br: String = "4M", crf: Int = 23): String =
        if (useHwEnc) "-c:v h264_mediacodec -b:v $br"
        else "-c:v libx264 -preset ultrafast -crf $crf"

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

    private fun measureDuration(file: File, tag: String, onProgress: (String) -> Unit): Float {
        try {
            val p = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(file.absolutePath)
            val d = p?.mediaInformation?.duration?.toFloatOrNull()
            if (d != null && d >= 0.1f) return d
        } catch (_: Exception) {}
        val mmr = getMediaDurationSecs(file)
        if (mmr >= 0.5f) return mmr
        val fb = (file.length() - 44L).coerceAtLeast(0L) / 48000.0f
        onProgress("[$tag] 파일크기 fallback: ${fb.fmtUS(1)}s")
        return fb.coerceAtLeast(0.5f)
    }

    private fun String.stripEmoji(): String = this
        .replace(Regex("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]"), "")
        .replace(Regex("[\\u2600-\\u27BF]"), "")
        .replace(Regex("[\\u2B00-\\u2BFF]"), "")
        .replace(Regex("  +"), " ").trim()

    private fun probeVideoMeta(file: File): Triple<String, String, String>? = try {
        val info = com.arthenica.ffmpegkit.FFprobeKit
            .getMediaInformation(file.absolutePath)?.mediaInformation
        val vs = info?.streams?.firstOrNull { it.type == "video" }
        vs?.let { Triple(it.codec ?: "?", "${it.width ?: 0}x${it.height ?: 0}", it.averageFrameRate ?: "?") }
    } catch (_: Exception) { null }

    // v3.30: BG_Effect — 시트 M열 효과 코드 → FFmpeg 필터 문자열 (없으면 빈 문자열)
    private fun parseBgEffect(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank() || s.equals("NONE", ignoreCase = true)) return ""
        val parts = s.split(":")
        val name  = parts[0].trim().uppercase(Locale.US)
        val mag   = parts.getOrNull(1)?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        return when (name) {
            "VIGNETTE"    -> { val ang = Math.PI / 6.0 + mag * (Math.PI / 6.0); ",vignette=angle=${String.format(Locale.US, "%.4f", ang)}" }
            "EDGE_GLOW"   -> ",unsharp=5:5:${(mag * 2f).fmtUS(2)}:5:5:0.0"
            "MOTION_BLUR" -> ",gblur=sigma=${(mag * 5f).coerceAtLeast(0.5f).fmtUS(2)}"
            else          -> ""
        }
    }

    // v3.30: 세그먼트별 BGV 비디오 필터(scale/pad + 효과) 빌더
    private fun buildBgvVf(effectCode: String?): String {
        val base = "scale=${VIDEO_W}:${VIDEO_H}:force_original_aspect_ratio=decrease," +
            "pad=${VIDEO_W}:${VIDEO_H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
        return "$base${parseBgEffect(effectCode)},format=yuv420p"
    }

    // v3.34: 이음새 없는 loop 단위 생성 (끝 D초~처음 D초 크로스페이드 → 재생 끝=시작). 부적합/실패 시 false → 호출측이 원본 직접 loop.
    private fun buildSeamlessLoopUnit(srcFile: File, vf: String, unitFile: File, hw: Boolean): Boolean {
        if (!SEAMLESS_BGV_LOOP) return false
        val srcDur = getMediaDurationSecs(srcFile)
        val d = 0.5f; val g = 0.1f
        if (srcDur < 2f * d + g + 0.5f || srcDur > 60f) return false   // 너무 짧음/너무 김 → 폴백
        val bodyLen = srcDur - d - g
        val lu = srcDur - d
        val tailStart = srcDur - d - g
        // v3.34.2: OOM 방지 — split 제거. tail은 -ss seek 입력(0), body는 입력(1). 두 입력 독립 디코딩 + 입력길이 -t 제한. GPU 인코딩 유지.
        val vc2 = if (hw) "-c:v h264_mediacodec -b:v 8M" else "-c:v libx264 -preset veryfast -crf 18"
        val filter = "[0:v]${vf},setpts=PTS-STARTPTS[tl];" +
            "[1:v]${vf},setpts=PTS-STARTPTS[bd];" +
            "[tl][bd]xfade=transition=fade:duration=${d.fmtUS()}:offset=${g.fmtUS()}[vout]"
        unitFile.delete()
        val cmd = "-y -ss ${tailStart.fmtUS()} -t ${(d + g).fmtUS()} -i ${srcFile.absolutePath} " +
            "-t ${bodyLen.fmtUS()} -i ${srcFile.absolutePath} " +
            "-filter_complex $filter -map [vout] -an -r 30 $vc2 -pix_fmt yuv420p -g 30 -t ${lu.fmtUS()} ${unitFile.absolutePath}"
        Log.i(TAG, "loopUnit(v2) cmd: $cmd")
        com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        return unitFile.exists() && unitFile.length() > 0L
    }

    private fun buildCardVf(prep: ScenePrep): String? {
        if (prep.cardStyle == CardStyle.NONE || prep.cardStyle == CardStyle.MINIMAL) return null
        val pm = prep.row.cardMain.trim().stripEmoji()
        val ps = prep.row.cardSub.trim().stripEmoji()
        val pd = prep.row.cardDesc.trim().stripEmoji()
        val r = (prep.gradient.topColor shr 16) and 0xFF
        val g = (prep.gradient.topColor shr 8)  and 0xFF
        val b =  prep.gradient.topColor and 0xFF
        val nearBlack = r < 10 && g < 10 && b < 10
        val rF = if (nearBlack) 15 else r
        val gF = if (nearBlack) 8  else g
        val bF = if (nearBlack) 25 else b
        val parts = mutableListOf<String>()
        parts += "drawbox=x=${prep.keyframes.holdX.toInt()}:y=${prep.keyframes.holdY.toInt()}:w=860:h=340:" +
            "color=0x${String.format(Locale.US,"%02X%02X%02X",rF,gF,bF)}" +
            "@${String.format(Locale.US,"%.2f",prep.cardStyle.alpha)}:t=fill"
        if (pm.isNotBlank() || ps.isNotBlank() || pd.isNotBlank()) {
            val fo2 = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""
            val cx  = prep.keyframes.holdX.toInt() + 430
            val mainLines = if (pm.isNotBlank()) splitToLines(pm,12) else emptyList()
            val subLines  = if (ps.isNotBlank()) splitToLines(ps,18) else emptyList()
            val descLines = if (pd.isNotBlank()) splitToLines(pd,24) else emptyList()
            val mainH = mainLines.size*62; val subH=subLines.size*46
            val gap12 = if(mainLines.isNotEmpty()&&subLines.isNotEmpty()) 20 else 0
            val gap23 = if(subLines.isNotEmpty()&&descLines.isNotEmpty()) 12 else 0
            val totalH = mainH+gap12+subH+gap23+descLines.size*40
            val by = (prep.keyframes.holdY+170-totalH/2).toInt()
            val sh = "shadowcolor=0x404040@0.9:shadowx=2:shadowy=2"
            mainLines.forEachIndexed  {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=52:fontcolor=white:${sh}:x=${cx}-tw/2:y=${by+i*62}"}
            subLines.forEachIndexed   {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=38:fontcolor=0xCCCCCC:${sh}:x=${cx}-tw/2:y=${by+mainH+gap12+i*46}"}
            descLines.forEachIndexed  {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=32:fontcolor=0xAAAAAA:${sh}:x=${cx}-tw/2:y=${by+mainH+gap12+subH+gap23+i*40}"}
        }
        return parts.joinToString(",")
    }

    suspend fun renderIntro(
        videoMeta    : VideoMeta,
        disclaimerWav: File? = null,
        onProgress   : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) { onProgress("인트로 BGV 미설정 — 생략"); return@withContext null }
        AppConfig.ensureDirs(); sceneTempDir.mkdirs()
        val bgv1    = AppConfig.resolveBgv(videoMeta.introBgv1)
        val hasBgv2 = videoMeta.introBgv2.isNotBlank()
        val bgv2    = if (hasBgv2) AppConfig.resolveBgv(videoMeta.introBgv2) else null
        val bgv1Dur  = getMediaDurationSecs(bgv1).takeIf { it > 1f } ?: 8f
        val bgv2Dur  = if (bgv2 != null && bgv2.exists()) getMediaDurationSecs(bgv2).takeIf { it > 1f } ?: 15f else 0f
        val xfadeDur = if (hasBgv2) 1.0f else 0f
        val totalDur = if (hasBgv2) bgv1Dur + bgv2Dur - xfadeDur else bgv1Dur
        onProgress("인트로: bgv1=${bgv1Dur.fmtUS(1)}s bgv2=${bgv2Dur.fmtUS(1)}s total=${totalDur.fmtUS(1)}s")
        val p1Start = 2.0f; val p1End = (bgv1Dur-xfadeDur-0.3f).coerceAtLeast(p1Start+1f)
        val p2Base  = bgv1Dur-xfadeDur
        val t1=p2Base+2f; val t2=p2Base+4f; val t3=p2Base+6f; val t4=p2Base+8f
        val tAllEnd = (t4+6f).coerceAtMost(totalDur-0.5f)
        val isXrp = videoMeta.introType.trim().uppercase() == "XRP"
        val titleWord = if (isXrp) "XRP 전망" else "크립토 갤러리"
        val rotWord   = if (isXrp) "예측하는" else "둘러보는"
        fun esc(s:String)=s.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
        fun alpha(ts:Float,te:Float):String{
            val fi=(ts+0.5f).fmtUS(2); val fo=(te-0.5f).coerceAtLeast(ts+0.6f).fmtUS(2)
            return "if(lt(t,$fi),(t-${ts.fmtUS(2)})/0.5,if(gt(t,$fo),(${te.fmtUS(2)}-t)/0.5,1))"
        }
        fun en(ts:Float,te:Float)="between(t,${ts.fmtUS(2)},${te.fmtUS(2)})"
        val fp = mutableListOf<String>(); val fOpt = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""
        if(hasBgv2&&bgv2!=null){
            val xOff=(bgv1Dur-xfadeDur).coerceAtLeast(0.5f)
            fp+="[0:v]scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,setpts=PTS-STARTPTS[v0]"; fp+="[1:v]scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,setpts=PTS-STARTPTS[v1]"
            fp+="[v0][v1]xfade=transition=fade:duration=${xfadeDur.fmtUS()}:offset=${xOff.fmtUS()}[bgv]"
        }else{fp+="[0:v]scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,setpts=PTS-STARTPTS[bgv]"}
        var cur="[bgv]"
        if(videoMeta.introText.isNotBlank()){
            fp+="${cur}drawtext=${fOpt}text='${esc(videoMeta.introText)}':fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H/3-th/2:alpha='${alpha(p1Start,p1End)}':enable='${en(p1Start,p1End)}'[p1]"
            cur="[p1]"
        }
        if(hasBgv2){
            val px100=160; val px70=112; val px40=64
            fp+="${cur}drawtext=${fOpt}text='베다점성술로':fontsize=$px70:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.15-th/2:alpha='${alpha(t1,tAllEnd)}':enable='${en(t1,tAllEnd)}'[t1]"; cur="[t1]"
            fp+="${cur}drawtext=${fOpt}text='${esc(rotWord)}':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.37-th/2:alpha='${alpha(t2,tAllEnd)}':enable='${en(t2,tAllEnd)}'[t2]"; cur="[t2]"
            fp+="${cur}drawtext=${fOpt}text='${esc(titleWord)}':fontsize=$px100:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.57-th/2:alpha='${alpha(t3,tAllEnd)}':enable='${en(t3,tAllEnd)}'[t3]"; cur="[t3]"
            fp+="${cur}drawtext=${fOpt}text='by ASTERION':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.77-th/2:alpha='${alpha(t4,tAllEnd)}':enable='${en(t4,tAllEnd)}'[t4]"; cur="[t4]"
        }
        fp+="${cur}format=yuv420p[vfinal]"
        val numVid=if(hasBgv2)2 else 1; val silentIdx=numVid
        val hasDisc=disclaimerWav!=null&&disclaimerWav.exists()
        if(hasDisc){
            fp+="[${silentIdx}:a]atrim=0:15.0,asetpts=PTS-STARTPTS[pre_sil]"
            fp+="[${numVid+1}:a]aformat=sample_rates=44100:channel_layouts=stereo,afftdn=nf=-25,asetpts=PTS-STARTPTS[disc_a]"
            fp+="[pre_sil][disc_a]concat=n=2:v=0:a=1,apad=whole_dur=${(totalDur+1.0f).fmtUS(1)}[aout]"
        }
        val outFile=File(sceneTempDir,"scene_intro.mp4"); val introVc=vc("4M",23)
        val cmd=buildString{
            append("-y ")
            append("-stream_loop -1 -i ${bgv1.absolutePath} ")
            if(hasBgv2&&bgv2!=null)append("-stream_loop -1 -i ${bgv2.absolutePath} ")
            append("-f lavfi -i anullsrc=r=44100:cl=stereo ")
            if(hasDisc)append("-i ${disclaimerWav!!.absolutePath} ")
            append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"[vfinal]\" ")
            if(hasDisc)append("-map \"[aout]\" -c:a aac -b:a 128k ")
            else       append("-map ${silentIdx}:a ")
            append("-t ${totalDur.fmtUS()} -r 30 $introVc -movflags +faststart ${outFile.absolutePath}")
        }
        onProgress("🎬 인트로 (${totalDur.toInt()}초)..."); Log.i(TAG,"renderIntro cmd: $cmd")
        val rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if(!outFile.exists()||outFile.length()==0L){
            if(useHwEnc){ useHwEnc=false; com.arthenica.ffmpegkit.FFmpegKit.execute(cmd.replace("-c:v h264_mediacodec -b:v 4M","-c:v libx264 -preset ultrafast -crf 23")) }
            if(!outFile.exists()||outFile.length()==0L){ Log.e(TAG,"인트로 실패: ${rc.logsAsString.takeLast(600)}"); onProgress("⚠ 인트로 실패"); return@withContext null }
        }
        subclipFiles.add(0,outFile); totalDurationSecs+=totalDur; actualIntroDurationSecs=totalDur
        onProgress("✅ 인트로 완료 (${totalDur.toInt()}초)"); outFile
    }

    suspend fun prepareScene(
        row        : ScriptDataRow,
        voiceConfig: VoiceConfig,
        startSecs  : Float,
        cacheDir   : File? = null,
        onProgress : (String) -> Unit = {}
    ): ScenePrep? = withContext(Dispatchers.IO) {
        val sceneId="scene_${row.rowIndex.toString().padStart(4,'0')}"
        try {
            val hasTts=row.script.isNotBlank()&&row.sectionType!=SectionType.BUFFER
            val wavCache=cacheDir?.let{File(it,"${sceneId}.wav")}
            val useCache=wavCache!=null&&wavCache.exists()&&wavCache.length()>1024L
            val ttsWavFile: File
            if(useCache){ttsWavFile=wavCache!!; onProgress("[$sceneId] WAV 캐시")}
            else{
                ttsWavFile=File(sceneTempDir,"${sceneId}_tts.wav")
                if(hasTts){ val cfg=voiceConfig.forSpeaker(row.speaker); onProgress("[$sceneId] TTS: ${cfg.label}(${cfg.sid})"); synthesizeChunked(row.script,cfg.sid,cfg.speed,ttsWavFile,cfg.numSteps,sceneId,onProgress) }
                else com.arthenica.ffmpegkit.FFmpegKit.execute("-y -f lavfi -i anullsrc=r=24000:cl=mono -t 3.0 -c:a pcm_s16le ${ttsWavFile.absolutePath}")
                if(wavCache!=null&&ttsWavFile.exists()&&ttsWavFile.length()>1024L) try{ttsWavFile.copyTo(wavCache,overwrite=true)}catch(_:Exception){}
            }
            val wavDuration=if(ttsWavFile.exists()&&ttsWavFile.length()>1024L) measureDuration(ttsWavFile,sceneId,onProgress)
                           else 3.0f.also{if(hasTts)onProgress("[$sceneId] ⚠️ WAV 실패")}
            onProgress("[$sceneId] WAV: ${ttsWavFile.length()/1024}KB ${wavDuration.fmtUS(1)}s")
            val (bgFile, isBgvFallback) = AppConfig.resolveBgvChecked(row.bgFileName)
            if (isBgvFallback) {
                bgvFallbackCount++
                onProgress("  ⚠️ [$sceneId] BG_File='${row.bgFileName}' 해석 실패 → DEFAULT_BGV fallback (시트 데이터 확인 필요)")
            }
            val pattern=AnimationPattern.from(row.animation)
            val keyframes=calcCardKeyframes(pattern,wavDuration)
            ScenePrep(
                row=row, wavFile=ttsWavFile.takeIf{it.exists()&&it.length()>1024L},
                bgvCutFile=null, bgFile=bgFile, wavDuration=wavDuration, startSecs=startSecs,
                cardStyle=CardStyle.from(row.cardStyle.trim()),
                gradient=GradientPreset.from(row.gradientPreset.trim().takeIf{it.isNotBlank()&&it.uppercase()!="DEFAULT"}?:row.cardStyle.trim()),
                keyframes=keyframes, textStartSecs=if(pattern in MOTION_PATTERNS)keyframes.tIn else 0f
            )
        }catch(e:Exception){ Log.e(TAG,"prepareScene [$sceneId]: $e"); onProgress("[$sceneId] ❌ ${e.message}"); null }
    }

    suspend fun assembleBody(
        preps      : List<ScenePrep>,
        outputName : String,
        onProgress : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (preps.isEmpty()) { onProgress("⚠️ 씬 없음"); return@withContext null }
        sceneTempDir.mkdirs()

        // ─ Step 1: 씬별 카드+WAV 인코딩 ───
        onProgress("🎬 인코딩 (${preps.size}씬) [${if(useHwEnc)"GPU" else "CPU"}]...")
        val cardSemaphore = Semaphore(3)
        val cardFileResults: List<File?> = coroutineScope {
            preps.map { prep -> async(Dispatchers.IO) { cardSemaphore.withPermit { encodeOneCard(prep, onProgress) } } }.awaitAll()
        }
        val cardFiles = cardFileResults.filterNotNull().toMutableList()
        if (cardFiles.isEmpty()) { onProgress("❌ 카드 없음"); return@withContext null }

        // ─ Step 2: 카드 concat → body_cards.mp4 ───
        onProgress("🔗 카드 concat (${cardFiles.size}개)...")
        val cardList = File(sceneTempDir, "card_list.txt")
        cardList.writeText(cardFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val bodyCardsFile = File(sceneTempDir, "body_cards.mov")
        val cRc = com.arthenica.ffmpegkit.FFmpegKit.execute("-y -f concat -safe 0 -i ${cardList.absolutePath} -c copy ${bodyCardsFile.absolutePath}")
        cardList.delete(); cardFiles.forEach { it.delete() }
        if (!bodyCardsFile.exists() || bodyCardsFile.length() == 0L) {
            Log.e(TAG, "body_cards 실패: ${cRc.logsAsString.takeLast(400)}"); onProgress("❌ 카드 concat 실패"); return@withContext null
        }
        val actualBodyDur = measureDuration(bodyCardsFile, "body_cards", onProgress)
        onProgress("✅ body_cards: ${bodyCardsFile.length()/1024/1024}MB, ${actualBodyDur.fmtUS(1)}s")

        // ─ Step 3: BGV body (씬별 세그먼트) ───
        // v3.30: 그룹화 키 = (BGV파일 + BG_Effect). 같은 파일이라도 효과가 다르면 분리.
        onProgress("📹 BGV 세그먼트 그룹화...")
        val codecNorm = "-pix_fmt yuv420p -g 30"

        // Fix5: BGV 그룹화 시 BUFFER/빈BG_File 행 안정 처리 (이전 유효 BGV 재사용)
        // v3.30: 세그먼트 = Triple(파일, 효과코드, 길이)
        val bgvSegments = mutableListOf<Triple<File, String, Float>>()
        val bgvTransitions = mutableListOf<Pair<BgTransition, Float>>()  // v3.33: 세그먼트별 전환(첫 행 기준), bgvSegments와 index 정합
        var lastValidBgv: File? = null
        for (prep in preps) {
            val bgvFile = prep.bgFile
            val fx      = prep.row.bgEffect.trim().ifBlank { "NONE" }
            val isValid = bgvFile.exists() && !bgvFile.isDirectory
            val effectiveBgv = if (isValid) {
                lastValidBgv = bgvFile
                bgvFile
            } else {
                // BUFFER 또는 빈 BG_File: 이전 유효 BGV 재사용
                if (lastValidBgv == null) {
                    Log.w(TAG, "BGV 유효하지 않은 prep 건너뜀 (lastValid 없음): ${bgvFile.absolutePath}")
                    continue  // 첫 구간에 유효한 BGV가 아직 없는 경우만 skip
                }
                Log.d(TAG, "BGV BUFFER/빈BG: ${bgvFile.name} → lastValid=${lastValidBgv!!.name}")
                onProgress("  🔄 BGV 결측(${bgvFile.name}) → 이전BGV 재사용: ${lastValidBgv!!.name}")
                lastValidBgv!!
            }
            val last = bgvSegments.lastOrNull()
            if (last != null && last.first.absolutePath == effectiveBgv.absolutePath && last.second == fx) {
                bgvSegments[bgvSegments.lastIndex] = Triple(last.first, last.second, last.third + prep.wavDuration)
            } else {
                bgvSegments.add(Triple(effectiveBgv, fx, prep.wavDuration))
                val trRaw = prep.row.bgTransition.trim()
                val tr = if (trRaw.isBlank()) BgTransition.FADE else BgTransition.from(trRaw)  // 빈칸=기본 크로스페이드
                bgvTransitions.add(tr to prep.row.bgTransitionDuration.coerceIn(0.3f, 2.0f))
            }
        }
        onProgress("📹 BGV ${bgvSegments.size}세그먼트: ${bgvSegments.joinToString { "${it.first.name}+${it.second}(${it.third.fmtUS(1)}s)" }}")
        if (bgvFallbackCount > 0) {
            onProgress("⚠️⚠️ BGV fallback 발생: ${bgvFallbackCount}건 / ${preps.size}씬 — BG_File 시트 값 확인 필요 (잘못된 값은 모두 DEFAULT_BGV로 묶임)")
        }

        val bgvBodyFile = File(sceneTempDir, "bgv_body.mp4")

        if (bgvSegments.size == 1) {
            val seg0Vf = buildBgvVf(bgvSegments[0].second)
            val src0   = bgvSegments[0].first
            // v3.34: seamless loop 단위 시도 (성공 시 단위 loop, 실패 시 원본 직접 loop)
            val unit0  = File(sceneTempDir, "bgv_loopunit_0.mp4")
            var loopSrc0 = src0; var loopVf0 = seg0Vf
            if (buildSeamlessLoopUnit(src0, seg0Vf, unit0, useHwEnc)) { loopSrc0 = unit0; loopVf0 = "" }
            fun runBgv(hw: Boolean): Boolean {
                val vc2 = if (hw) "-c:v h264_mediacodec -b:v 4M" else "-c:v libx264 -preset ultrafast -crf 23"
                val vfArg = if (loopVf0.isBlank()) "" else "-vf $loopVf0 "
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -stream_loop -1 -i ${loopSrc0.absolutePath} " +
                    "-an ${vfArg}-r 30 $vc2 $codecNorm -t ${actualBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
                )
                return bgvBodyFile.exists() && bgvBodyFile.length() > 0L
            }
            if (!runBgv(useHwEnc) && useHwEnc) { useHwEnc = false; bgvBodyFile.delete(); runBgv(false) }
            unit0.delete()
            if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
                onProgress("❌ BGV 실패"); bodyCardsFile.delete(); return@withContext null
            }
        } else {
            // Fix4: isDirectory 체크 + 진단 로그 / v3.30: 세그먼트별 효과 vf 적용
            fun encodeSeg(idx: Int, bgvFile: File, fx: String, segDur: Float): File? {
                val hw = useHwEnc
                val segFile = File(sceneTempDir, "bgv_seg_${idx}.mp4")
                val segVf   = buildBgvVf(fx)
                if (!bgvFile.exists() || bgvFile.isDirectory) {
                    val reason = if (bgvFile.isDirectory) "DIR" else "NOT_FOUND"
                    Log.e(TAG, "BGV[$idx] 입력비정상($reason): ${bgvFile.absolutePath}")
                    onProgress("  ❌ BGV[$idx] $reason: ${bgvFile.absolutePath}")
                    return null
                }
                if (segDur < 0.5f) {
                    Log.e(TAG, "BGV[$idx] duration비정상: ${segDur.fmtUS(3)}s")
                    onProgress("  ❌ BGV[$idx] duration비정상(${segDur.fmtUS(3)}s): ${bgvFile.absolutePath}")
                    return null
                }
                // v3.34: seamless loop 단위 시도 (실패/부적합 시 원본 직접 loop)
                val unitFile = File(sceneTempDir, "bgv_loopunit_${idx}.mp4")
                var loopSrc = bgvFile; var loopVf = segVf
                if (buildSeamlessLoopUnit(bgvFile, segVf, unitFile, hw)) { loopSrc = unitFile; loopVf = "" }
                fun runEnc(useHw: Boolean): Boolean {
                    segFile.delete()
                    val vc2 = if (useHw) "-c:v h264_mediacodec -b:v 4M" else "-c:v libx264 -preset ultrafast -crf 23"
                    val vfArg = if (loopVf.isBlank()) "" else "-vf $loopVf "
                    val ffCmd = "-y -stream_loop -1 -i ${loopSrc.absolutePath} " +
                        "-an ${vfArg}-r 30 $vc2 $codecNorm " +
                        "-t ${segDur.fmtUS()} ${segFile.absolutePath}"
                    Log.d(TAG, "BGV[$idx] cmd: $ffCmd")
                    val sess = com.arthenica.ffmpegkit.FFmpegKit.execute(ffCmd)
                    if (segFile.exists() && segFile.length() > 0L) return true
                    val rc = sess.returnCode?.value ?: -1
                    val errLog = sess.logsAsString?.takeLast(2000) ?: "log unavailable"
                    Log.e(TAG, "BGV[$idx] 실패 rc=$rc hw=$useHw\npath=${bgvFile.absolutePath}\ncmd=$ffCmd\n$errLog")
                    onProgress("  ❌ BGV[$idx] rc=$rc [${if(useHw)"GPU" else "CPU"}] ${segDur.fmtUS(1)}s\n    path=${bgvFile.absolutePath}")
                    return false
                }
                if (runEnc(hw)) {
                    unitFile.delete()
                    onProgress("  BGV[$idx] ${bgvFile.name}+${fx}: ${segFile.length()/1024}KB (${segDur.fmtUS(1)}s) [${if(hw)"GPU" else "CPU"}]")
                    return segFile
                }
                if (!hw) { unitFile.delete(); return null }
                useHwEnc = false
                try { Thread.sleep(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                if (runEnc(false)) { unitFile.delete(); onProgress("  ✅ BGV[$idx] CPU 재시도 성공"); return segFile }
                unitFile.delete()
                return null
            }

            // v3.33: 전환 효과용 세그먼트 길이 계산 — 전환 시 나가는 세그먼트를 겹침(d)만큼 연장해 총 길이 보존
            val transTypes = List(bgvSegments.size) { i -> if (i == 0) BgTransition.NONE else bgvTransitions[i].first }
            val transDurs  = List(bgvSegments.size) { i ->
                if (i == 0 || transTypes[i] == BgTransition.NONE) 0f
                else bgvTransitions[i].second.coerceIn(0.3f, 2.0f).coerceAtMost(bgvSegments[i].third * 0.8f)
            }
            val hasAnyTransition = transTypes.any { it != BgTransition.NONE }
            val encDurs = List(bgvSegments.size) { i ->
                val outPad = if (i < bgvSegments.size - 1) transDurs[i + 1] else 0f
                val eps    = if (hasAnyTransition && i == bgvSegments.size - 1) 0.2f else 0f
                bgvSegments[i].third + outPad + eps
            }
            val bgvSemaphore = Semaphore(3)
            val segFiles: List<File?> = coroutineScope {
                bgvSegments.mapIndexed { idx, seg ->
                    async(Dispatchers.IO) { bgvSemaphore.withPermit { encodeSeg(idx, seg.first, seg.second, encDurs[idx]) } }
                }.awaitAll()
            }
            val validSegs = segFiles.filterNotNull()
            if (validSegs.size != bgvSegments.size) {
                onProgress("❌ BGV 세그먼트 실패 (${validSegs.size}/${bgvSegments.size})")
                bodyCardsFile.delete(); validSegs.forEach { it.delete() }; return@withContext null
            }
            // v3.30: codec/해상도만 비교 (프레임레이트 문자열 차이로 인한 오탐 제거)
            val refMeta = probeVideoMeta(validSegs[0])
            if (refMeta != null) {
                validSegs.drop(1).forEachIndexed { i, seg ->
                    val meta = probeVideoMeta(seg)
                    if (meta != null && (meta.first != refMeta.first || meta.second != refMeta.second)) {
                        val msg = "BGV 세그[${i+1}] 메타불일치 codec:${meta.first}/${refMeta.first} res:${meta.second}/${refMeta.second}"
                        onProgress("⚠️ $msg"); Log.w(TAG, msg)
                    }
                }
            }
            val segListFile = File(sceneTempDir, "bgv_seg_list.txt")
            segListFile.writeText(validSegs.joinToString("\n") { "file '${it.absolutePath}'" })
            if (!hasAnyTransition) {
                com.arthenica.ffmpegkit.FFmpegKit.execute("-y -f concat -safe 0 -i ${segListFile.absolutePath} -c copy ${bgvBodyFile.absolutePath}")
            } else {
                // v3.33: 전환 효과 — xfade(전환)/concat(NONE) 체인. 나가는 세그먼트 사전 연장 + -t 클램프로 카드 길이와 정확 일치.
                fun xfadeName(t: BgTransition): String = when (t) {
                    BgTransition.FADE -> "fade"
                    BgTransition.SLIDE_LEFT -> "slideleft"
                    BgTransition.SLIDE_UP -> "slideup"
                    BgTransition.ZOOM_IN -> "zoomin"
                    BgTransition.ZOOM_OUT -> "circleclose"
                    BgTransition.BLUR_FADE -> "hblur"
                    BgTransition.WIPE_RIGHT -> "wiperight"
                    else -> "fade"
                }
                val xInputs = validSegs.joinToString(" ") { "-i ${it.absolutePath}" }
                val fpx = mutableListOf<String>()
                for (i in validSegs.indices) fpx += "[$i:v]setpts=PTS-STARTPTS[v$i]"
                var acc = "[v0]"; var accLen = encDurs[0]
                for (i in 1 until validSegs.size) {
                    if (transTypes[i] == BgTransition.NONE) {
                        fpx += "${acc}[v$i]concat=n=2:v=1:a=0[c$i]"; accLen += encDurs[i]; acc = "[c$i]"
                    } else {
                        val d = transDurs[i]; val off = (accLen - d).coerceAtLeast(0.1f)
                        fpx += "${acc}[v$i]xfade=transition=${xfadeName(transTypes[i])}:duration=${d.fmtUS(3)}:offset=${off.fmtUS(3)}[x$i]"
                        accLen = off + encDurs[i]; acc = "[x$i]"
                    }
                }
                fpx += "${acc}format=yuv420p[vout]"
                val xCmd = "-y $xInputs -filter_complex ${fpx.joinToString(";")} -map [vout] -an -r 30 ${vc("4M",23)} $codecNorm -t ${actualBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
                onProgress("🎬 BGV 전환 합성 (${validSegs.size}세그, xfade)...")
                Log.i(TAG, "xfade cmd: $xCmd")
                com.arthenica.ffmpegkit.FFmpegKit.execute(xCmd)
                if ((!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) && useHwEnc) {
                    useHwEnc = false; bgvBodyFile.delete()
                    com.arthenica.ffmpegkit.FFmpegKit.execute(xCmd.replace("-c:v h264_mediacodec -b:v 4M", "-c:v libx264 -preset ultrafast -crf 23"))
                }
            }
            segListFile.delete(); validSegs.forEach { it.delete() }
            if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
                onProgress("❌ BGV 합성 실패"); bodyCardsFile.delete(); return@withContext null
            }
        }

        val bgvBodyDur = measureDuration(bgvBodyFile, "bgv_body", onProgress)
        val durDiff    = kotlin.math.abs(bgvBodyDur - actualBodyDur)
        when {
            durDiff >= 0.5f -> {
                Log.e(TAG, "BGV duration fatal: bgv=${bgvBodyDur.fmtUS(2)} cards=${actualBodyDur.fmtUS(2)} Δ${durDiff.fmtUS(3)}")
                onProgress("❌ BGV duration 오차 치명 (Δ${durDiff.fmtUS(3)}s ≥0.5s)")
                bgvBodyFile.delete(); bodyCardsFile.delete(); return@withContext null
            }
            durDiff >= 0.1f -> onProgress("⚠️ BGV duration 경고: ${bgvBodyDur.fmtUS(2)}s / ${actualBodyDur.fmtUS(2)}s (Δ${durDiff.fmtUS(3)}s)")
            else            -> onProgress("✅ BGV: ${bgvBodyFile.length()/1024/1024}MB (Δ${durDiff.fmtUS(3)}s OK)")
        }

        val bodyFile = File(AppConfig.OUTPUT_DIR, "${outputName}_body.mp4")
        onProgress("🎬 블렌딩 (${actualBodyDur.fmtUS(1)}s)...")
        val ckFilter = "[1:v]format=rgba[ov];[0:v][ov]overlay=0:0,format=yuv420p[vout]"
        val blendCmd = "-y -i ${bgvBodyFile.absolutePath} -i ${bodyCardsFile.absolutePath} " +
            "-filter_complex $ckFilter -map [vout] -map 1:a ${vc("5M",20)} -c:a aac -b:a 192k " +
            "-t ${actualBodyDur.fmtUS()} -movflags +faststart ${bodyFile.absolutePath}"
        com.arthenica.ffmpegkit.FFmpegKit.execute(blendCmd)
        if (!bodyFile.exists() || bodyFile.length() == 0L) {
            if (useHwEnc) { useHwEnc=false; com.arthenica.ffmpegkit.FFmpegKit.execute(blendCmd.replace("-c:v h264_mediacodec -b:v 5M","-c:v libx264 -preset ultrafast -crf 20")) }
            if (!bodyFile.exists() || bodyFile.length() == 0L) {
                onProgress("❌ 블렌딩 실패"); bgvBodyFile.delete(); bodyCardsFile.delete(); return@withContext null
            }
        }
        bgvBodyFile.delete(); bodyCardsFile.delete()
        preps.forEach { it.wavFile?.let { f -> if (f.absolutePath.contains(TEMP_SUBDIR)) f.delete() } }
        totalDurationSecs += actualBodyDur; subclipFiles.add(bodyFile)
        onProgress("✅ body: ${bodyFile.length()/1024/1024}MB (${actualBodyDur.fmtUS(1)}s)")
        bodyFile
    }

    private fun encodeOneCard(prep: ScenePrep, onProgress: (String) -> Unit): File? {
        val idx = prep.row.rowIndex.toString().padStart(4,'0')
        val cardFile = File(sceneTempDir, "card_${idx}.mov")
        val wavSrc = prep.wavFile?.takeIf { it.exists() && it.length() > 1024L }
        val audioSrc: File; var tempSil: File? = null
        if (wavSrc != null) { audioSrc = wavSrc }
        else {
            val sil = File(sceneTempDir, "sil_${idx}.wav")
            com.arthenica.ffmpegkit.FFmpegKit.execute("-y -f lavfi -i anullsrc=r=24000:cl=mono -t ${prep.wavDuration.fmtUS()} -c:a pcm_s16le ${sil.absolutePath}")
            audioSrc = sil; tempSil = sil
        }
        val pngFile = File(sceneTempDir, "card_${idx}.png")
        val hasCard = CardRenderer.render(prep.row, pngFile, cardX=prep.keyframes.holdX.toInt(), cardY=prep.keyframes.holdY.toInt())
        // v3.32: alpha-preserving codec (qtrle/.mov). magenta + colorkey workaround removed.
        if (hasCard) {
            // RGBA PNG -> qtrle: keep alpha (static loop, bounded by audio via -shortest)
            com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -r 30 -loop 1 -i ${pngFile.absolutePath} -i ${audioSrc.absolutePath} " +
                "-vf format=argb -c:v qtrle -c:a aac -b:a 128k -shortest ${cardFile.absolutePath}"
            )
        } else {
            // No card: fully transparent clip -> BGV 100% visible after overlay
            com.arthenica.ffmpegkit.FFmpegKit.execute(
                "-y -f lavfi -i color=c=black:size=${VIDEO_W}x${VIDEO_H}:rate=30 -i ${audioSrc.absolutePath} " +
                "-filter_complex [0:v]format=rgba,colorchannelmixer=aa=0,format=argb[cv] " +
                "-map [cv] -map 1:a -c:v qtrle -c:a aac -b:a 128k -shortest ${cardFile.absolutePath}"
            )
        }
        pngFile.delete(); tempSil?.delete()
        return if (cardFile.exists() && cardFile.length() > 0L) { onProgress("  카드[$idx]: ${cardFile.length()/1024}KB"); cardFile }
               else { Log.e(TAG, "카드[$idx] 실패"); null }
    }

    // v3.38: BGM 이음새 없는 loop 단위 (끝 d초 <-> 처음 d초 acrossfade). 소스 시작/끝 무음으로 인한 loop 끊김 제거. 실패 시 null.
    private fun buildSeamlessBgmLoop(bgmFile: File): File? {
        val bgmDur = getMediaDurationSecs(bgmFile)
        val d = 3.0f
        if (bgmDur < 2f * d + 1f) return null
        val unit = File(sceneTempDir, "bgm_seamless.m4a")
        val tailStart = bgmDur - d
        val bodyLen = bgmDur - d
        val filter = "[0:a]asetpts=PTS-STARTPTS[tl];[1:a]asetpts=PTS-STARTPTS[bd];[tl][bd]acrossfade=d=${d.fmtUS()}[a]"
        unit.delete()
        val cmd = "-y -ss ${tailStart.fmtUS()} -t ${d.fmtUS()} -i ${bgmFile.absolutePath} " +
            "-t ${bodyLen.fmtUS()} -i ${bgmFile.absolutePath} " +
            "-filter_complex $filter -map [a] -c:a aac -b:a 192k ${unit.absolutePath}"
        Log.i(TAG, "bgmLoop cmd: $cmd")
        com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        return if (unit.exists() && unit.length() > 0L) unit else null
    }

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
        val bgmSrc     = if (bgmFile != null) (buildSeamlessBgmLoop(bgmFile) ?: bgmFile) else null  // v3.38: BGM 이음매 크로스페이드 loop(실패 시 원본)
        val duration   = totalDurationSecs
        val wmEnd      = (duration - 5f).coerceAtLeast(introDurSecs + 1f)
        onProgress("합치기: ${subclipFiles.size}개 / ${duration.toInt()}초")
        // v3.36: 인트로↔바디 크로스페이드 (정확히 2개[인트로,바디]일 때만; 아니면 기존 하드컷 유지)
        val doXf = INTRO_BODY_XFADE > 0f && subclipFiles.size == 2
        val introDurX = if (doXf) getMediaDurationSecs(subclipFiles[0]) else 0f
        val xfD = INTRO_BODY_XFADE.coerceAtMost(introDurX * 0.5f).coerceAtLeast(0.3f)
        val canXf = doXf && introDurX > xfD + 0.5f
        // v3.36.2: 크로스페이드 실패 시 하드컷 자동 폴백 + 에러 화면 출력
        val xfAttempts = if (canXf) listOf(true, false) else listOf(false)
        var lastLog = ""
        for ((atI, useXf) in xfAttempts.withIndex()) {
        if (atI > 0) { useHwEnc = android.os.Build.VERSION.SDK_INT >= 21; onProgress("⚠ 크로스페이드 실패 → 하드컷 폴백 재시도") }
        val vFilt  = if (useXf) "[xv]" else "[0:v]"
        val aFilt  = if (useXf) "[xa]" else "[0:a]"
        val bgmIdx = if (useXf) "2" else "1"
        val fp = mutableListOf<String>()
        var vMap = if (useXf) "[xv]" else "0:v"
        var aMap = if (useXf) "[xa]" else "0:a"
        if (useXf) {
            val off = introDurX - xfD
            fp+="[0:v][1:v]xfade=transition=fade:duration=${xfD.fmtUS()}:offset=${off.fmtUS()}[xv]"
            fp+="[0:a]aformat=sample_rates=44100:channel_layouts=stereo[xa0]"
            fp+="[1:a]aformat=sample_rates=44100:channel_layouts=stereo[xa1]"
            fp+="[xa0][xa1]acrossfade=d=${xfD.fmtUS()}[xa]"
            onProgress("🎬 인트로↔바디 크로스페이드 ${xfD.fmtUS(1)}s")
        }
        if (watermarkText.isNotBlank()) {
            val esc=escapeDrawtext(watermarkText); val fo=if(fontPath.isNotEmpty())"fontfile='${fontPath}':" else ""
            fp+="${vFilt}drawtext=${fo}text='$esc':fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:x=30:y=40:enable='between(t\\,${introDurSecs.fmtUS(1)}\\,${wmEnd.fmtUS(1)})'[wm]"
            fp+="[wm]format=yuv420p[vout]"; vMap="[vout]"
        }
        if (bgmFile != null) {
            fp+="${aFilt}volume=0.85[tts]"
            fp+="[${bgmIdx}:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=volume='if(lt(t\\,13.0)\\,0.40\\,if(lt(t\\,15.0)\\,0.40+(t-13.0)*(-0.18)\\,0.04))':eval=frame[bgm]"
            fp+="[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3:normalize=0[aout]"; aMap="[aout]"
        }
        val cmd=buildString{
            if (useXf) append("-y -i ${subclipFiles[0].absolutePath} -i ${subclipFiles[1].absolutePath} ")
            else append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if(bgmFile!=null)append("-stream_loop -1 -i ${bgmSrc!!.absolutePath} ")
            if(fp.isNotEmpty())append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"$vMap\" -map \"$aMap\" ${vc("5M",20)} -c:a aac -b:a 192k -movflags +faststart ${outputFile.absolutePath}")
        }
        var rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if((!outputFile.exists()||outputFile.length()==0L)&&useHwEnc){
            useHwEnc=false; rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd.replace("-c:v h264_mediacodec -b:v 5M","-c:v libx264 -preset fast -crf 20"))
        }
        lastLog = "rc=${rc.returnCode?.value ?: -1} ${(rc.logsAsString ?: "").takeLast(600)}"
        if (outputFile.exists() && outputFile.length() > 0L) break
        onProgress("⚠ 합치기 실패(xf=$useXf): $lastLog")
        }
        listFile.delete()
        subclipFiles.filter{it.name.endsWith("_body.mp4")}.forEach{it.delete()}
        runCatching{sceneTempDir.listFiles()?.forEach{it.delete()}}
        return@withContext if(outputFile.exists()&&outputFile.length()>0){
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length()/1024/1024}MB)"); outputFile
        }else{ Log.e(TAG,"concat 실패: $lastLog"); onProgress("❌ concat 실패: $lastLog"); null }
    }

    fun release() {
        subclipFiles.clear(); totalDurationSecs=0f; actualIntroDurationSecs=21f; bgvFallbackCount=0
        runCatching{sceneTempDir.listFiles()?.forEach{it.delete()}}
    }

    fun getMediaDurationSecs(file: File): Float {
        if (!file.exists()) return 0f
        return try {
            val mmr=android.media.MediaMetadataRetriever(); mmr.setDataSource(file.absolutePath)
            val ms=mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            mmr.release(); ms/1000.0f
        }catch(e:Exception){Log.w(TAG,"duration [${file.name}]: ${e.message}");0f}
    }
    private fun synthesizeChunked(text:String,speakerId:Int,speed:Float,outputFile:File,numSteps:Int,sceneId:String,onProgress:(String)->Unit){
        ttsEngine.synthesize(text,speakerId,speed,outputFile,numSteps)
    }
    private fun calcCardKeyframes(pattern:AnimationPattern,tTotal:Float):CardKeyframes{
        val tOut=(tTotal-1f).coerceAtLeast(1.1f); val cx=VIDEO_W/2f-430f; val cy=VIDEO_H/2f-230f
        return when(pattern){
            AnimationPattern.A->CardKeyframes(-860f,VIDEO_H*.18f,VIDEO_W*.05f,VIDEO_H*.18f,CardOutType.TOP,1f,tOut,tTotal)
            AnimationPattern.B->CardKeyframes(VIDEO_W.toFloat(),VIDEO_H*.18f,VIDEO_W/2f-430f,VIDEO_H*.18f,CardOutType.BOTTOM,1f,tOut,tTotal)
            AnimationPattern.C->CardKeyframes(cx,-460f,cx,cy,CardOutType.BOTTOM,1f,tOut,tTotal)
            AnimationPattern.D->CardKeyframes(cx,cy,cx,cy,CardOutType.FADE,1f,tOut,tTotal)
            AnimationPattern.E->CardKeyframes(VIDEO_W*.05f,VIDEO_H*.68f,cx,cy,CardOutType.FADE,1f,tOut,tTotal)
            AnimationPattern.F->CardKeyframes(cx,cy,cx,cy,CardOutType.SCALE,0.5f,tOut,tTotal)
            AnimationPattern.G->CardKeyframes(-860f,VIDEO_H*.18f,VIDEO_W*.05f,VIDEO_H*.18f,CardOutType.ROTATE_SCALE,1f,tOut,tTotal)
            else->CardKeyframes(cx,cy,cx,cy,CardOutType.FADE,1f,tOut,tTotal)
        }
    }
    private fun escapeDrawtext(text:String):String=text.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
    private fun splitToLines(text: String, max: Int): List<String> =
        text.replace("\\N", "\n").replace("\\n", "\n").split("\n")
            .flatMap { line -> if (line.length > max) line.chunked(max) else listOf(line) }
            .filter { line -> line.isNotBlank() }
}

data class CardKeyframes(
    val inStartX:Float,val inStartY:Float,val holdX:Float,val holdY:Float,
    val outType:CardOutType,val tIn:Float,val tHold:Float,val tOut:Float
)
enum class CardOutType{TOP,BOTTOM,LEFT,RIGHT,FADE,SCALE,ROTATE_SCALE}
