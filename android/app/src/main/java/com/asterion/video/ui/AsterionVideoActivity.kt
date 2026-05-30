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

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 48)
        }
        scroll.addView(layout)
        setContentView(scroll)

        // 상태바 높이만큼 위 패딩 보정
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layout.setPadding(48, bars.top + 16, 48, bars.bottom + 48)
            insets
        }

        tvKeyStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 12)
        }
        spinner = Spinner(this)
        btnStart = Button(this).apply { text = "▶ 영상 제작 시작"; isEnabled = false }
        btnStop  = Button(this).apply { text = "⏹ 중지"; isEnabled = false }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = 12 }
        }
        tvStatus = TextView(this).apply { text = "시작 중..."; textSize = 14f; setPadding(0, 16, 0, 8) }
        tvLog    = TextView(this).apply { textSize = 11f; setTextColor(0xFF777777.toInt()); maxLines = 14 }

        listOf(tvKeyStatus, spinner, btnStart, btnStop, progressBar, tvStatus, tvLog)
            .forEach { layout.addView(it) }

        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }

        lifecycleScope.launch { initCore() }
    }

    private suspend fun initCore() {
        withContext(Dispatchers.Main) {
            tvKeyStatus.text = auth.keyStatusMessage()
        }

        val hasKey = auth.keyStatusMessage().startsWith("✅")
        if (!hasKey) return

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
                        android.R.layout.simple_spinner_item, sheets
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
        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 대본 읽는 중...")
                val data = reader!!.readReadyRows(sheet).getOrThrow()
                withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size }
                var done = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val f = engine!!.renderScene(row, data.videoMeta) { msg -> appendLog(msg); updateStatus(msg) }
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

    private fun stopRendering() { isRendering = false; updateStatus("⏹ 중지 — READY 행부터 재개 가능") }
    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(13) + msg).joinToString("\n")
    }
    override fun onDestroy() { super.onDestroy(); engine?.release() }
}
