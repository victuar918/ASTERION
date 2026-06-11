package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// =================================================================
// ASTERION 영상 자동화 — 씬 렌더링 엔진 v3.28
//
// [변경로그 v3.28]
//   ■ 병렬 카드 인코딩: Semaphore(3) — 최대 3개 동시 FFmpeg
//   ■ h264_mediacodec GPU 인코더 우선, 실패 시 libx264 자동 폴백
//   ■ CardRenderer effRGB 제거 — 원본 그라디언트 색상 직접 사용
//   ■ 진단 로그 정리 (디버깅 완료)
// [변경로그 v3.27] assembleBody 영상 재설계 (WAV+카드 동기화)
// [변경로그 v3.26] BGV fade-in 제거 + libx264 직접 사용
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

    // h264_mediacodec(GPU) 우선, 실패 시 libx264 자동 폴백
    @Volatile private var useHwEnc: Boolean = android.os.Build.VERSION.SDK_INT >= 21

    /** 비디오 코덱 인자 반환. hw=true → h264_mediacodec, false → libx264 */
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

    // ── 카드 VF 빌더 (사용 안 함, CardRenderer로 대체됨) ────────────
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

    // ── 인트로 렌더링 ──────────────────────────────────

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
            val ds="15.0"
            fp+="[${silentIdx}:a]atrim=0:${ds},asetpts=PTS-STARTPTS[pre_sil]"
            fp+="[${numVid+1}:a]asetpts=PTS-STARTPTS[disc_a]"
            fp+="[pre_sil][disc_a]concat=n=2:v=0:a=1,apad=whole_dur=${(totalDur+1.0f).fmtUS(1)}[aout]"
        }
        val outFile=File(sceneTempDir,"scene_intro.mp4")
        val introVc = vc("4M", 23)
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
            append("-t ${totalDur.fmtUS()} -r 30 $introVc ")
            append("-movflags +faststart ${outFile.absolutePath}")
        }
        onProgress("🎬 인트로 (${totalDur.toInt()}초, 멌책=15s)...")
        Log.i(TAG,"renderIntro cmd: $cmd")
        val rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        if(!outFile.exists()||outFile.length()==0L){
            // HW 인코더 실패 시 libx264 폴백
            if(useHwEnc){
                useHwEnc=false
                val fbCmd=cmd.replace("-c:v h264_mediacodec -b:v 4M","-c:v libx264 -preset ultrafast -crf 23")
                com.arthenica.ffmpegkit.FFmpegKit.execute(fbCmd)
            }
            if(!outFile.exists()||outFile.length()==0L){
                Log.e(TAG,"인트로 실패: ${rc.logsAsString.takeLast(600)}")
                onProgress("⚠ 인트로 실패"); return@withContext null
            }
        }
        subclipFiles.add(0,outFile); totalDurationSecs+=totalDur
        actualIntroDurationSecs=totalDur
        onProgress("✅ 인트로 완료 (${totalDur.toInt()}초)")
        outFile
    }

    // ── Phase 1: WAV 생성 ──────────────────────────────────

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
            if(useCache){ttsWavFile=wavCache!!; onProgress("[$sceneId] WAV 쾐시")}
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
            val wavDuration=if(ttsWavFile.exists()&&ttsWavFile.length()>1024L)
                measureDuration(ttsWavFile,sceneId,onProgress)
            else 3.0f.also{if(hasTts)onProgress("[$sceneId] ⚠️ WAV실패")}
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

    // ── Phase 2: 조립 ────────────────────────────────────────
    //
    // v3.27 영상 재설계: WAV+카드 씨별 인코딩 → concat → BGV overlay
    // v3.28 병렬 인코딩 + h264_mediacodec GPU 추가

    suspend fun assembleBody(
        preps      : List<ScenePrep>,
        outputName : String,
        onProgress : (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (preps.isEmpty()) { onProgress("⚠️ 씨 없음"); return@withContext null }
        sceneTempDir.mkdirs()

        // ─ Step 1: 씨별 카드+WAV 인코딩 (병렬 Semaphore(3)) ───
        onProgress("🂳 씨별 카드+WAV 인코딩 (${preps.size}샐) [병렬 ${if(useHwEnc)"GPU" else "CPU"}]...")
        val cardSemaphore = Semaphore(3)  // 디바이스 하드웨어 인코더 사용량 고려

        val cardFileResults: List<File?> = coroutineScope {
            preps.map { prep ->
                async(Dispatchers.IO) {
                    cardSemaphore.withPermit {
                        encodeOneCard(prep, onProgress)
                    }
                }
            }.awaitAll()
        }

        val cardFiles = cardFileResults.filterNotNull().toMutableList()
        if (cardFiles.isEmpty()) { onProgress("❌ 카드 없음"); return@withContext null }

        // ─ Step 2: 카드 concat → body_cards.mp4 ───────────────
        onProgress("🔗 카드 concat (${cardFiles.size}샐)...")
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
        val actualBodyDur = measureDuration(bodyCardsFile, "body_cards", onProgress)
        onProgress("✅ body_cards: ${bodyCardsFile.length()/1024/1024}MB, ${actualBodyDur.fmtUS(1)}s")

        // ─ Step 3: BGV body ─────────────────────────────
        onProgress("📹 BGV 준비 (${actualBodyDur.fmtUS(1)}s)...")
        val uniqueBgvList = preps.map { it.bgFile }.distinctBy { it.absolutePath }
        val vfNorm = "scale=${VIDEO_W}:${VIDEO_H}:force_original_aspect_ratio=decrease," +
            "pad=${VIDEO_W}:${VIDEO_H}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,format=yuv420p"
        val bgvBodyFile = File(sceneTempDir, "bgv_body.mp4")
        val bgvCmd = "-y -stream_loop -1 -i ${uniqueBgvList[0].absolutePath} " +
            "-an -vf $vfNorm -r 30 ${vc("4M")} " +
            "-t ${actualBodyDur.fmtUS()} ${bgvBodyFile.absolutePath}"
        com.arthenica.ffmpegkit.FFmpegKit.execute(bgvCmd)
        if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
            // HW 폴백
            if (useHwEnc) {
                useHwEnc = false
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    bgvCmd.replace("-c:v h264_mediacodec -b:v 4M","-c:v libx264 -preset ultrafast -crf 23")
                )
            }
            if (!bgvBodyFile.exists() || bgvBodyFile.length() == 0L) {
                onProgress("❌ BGV 실패"); bodyCardsFile.delete(); return@withContext null
            }
        }
        onProgress("✅ BGV: ${bgvBodyFile.length()/1024/1024}MB")

        // ─ Step 4: colorkey overlay (BGV + 카드 레이어) ────
        val bodyFile = File(AppConfig.OUTPUT_DIR, "${outputName}_body.mp4")
        onProgress("🎬 BGV+카드 블렌딩 (${actualBodyDur.fmtUS(1)}s)...")
        val ckFilter = "[1:v]format=rgb24,colorkey=0xFF00FF:0.1:0.0[ck];" +
            "[0:v][ck]overlay=0:0,format=yuv420p[vout]"
        val blendCmd = "-y " +
            "-i ${bgvBodyFile.absolutePath} " +
            "-i ${bodyCardsFile.absolutePath} " +
            "-filter_complex $ckFilter " +
            "-map [vout] -map 1:a " +
            "${vc("5M", 20)} " +
            "-c:a aac -b:a 192k " +
            "-t ${actualBodyDur.fmtUS()} " +
            "-movflags +faststart " +
            bodyFile.absolutePath
        com.arthenica.ffmpegkit.FFmpegKit.execute(blendCmd)
        if (!bodyFile.exists() || bodyFile.length() == 0L) {
            if (useHwEnc) {
                useHwEnc = false
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    blendCmd.replace("-c:v h264_mediacodec -b:v 5M","-c:v libx264 -preset ultrafast -crf 20")
                )
            }
            if (!bodyFile.exists() || bodyFile.length() == 0L) {
                onProgress("❌ body 블렌딩 실패"); bgvBodyFile.delete(); bodyCardsFile.delete(); return@withContext null
            }
        }
        bgvBodyFile.delete(); bodyCardsFile.delete()
        preps.forEach { it.wavFile?.let { f -> if (f.absolutePath.contains(TEMP_SUBDIR)) f.delete() } }
        totalDurationSecs += actualBodyDur
        subclipFiles.add(bodyFile)
        onProgress("✅ body: ${bodyFile.length()/1024/1024}MB (${actualBodyDur.fmtUS(1)}s)")
        bodyFile
    }

    /** 씨 하나 인코딩 — 병렬 스레드에서 안전하게 호출 */
    private fun encodeOneCard(prep: ScenePrep, onProgress: (String) -> Unit): File? {
        val idx = prep.row.rowIndex.toString().padStart(4,'0')
        val cardFile = File(sceneTempDir, "card_${idx}.mp4")
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
        val pngFile = File(sceneTempDir, "card_${idx}.png")
        val hasCard = CardRenderer.render(
            prep.row, pngFile,
            cardX = prep.keyframes.holdX.toInt(),
            cardY = prep.keyframes.holdY.toInt()
        )
        fun runEncode(hw: Boolean): Boolean {
            val vc2 = if (hw) "-c:v h264_mediacodec -b:v 4M" else "-c:v libx264 -preset ultrafast -crf 23"
            if (hasCard) {
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y " +
                    "-f lavfi -i color=c=0xFF00FF:size=${VIDEO_W}x${VIDEO_H}:rate=30 " +
                    "-r 30 -loop 1 -i ${pngFile.absolutePath} " +
                    "-i ${audioSrc.absolutePath} " +
                    "-filter_complex [0:v][1:v]overlay=0:0[cv] " +
                    "-map [cv] -map 2:a $vc2 -c:a aac -b:a 128k " +
                    "-shortest ${cardFile.absolutePath}"
                )
            } else {
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y " +
                    "-f lavfi -i color=c=0xFF00FF:size=${VIDEO_W}x${VIDEO_H}:rate=30 " +
                    "-i ${audioSrc.absolutePath} $vc2 -c:a aac -b:a 128k " +
                    "-shortest ${cardFile.absolutePath}"
                )
            }
            return cardFile.exists() && cardFile.length() > 0L
        }
        // GPU 우선, 실패 시 libx264 폴백
        if (!runEncode(useHwEnc) && useHwEnc) {
            cardFile.delete(); useHwEnc = false
            runEncode(false)
        }
        pngFile.delete(); tempSil?.delete()
        return if (cardFile.exists() && cardFile.length() > 0L) {
            onProgress("  새[$idx]: ${cardFile.length()/1024}KB")
            cardFile
        } else {
            Log.e(TAG, "연[$idx] 카드 생성 실패")
            null
        }
    }

    // ── 합치기 + BGM + 워터마크 ─────────────────────────

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
            append("${vc("5M", 20)} -c:a aac -b:a 192k ")
            append("-movflags +faststart ${outputFile.absolutePath}")
        }
        val rc=com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        // 쳙 실패 시 libx264 폴백
        if ((!outputFile.exists()||outputFile.length()==0L) && useHwEnc) {
            useHwEnc=false
            val fbCmd=cmd.replace("-c:v h264_mediacodec -b:v 5M","-c:v libx264 -preset fast -crf 20")
            com.arthenica.ffmpegkit.FFmpegKit.execute(fbCmd)
        }
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
