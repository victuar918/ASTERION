package com.asterion.video.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.asterion.video.R
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.model.*
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.sheets.SheetsVideoReader
import com.asterion.video.sheets.VideoScriptData
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.*

// ============================================================
// ASTERION 영상 자동화 — 메인 액티비티 v3.1
// [버그⑨] concatSubclips 파라미터 수정
//   이전: bgmFile = File(...).takeIf { it.exists() }  (File? ≠ String, 타입 불일치)
//   이후: bgmFilename = result.videoMeta.mainBgm       (String만 전달)
//         watermarkText = result.videoMeta.topWatermark
// [신규] 테스트 플레이 → AudioTrack 스트리밍 (WAV 파일 디스크 미기록)
// [신규] 화자 스피너 1~9번 전체 표시
// ============================================================

private const val TAG = "AsterionVideoActivity"

class AsterionVideoActivity : AppCompatActivity() {

    private lateinit var tvStatus:       TextView
    private lateinit var tvSheetName:    TextView
    private lateinit var spinnerSpeaker: Spinner
    private lateinit var btnTestPlay:    Button
    private lateinit var etTestText:     EditText
    private lateinit var btnRender:      Button
    private lateinit var progressBar:    ProgressBar

    private var engine: AsterionRenderEngine? = null
    private var currentJob: Job? = null
    private var loadedResult: VideoScriptData? = null

    private val speakerLabels = listOf(
        "1 — 아스터 (분석가)", "2 — 리언 (코호스트)", "3 — 나레이터 (철학)",
        "4 — 라후 (하이퍼)",   "5 — 케투 (건조)",    "6 — 수성 (초고속)",
        "7 — 달 (감성)",       "8 — 태양 (카리스마)", "9 — 행성 게스트 (화/목/금/토)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews(); setupSpinner(); setupButtons(); initEngine()
    }

    private fun bindViews() {
        tvStatus       = findViewById(R.id.tvStatus)
        tvSheetName    = findViewById(R.id.tvSheetName)
        spinnerSpeaker = findViewById(R.id.spinnerSpeaker)
        btnTestPlay    = findViewById(R.id.btnTestPlay)
        etTestText     = findViewById(R.id.etTestText)
        btnRender      = findViewById(R.id.btnRender)
        progressBar    = findViewById(R.id.progressBar)
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speakerLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSpeaker.adapter = adapter
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnLoadSheet).setOnClickListener {
            val name = findViewById<EditText>(R.id.etSheetName).text.toString().trim()
            if (name.isBlank()) { updateStatus("⚠️ 시트명을 입력하세요"); return@setOnClickListener }
            loadSheet(name)
        }
        btnTestPlay.setOnClickListener {
            val speakerNum = spinnerSpeaker.selectedItemPosition + 1
            val text = etTestText.text.toString().trim()
                .ifBlank { "엑스알피는 현재 팽창 우세 구조 상태입니다." }
            playTestTts(speakerNum, text)
        }
        btnRender.setOnClickListener {
            if (currentJob?.isActive == true) {
                currentJob?.cancel()
                updateStatus("⏹ 취소됨")
                btnRender.text = "영상 제작 시작"
                return@setOnClickListener
            }
            loadedResult?.let { startRender(it) } ?: updateStatus("⚠️ 시트를 먼저 로드하세요")
        }
    }

    private fun initEngine() {
        lifecycleScope.launch {
            engine = AsterionRenderEngine(this@AsterionVideoActivity, VoiceConfig.DEFAULT)
            engine?.init { msg -> updateStatus(msg) }
            updateStatus("준비 완료 — 시트명을 입력하고 로드하세요")
        }
    }

    private fun loadSheet(sheetName: String) {
        lifecycleScope.launch {
            updateStatus("시트 로딩 중: $sheetName")
            setUiEnabled(false)
            try {
                val token = ServiceAccountAuth(this@AsterionVideoActivity).getAccessToken()
                val result = withContext(Dispatchers.IO) {
                    SheetsVideoReader(token).readScript(sheetName).getOrThrow()
                }
                loadedResult = result
                tvSheetName.text = "✅ ${result.sheetName} — ${result.scriptRows.size}행 / ${result.videoMeta.youtubeTitle}"
                updateStatus("시트 로드 완료. 영상 제작 시작 버튼을 누르세요.")
            } catch (e: Exception) {
                Log.e(TAG, "시트 로드 실패: $e")
                updateStatus("❌ 시트 로드 실패: ${e.message}")
            } finally { setUiEnabled(true) }
        }
    }

    /** WAV 파일 디스크 미기록 — AudioTrack 직접 스트리밍 */
    private fun playTestTts(speakerNum: Int, text: String) {
        val eng = engine ?: run { updateStatus("⚠️ TTS 엔진 미초기화"); return }
        lifecycleScope.launch {
            val cfg = VoiceConfig.DEFAULT.forSpeaker(speakerNum)
            updateStatus("🎙 테스트: ${cfg.label} (sid=${cfg.sid}, spd=${cfg.speed})")
            btnTestPlay.isEnabled = false
            try {
                eng.playPreviewDirect(text, cfg.sid, cfg.speed) { msg -> updateStatus(msg) }
            } finally { btnTestPlay.isEnabled = true }
        }
    }

    private fun startRender(result: VideoScriptData) {
        val eng = engine ?: return
        currentJob = lifecycleScope.launch {
            btnRender.text = "⏹ 취소"
            setUiEnabled(false, keepRender = true)
            progressBar.visibility = View.VISIBLE
            progressBar.max = result.scriptRows.size; progressBar.progress = 0
            updateStatus("▶ 영상 제작 시작: ${result.sheetName} (${result.scriptRows.size}개 씬)")
            eng.release()
            var ok = 0; var fail = 0

            result.scriptRows.forEachIndexed { idx, row ->
                if (!isActive) { updateStatus("⏹ 취소됨"); return@launch }
                val scene = eng.renderScene(row, result.videoMeta) { msg ->
                    updateStatus("[${idx + 1}/${result.scriptRows.size}] $msg")
                }
                if (scene != null) ok++ else fail++
                progressBar.progress = idx + 1
            }
            updateStatus("씬 렌더 완료: 성공 $ok / 실패 $fail")
            if (ok == 0) { updateStatus("❌ 렌더된 씬 없음"); onRenderFinished(); return@launch }

            updateStatus("최종 합치기 시작...")

            // ★ [버그⑨] 수정: bgmFilename(String) + watermarkText 전달
            val finalFile = eng.concatSubclips(
                outputName    = result.sheetName,
                bgmFilename   = result.videoMeta.mainBgm,
                watermarkText = result.videoMeta.topWatermark
            ) { msg -> updateStatus(msg) }

            if (finalFile != null)
                updateStatus("🎬 완성: ${finalFile.name} (${finalFile.length() / 1024 / 1024}MB)")
            else
                updateStatus("❌ concat 실패 — 로그 확인")
            onRenderFinished()
        }
    }

    private fun onRenderFinished() {
        btnRender.text = "영상 제작 시작"
        setUiEnabled(true)
        progressBar.visibility = View.GONE
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            val lines = tvStatus.text.toString().lines()
            val trimmed = if (lines.size > 30) lines.takeLast(29).joinToString("\n") else tvStatus.text.toString()
            tvStatus.text = "$trimmed\n$msg"
            Log.d(TAG, msg)
        }
    }

    private fun setUiEnabled(enabled: Boolean, keepRender: Boolean = false) {
        runOnUiThread {
            findViewById<Button>(R.id.btnLoadSheet).isEnabled = enabled
            btnTestPlay.isEnabled = enabled
            if (!keepRender) btnRender.isEnabled = enabled
        }
    }

    override fun onDestroy() { super.onDestroy(); currentJob?.cancel(); engine?.release() }
}