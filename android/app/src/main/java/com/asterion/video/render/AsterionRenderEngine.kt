package com.asterion.video.render

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.asterion.video.AppConfig
import com.asterion.video.model.*
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AsterionRenderEngine"
private const val VW = 1920; private const val VH = 1080

class AsterionRenderEngine(private val context: Context, private val voiceConfig: VoiceConfig = VoiceConfig.DEFAULT) {
    private val tts = SupertonicTtsEngine(context)
    private val clips = mutableListOf<File>()

    suspend fun init(onProgress: (String)->Unit = {}) {
        AppConfig.ensureDirs(); tts.init(onProgress)
    }

    suspend fun renderScene(row: ScriptDataRow, meta: VideoMeta, onProgress: (String)->Unit = {}): File? =
        withContext(Dispatchers.IO) {
            val id = "scene_${row.rowIndex.toString().padStart(4,'0')}"
            onProgress("[$id] ${row.section}")
            try {
                val bg = AppConfig.resolveBgv(row.bgFileName)
                val wav = File(AppConfig.OUTPUT_DIR, "${id}_tts.wav")
                val cfg = voiceConfig.forSpeaker(row.speaker)
                if (row.script.isNotBlank() && row.sectionType != SectionType.BUFFER)
                    tts.synthesize(row.script, cfg.sid, cfg.speed, wav)
                val t = if (wav.exists()) tts.estimateDurationFromWav(wav) else 3.0f
                onProgress("[$id] T=${String.format("%.1f",t)}s")
                val ass = File(AppConfig.OUTPUT_DIR, "${id}.ass")
                buildAss(row, meta, ass)
                val out = File(AppConfig.OUTPUT_DIR, "${id}.mp4")
                val cmd = buildCmd(bg, wav.takeIf{it.exists()}, t, ass,
                    CardStyle.from(row.cardStyle), GradientPreset.from(row.gradientPreset),
                    row.bgEffectCode, out)
                onProgress("[$id] FFmpeg...")
                val rc = FFmpegKit.execute(cmd)
                wav.delete(); ass.delete()
                if (rc.returnCode.isValueSuccess) { clips.add(out); onProgress("[$id] ✅"); out }
                else { Log.e(TAG, rc.logsAsString.takeLast(200)); onProgress("[$id] ❌"); null }
            } catch(e: Exception) { Log.e(TAG, "$e"); null }
        }

    suspend fun concatSubclips(name: String, bgm: String, onProgress: (String)->Unit = {}): File? =
        withContext(Dispatchers.IO) {
            if (clips.isEmpty()) return@withContext null
            val list = File(AppConfig.OUTPUT_DIR, "concat_list.txt")
            list.writeText(clips.joinToString("\n") { "file '${it.absolutePath}'" })
            val out = File(AppConfig.OUTPUT_DIR, "$name.mp4")
            val bgmFile = AppConfig.resolveBgm(bgm)
            val cmd = if (bgmFile != null)
                "ffmpeg -y -f concat -safe 0 -i ${list.absolutePath} -i ${bgmFile.absolutePath} " +
                "-filter_complex \"[0:a]volume=1.0[t];[1:a]volume=0.3,afade=t=in:ss=0:d=2[b];[t][b]amix=inputs=2:duration=first[o]\" " +
                "-map 0:v -map \"[o]\" -c:v copy -c:a aac -b:a 192k ${out.absolutePath}"
            else "ffmpeg -y -f concat -safe 0 -i ${list.absolutePath} -c copy ${out.absolutePath}"
            val rc = FFmpegKit.execute(cmd)
            list.delete()
            if (rc.returnCode.isValueSuccess) { onProgress("✅ ${out.name} (${out.length()/1024/1024}MB)"); out } else null
        }

    fun release() { tts.release(); clips.clear() }

    private fun buildAss(row: ScriptDataRow, meta: VideoMeta, f: File) {
        val hl = row.highlightWord.trim()
        val main = if (hl.isNotBlank() && row.cardMain.contains(hl))
            row.cardMain.replace(hl, "{\\c&H4DDBFF&}$hl{\\c&HFFFFFF&}") else row.cardMain
        f.writeText(buildString {
            appendLine("[Script Info]\nScriptType: v4.00+\nPlayResX: $VW\nPlayResY: $VH\n")
            appendLine("[V4+ Styles]\nFormat: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding")
            appendLine("Style: Main,NotoSansKR-Bold,52,&H00FFFFFF,&H004DDBFF,&H80000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,30,30,80,1")
            appendLine("Style: Sub,NotoSansKR-Regular,38,&H00CCCCCC,&H00000000,&H80000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,30,30,80,1")
            appendLine("Style: WM,NotoSansKR-Regular,34,&H99FFFFFF,&H00000000,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,30,30,40,1\n")
            appendLine("[Events]\nFormat: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text")
            val cx = VW/2
            if (main.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,9:59:59.99,Main,,0,0,0,,{\\pos($cx,${(VH*0.28).toInt()})}${main.chunked(12).joinToString("\\N")}")
            if (row.cardSub.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,9:59:59.99,Sub,,0,0,0,,{\\pos($cx,${(VH*0.40).toInt()})}${row.cardSub.chunked(18).joinToString("\\N")}")
            if (meta.topWatermark.isNotBlank()) appendLine("Dialogue: 0,0:00:00.00,9:59:59.99,WM,,0,0,0,,{\\pos($cx,40)}${meta.topWatermark}")
        }, Charsets.UTF_8)
    }

    private fun buildCmd(bg: File, wav: File?, t: Float, ass: File, style: CardStyle, grad: GradientPreset, fx: String, out: File): String {
        val ap = ass.absolutePath.replace("'","\\'")
        val fxF = when(fx.split(":")[0]) { "VIGNETTE"->"vignette=PI/4"; "MOTION_BLUR"->"tmix=frames=3"; else->"" }
        val fxPart = if (fxF.isNotEmpty()) ",$fxF" else ""
        val cardPart = if (style!=CardStyle.NONE && style!=CardStyle.MINIMAL) {
            val r=(grad.topColor shr 16) and 0xFF; val g=(grad.topColor shr 8) and 0xFF; val b=grad.topColor and 0xFF
            ",drawbox=x=100:y=${(VH*0.18).toInt()}:w=860:h=360:color=0x${String.format("%02X%02X%02X",r,g,b)}@${style.alpha}:t=fill"
        } else ""
        val filter = "[0:v]trim=duration=$t,setpts=PTS-STARTPTS${fxPart}${cardPart},subtitles='$ap'[v]"
        val audioIn = if (wav!=null) "-i ${wav.absolutePath} " else ""
        val audioMap = if (wav!=null) "-map 1:a -c:a aac -b:a 192k " else "-an "
        return "ffmpeg -y -stream_loop -1 -i ${bg.absolutePath} ${audioIn}" +
               "-filter_complex \"$filter\" -map \"[v]\" ${audioMap}" +
               "-c:v h264_mediacodec -b:v 8M -t $t -movflags +faststart ${out.absolutePath}"
    }
}
