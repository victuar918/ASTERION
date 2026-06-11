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
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.27
//
// [변경로그 v3.27] assembleBody 영샐 재설계
//   ■ WAV+카드 있엔코딩: WAV 재생 시점 = 카드 원시 시점 (눈금 시간 계산 없음)
//     - 각 씨: 검은 배경 + 카드 + WAV → scene_N_card.mp4 (-shortest)
//     - 드리프트 원체 차단: 누적 계산 없음, 실제 WAV 길이가 카드 길이
//   ■ 4단계 파이프라인:
//     1. 씬별 card.mp4 (WAV+카드, 검은 배경)
//     2. concat → body_cards.mp4 (실제 body 시간 = authoritative)
//     3. BGV body 인코딩 (body_cards 실제 길이 기준)
//     4. colorkey overlay: 카드레이어(검은배경투명) + BGV 엹음
//   ■ renderIntro: -r 30 추가 (프레임레이트 일치, 인트로→바디 전환 버터 방지)
// [변경로그 v3.26] BGV fade-in 제거 + libx264 직접 사용
// [변경로그 v3.25] WAV FFprobeKit정밀측정+BGM13/15+멄15s+인트로실제길이
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

    // WAV 길이: FFprobeKit → MMR → 파일크기 순서
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

    // 이모지 제거: FFmpeg drawtext 이모지 미지원 → filter 실패 방지
    private fun String.stripEmoji(): String = this
        .replace(Regex("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]"), "")
        .replace(Regex("[\\u2600-\\u27BF]"), "")
        .replace(Regex("[\\u2B00-\\u2BFF]"), "")
        .replace(Regex("  +"), " ").trim()

    // ── 카드 VF 빌더 (시간 조건 없음, 복잡한 enable= 불필요) ──────────
    // 검은 배경 위에 직접 그림, colorkey 시 검은색=투명
    // bordercolor 제거, shadowcolor=0x404040 (컄러키 안전리)
    private fun buildCardVf(prep: ScenePrep): String? {
        if (prep.cardStyle == CardStyle.NONE || prep.cardStyle == CardStyle.MINIMAL) return null
        val pm = prep.row.cardMain.trim().stripEmoji()
        val ps = prep.row.cardSub.trim().stripEmoji()
        val pd = prep.row.cardDesc.trim().stripEmoji()
        val r = (prep.gradient.topColor shr 16) and 0xFF
        val g = (prep.gradient.topColor shr 8)  and 0xFF
        val b =  prep.gradient.topColor and 0xFF
        // colorkey=black:0.05 안전: 순수 검은색 계열은 colorkey에 투명화되므로 최소 밝기 보정
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
            // shadowcolor=0x404040: 컄러키 threshold(0.05) 이상이어서 투명화 안됨
            val sh = "shadowcolor=0x404040@0.9:shadowx=2:shadowy=2"
            mainLines.forEachIndexed  {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=52:fontcolor=white:${sh}:x=${cx}-tw/2:y=${by+i*62}"}
            subLines.forEachIndexed   {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=38:fontcolor=0xCCCCCC:${sh}:x=${cx}-tw/2:y=${by+mainH+gap12+i*46}"}
            descLines.forEachIndexed  {i,l->parts+="drawtext=${fo2}text='${escapeDrawtext(l)}':fontsize=32:fontcolor=0xAAAAAA:${sh}:x=${cx}-tw/2:y=${by+mainH+gap12+subH+gap23+i*40}"}
        }
        return parts.joinToString(",")
    }

    // ── 인트로 렌더링 ──────────────────────────────────────────

    suspend fun renderIntro(
        videoMeta    : VideoMeta,
        disclaimerWav: File? = null,
        onProgress   : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (videoMeta.introBgv1.isBlank()) {
            onProgress("인트로 BGV 미설정 — 생략")
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
        onProgress("인트로: bgv1=${bgv1Dur.fmtUS(1)}s bgv2=${bgv2Dur.fmtUS(1)}s total=${totalDur.fmtUS(1)}s")
        val p1Start = 2.0f
        val p1End   = (bgv1Dur-xfadeDur-0.3f).coerceAtLeast(p1Start+1f)
        val p2Base  = bgv1Dur-xfadeDur
        val t1=p2Base+2f; val t2=p2Base+4f; val t3=p2Base+6f; val t4=p2Base+8f
        val tAllEnd = (t4+6f).coerceAtMost(totalDur-0.5f)
        val isXrp = videoMeta.introType.trim().uppercase() == "XRP"
        val titleWord = if (isXrp) "XRP 전망" else "크립토 갤러리"
        val rotWord   = if (isXrp) "예측하는" else "둘러보는"
        fun esc(s:String)=s.replace("\\","\\\\").replace("'","\\'").replace(":","\\:").replace(",","\\,")
        fun alpha(ts:Float,te:Float):String{
            val fi=(ts+0.5f).fmtUS(2);val fo=(te-0.5f).coerceAtLeast(ts+0.6f).fmtUS(2)
            return "if(lt(t,$fi),(t-${ts.fmtUS(2)})/0.5,if(gt(t,$fo),(${te.fmtUS(2)}-t)/0.5,1))"
        }
        fun en(ts:Float,te:Float)="between(t,${ts.fmtUS(2)},${te.fmtUS(2)})"
        val fp   = mutableListOf<String>()
        val fOpt = if (fontPath.isNotEmpty()) "fontfile='$fontPath':" else ""
        if(hasBgv2&&bgv2!=null){
            val xOff=(bgv1Dur-xfadeDur).coerceAtLeast(0.5f)
            fp+="[0:v]setpts=PTS-STARTPTS[v0]"; fp+="[1:v]setpts=PTS-STARTPTS[v1]"
            fp+="[v0][v1]xfade=transition=fade:duration=${xfadeDur.fmtUS()}:offset=${xOff.fmtUS()}[bgv]"
        }else{fp+="[0:v]setpts=PTS-STARTPTS[bgv]"}
        var cur="[bgv]"
        if(videoMeta.introText.isNotBlank()){
            fp+="${cur}drawtext=${fOpt}text='${esc(videoMeta.introText)}':fontsize=64:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H/3-th/2:alpha='${alpha(p1Start,p1End)}':enable='${en(p1Start,p1End)}'[p1]"
            cur="[p1]"
        }
        if(hasBgv2){
            val px100=160;val px70=112;val px40=64
            fp+="${cur}drawtext=${fOpt}text='베다점성술로':fontsize=$px70:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.15-th/2:alpha='${alpha(t1,tAllEnd)}':enable='${en(t1,tAllEnd)}'[t1]";cur="[t1]"
            fp+="${cur}drawtext=${fOpt}text='${esc(rotWord)}':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.37-th/2:alpha='${alpha(t2,tAllEnd)}':enable='${en(t2,tAllEnd)}'[t2]";cur="[t2]"
            fp+="${cur}drawtext=${fOpt}text='${esc(titleWord)}':fontsize=$px100:fontcolor=white:borderw=3:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.57-th/2:alpha='${alpha(t3,tAllEnd)}':enable='${en(t3,tAllEnd)}'[t3]";cur="[t3]"
            fp+="${cur}drawtext=${fOpt}text='by ASTERION':fontsize=$px40:fontcolor=white:borderw=2:bordercolor=black@0.8:x=(W-tw)/2:y=H*0.77-th/2:alpha='${alpha(t4,tAllEnd)}':enable='${en(t4,tAllEnd)}'[t4]";cur="[t4]"
        }
        fp+="${cur}format=yuv420p[vfinal]"
        val numVid=if(hasBgv2)2 else 1; val silentIdx=numVid
        val hasDisc=disclaimerWav!=null&&disclaimerWav.exists()
        if(hasDisc){
            val ds="15.0"  // BGM 13-15s 페이드 후 t=15s 면책문구
            fp+="[${silentIdx}:a]atrim=0:${ds},asetpts=PTS-STARTPTS[pre_sil]"
            fp+="[${numVid+1}:a]asetpts=PTS-STARTPTS[disc_a]"
            fp+="[pre_sil][disc_a]concat=n=2:v=0:a=1,apad=whole_dur=${(totalDur+1.0f).fmtUS(1)}[aout]"
        }
        val outFile=File(sceneTempDir,"scene_intro.mp4")
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
            // v3.27: -r 30 추가 → 본문 body(30fps)와 프레임레이트 일치 → 팅키 방지
            append("-t ${totalDur.fmtUS()} -r 30 -c:v libx264 -preset ultrafast -crf 23 ")
            append("-movflags +faststart ${outFile.absolutePath}")
        }
        onProgress("🎬 인트로 (${totalDur.toInt()}초, 멄책=15s)...")
        Log.i(TAG,"renderIntro cmd: $cmd")
        val rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if(!outFile.exists()||outFile.length()==0L){
            Log.e(TAG,"인트로 실패: ${rc.logsAsString.takeLast(600)}")
            onProgress("⚠ 인트로 실패"); return@withContext null
        }
        subclipFiles.add(0,outFile); totalDurationSecs+=totalDur
        actualIntroDurationSecs=totalDur
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
        val sceneId="scene_${row.rowIndex.toString().padStart(4,'0')}"
        try {
            val hasTts=row.script.isNotBlank()&&row.sectionType!=SectionType.BUFFER
            val wavCache=cacheDir?.let{File(it,"${sceneId}.wav")}
            val useCache=wavCache!=null&&wavCache.exists()&&wavCache.length()>1024L
            val ttsWavFile: File
            if(useCache){ttsWavFile=wavCache!!; onProgress("[$sceneId] WAV 캐시")}
            else{
                ttsWavFile=File(sceneTempDir,"${sceneId}_tts.wav")
                if(hasTts){
                    val cfg=voiceConfig.forSpeaker(row.speaker)
                    onProgress("[$sceneId] TTS: ${cfg.label}(${cfg.sid},${cfg.numSteps}step)")
                    synthesizeChunked(row.script,cfg.sid,cfg.speed,ttsWavFile,cfg.numSteps,sceneId,onProgress)
                }else{
                    com.arthenica.ffmpegkit.FFmpegKit.execute("-y -f lavfi -i anullsrc=r=24000:cl=mono -t 3.0 -c:a pcm_s16le ${ttsWavFile.absolutePath}")
                }
                if(wavCache!=null&&ttsWavFile.exists()&&ttsWavFile.length()>1024L)
                    try{ttsWavFile.copyTo(wavCache,overwrite=true)}catch(_:Exception){}
            }
            // v3.25: FFprobeKit 정밀 측정 (prepareScene에서는 사용되지 않음)
            // v3.27: 실제 인코딩에서 -shortest 사용하므로 wavDuration은 추정치만 3.0s로
            // 먹버캿 수: WAV 카드 인코딩 시 -shortest로 자동 정확한 시간 결정
            val wavDuration=if(ttsWavFile.exists()&&ttsWavFile.length()>1024L)
                measureDuration(ttsWavFile,sceneId,onProgress)
            else 3.0f.also{if(hasTts)onProgress("[$sceneId] ⚠️ WAV실패도리평s")}
            onProgress("[$sceneId] WAV: ${ttsWavFile.length()/1024}KB ${wavDuration.fmtUS(1)}s")
            val bgFile=AppConfig.resolveBgv(row.bgFileName)
            val pattern=AnimationPattern.from(row.animation)
            val keyframes=calcCardKeyframes(pattern,wavDuration)
            val textStartSecs=if(pattern in MOTION_PATTERNS)keyframes.tIn else 0f
            ScenePrep(
                row=row,wavFile=ttsWavFile.takeIf{it.exists()&&it.length()>1024L},
                bgvCutFile=null,bgFile=bgFile,wavDuration=wavDuration,startSecs=startSecs,
                cardStyle=CardStyle.from(row.cardStyle.trim()),
                gradient=GradientPreset.from(
                    row.gradientPreset.trim()
                        .takeIf { it.isNotBlank() && it.uppercase() != "DEFAULT" }
                        ?: row.cardStyle.trim()
                ),
                keyframes=keyframes,textStartSecs=textStartSecs
            )
        }catch(e:Exception){
            Log.e(TAG,"prepareScene [$sceneId]: $e"); onProgress("[$sceneId] ❌ ${e.message}"); null
        }
    }

    // ── Phase 2: 조립 ────────────────────────────────────────────
    //
    // v3.27 영샐 재설계 - WAV 동기화
    //
    // 이전 방식 (문제): filter_complex_script + enable= 조건
    //   → WAV 측정 오차가 씨마다 누적 → 갈수록 카드가 점점 뒤에 나타남
    //
    // 새 방식: 써별 카드+WAV 인코딩
    //   Step1. 각 씨: [검은배경 + 카드] + [WAV] → card_N.mp4 (-shortest)
    //          시간 계산 없음: WAV 재생 = 카드 동시
    //   Step2. concat → body_cards.mp4 (실제 body 시간 = authoritative)
    //   Step3. BGV body (body_cards 실제 시간에 맞춰 stream_loop)
    //   Step4. colorkey overlay: 검은배경투명 → BGV가 배경으로 보임

    suspend fun assembleBody(
        preps      : List<ScenePrep>,
        outputName : String,
        onProgress : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (preps.isEmpty()) { onProgress("⚠️ 씬 없음"); return@withContext null }
        sceneTempDir.mkdirs()

        // ─ Step 1: 씨별 카드+WAV 인코딩 ───────────────────────
        // [검은배경] 위에 카드 그림 + WAV 오디오 → card_N.mp4
        // -shortest: 취른 시간 = WAV 실제 길이 (측정 오차 없음)
        onProgress("🃏 씨별 카드+WAV 인코딩 (${preps.size}씨)...")
        val cardFiles = mutableListOf<File>()
        for (prep in preps) {
            val idx = prep.row.rowIndex.toString().padStart(4,'0')
            val cardFile = File(sceneTempDir, "card_${idx}.mp4")
            // WAV 소스 확정
            val wavSrc = prep.wavFile?.takeIf { it.exists() && it.length() > 1024L }
            val audioSrc: File
            var tempSil: File? = null
            if (wavSrc != null) {
                audioSrc = wavSrc
            } else {
                val sil = File(sceneTempDir, "sil_${idx}.wav")
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -f lavfi -i anullsrc=r=24000:cl=mono " +
                    "-t ${prep.wavDuration.fmtUS()} -c:a pcm_s16le ${sil.absolutePath}"
                )
                audioSrc = sil; tempSil = sil
            }
            // CardRenderer (Android Canvas) → ARGB PNG → 마젠타배경 overlay
            // Step4 colorkey=0xFF00FF: 마젠타만 투명화, 카드 패널 색상은 모두 안전
            val pngFile = File(sceneTempDir, "card_${idx}.png")
            val resolvedGradKey = prep.row.gradientPreset.trim()
                .takeIf { it.isNotBlank() && it.uppercase() != "DEFAULT" } ?: prep.row.cardStyle.trim()
            val resolvedGrad = GradientPreset.from(resolvedGradKey)
            onProgress("[STYLE-$idx] sty=${prep.cardStyle.name} a=${prep.cardStyle.alpha} " +
                "top=0x${String.format("%06X", resolvedGrad.topColor and 0xFFFFFF)} " +
                "bot=0x${String.format("%06X", resolvedGrad.bottomColor and 0xFFFFFF)}")
            onProgress("[GRAD-RAW-$idx] key='$resolvedGradKey' " +
                "rawTop=0x${Integer.toHexString(resolvedGrad.topColor)} " +
                "rawBot=0x${Integer.toHexString(resolvedGrad.bottomColor)}")
            val fR = ((resolvedGrad.topColor shr 16 and 0xFF) * prep.cardStyle.alpha).toInt()
            val fG = ((resolvedGrad.topColor shr 8  and 0xFF) * prep.cardStyle.alpha).toInt()
            val fB = ((resolvedGrad.topColor        and 0xFF) * prep.cardStyle.alpha).toInt()
            onProgress("[COLOR-$idx] rawTop=0x${Integer.toHexString(resolvedGrad.topColor)} " +
                "finalRGB=($fR,$fG,$fB) finalARGB=(255,$fR,$fG,$fB)")
            onProgress("[POS-$idx] holdX=${prep.keyframes.holdX.toInt()} holdY=${prep.keyframes.holdY.toInt()} " +
                "finalX=${prep.keyframes.holdX.toInt()} finalY=${prep.keyframes.holdY.toInt()}")
            val hasCard = CardRenderer.render(
                prep.row, pngFile,
                cardX = prep.keyframes.holdX.toInt(),
                cardY = prep.keyframes.holdY.toInt()
            )
            if (hasCard) {
                val rc = com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y " +
                    "-f lavfi -i color=c=0xFF00FF:size=${VIDEO_W}x${VIDEO_H}:rate=30 " +
                    "-r 30 -loop 1 -i ${pngFile.absolutePath} " +
                    "-i ${audioSrc.absolutePath} " +
                    "-filter_complex [0:v][1:v]overlay=0:0[cv] " +
                    "-map [cv] -map 2:a " +
                    "-c:v libx264 -preset ultrafast -crf 23 " +
                    "-c:a aac -b:a 128k " +
                    "-shortest ${cardFile.absolutePath}"
                )
                if (!cardFile.exists() || cardFile.length() == 0L) {
                    Log.w(TAG, "카드 인코딩 실패[$idx]: ${rc.logsAsString.takeLast(200)}")
                }
            }
            // PNG 픽셀 검증 (0001·0003 기준 — 카드/배경 alpha 실측)
            if (idx in setOf("0001","0003") && pngFile.exists()) {
                val bmpFull = android.graphics.BitmapFactory.decodeFile(pngFile.absolutePath)
                if (bmpFull != null) {
                    val cX = (prep.keyframes.holdX.toInt() + CardRenderer.CARD_W / 2).coerceIn(0, bmpFull.width - 1)
                    val cY = (prep.keyframes.holdY.toInt() + CardRenderer.CARD_H / 2).coerceIn(0, bmpFull.height - 1)
                    val cp = bmpFull.getPixel(cX, cY)
                    val bp = bmpFull.getPixel(10, 10)
                    onProgress("[PNG-$idx] 카드중심($cX,$cY): " +
                        "A=${android.graphics.Color.alpha(cp)} R=${android.graphics.Color.red(cp)} " +
                        "G=${android.graphics.Color.green(cp)} B=${android.graphics.Color.blue(cp)}")
                    onProgress("[PNG-$idx] 배경(10,10): " +
                        "A=${android.graphics.Color.alpha(bp)} R=${android.graphics.Color.red(bp)} " +
                        "G=${android.graphics.Color.green(bp)} B=${android.graphics.Color.blue(bp)}")
                    bmpFull.recycle()
                }
            }
            // PNG 진단 로그 (0001·0010·0050)
            if (idx in setOf("0001","0010","0050") && pngFile.exists()) {
                val bOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(pngFile.absolutePath, bOpts)
                val ct = try { pngFile.inputStream().use { s -> s.skip(25); s.read() } } catch (_:Exception){-1}
                val ctStr = when(ct) { 6->"RGBA" 2->"RGB" else->"?" }
                Log.i(TAG, "[PNG진단-$idx] ${pngFile.length()/1024}KB " +
                    "${bOpts.outWidth}\u00d7${bOpts.outHeight} " +
                    "colorType=$ct($ctStr) hasCard=$hasCard")
            }
            pngFile.delete()
            if (!cardFile.exists() || cardFile.length() == 0L) {
                // 카드 없음 또는 실패: 마젠타배경 + WAV만
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y " +
                    "-f lavfi -i color=c=0xFF00FF:size=${VIDEO_W}x${VIDEO_H}:rate=30 " +
                    "-i ${audioSrc.absolutePath} " +
                    "-c:v libx264 -preset ultrafast -crf 23 " +
                    "-c:a aac -b:a 128k " +
                    "-shortest ${cardFile.absolutePath}"
                )
            }
            tempSil?.delete()
            if (cardFile.exists() && cardFile.length() > 0L) {
                cardFiles.add(cardFile)
                onProgress("  씨[$idx]: ${cardFile.length()/1024}KB")
            } else {
                Log.e(TAG, "씨[$idx] 카드 생성 실패")
            }
        }
        if (cardFiles.isEmpty()) { onProgress("❌ 카드 없음"); return@withContext null }

        // ─ Step 2: 카드 concat → body_cards.mp4 ───────────────────
        // body_cards의 실제 시간 = WAV 실제 길이 합산 (authoritative)
        onProgress("🔗 카드 concat (${cardFiles.size}씨)...")
        val cardList = File(sceneTempDir, "card_list.txt")
        cardList.writeText(cardFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        val bodyCardsFile = File(sceneTempDir, "body_cards.mp4")
        val cRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -f concat -safe 0 -i ${cardList.absolutePath} -c copy ${bodyCardsFile.absolutePath}"
        )
        cardList.delete(); cardFiles.forEach { it.delete() }
        if (!bodyCardsFile.exists() || bodyCardsFile.length() == 0L) {
            Log.e(TAG, "body_cards concat 실패: ${cRc.logsAsString.takeLast(400)}")
            onProgress("❌ 카드 concat 실패"); return@withContext null
        }
        // 실제 body 길이 측정 (WAV 실제 시간 기반)
        val actualBodyDur = measureDuration(bodyCardsFile, "body_cards", onProgress)
        onProgress("✅ body_cards: ${bodyCardsFile.length()/1024/1024}MB, ${actualBodyDur.fmtUS(1)}s")

        // ─ Step 3: BGV body (body_cards 실제 시간 기준) ─────────
        onProgress("📹 BGV 준비 (${actualBodyDur.fmtUS(1)}s)...")
        val uniqueBgvList = preps.map { it.bgFile }.distinctBy { it.absolutePath }
        val vfNorm = "scale=${VIDEO_W}:${VIDEO_H}:force_original_aspect_ratio=decrease," +
            "pad=${VIDEO_W}:${VIDEO_H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p"
        val bgvBodyFile = File(sceneTempDir, "bgv_body.mp4")
        val bgvRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -stream_loop -1 -i ${uniqueBgvList[0].absolutePath} " +
            "-an -vf $vfNorm -r 30 -c:v libx264 -preset ultrafast -crf 23 " +
            "-t ${actualBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
        )
        if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
            Log.e(TAG, "BGV 실패: ${bgvRc.logsAsString.takeLast(300)}")
            onProgress("❌ BGV 실패"); bodyCardsFile.delete(); return@withContext null
        }
        onProgress("✅ BGV: ${bgvBodyFile.length()/1024/1024}MB")

        // ─ Step 4: colorkey overlay (BGV 배경 + 카드 레이어) ──────
        // 카드레이어 검은배경(0x000000) → colorkey로 투명화
        // BGV가 배경으로 보이고 색상입힘 카드만 위에 오버레이
        // shadowcolor=0x404040 사용한 텍스트 스노우: threshold(0.05)보다 높아 투명화 안됨
        val bodyFile = File(AppConfig.OUTPUT_DIR, "${outputName}_body.mp4")
        onProgress("🎬 BGV+카드 블렌딩 (${actualBodyDur.fmtUS(1)}s)...")
        Log.i(TAG, "assembleBody v3.27: src=${uniqueBgvList.size} preps=${preps.size} dur=${actualBodyDur.fmtUS(1)}s")
        // filter_complex: 이스케이프 없이 변수로 분리 (parser 오류 방지)
        // colorkey=0xFF00FF: 마젠타 배경 투명화 → BGV 노출
        // 카드 패널(그라디언트) 색상은 마젠타와 충분히 달라 threshold=0.1에서도 안전
        val ckFilter = "[1:v]format=rgb24,colorkey=0xFF00FF:0.1:0.0[ck];" +
            "[0:v][ck]overlay=0:0,format=yuv420p[vout]"
        val bRc = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y " +
            "-i ${bgvBodyFile.absolutePath} " +
            "-i ${bodyCardsFile.absolutePath} " +
            "-filter_complex $ckFilter " +
            "-map [vout] -map 1:a " +
            "-c:v libx264 -preset ultrafast -crf 20 " +
            "-c:a aac -b:a 192k " +
            "-t ${actualBodyDur.fmtUS()} " +
            "-movflags +faststart " +
            bodyFile.absolutePath
        )
        bgvBodyFile.delete(); bodyCardsFile.delete()
        preps.forEach { it.wavFile?.let { f -> if (f.absolutePath.contains(TEMP_SUBDIR)) f.delete() } }
        if (!bodyFile.exists() || bodyFile.length() == 0L) {
            Log.e(TAG, "body 블렌딩 실패: ${bRc.logsAsString.takeLast(400)}")
            onProgress("❌ body 블렌딩 실패"); return@withContext null
        }
        totalDurationSecs += actualBodyDur
        subclipFiles.add(bodyFile)
        onProgress("✅ body: ${bodyFile.length()/1024/1024}MB (${actualBodyDur.fmtUS(1)}s)")
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
        val fp = mutableListOf<String>(); var vMap="0:v"; var aMap="0:a"
        if (watermarkText.isNotBlank()) {
            val esc = escapeDrawtext(watermarkText)
            val fo  = if (fontPath.isNotEmpty()) "fontfile='${fontPath}':" else ""
            fp+="[0:v]drawtext=${fo}text='$esc':fontsize=34:fontcolor=white@0.65:shadowcolor=black@0.75:shadowx=2:shadowy=2:x=30:y=40:enable='between(t\\,${introDurSecs.fmtUS(1)}\\,${wmEnd.fmtUS(1)})'[wm]"
            fp+="[wm]format=yuv420p[vout]"; vMap="[vout]"
        }
        if (bgmFile != null) {
            fp+="[0:a]volume=0.85[tts]"
            // v3.25: BGM 페이드 13s~15s (면책문구 t=15s 시작점 기준)
            fp+="[1:a]aformat=sample_rates=44100:channel_layouts=stereo," +
                "volume=volume='if(lt(t\\,13.0)\\,0.40\\,if(lt(t\\,15.0)\\,0.40+(t-13.0)*(-0.175)\\,0.05))':eval=frame[bgm]"
            fp+="[tts][bgm]amix=inputs=2:duration=first:dropout_transition=3:normalize=0[aout]"
            aMap="[aout]"
        }
        val cmd=buildString{
            append("-y -f concat -safe 0 -i ${listFile.absolutePath} ")
            if(bgmFile!=null)append("-stream_loop -1 -i ${bgmFile.absolutePath} ")
            if(fp.isNotEmpty())append("-filter_complex \"${fp.joinToString(";")}\" ")
            append("-map \"$vMap\" -map \"$aMap\" ")
            append("-c:v libx264 -preset fast -crf 20 -c:a aac -b:a 192k ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }
        val rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        listFile.delete()
        subclipFiles.filter{it.name.endsWith("_body.mp4")}.forEach{it.delete()}
        runCatching{sceneTempDir.listFiles()?.forEach{it.delete()}}
        return@withContext if(outputFile.exists()&&outputFile.length()>0){
            onProgress("✅ 완성: ${outputFile.name} (${outputFile.length()/1024/1024}MB)"); outputFile
        }else{
            Log.e(TAG,"concat 실패: ${rc.logsAsString.takeLast(400)}"); onProgress("❌ concat 실패"); null
        }
    }

    fun release() {
        subclipFiles.clear(); totalDurationSecs=0f; actualIntroDurationSecs=21f
        runCatching{sceneTempDir.listFiles()?.forEach{it.delete()}}
    }

    fun getMediaDurationSecs(file: File): Float {
        if (!file.exists()) return 0f
        return try {
            val mmr=android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val ms=mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            mmr.release(); ms/1000.0f
        }catch(e:Exception){Log.w(TAG,"재생길이 [${file.name}]: ${e.message}");0f}
    }
    private fun getBgvDurationSecs(bgFile:File):Float=getMediaDurationSecs(bgFile)
    private fun synthesizeChunked(text:String,speakerId:Int,speed:Float,outputFile:File,numSteps:Int,sceneId:String,onProgress:(String)->Unit){
        ttsEngine.synthesize(text,speakerId,speed,outputFile,numSteps)
    }
    private fun calcCardKeyframes(pattern:AnimationPattern,tTotal:Float):CardKeyframes{
        val tOut=(tTotal-1f).coerceAtLeast(1.1f);val cx=VIDEO_W/2f-430f;val cy=VIDEO_H/2f-170f
        return when(pattern){
            AnimationPattern.A->CardKeyframes(-860f,VIDEO_H*.18f,VIDEO_W*.05f,VIDEO_H*.18f,CardOutType.TOP,1f,tOut,tTotal)
            AnimationPattern.B->CardKeyframes(VIDEO_W.toFloat(),VIDEO_H*.18f,VIDEO_W/2f-430f,VIDEO_H*.18f,CardOutType.BOTTOM,1f,tOut,tTotal)
            AnimationPattern.C->CardKeyframes(cx,-340f,cx,cy,CardOutType.BOTTOM,1f,tOut,tTotal)
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
