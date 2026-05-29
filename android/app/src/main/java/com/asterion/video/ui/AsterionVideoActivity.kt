package com.asterion.video.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.asterion.video.AppConfig
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.sheets.SheetsVideoReader
import com.asterion.video.sheets.VIDEO_SS_ID
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AsterionVideoActivity : AppCompatActivity() {
    private lateinit var spinner: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val auth by lazy { ServiceAccountAuth(this) }
    private var reader: SheetsVideoReader? = null
    private var engine: AsterionRenderEngine? = null
    private var isRendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32,32,32,32)
        }
        tvStatus = TextView(this).apply { text = "대기 중" }
        spinner  = Spinner(this)
        btnStart = Button(this).apply { text = "영상 제작 시작" }
        btnStop  = Button(this).apply { text = "중지"; isEnabled = false }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        tvLog    = TextView(this).apply { maxLines = 10; textSize = 11f }
        listOf(tvStatus, spinner, btnStart, btnStop, progress, tvLog).forEach { layout.addView(it) }
        setContentView(layout)

        lifecycleScope.launch { initCore() }
        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering()  }
    }

    private suspend fun initCore() {
        try {
            val token = auth.getAccessToken()
            reader = SheetsVideoReader(token, VIDEO_SS_ID)
            engine = AsterionRenderEngine(this, VoiceConfig.DEFAULT)
            engine!!.init { msg -> updateStatus(msg) }
            val sheets = reader!!.listScriptSheets()
            withContext(Dispatchers.Main) {
                if (sheets.isEmpty()) { tvStatus.text = "대본 없음 — Claude로 대본을 먼저 작성하세요"; btnStart.isEnabled = false }
                else {
                    spinner.adapter = ArrayAdapter(this@AsterionVideoActivity, android.R.layout.simple_spinner_item, sheets).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    tvStatus.text = "시트 ${sheets.size}개 — 대본 선택하세요"
                    btnStart.isEnabled = true
                }
            }
        } catch(e: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "실패: ${e.message}" } }
    }

    private fun startRendering() {
        if (isRendering) return
        val sheet = spinner.selectedItem?.toString() ?: return
        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 대본 읽는 중...")
                val data = reader!!.readReadyRows(sheet).getOrThrow()
                withContext(Dispatchers.Main) { progress.max = data.scriptRows.size }
                var done = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val f = engine!!.renderScene(row, data.videoMeta) { msg -> appendLog(msg); updateStatus(msg) }
                    reader!!.updateStatus(sheet, row.rowIndex, if (f != null) "DONE" else "ERROR")
                    if (f != null) done++
                    withContext(Dispatchers.Main) { progress.progress = done }
                }
                if (isRendering) {
                    updateStatus("클립 합치는 중...")
                    engine!!.concatSubclips(sheet, data.videoMeta.mainBgm) { msg -> updateStatus(msg) }
                }
            } catch(e: Exception) { updateStatus("❌ ${e.message}") }
            finally {
                isRendering = false
                withContext(Dispatchers.Main) { btnStart.isEnabled = true; btnStop.isEnabled = false }
            }
        }
    }

    private fun stopRendering() { isRendering = false; updateStatus("⏹ 중지 — 다시 시작하면 READY 행부터 재개") }

    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(9) + msg).joinToString("\n")
    }

    override fun onDestroy() { super.onDestroy(); engine?.release() }
}
