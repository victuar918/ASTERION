package com.asterion.video.ui

import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.asterion.video.AppConfig
import com.asterion.video.auth.ServiceAccountAuth
import com.asterion.video.render.AsterionRenderEngine
import com.asterion.video.service.RenderForegroundService
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
    private lateinit var btnReset: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val speakerSpinners       = mutableMapOf<Int, Spinner>()
    private val speakerSeekBars       = mutableMapOf<Int, SeekBar>()
    private val speakerSpeedLabels    = mutableMapOf<Int, TextView>()
    private val speakerNumStepsBars   = mutableMapOf<Int, SeekBar>()   // 품질(numSteps) 슬라이더
    private val speakerNumStepsLabels = mutableMapOf<Int, TextView>()  // numSteps 값 레이블

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
        btnReset     = Button(this).apply { text="🗑 초기화"; isEnabled=false }
        progressBar  = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).also{it.topMargin=12} }
        tvStatus = TextView(this).apply { text="시작 중..."; textSize=14f; setPadding(0,16,0,8) }
        tvLog    = TextView(this).apply { textSize=10f; setTextColor(0xFF777777.toInt()); maxLines=16 }
        // 시작/중지/초기화 버튼을 한 행에 배치
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnStart.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        btnStop.layoutParams  = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnReset.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnRow.addView(btnStart); btnRow.addView(btnStop); btnRow.addView(btnReset)
        listOf(tvKeyStatus, spinnerSheet, llSpeakers, btnRow, progressBar, tvStatus, tvLog)
            .forEach { layout.addView(it) }
        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }
        btnReset.setOnClickListener { confirmReset() }
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

    // ── 화자 UI ───────────────────────────────────────────────────────────────
    private fun buildSpeakerUI(speakers: List<Int>) {
        llSpeakers.removeAllViews()
        speakerSpinners.clear(); speakerSeekBars.clear(); speakerSpeedLabels.clear()
        speakerNumStepsBars.clear(); speakerNumStepsLabels.clear()
        if (speakers.isEmpty()) return

        llSpeakers.addView(TextView(this).apply {
            text = "🎤 화자 설정 (Supertonic 3)"
            textSize = 11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0, 12, 0, 4)
        })

        // sid 0-4 = 여성, sid 5-9 = 남성
        // defVoice: ASTERION 화자번호 → 스피너 초기 인덱스 (= sid 값과 동일)
        val defVoice    = mapOf(1 to 5, 2 to 0, 3 to 6)
        val defSpeed    = mapOf(1 to 50, 2 to 42, 3 to 58)
        val defNumSteps = 4  // progress=4 → numSteps=8
        val MP = ViewGroup.LayoutParams.MATCH_PARENT
        val WC = ViewGroup.LayoutParams.WRAP_CONTENT

        for (sid in speakers.sorted()) {
            val name = when(sid) { 1 -> "아스터"; 2 -> "리언"; 3 -> "나레이터"; else -> "Speaker$sid" }

            // ─ 행 1: [화자이름] [Spinner] [테스트버튼] — 한 줄 ───────────────────
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = 20 }
            }
            headerRow.addView(TextView(this).apply {
                text = name; textSize = 12f; setTextColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.gravity = Gravity.CENTER_VERTICAL; it.marginEnd = 8
                }
            })
            val spinner = Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                adapter = ArrayAdapter(this@AsterionVideoActivity,
                    android.R.layout.simple_spinner_item, VoiceConfig.VOICE_LABELS)
                    .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(defVoice[sid] ?: 0)
            }
            speakerSpinners[sid] = spinner
            headerRow.addView(spinner)
            headerRow.addView(Button(this).apply {
                text = "🔊"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { testSpeaker(sid) }
            })
            llSpeakers.addView(headerRow)

            // ─ 행 2: [속도] ━━━━━━━━━━ [x.xx배] — 업는 SeekBar ───────────────
            val speedValueLabel = TextView(this).apply {
                textSize = 10f; setTextColor(0xFF999999.toInt()); minWidth = 100
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = 6
                }
            }
            speakerSpeedLabels[sid] = speedValueLabel
            val speedBar = SeekBar(this).apply {
                max = 100; progress = defSpeed[sid] ?: 50
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                        speedValueLabel.text = "${progressToSpeed(v)}x"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            speedValueLabel.text = "${progressToSpeed(speedBar.progress)}x"
            speakerSeekBars[sid] = speedBar

            val speedRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = 6 }
            }
            speedRow.addView(TextView(this).apply {
                text = "속도"; textSize = 10f; setTextColor(0xFF777777.toInt()); minWidth = 72
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER_VERTICAL }
            })
            speedRow.addView(speedBar)
            speedRow.addView(speedValueLabel)
            llSpeakers.addView(speedRow)

            // ─ 행 3: [품질] ━━━━━━━━━━ [Xstep] — numSteps SeekBar ────────────
            val numStepsValueLabel = TextView(this).apply {
                textSize = 10f; setTextColor(0xFF999999.toInt()); minWidth = 100
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = 6
                }
            }
            speakerNumStepsLabels[sid] = numStepsValueLabel
            val numStepsBar = SeekBar(this).apply {
                max = 28; progress = defNumSteps  // progress 0~28 → numSteps 4~32
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                        numStepsValueLabel.text = "${progressToNumSteps(v)}step"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            numStepsValueLabel.text = "${progressToNumSteps(numStepsBar.progress)}step"
            speakerNumStepsBars[sid] = numStepsBar

            val numStepsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = 2 }
            }
            numStepsRow.addView(TextView(this).apply {
                text = "품질"; textSize = 10f; setTextColor(0xFF777777.toInt()); minWidth = 72
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.gravity = Gravity.CENTER_VERTICAL }
            })
            numStepsRow.addView(numStepsBar)
            numStepsRow.addView(numStepsValueLabel)
            llSpeakers.addView(numStepsRow)
        }
    }

    private fun testSpeaker(sid: Int) {
        val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[sid]?.selectedItemPosition ?: 0]
        val speed     = progressToSpeed(speakerSeekBars[sid]?.progress ?: 50)
        val numSteps  = progressToNumSteps(speakerNumStepsBars[sid]?.progress ?: 4)
        val testText  = when(sid) {
            1    -> "안녕하세요. 에너지 분석을 시작합니다."
            2    -> "극과의 에너지가 축적되는 구간입니다."
            else -> "운명은 해석하는 순간 바뀌지 않습니다."
        }
        AppConfig.ensureDirs()
        val errFile = File(applicationContext.filesDir, "tts_error.txt")
        errFile.delete()
        updateStatus("🔊 [$sid] sid=$sherpaSid steps=$numSteps speed=$speed 합성 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te  = ttsEngine ?: run { withContext(Dispatchers.Main) { updateStatus("❌ TTS 엔진 미초기화") }; return@launch }
                // cacheDir: 앱 전용 내부 캐시 (외부 노웈 없음, 권한 불필요)
                val out = File(applicationContext.cacheDir, "test_sid${sid}.wav")
                val ok  = te.synthesize(testText, sherpaSid, speed, out, numSteps)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(out.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener { out.delete() }  // 재생 완료 후 캐시 파일 자동 삭제
                        }
                        updateStatus("🔊 [$sid] sid=$sherpaSid steps=$numSteps ${out.length()/1024}KB 재생 중")
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

    /** SeekBar progress(0~100) → 발화 속도 (0.70배~1.30배) */
    private fun progressToSpeed(p: Int): Float =
        String.format("%.2f", 0.7f + p.toFloat() / 100f * 0.6f).toFloat()

    /** SeekBar progress(0~28) → numSteps(4~32) */
    private fun progressToNumSteps(p: Int): Int = p + 4

    private fun buildVoiceConfig(): VoiceConfig {
        // speakerNum = ASTERION 화자 번호 (1,2,3...) — speakerSpinners의 key
        // sherpaSid  = Supertonic 3 speaker ID (0~9) — 스피너 선택 위치와 동일
        val map = speakerSpinners.keys.associateWith { speakerNum ->
            val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[speakerNum]?.selectedItemPosition ?: 0]
            val speed     = progressToSpeed(speakerSeekBars[speakerNum]?.progress ?: 50)
            val numSteps  = progressToNumSteps(speakerNumStepsBars[speakerNum]?.progress ?: 4)
            val name      = when(speakerNum) { 1 -> "아스터"; 2 -> "리언"; 3 -> "나레이터"; else -> "Speaker$speakerNum" }
            SpeakerConfig(sherpaSid, speed, name, numSteps)
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
                    btnReset.isEnabled = speakers.isNotEmpty()
                }
            } catch(e: Exception) { Log.e("Activity","loadSpeakers: $e") }
        }
    }

    /**
     * 🗑 초기화 연산 확인 다이얼로그
     * 캐시 MP4 삭제 + 시트 K열 전체 READY 로 초기화
     */
    private fun confirmReset() {
        val sheet = spinnerSheet.selectedItem?.toString() ?: return
        val cacheDir = AppConfig.sceneCacheDir(sheet)
        val cachedCount = cacheDir.listFiles()?.size ?: 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("초기화 확인")
            .setMessage("「$sheet」를 초기화합니다.\n\n" +
                    "• 캐시된 진: ${cachedCount}개\n" +
                    "• 시트 상태: 전체 READY 로 초기화\n\n" +
                    "중단한 영상을 시움합니다.")
            .setPositiveButton("초기화") { _, _ -> doReset(sheet) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doReset(sheet: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            updateStatus("[$sheet] 초기화 중...")
            // 1. 캐시 MP4 삭제
            val cacheDir = AppConfig.sceneCacheDir(sheet)
            val deleted  = cacheDir.listFiles()?.count { it.delete() } ?: 0
            appendLog("찾수 $deleted 삭제")
            // 2. 시트 K열 READY 일괄 초기화
            val token = auth.getAccessToken()
            val r   = SheetsVideoReader(token, VIDEO_SS_ID)
            val token2 = auth.getAccessToken()
            val r2  = SheetsVideoReader(token2, VIDEO_SS_ID)
            val ok  = r2.resetAllStatuses(sheet)
            // 3. 화자 UI 재로드
            withContext(Dispatchers.Main) {
                if (ok) {
                    updateStatus("✅ [$sheet] 초기화 완료 — 진 ${deleted}개 삭제, 상태 READY 통일")
                    loadSpeakersFromSheet(sheet)
                } else {
                    updateStatus("⚠ 시트 초기화 실패 — 수동으로 K열 READY 확인 필요")
                }
            }
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

        startForegroundService(Intent(this, RenderForegroundService::class.java))

        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateStatus("[$sheet] 토큰 갱신...")
                val token = auth.getAccessToken()
                reader = SheetsVideoReader(token, VIDEO_SS_ID)
                updateStatus("[$sheet] 대본 읽는 중...")
                // 전체 행 읽기 (DONE 포함) — 캐시 재사용을 위해 readReadyRows → readScript
                val result = reader!!.readScript(sheet)
                if (result.isFailure) {
                    updateStatus("❌ 대본 로드 실패: ${result.exceptionOrNull()?.message}")
                    return@launch
                }
                val data = result.getOrThrow()
                if (data.scriptRows.isEmpty()) { updateStatus("⚠ 대본 행 없음"); return@launch }
                withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size }

                // 인트로 렌더링
                engine!!.renderIntro(data.videoMeta) { msg -> appendLog(msg); updateStatus(msg) }

                // 씬캐시 디렉토리 — 시트명별 영구 보관
                val cacheDir = AppConfig.sceneCacheDir(sheet)

                var done      = 0
                var processed = 0
                for (row in data.scriptRows) {
                    if (!isRendering) break
                    val sceneId   = "scene_${row.rowIndex.toString().padStart(4, '0')}"
                    val cacheFile = java.io.File(cacheDir, "$sceneId.mp4")

                    if (row.status == "DONE" && cacheFile.exists() && cacheFile.length() > 0) {
                        // ✅ 이미 렌더링된 씬 — 재렌더링 없이 재사용
                        engine!!.addExistingSubclip(cacheFile) { msg -> appendLog(msg) }
                        updateStatus("[$sceneId] ⏭️ DONE — 캐시 (${cacheFile.length()/1024}KB)")
                        done++
                    } else {
                        // 렌더링 필요 (READY / ERROR / 캐시 없는 DONE)
                        val f = engine!!.renderScene(row, data.videoMeta, voiceConfig, cacheDir) { msg ->
                            appendLog(msg); updateStatus(msg)
                        }
                        val newStatus = if (f != null) "DONE" else "ERROR"
                        reader!!.updateStatus(sheet, row.rowIndex, newStatus, data.scriptStartSheetRow)
                        if (f != null) done++
                    }
                    processed++
                    withContext(Dispatchers.Main) { progressBar.progress = processed }
                }

                if (isRendering && done > 0) {
                    val safeSheet = sheet.replace(Regex("[^\\w가-힣]"), "_")
                    updateStatus("🔗 씬 ${done}개 concat 중...")
                    val finalFile = engine!!.concatSubclips(
                        outputName    = safeSheet,
                        bgmFileName   = data.videoMeta.mainBgm,
                        watermarkText = data.videoMeta.topWatermark   // Video_Meta 시트 Top_Watermark 값
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
                stopService(Intent(this@AsterionVideoActivity, RenderForegroundService::class.java))
                withContext(Dispatchers.Main) { btnStart.isEnabled = true; btnStop.isEnabled = false }
            }
        }
    }

    private fun stopRendering() {
        isRendering = false
        stopService(Intent(this, RenderForegroundService::class.java))
        updateStatus("⏹ 중지")
    }

    private fun updateStatus(msg: String) = lifecycleScope.launch(Dispatchers.Main) { tvStatus.text = msg }
    private fun appendLog(msg: String) = lifecycleScope.launch(Dispatchers.Main) {
        tvLog.text = (tvLog.text.toString().lines().takeLast(15) + msg).joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        ttsEngine?.release()
        stopService(Intent(this, RenderForegroundService::class.java))
    }
}
