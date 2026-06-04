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
import com.asterion.video.tts.VoiceConfig
import kotlinx.coroutines.*

// ============================================================
// ASTERION 영상 자동화 — 메인 액티비티 v3.2
//
// 변경 이력:
//   v3.2: 수동 시트명 입력 → 자동 VS_ 시트 목록 로드 (spinnerSheet)
//         initEngine() 완료 후 listScriptSheets() 자동 호출
//         btnRender: 선택된 시트 직접 읽기 + 렌더 원스텝 처리
//         제거: etSheetName, btnLoadSheet, tvSheetName, loadedResult
// ============================================================

private const val TAG = "AsterionVideoActivity"

class AsterionVideoActivity : AppCompatActivity() {

    // ── View 참조 ──────────────────────────────────────────
    private lateinit var spinnerSheet:   Spinner    // VS_ 시트 자동 로드
    private lateinit var spinnerSpeaker: Spinner    // 화자 선택
    private lateinit var btnTestPlay:    Button
    private lateinit var etTestText:     EditText
    private lateinit var btnRender:      Button
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvStatus:       TextView

    // ── 상태 ───────────────────────────────────────────────
    private var engine:     AsterionRenderEngine? = null
    private var currentJob: Job? = null

    // ── 화자 스피너 목록 ───────────────────────────────────
    private val speakerLabels = listOf(
        "1 — 아스터 (분석가)", "2 — 리언 (코호스트)", "3 — 나레이터 (철학)",
        "4 — 라후 (하이퍼)",   "5 — 케투 (건조)",    "6 — 수성 (초고속)",
        "7 — 달 (감성)",       "8 — 태양 (카리스마)", "9 — 행성 게스트 (화/목/금/토)"
    )

    // ──────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSpeakerSpinner()
        setupButtons()
        initEngine()   // TTS 초기화 → 완료 후 시트 목록 자동 로드
    }

    // ── View 바인딩 ───────────────────────────────────────
    private fun bindViews() {
        spinnerSheet   = findViewById(R.id.spinnerSheet)
        spinnerSpeaker = findViewById(R.id.spinnerSpeaker)
        btnTestPlay    = findViewById(R.id.btnTestPlay)
        etTestText     = findViewById(R.id.etTestText)
        btnRender      = findViewById(R.id.btnRender)
        progressBar    = findViewById(R.id.progressBar)
        tvStatus       = findViewById(R.id.tvStatus)
    }

    // ── 화자 스피너 초기화 ────────────────────────────────
    private fun setupSpeakerSpinner() {
        spinnerSpeaker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, speakerLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    // ── 버튼 이벤트 ───────────────────────────────────────
    private fun setupButtons() {

        // 화자 테스트 플레이 (AudioTrack 스트리밍 — WAV 파일 미기록)
        btnTestPlay.setOnClickListener {
            val eng = engine ?: run { updateStatus("⚠️ TTS 엔진 미초기화"); return@setOnClickListener }
            val speakerNum = spinnerSpeaker.selectedItemPosition + 1
            val text = etTestText.text.toString().trim()
                .ifBlank { "엑스알피는 현재 팽창 우세 구조 상태입니다." }
            lifecycleScope.launch {
                val cfg = VoiceConfig.DEFAULT.forSpeaker(speakerNum)
                updateStatus("🎙 테스트: ${cfg.label} (sid=${cfg.sid}, spd=${cfg.speed})")
                btnTestPlay.isEnabled = false
                try { eng.playPreviewDirect(text, cfg.sid, cfg.speed) { msg -> updateStatus(msg) } }
                finally { btnTestPlay.isEnabled = true }
            }
        }

        // 영상 제작 / 취소
        btnRender.setOnClickListener {
            if (currentJob?.isActive == true) {
                currentJob?.cancel()
                updateStatus("⏹ 취소됨")
                btnRender.text = "영상 제작 시작"
                return@setOnClickListener
            }
            val sheetName = spinnerSheet.selectedItem?.toString()?.trim()
            if (sheetName.isNullOrBlank()) {
                updateStatus("⚠️ 시트를 선택하세요 (목록 로드 중이면 잠시 기다려주세요)")
                return@setOnClickListener
            }
            startRenderFlow(sheetName)
        }
    }

    // ── TTS 초기화 → 시트 목록 자동 로드 ─────────────────
    private fun initEngine() {
        lifecycleScope.launch {
            engine = AsterionRenderEngine(this@AsterionVideoActivity, VoiceConfig.DEFAULT)
            engine?.init { msg -> updateStatus(msg) }
            // TTS 준비 완료 후 VS_ 시트 목록 자동 로드
            loadSheetList()
        }
    }

    // ── VS_ 시트 목록 로드 → spinnerSheet 채우기 ──────────
    //
    // 잠재적 문제:
    //   - 네트워크 불량 시 실패 → 오류 메시지 표시, 수동 새로고침 가능
    //   - 토큰 만료 시 새 토큰을 요청하므로 자동 복구됨
    //   - 스피너에 "선택 중..." 기본 항목 없음 → 첫 번째 항목이 자동 선택됨
    private fun loadSheetList() {
        lifecycleScope.launch {
            updateStatus("VS_ 시트 목록 로딩 중...")
            try {
                val token = withContext(Dispatchers.IO) {
                    ServiceAccountAuth(this@AsterionVideoActivity).getAccessToken()
                }
                val sheets = withContext(Dispatchers.IO) {
                    SheetsVideoReader(token).listScriptSheets()
                }
                if (sheets.isEmpty()) {
                    updateStatus("VS_로 시작하는 시트가 없습니다")
                } else {
                    spinnerSheet.adapter = ArrayAdapter(
                        this@AsterionVideoActivity,
                        android.R.layout.simple_spinner_item,
                        sheets
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    updateStatus("✅ VS_ 시트 ${sheets.size}개 — 시트를 선택하고 영상 제작을 시작하세요")
                }
            } catch (e: Exception) {
                Log.e(TAG, "시트 목록 로드 실패: $e")
                updateStatus("❌ 시트 목록 로드 실패: ${e.message}\n(앱을 재시작하거나 네트워크를 확인하세요)")
            }
        }
    }

    // ── 시트 로드 + 렌더링 원스텝 ────────────────────────
    //
    // 흐름: 토큰 발급 → readScript() → 씬별 renderScene() → concatSubclips()
    //
    // 잠재적 문제:
    //   - 토큰 발급 실패 → catch 블록에서 처리
    //   - 시트가 비어있거나 스크립트가 없으면 ok==0 → 조기 종료
    //   - 렌더 중 취소(isActive==false) → 안전하게 루프 탈출
    private fun startRenderFlow(sheetName: String) {
        val eng = engine ?: return
        currentJob = lifecycleScope.launch {
            btnRender.text = "⏹ 취소"
            setUiEnabled(false)

            // 1. 시트 로드
            updateStatus("시트 로딩: $sheetName")
            val result = try {
                val token = withContext(Dispatchers.IO) {
                    ServiceAccountAuth(this@AsterionVideoActivity).getAccessToken()
                }
                withContext(Dispatchers.IO) {
                    SheetsVideoReader(token).readScript(sheetName).getOrThrow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "시트 로드 실패: $e")
                updateStatus("❌ 시트 로드 실패: ${e.message}")
                onRenderFinished()
                return@launch
            }

            // 2. 씬별 렌더링
            progressBar.visibility = View.VISIBLE
            progressBar.max = result.scriptRows.size
            progressBar.progress = 0
            updateStatus("▶ 렌더 시작: $sheetName (${result.scriptRows.size}개 씬)")
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

            updateStatus("씬 완료: 성공 $ok / 실패 $fail")
            if (ok == 0) {
                updateStatus("❌ 렌더된 씬 없음 — 로그 확인")
                onRenderFinished()
                return@launch
            }

            // 3. concat → 최종 MP4
            updateStatus("최종 합치기 시작...")
            val finalFile = eng.concatSubclips(
                outputName    = sheetName,
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

    // ── 렌더 완료 후 UI 복구 ─────────────────────────────
    private fun onRenderFinished() {
        btnRender.text = "영상 제작 시작"
        setUiEnabled(true)
        progressBar.visibility = View.GONE
    }

    // ── 상태 메시지 (최대 30줄 유지) ─────────────────────
    private fun updateStatus(msg: String) {
        runOnUiThread {
            val lines = tvStatus.text.toString().lines()
            val base  = if (lines.size > 30) lines.takeLast(29).joinToString("\n") else tvStatus.text.toString()
            tvStatus.text = "$base\n$msg"
            Log.d(TAG, msg)
        }
    }

    // ── UI 활성/비활성 ────────────────────────────────────
    private fun setUiEnabled(enabled: Boolean) {
        runOnUiThread {
            spinnerSheet.isEnabled   = enabled
            spinnerSpeaker.isEnabled = enabled
            btnTestPlay.isEnabled    = enabled
            btnRender.isEnabled      = true   // 취소 버튼 역할도 하므로 항상 활성
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        engine?.release()
    }
}