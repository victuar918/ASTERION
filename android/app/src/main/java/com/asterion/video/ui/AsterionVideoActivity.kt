package com.asterion.video.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.asterion.video.AppConfig
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.sheets.SheetsVideoReader
import com.asterion.video.sheets.VIDEO_SS_ID
import com.asterion.video.tts.SpeakerConfig
import com.asterion.video.tts.SupertonicTtsEngine
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AsterionVideoActivity : AppCompatActivity() {

    private lateinit var tvKeyStatus: TextView
    private lateinit var spinnerSheet: Spinner
    private lateinit var llSpeakers: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val speakerSpinners    = mutableMapOf<Int, Spinner>()
    private val speakerSeekBars    = mutableMapOf<Int, SeekBar>()
    private val speakerSpeedLabels = mutableMapOf<Int, TextView>()

    private val auth by lazy { ServiceAccountAuth(this) }
    private var reader: SheetsVideoReader? = null
    private var engine: AsterionRenderEngine? = null
    private var ttsEngine: SupertonicTtsEngine? = null
    private var isRendering = false
    private var mediaPlayer: MediaPlayer? = null

    private fun hasAllFilesPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            true

    private fun requestAllFilesPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${packageName}")
            )
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,16,48,48) }
        scroll.addView(layout); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layout.setPadding(48, b.top+16, 48, b.bottom+48); insets
        }
        tvKeyStatus  = TextView(this).apply { textSize=12f; setTextColor(0xFFAAAAAA.toInt()) }
        spinnerSheet = Spinner(this)
        llSpeakers   = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        btnStart     = Button(this).apply { text="▶ 영상 제작 시작"; isEnabled=false }
        btnStop      = Button(this).apply { text="⏹ 중지"; isEnabled=false }
        progressBar  = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).also{it.topMargin=12} }
        tvStatus = TextView(this).apply { text="시작 중..."; textSize=14f; setPadding(0,16,0,8) }
        tvLog    = TextView(this).apply { textSize=10f; setTextColor(0xFF777777.toInt()); maxLines=16 }
        listOf(tvKeyStatus,spinnerSheet,llSpeakers,btnStart,btnStop,progressBar,tvStatus,tvLog).forEach{layout.addView(it)}
        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }
        spinnerSheet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long) {
                loadSpeakersFromSheet(spinnerSheet.selectedItem?.toString() ?: return)
            }
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
        lifecycleScope.launch { initCore() }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesPermission() && engine == null) {
            lifecycleScope.launch { initCore() }
        }
    }

    private fun buildSpeakerUI(speakers: List<Int>) {
        llSpeakers.removeAllViews()
        speakerSpinners.clear(); speakerSeekBars.clear(); speakerSpeedLabels.clear()
        if (speakers.isEmpty()) return
        llSpeakers.addView(TextView(this).apply { text="🎤 화자 음성 설정 (Supertonic 3)"; textSize=12f; setTextColor(0xFFCCCCCC.toInt()); setPadding(0,16,0,4) })
        // defVoice: ASTERION 화자번호 → Supertonic 3 sid 초기 선택 인덱스
        // VoiceConfig.SID_LIST = [0,1,...,9] 이므로 인덱스 = sid 값과 일치
        val defVoice = mapOf(1 to 0, 2 to 5, 3 to 1)
        val defSpeed = mapOf(1 to 50, 2 to 42, 3 to 58)
        for (sid in speakers.sorted()) {
            val name = when(sid){1->"아스터";2->"리언";3->"나레이터";else->"Speaker$sid"}
            llSpeakers.addView(TextView(this).apply{text="[$sid] $name";textSize=12f;setTextColor(0xFFEEEEEE.toInt());setPadding(0,12,0,2)})
            val rowModel = LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;
                layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)}
            val spinner = Spinner(this).apply{
                layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)
                adapter=ArrayAdapter(this@AsterionVideoActivity,android.R.layout.simple_spinner_item,VoiceConfig.VOICE_LABELS)
                    .also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
                setSelection(defVoice[sid]?:0)
            }
            speakerSpinners[sid]=spinner
            val btnTest=Button(this).apply{text="🔊";textSize=14f;
                layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener{testSpeaker(sid)}}
            rowModel.addView(spinner);rowModel.addView(btnTest);llSpeakers.addView(rowModel)
            val speedLabel=TextView(this).apply{textSize=11f;setTextColor(0xFF999999.toInt());setPadding(0,4,0,0)}
            speakerSpeedLabels[sid]=speedLabel
            val seekBar=SeekBar(this).apply{
                max=100; progress=defSpeed[sid]?:50
                layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){speedLabel.text="  속도: ${progressToSpeed(v)}x"}
                    override fun onStartTrackingTouch(s:SeekBar?){} override fun onStopTrackingTouch(s:SeekBar?){}})
            }
            speedLabel.text="  속도: ${progressToSpeed(seekBar.progress)}x"
            speakerSeekBars[sid]=seekBar; llSpeakers.addView(seekBar); llSpeakers.addView(speedLabel)
        }
    }

    private fun testSpeaker(sid: Int) {
        // VoiceConfig.SID_LIST = [0,1,...,9] — 스피너 선택 위치 = sherpa-onnx speaker ID
        val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[sid]?.selectedItemPosition ?: 0]
        val speed     = progressToSpeed(speakerSeekBars[sid]?.progress ?: 50)
        val testText  = when(sid){1->"안녕하세요. 에너지 분석을 시작합니다.";2->"극과의 에너지가 축적되는 구간입니다.";else->"운명은 해석하는 순간 바뀌지 않습니다."}
        AppConfig.ensureDirs()
        val errFile = File(applicationContext.filesDir, "tts_error.txt")
        errFile.delete()
        updateStatus("🔊 [$sid] sid=$sherpaSid speed=$speed 합성 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te  = ttsEngine ?: run { withContext(Dispatchers.Main) { updateStatus("❌ TTS 엔진 미초기화") }; return@launch }
                val out = File(AppConfig.OUTPUT_DIR, "test_sid${sid}.wav")
                val ok  = te.synthesize(testText, sherpaSid, speed, out)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply { setDataSource(out.absolutePath); prepare(); start() }
                        updateStatus("🔊 [$sid] sid=$sherpaSid ${out.length()/1024}KB 재생 중")
                    } else {
                        val errMsg = if (errFile.exists()) errFile.readText() else "synthesize() false"
                        updateStatus("❌ TTS 실패:\n$errMsg")
                        appendLog("❌ $errMsg")
                    }
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) { updateStatus("❌ 예외: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private fun progressToSpeed(p: Int): Float =
        String.format("%.2f", 0.7f + p.toFloat()/100f*0.6f).toFloat()

    private fun buildVoiceConfig(): VoiceConfig {
        // speakerNum = ASTERION 화자 번호 (speakerSpinners 의 key: 1, 2, 3...)
        // sherpaSid  = Supertonic 3 speaker ID (0~9) — 스피너 선택 위치와 일치
        val map = speakerSpinners.keys.associateWith { speakerNum ->
            val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[speakerNum]?.selectedItemPosition ?: 0]
            val speed     = progressToSpeed(speakerSeekBars[speakerNum]?.progress ?: 50)
            val name      = when(speakerNum){1->"아스터";2->"리언";3->"나레이터";else->"Speaker$speakerNum"}
            SpeakerConfig(sherpaSid, speed, name)
        }
        return if (map.isEmpty()) VoiceConfig.DEFAULT else VoiceConfig(map)
    }

    private fun loadSpeakersFromSheet(sheet: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data     = reader?.readScript(sheet)?.getOrNull() ?: return@launch
                val speakers = data.scriptRows.map { it.speaker }.distinct().sorted()
                withContext(Dispatchers.Main) {
                    buildSpeakerUI(speakers)
                    tvStatus.text = "$sheet | 화자 ${speakers.size}명: $speakers"
                    btnStart.isEnabled = speakers.isNotEmpty()
                }
            } catch(e: Exception) { Log.e("Activity","loadSpeakers: $e") }
        }
    }

    private suspend fun initCore() {
        if (!hasAllFilesPermission()) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "⚠ '모든 파일 접근' 권한 필요\n" +
                    "BGV/BGM/출력 폴더에 접근하려면 해당 권한이 필요합니다.\n" +
                    "설정 화면으로 이동합니다..."
                requestAllFilesPermission()
            }
            return
        }
        withContext(Dispatchers.Main) { tvKeyStatus.text = auth.keyStatusMessage() }
        if (!auth.keyStatusMessage().startsWith("✅")) return
        try {
            val token = auth.getAccessToken()
            reader    = SheetsVideoReader(token, VIDEO_SS_ID)
            val te    = SupertonicTtsEngine(this)
            te.init { msg -> appendLog(msg) }
            ttsEngine = te
            engine    = AsterionRenderEngine(this, te)
            val sheets = reader!!.listScriptSheets()
            withContext(Dispatchers.Main) {
                if (sheets.isEmpty()) tvStatus.text = "대본 없음 — VS_로 시작하는 시트명 사용"
                else {
                    spinnerSheet.adapter = ArrayAdapter(this@AsterionVideoActivity,
                        android.R.layout.simple_spinner_item, sheets)
                        .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    tvStatus.text = "VS_ 시트 ${sheets.size}개 — 선택 후 화자 설정"
                }
            }
        } catch(e: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "❌ ${e.message}" } }
    }

    private fun startRendering() {
        if (isRendering) return
        if (!hasAllFilesPermission()) {
            updateStatus("⚠ '모든 파일 접근' 권한이 없습니다. 설정에서 부여해 주세요.")
            requestAllFilesPermission()
            return
        }
        val sheet       = spinnerSheet.selectedItem?.toString() ?: return
        val voiceConfig = buildVoiceConfig()
        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 토큰 갱신...")
                val token = auth.getAccessToken()
                reader = SheetsVideoReader(token, VIDEO_SS_ID)
                updateStatus("[$sheet] 대본 읽는 중...")
                val result = reader!!.readReadyRows(sheet)
                if (result.isFailure) {
                    updateStatus("❌ 대본 로드 실패: ${result.exceptionOrNull()?.message}")
                    return@launch
                }
                val data = result.getOrThrow()
                if (data.scriptRows.isEmpty()) { updateStatus("⚠ READY 행 없음"); return@launch }
                withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size }

                var done      = 0
                var processed = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val f = engine!!.renderScene(row, data.videoMeta, voiceConfig) { msg ->
                        appendLog(msg); updateStatus(msg)
                    }
                    reader!!.updateStatus(sheet, row.rowIndex, if (f != null) "DONE" else "ERROR")
                    if (f != null) done++
                    processed++
                    withContext(Dispatchers.Main) { progressBar.progress = processed }
                }

                if (isRendering && done > 0) {
                    val safeSheet  = sheet.replace(Regex("[^\\w가-힣]"), "_")
                    updateStatus("🔗 씬 ${done}개 concat 중...")
                    val finalFile = engine!!.concatSubclips(
                        outputName   = safeSheet,
                        bgmFileName  = data.videoMeta.mainBgm
                    ) { msg -> appendLog(msg); updateStatus(msg) }
                    if (finalFile != null && finalFile.exists()) {
                        updateStatus("🎬 완료: ${finalFile.name} (${finalFile.length()/1024/1024}MB)")
                    } else {
                        updateStatus("❌ concat 실패 — output 폴더의 씬별 MP4 확인")
                    }
                } else if (!isRendering) {
                    updateStatus("⏹ 중지 — 씬 ${done}개 완료")
                } else {
                    updateStatus("⚠ 성공한 씬 없음 — 오류 로그 확인")
                }
            } catch(e: Exception) {
                updateStatus("❌ ${e.message}")
            } finally {
                isRendering = false
                withContext(Dispatchers.Main) { btnStart.isEnabled = true; btnStop.isEnabled = false }
            }
        }
    }

    private fun stopRendering() { isRendering = false; updateStatus("⏹ 중지") }

    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(15) + msg).joinToString("\n")
    }
    override fun onDestroy() { super.onDestroy(); mediaPlayer?.release(); ttsEngine?.release() }
}
