package com.asterion.video.ui

import android.os.Bundle
import android.widget.*
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.sheets.SheetsVideoReader
import com.asterion.video.sheets.VIDEO_SS_ID
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AsterionVideoActivity : AppCompatActivity() {

    private lateinit var tvKeyStatus: TextView
    private lateinit var spinner: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val auth by lazy { ServiceAccountAuth(this) }
    private var reader: SheetsVideoReader? = null
    private var engine: AsterionRenderEngine? = null
    private var isRendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 루트: ScrollView 안에 LinearLayout
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(layout)
        setContentView(scroll)

        // 키 상태
        tvKeyStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        // 시트 스피너
        spinner = Spinner(this)

        // 버튼들
        btnStart = Button(this).apply {
            text = "▶ 영상 제작 시작"
            isEnabled = false
        }
        btnStop = Button(this).apply {
            text = "⏹ 중지"
            isEnabled = false
        }

        // 프로그레스
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 16 }
        }

        // 상태 텍스트
        tvStatus = TextView(this).apply {
            text = "시작 중..."
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }

        // 로그
        tvLog = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 0)
            maxLines = 12
        }

        listOf(tvKeyStatus, spinner, btnStart, btnStop, progressBar, tvStatus, tvLog)
            .forEach { layout.addView(it) }

        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }

        lifecycleScope.launch { initCore() }
    }

    private suspend fun initCore() {
        // 키 파일 확인
        val keyPath = auth.keyFilePath()
        withContext(Dispatchers.Main) {
            tvKeyStatus.text = if (keyPath != null)
                "✅ 키: $keyPath"
            else
                "⚠️ service_account.json 없음\n→ 내장저장소/Documents/work/ASTERION/ 에 저장"
        }
        if (keyPath == null) return

        try {
            val token = auth.getAccessToken()
            reader = SheetsVideoReader(token, VIDEO_SS_ID)
            engine = AsterionRenderEngine(this, VoiceConfig.DEFAULT)
            engine!!.init { msg -> appendLog(msg) }

            val sheets = reader!!.listScriptSheets()
            withContext(Dispatchers.Main) {
                if (sheets.isEmpty()) {
                    tvStatus.text = "대본 없음 — Claude로 대본을 먼저 작성하세요"
                } else {
                    spinner.adapter = ArrayAdapter(
                        this@AsterionVideoActivity,
                        android.R.layout.simple_spinner_item,
                        sheets
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    tvStatus.text = "시트 ${sheets.size}개 — 대본을 선택하세요"
                    btnStart.isEnabled = true
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { tvStatus.text = "❌ ${e.message}" }
        }
    }

    private fun startRendering() {
        if (isRendering) return
        val sheet = spinner.selectedItem?.toString() ?: return
        isRendering = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 대본 읽는 중...")
                val data = reader!!.readReadyRows(sheet).getOrThrow()
                withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size }
                var done = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val f = engine!!.renderScene(row, data.videoMeta) { msg ->
                        appendLog(msg); updateStatus(msg)
                    }
                    reader!!.updateStatus(sheet, row.rowIndex, if (f != null) "DONE" else "ERROR")
                    if (f != null) done++
                    withContext(Dispatchers.Main) { progressBar.progress = done }
                }
                if (isRendering) {
                    updateStatus("클립 합치는 중...")
                    engine!!.concatSubclips(sheet, data.videoMeta.mainBgm) { msg -> updateStatus(msg) }
                }
            } catch (e: Exception) {
                updateStatus("❌ ${e.message}")
            } finally {
                isRendering = false
                withContext(Dispatchers.Main) { btnStart.isEnabled = true; btnStop.isEnabled = false }
            }
        }
    }

    private fun stopRendering() {
        isRendering = false
        updateStatus("⏹ 중지 — READY 행부터 재개 가능")
    }

    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(11) + msg).joinToString("\n")
    }

    override fun onDestroy() { super.onDestroy(); engine?.release() }
}
