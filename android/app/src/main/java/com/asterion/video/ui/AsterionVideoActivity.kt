package com.asterion.video.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.sheets.SheetsVideoReader
import com.asterion.video.sheets.VIDEO_SS_ID
import com.asterion.video.tts.SpeakerConfig
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AsterionVideoActivity : AppCompatActivity() {

    // UI
    private lateinit var tvKeyStatus: TextView
    private lateinit var spinnerSheet: Spinner
    private lateinit var llSpeakers: LinearLayout   // 화자 설정 컨테이너
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    // 화자 UI 다이나믹 저장
    private val speakerSpinners = mutableMapOf<Int, Spinner>()    // sid → 모델 Spinner
    private val speakerSeekBars = mutableMapOf<Int, SeekBar>()    // sid → 속도 SeekBar
    private val speakerLabels   = mutableMapOf<Int, TextView>()   // sid → 속도 라벨

    private val auth by lazy { ServiceAccountAuth(this) }
    private var reader: SheetsVideoReader? = null
    private var engine: AsterionRenderEngine? = null
    private var isRendering = false
    private var detectedSpeakers: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 48)
        }
        scroll.addView(layout)
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layout.setPadding(48, bars.top + 16, 48, bars.bottom + 48)
            insets
        }

        // 키 상태
        tvKeyStatus = TextView(this).apply { textSize = 12f; setTextColor(0xFFAAAAAA.toInt()) }

        // 시트 선택 Spinner
        spinnerSheet = Spinner(this)

        // 화자 설정 컨테이너 (시트 로드 후 동적 생성)
        llSpeakers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 버튼
        btnStart = Button(this).apply { text = "▶ 영상 제작 시작"; isEnabled = false }
        btnStop  = Button(this).apply { text = "⏹ 중지"; isEnabled = false }

        // 프로그레스 + 상태
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = 12 }
        }
        tvStatus = TextView(this).apply { text = "시작 중..."; textSize = 14f; setPadding(0, 16, 0, 8) }
        tvLog    = TextView(this).apply { textSize = 11f; setTextColor(0xFF777777.toInt()); maxLines = 14 }

        listOf(tvKeyStatus, spinnerSheet, llSpeakers, btnStart, btnStop, progressBar, tvStatus, tvLog)
            .forEach { layout.addView(it) }

        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }

        // 시트 변경 시 화자 자동 감지
        spinnerSheet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val sheet = spinnerSheet.selectedItem?.toString() ?: return
                loadSpeakersFromSheet(sheet)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        lifecycleScope.launch { initCore() }
    }

    // 화자 설정 UI 동적 생성
    private fun buildSpeakerUI(speakers: List<Int>) {
        llSpeakers.removeAllViews()
        speakerSpinners.clear(); speakerSeekBars.clear(); speakerLabels.clear()

        if (speakers.isEmpty()) return

        val header = TextView(this).apply {
            text = "🎤 화자 음성 설정"
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 16, 0, 4)
        }
        llSpeakers.addView(header)

        // 기본 매핑 (1=M1, 2=F1, 3=M2)
        val defaultVoice = mapOf(1 to 0, 2 to 5, 3 to 1)  // VoiceConfig.VOICE_FILES 인덱스
        val defaultSpeed = mapOf(1 to 50, 2 to 47, 3 to 52) // SeekBar 값 (0~100, 중간=50 →1.0x)

        for (sid in speakers.sorted()) {
            val name = when(sid) { 1 -> "아스터"; 2 -> "리언"; 3 -> "나레이터"; else -> "Speaker $sid" }

            // 화자 라벨
            llSpeakers.addView(TextView(this).apply {
                text = "[$sid] $name"
                textSize = 12f
                setTextColor(0xFFEEEEEE.toInt())
                setPadding(0, 12, 0, 2)
            })

            // 모델 Spinner
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, VoiceConfig.VOICE_LABELS)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(defaultVoice[sid] ?: 0)
            speakerSpinners[sid] = spinner
            llSpeakers.addView(spinner)

            // 속도 SeekBar + 라벨
            val speedLabel = TextView(this).apply {
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 4, 0, 0)
            }
            speakerLabels[sid] = speedLabel

            val seekBar = SeekBar(this).apply {
                max = 100
                progress = defaultSpeed[sid] ?: 50
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    speedLabel.text = "  속도: ${progressToSpeed(v)}x"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            speedLabel.text = "  속도: ${progressToSpeed(seekBar.progress)}x"
            speakerSeekBars[sid] = seekBar
            llSpeakers.addView(seekBar)
            llSpeakers.addView(speedLabel)
        }
    }

    // SeekBar 0~100 → 속도 0.7~1.3
    private fun progressToSpeed(p: Int): Float =
        (0.7f + p.toFloat() / 100f * 0.6f).let { String.format("%.2f", it).toFloat() }

    // 현재 UI 설정으로 VoiceConfig 생성
    private fun buildVoiceConfig(): VoiceConfig {
        val map = speakerSpinners.keys.associateWith { sid ->
            val voiceFile = VoiceConfig.VOICE_FILES[speakerSpinners[sid]?.selectedItemPosition ?: 0]
            val speed     = progressToSpeed(speakerSeekBars[sid]?.progress ?: 50)
            val name      = when(sid) { 1 -> "아스터"; 2 -> "리언"; 3 -> "나레이터"; else -> "Speaker$sid" }
            SpeakerConfig(sid, speed, name, voiceFile)
        }
        return if (map.isEmpty()) VoiceConfig.DEFAULT else VoiceConfig(map)
    }

    // 시트에서 고유 화자 감지
    private fun loadSpeakersFromSheet(sheet: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = reader?.readScript(sheet)?.getOrNull() ?: return@launch
                val speakers = data.scriptRows.map { it.speaker }.distinct().sorted()
                withContext(Dispatchers.Main) {
                    detectedSpeakers = speakers
                    buildSpeakerUI(speakers)
                    tvStatus.text = "시트: $sheet | 화자 ${speakers.size}명 감지: $speakers"
                    btnStart.isEnabled = true
                }
            } catch(e: Exception) { /* 무시 */ }
        }
    }

    private suspend fun initCore() {
        withContext(Dispatchers.Main) { tvKeyStatus.text = auth.keyStatusMessage() }
        val hasKey = auth.keyStatusMessage().startsWith("✅")
        if (!hasKey) return

        try {
            val token = auth.getAccessToken()
            reader = SheetsVideoReader(token, VIDEO_SS_ID)
            engine = AsterionRenderEngine(this)
            engine!!.init { msg -> appendLog(msg) }

            val sheets = reader!!.listScriptSheets()
            withContext(Dispatchers.Main) {
                if (sheets.isEmpty()) {
                    tvStatus.text = "대본 없음 — Claude로 대본을 먼저 작성하세요"
                } else {
                    spinnerSheet.adapter = ArrayAdapter(this@AsterionVideoActivity,
                        android.R.layout.simple_spinner_item, sheets)
                        .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    tvStatus.text = "시트 ${sheets.size}개 — 대본 선택 후 화자 설정"
                }
            }
        } catch(e: Exception) {
            withContext(Dispatchers.Main) { tvStatus.text = "❌ ${e.message}" }
        }
    }

    private fun startRendering() {
        if (isRendering) return
        val sheet = spinnerSheet.selectedItem?.toString() ?: return
        val voiceConfig = buildVoiceConfig()
        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 대본 읽는 중...")
                val data = reader!!.readReadyRows(sheet).getOrThrow()
                withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size }
                var done = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val f = engine!!.renderScene(row, data.videoMeta, voiceConfig) { msg ->
                        appendLog(msg); updateStatus(msg)
                    }
                    reader!!.updateStatus(sheet, row.rowIndex, if (f != null) "DONE" else "ERROR")
                    if (f != null) done++
                    withContext(Dispatchers.Main) { progressBar.progress = ++done }
                }
            } catch(e: Exception) { updateStatus("❌ ${e.message}")
            } finally {
                isRendering = false
                withContext(Dispatchers.Main) { btnStart.isEnabled = true; btnStop.isEnabled = false }
            }
        }
    }

    private fun stopRendering() { isRendering = false; updateStatus("⏹ 중지") }
    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(13) + msg).joinToString("\n")
    }
    override fun onDestroy() { super.onDestroy(); engine?.release() }
}
