package com.asterion.video.ui

import android.content.Intent
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
import com.asterion.video.render.ScenePrep
import com.asterion.video.service.RenderForegroundService
import com.asterion.video.service.YouTubeUploader
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

    private lateinit var tvKeyStatus   : TextView
    private lateinit var btnSheetSelect: Button          // v3.39: 다중선택 렌더 큐 (기존 Spinner 대체)
    private lateinit var llSpeakers    : LinearLayout
    private lateinit var btnStart      : Button
    private lateinit var btnStop       : Button
    private lateinit var btnReset      : Button
    private lateinit var progressBar   : ProgressBar
    private lateinit var tvStatus      : TextView
    private lateinit var tvLog         : TextView

    private val speakerSpinners       = mutableMapOf<Int, Spinner>()
    private val speakerSeekBars       = mutableMapOf<Int, SeekBar>()
    private val speakerSpeedLabels    = mutableMapOf<Int, TextView>()
    private val speakerNumStepsBars   = mutableMapOf<Int, SeekBar>()
    private val speakerNumStepsLabels = mutableMapOf<Int, TextView>()

    // v3.39: 렌더 큐 상태
    private var allSheets    = listOf<String>()          // 사용 가능한 전체 시트
    private val renderQueue  = mutableListOf<String>()   // 선택된 시트(순서 유지)
    private val failedSheets = linkedSetOf<String>()     // 실패(업로드 안 됨) 시트 — 영구 저장
    private val prefs by lazy { getSharedPreferences("asterion_render", MODE_PRIVATE) }

    private val auth            by lazy { ServiceAccountAuth(this) }
    private val youtubeUploader  by lazy { YouTubeUploader(this) }
    private var reader     : SheetsVideoReader? = null
    private var engine     : AsterionRenderEngine? = null
    private var ttsEngine  : SupertonicTtsEngine? = null
    private var isRendering = false
    private var mediaPlayer: MediaPlayer? = null

    private fun hasAllFilesPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    private fun requestAllFilesPermission() {
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${packageName}")
            ))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFailed()
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,16,48,48) }
        scroll.addView(layout); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layout.setPadding(48, b.top+16, 48, b.bottom+48); insets
        }
        tvKeyStatus  = TextView(this).apply { textSize=12f; setTextColor(0xFFAAAAAA.toInt()) }
        btnSheetSelect = Button(this).apply {
            text = "▼ 시트 선택 (렌더 큐)"
            setOnClickListener { showSheetQueueDialog() }
        }
        llSpeakers   = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        btnStart     = Button(this).apply { text="▶ 영상 제작 시작"; isEnabled=false }
        btnStop      = Button(this).apply { text="⏹ 중지"; isEnabled=false }
        btnReset     = Button(this).apply { text="🗑 초기화"; isEnabled=false }
        progressBar  = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; layoutParams=LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also{it.topMargin=12} }
        tvStatus = TextView(this).apply { text="시작 중..."; textSize=14f; setPadding(0,16,0,8) }
        tvLog    = TextView(this).apply { textSize=10f; setTextColor(0xFF777777.toInt()); maxLines=16 }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnStart.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        btnStop.layoutParams  = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnReset.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnRow.addView(btnStart); btnRow.addView(btnStop); btnRow.addView(btnReset)
        listOf(tvKeyStatus, btnSheetSelect, llSpeakers, btnRow, progressBar, tvStatus, tvLog)
            .forEach { layout.addView(it) }
        btnStart.setOnClickListener { startRendering() }
        btnStop.setOnClickListener  { stopRendering() }
        btnReset.setOnClickListener { showResetPicker() }
        lifecycleScope.launch { initCore() }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesPermission() && engine == null) {
            lifecycleScope.launch { initCore() }
        }
    }

    // ── 렌더 큐 (다중선택) ────────────────────────────────────────
    // v3.39: 시트를 누른 순서대로 큐에 추가(번호 표시), 다시 누르면 해제.
    //        실패한 시트는 ❌로 표시(누르면 체크박스 복귀). 실패기록은 영구 저장.
    private fun showSheetQueueDialog() {
        if (allSheets.isEmpty()) { updateStatus("시트 목록 없음"); return }
        val ctx = this
        val lv = ListView(ctx)
        val ad = object : BaseAdapter() {
            override fun getCount() = allSheets.size
            override fun getItem(p: Int) = allSheets[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: android.view.View?, parent: ViewGroup?): android.view.View {
                val tv = (cv as? TextView) ?: TextView(ctx).apply { textSize = 15f; setPadding(36,26,36,26) }
                val s = allSheets[p]; val order = renderQueue.indexOf(s)
                when {
                    failedSheets.contains(s) -> { tv.text = "❌  $s  (실패)"; tv.setTextColor(0xFFE57373.toInt()) }
                    order >= 0               -> { tv.text = "${order + 1}.  $s"; tv.setTextColor(0xFF81C784.toInt()) }
                    else                     -> { tv.text = "☐  $s"; tv.setTextColor(0xFFBBBBBB.toInt()) }
                }
                return tv
            }
        }
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, pos, _ ->
            val s = allSheets[pos]
            when {
                failedSheets.contains(s) -> { failedSheets.remove(s); saveFailed() }   // ❌ → 체크박스 복귀
                renderQueue.contains(s)  -> renderQueue.remove(s)                        // 큐에서 해제
                else                     -> renderQueue.add(s)                           // 큐에 추가(순서 부여)
            }
            ad.notifyDataSetChanged()
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("렌더 큐 — 누른 순서대로 렌더")
            .setView(lv)
            .setPositiveButton("확인") { _, _ -> onQueueConfirmed() }
            .setNeutralButton("큐 비우기") { _, _ -> renderQueue.clear(); onQueueConfirmed() }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun onQueueConfirmed() {
        updateSheetButton()
        if (renderQueue.isNotEmpty()) loadSpeakersFromSheet(renderQueue.first())
        else { btnStart.isEnabled = false; tvStatus.text = "큐 비어있음 — 시트 선택" }
    }

    private fun updateSheetButton() {
        btnSheetSelect.text =
            if (renderQueue.isEmpty()) "▼ 시트 선택 (렌더 큐)"
            else "🎬 큐 ${renderQueue.size}편: " + renderQueue.joinToString(", ")
    }

    private fun loadFailed() {
        failedSheets.clear()
        failedSheets.addAll(prefs.getStringSet("failed_sheets", emptySet()) ?: emptySet())
    }
    private fun saveFailed() { prefs.edit().putStringSet("failed_sheets", failedSheets.toSet()).apply() }

    // ── 화자 UI ────────────────────────────────────────────────────

    private fun buildSpeakerUI(speakers: List<Int>) {
        llSpeakers.removeAllViews()
        speakerSpinners.clear(); speakerSeekBars.clear(); speakerSpeedLabels.clear()
        speakerNumStepsBars.clear(); speakerNumStepsLabels.clear()
        if (speakers.isEmpty()) return

        llSpeakers.addView(TextView(this).apply {
            text="🎙 화자 설정 (Supertonic 3)"; textSize=11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0,12,0,4)
        })
        val defVoice=mapOf(1 to 5,2 to 0,3 to 6); val defSpeed=mapOf(1 to 50,2 to 42,3 to 58)
        val MP=ViewGroup.LayoutParams.MATCH_PARENT; val WC=ViewGroup.LayoutParams.WRAP_CONTENT

        for (sid in speakers.sorted()) {
            val name = when(sid){1->"아스터";2->"리언";3->"나레이터";else->"Speaker$sid"}
            val headerRow = LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                layoutParams=LinearLayout.LayoutParams(MP,WC).also{it.topMargin=20}
            }
            headerRow.addView(TextView(this).apply {
                text=name; textSize=12f; setTextColor(0xFFEEEEEE.toInt())
                layoutParams=LinearLayout.LayoutParams(WC,WC).also{it.gravity=Gravity.CENTER_VERTICAL;it.marginEnd=8}
            })
            val spinner = Spinner(this).apply {
                layoutParams=LinearLayout.LayoutParams(0,WC,1f)
                adapter=ArrayAdapter(this@AsterionVideoActivity, android.R.layout.simple_spinner_item, VoiceConfig.VOICE_LABELS)
                    .also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
                setSelection(defVoice[sid]?:0)
            }
            speakerSpinners[sid]=spinner
            headerRow.addView(spinner)
            headerRow.addView(Button(this).apply {
                text="🔊"; textSize=13f; layoutParams=LinearLayout.LayoutParams(WC,WC)
                setOnClickListener{testSpeaker(sid)}
            })
            llSpeakers.addView(headerRow)

            val speedLabel = TextView(this).apply {
                textSize=10f; setTextColor(0xFF999999.toInt()); minWidth=100
                layoutParams=LinearLayout.LayoutParams(WC,WC).also{it.gravity=Gravity.CENTER_VERTICAL;it.marginStart=6}
            }
            speakerSpeedLabels[sid]=speedLabel
            val speedBar = SeekBar(this).apply {
                max=100; progress=defSpeed[sid]?:50; layoutParams=LinearLayout.LayoutParams(0,WC,1f)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){speedLabel.text="${progressToSpeed(v)}x"}
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){}
                })
            }
            speedLabel.text="${progressToSpeed(speedBar.progress)}x"; speakerSeekBars[sid]=speedBar
            val speedRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(MP,WC).also{it.topMargin=6}}
            speedRow.addView(TextView(this).apply{text="속도";textSize=10f;setTextColor(0xFF777777.toInt());minWidth=72;layoutParams=LinearLayout.LayoutParams(WC,WC).also{it.gravity=Gravity.CENTER_VERTICAL}})
            speedRow.addView(speedBar); speedRow.addView(speedLabel); llSpeakers.addView(speedRow)

            val stepsLabel=TextView(this).apply{textSize=10f;setTextColor(0xFF999999.toInt());minWidth=100;layoutParams=LinearLayout.LayoutParams(WC,WC).also{it.gravity=Gravity.CENTER_VERTICAL;it.marginStart=6}}
            speakerNumStepsLabels[sid]=stepsLabel
            val stepsBar=SeekBar(this).apply{
                max=28; progress=4; layoutParams=LinearLayout.LayoutParams(0,WC,1f)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){stepsLabel.text="${progressToNumSteps(v)}step"}
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){}
                })
            }
            stepsLabel.text="${progressToNumSteps(stepsBar.progress)}step"; speakerNumStepsBars[sid]=stepsBar
            val stepsRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;layoutParams=LinearLayout.LayoutParams(MP,WC).also{it.topMargin=2}}
            stepsRow.addView(TextView(this).apply{text="품질";textSize=10f;setTextColor(0xFF777777.toInt());minWidth=72;layoutParams=LinearLayout.LayoutParams(WC,WC).also{it.gravity=Gravity.CENTER_VERTICAL}})
            stepsRow.addView(stepsBar); stepsRow.addView(stepsLabel); llSpeakers.addView(stepsRow)
        }
    }

    private fun testSpeaker(sid: Int) {
        val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[sid]?.selectedItemPosition ?: 0]
        val speed     = progressToSpeed(speakerSeekBars[sid]?.progress ?: 50)
        val numSteps  = progressToNumSteps(speakerNumStepsBars[sid]?.progress ?: 4)
        val testText  = when(sid){1->"안녕하세요. 에너지 분석을 시작합니다.";2->"극과의 에너지가 축적되는 구간입니다.";else->"운명은 해석하는 순간 바뀌지 않습니다."}
        AppConfig.ensureDirs()
        updateStatus("🔊 [$sid] sid=$sherpaSid steps=$numSteps speed=$speed 합성 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te  = ttsEngine ?: run { withContext(Dispatchers.Main){updateStatus("❌ TTS 엔진 미초기화")}; return@launch }
                val out = File(applicationContext.cacheDir, "test_sid${sid}.wav")
                val ok  = te.synthesize(testText, sherpaSid, speed, out, numSteps)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(out.absolutePath); prepare(); start()
                            setOnCompletionListener { out.delete() }
                        }
                        updateStatus("🔊 [$sid] ${out.length()/1024}KB 재생 중")
                    } else {
                        updateStatus("❌ TTS 실패")
                    }
                }
            } catch(e:Exception){ withContext(Dispatchers.Main){updateStatus("❌ 예외: ${e.message}")} }
        }
    }

    private fun progressToSpeed(p: Int): Float =
        String.format("%.2f", 0.7f + p.toFloat() / 100f * 0.6f).toFloat()

    private fun progressToNumSteps(p: Int): Int = p + 4

    private fun buildVoiceConfig(): VoiceConfig {
        val map = speakerSpinners.keys.associateWith { speakerNum ->
            val sherpaSid = VoiceConfig.SID_LIST[speakerSpinners[speakerNum]?.selectedItemPosition ?: 0]
            val speed     = progressToSpeed(speakerSeekBars[speakerNum]?.progress ?: 50)
            val numSteps  = progressToNumSteps(speakerNumStepsBars[speakerNum]?.progress ?: 4)
            val name      = when(speakerNum){1->"아스터";2->"리언";3->"나레이터";else->"Speaker$speakerNum"}
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
                }
            } catch(e:Exception){ Log.e("Activity","loadSpeakers: $e") }
        }
    }

    // ── 초기화 (렌더 정지 상태에서 단일 선택) ─────────────────────
    // v3.39: 큐와 분리 — 정지 상태에서 시트 하나 골라 초기화
    private fun showResetPicker() {
        if (isRendering) { updateStatus("⚠ 렌더 중엔 초기화 불가 — 먼저 중지"); return }
        if (allSheets.isEmpty()) { updateStatus("시트 목록 없음"); return }
        val arr = allSheets.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("초기화할 시트 선택")
            .setItems(arr) { _, w -> confirmReset(arr[w]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmReset(sheet: String) {
        val cacheDir    = AppConfig.sceneCacheDir(sheet)
        val cachedCount = cacheDir.listFiles()?.size ?: 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("초기화 확인")
            .setMessage("『$sheet』 초기화: 캐시 ${cachedCount}개(WAV·BGV cut) 삭제 + 시트 전체 READY. 중단한 영상을 재시작합니다.")
            .setPositiveButton("초기화") { _, _ -> doReset(sheet) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doReset(sheet: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            updateStatus("[$sheet] 초기화 중...")
            val cacheDir = AppConfig.sceneCacheDir(sheet)
            val deleted  = cacheDir.listFiles()?.count { it.delete() } ?: 0
            appendLog("삭제 $deleted 개")
            val token2 = auth.getAccessToken()
            val r2     = SheetsVideoReader(token2, VIDEO_SS_ID)
            val ok     = r2.resetAllStatuses(sheet)
            withContext(Dispatchers.Main) {
                if (ok) {
                    updateStatus("✅ [$sheet] 초기화 완료 — 파일 ${deleted}개 삭제, 상태 READY")
                } else {
                    updateStatus("⚠ 시트 초기화 실패 — 수동으로 K열 READY 확인 필요")
                }
            }
        }
    }

    private suspend fun initCore() {
        if (!hasAllFilesPermission()) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "⚠ '모든 파일 접근' 권한 필요 — 설정 화면으로 이동합니다..."
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
                allSheets = sheets
                if (sheets.isEmpty()) tvStatus.text = "대본 없음 — VS_로 시작하는 시트명 사용"
                else {
                    btnReset.isEnabled = true
                    tvStatus.text = "VS_ 시트 ${sheets.size}개 — '시트 선택'에서 렌더 큐 구성"
                }
            }
        } catch(e:Exception){ withContext(Dispatchers.Main){tvStatus.text="❌ ${e.message}"} }
    }

    // ── 렌더링 메인 (큐 순차) ─────────────────────────────────────
    // v3.39: 큐를 순서대로 렌더→업로드. 실패해도 다음 편 계속(A). 실패 시트는 ❌ 표시+영구저장.
    private fun startRendering() {
        if (isRendering) return
        if (!hasAllFilesPermission()) {
            updateStatus("⚠ '모든 파일 접근' 권한이 없습니다.")
            requestAllFilesPermission()
            return
        }
        if (renderQueue.isEmpty()) { updateStatus("⚠ 렌더 큐가 비어있음 — 시트를 선택하세요"); return }
        val voiceConfig = buildVoiceConfig()
        val queue = renderQueue.toList()          // 스냅샷
        failedSheets.clear(); saveFailed()        // 새 렌더 → 실패기록 초기화

        startForegroundService(Intent(this, RenderForegroundService::class.java))
        isRendering = true; btnStart.isEnabled = false; btnStop.isEnabled = true; btnReset.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for ((qi, sheet) in queue.withIndex()) {
                    if (!isRendering) break
                    updateStatus("═══ 큐 ${qi + 1}/${queue.size}: $sheet ═══")
                    appendLog("▶ [$sheet] 시작 (${qi + 1}/${queue.size})")
                    val ok = try {
                        renderOneSheet(sheet, voiceConfig)
                    } catch (e: Exception) {
                        Log.e("Activity", "[$sheet] 예외", e); updateStatus("❌ [$sheet] ${e.message}"); false
                    }
                    when {
                        ok            -> appendLog("✅ [$sheet] 업로드 완료")
                        !isRendering  -> appendLog("⏹ [$sheet] 중지됨")
                        else          -> { failedSheets.add(sheet); saveFailed(); appendLog("❌❌ [$sheet] 실패(업로드 안 됨) → 다음 편") }
                    }
                }
                val fails = queue.count { failedSheets.contains(it) }
                updateStatus("🏁 큐 종료 — 성공 ${queue.size - fails}편" +
                    if (fails > 0) ", 실패 ${fails}편 ('시트 선택'에서 ❌ 확인)" else " (전편 완료)")
            } catch (e: Exception) {
                updateStatus("❌ ${e.message}"); Log.e("Activity", "startRendering 예외", e)
            } finally {
                isRendering = false
                stopService(Intent(this@AsterionVideoActivity, RenderForegroundService::class.java))
                withContext(Dispatchers.Main) {
                    btnStart.isEnabled = true; btnStop.isEnabled = false; btnReset.isEnabled = true
                    renderQueue.clear(); updateSheetButton()
                }
            }
        }
    }

    // v3.39: 한 편 렌더 → 업로드. 업로드까지 성공하면 true, 아니면 false.
    private suspend fun renderOneSheet(sheet: String, voiceConfig: VoiceConfig): Boolean {
        engine!!.release()

        updateStatus("[$sheet] 토큰 갱신...")
        val token = auth.getAccessToken()
        reader = SheetsVideoReader(token, VIDEO_SS_ID)
        updateStatus("[$sheet] 대본 읽는 중...")
        val result = reader!!.readScript(sheet)
        if (result.isFailure) { updateStatus("❌ [$sheet] 대본 로드 실패: ${result.exceptionOrNull()?.message}"); return false }
        val data = result.getOrThrow()
        if (data.scriptRows.isEmpty()) { updateStatus("⚠ [$sheet] 대본 행 없음"); return false }
        withContext(Dispatchers.Main) { progressBar.max = data.scriptRows.size; progressBar.progress = 0 }

        val isXrp      = sheet.contains("XRP", ignoreCase = true)
        val mergedMeta = data.videoMeta.copy(
            introBgv1         = "intro01_asterion_signature_bracelet.mp4",
            introBgv2         = "intro02_golden_fluid_ink_loop_slow.mp4",
            introText         = "빛은 선택된 이에게만 닿는다",
            introDurationSecs = 21f,
            introType         = if (isXrp) "XRP" else "CRYPTO",
            disclaimerText    = if (data.videoMeta.disclaimerText.isNotBlank())
                                    data.videoMeta.disclaimerText
                                else
                                    "본 영상은 투자 권유 또는 투자 조언이 아닙니다. " +
                                    "모든 투자 결정은 시청자 본인의 판단과 책임 하에 이루어져야 합니다.",
            topWatermark      = if (isXrp)
                                    "베다점성술로 예측하는 XRP 전망 by ASTERION"
                                else
                                    "베다점성술로 둘러보는 크립토 갤러리 by ASTERION"
        )

        val disclaimerWav = if (mergedMeta.disclaimerText.isNotBlank()) {
            val dWav = File(AppConfig.OUTPUT_DIR, "intro_disclaimer.wav")
            try {
                val spk = data.scriptRows.firstOrNull()?.speaker ?: 1
                val cfg = voiceConfig.forSpeaker(spk)
                engine!!.ttsEnginePublic.synthesize(
                    mergedMeta.disclaimerText, cfg.sid, cfg.speed, dWav, cfg.numSteps
                )
                if (dWav.exists() && dWav.length() > 0) dWav else null
            } catch (e: Exception) { Log.w("Activity","disclaimer TTS 실패: $e"); null }
        } else null

        engine!!.renderIntro(mergedMeta, disclaimerWav) { msg -> appendLog(msg); updateStatus(msg) }

        val cacheDir    = AppConfig.sceneCacheDir(sheet)
        val prepList    = mutableListOf<ScenePrep>()
        var cumSecs     = 0f
        var processed   = 0

        for (row in data.scriptRows) {
            if (!isRendering) break
            val sceneId = "scene_${row.rowIndex.toString().padStart(4,'0')}"
            val prep = engine!!.prepareScene(
                row         = row,
                voiceConfig = voiceConfig,
                startSecs   = cumSecs,
                cacheDir    = cacheDir
            ) { msg -> appendLog(msg); updateStatus(msg) }

            if (prep != null) {
                prepList.add(prep)
                cumSecs += prep.wavDuration
                reader!!.updateStatus(sheet, row.rowIndex, "DONE", data.scriptStartSheetRow)
            } else {
                reader!!.updateStatus(sheet, row.rowIndex, "ERROR", data.scriptStartSheetRow)
                appendLog("[$sceneId] ❌ prepareScene 실패")
            }
            processed++
            withContext(Dispatchers.Main) { progressBar.progress = processed }
        }

        if (!isRendering) { updateStatus("⏹ 중지 — [$sheet] Phase 1 ${prepList.size}/${data.scriptRows.size}씬"); return false }

        val safeSheet = sheet.replace(Regex("[^가-힣A-Za-z0-9_]"), "_")

        if (prepList.isNotEmpty()) {
            updateStatus("🎬 [$sheet] 단일 인코딩 조립 중 (${prepList.size}씬)...")
            val bodyFile = engine!!.assembleBody(prepList, safeSheet) { msg -> appendLog(msg); updateStatus(msg) }
            if (bodyFile == null) { updateStatus("❌ [$sheet] body 조립 실패 — output 폴더 임시파일 확인"); return false }
        }

        updateStatus("🔗 [$sheet] 최종 합치기 + BGM...")
        val finalFile = engine!!.concatSubclips(
            outputName    = safeSheet,
            bgmFileName   = mergedMeta.mainBgm,
            watermarkText = mergedMeta.topWatermark,
            introDurSecs  = engine!!.actualIntroDurationSecs
        ) { msg -> appendLog(msg); updateStatus(msg) }

        if (finalFile == null || !finalFile.exists()) { updateStatus("❌ [$sheet] 최종 합치기 실패 — output 폴더 확인"); return false }

        updateStatus("🎬 [$sheet] 완료: ${finalFile.name} (${finalFile.length()/1024/1024}MB) → 업로드")
        val videoId = youtubeUploader.upload(
            videoFile     = finalFile,
            title         = buildYouTubeTitle(sheet, isXrp),
            description   = buildYouTubeDescription(isXrp),
            tags          = buildYouTubeTags(isXrp),
            privacyStatus = "private"
        ) { msg -> appendLog(msg); updateStatus(msg) }

        return videoId != null   // 업로드까지 성공해야 true
    }

    private fun stopRendering() {
        isRendering = false
        stopService(Intent(this, RenderForegroundService::class.java))
        updateStatus("⏹ 중지 요청 — 현재 편 마무리 후 정지")
    }

    // ── YouTube 메타데이터 빌더 ───────────────────────────────

    private fun buildYouTubeTitle(sheet: String, isXrp: Boolean): String {
        val datePart = Regex("([0-9]{8})").find(sheet)?.groupValues?.get(1)?.let {
            "${it.substring(0,4)}.${it.substring(4,6)}.${it.substring(6,8)}"
        } ?: ""
        val typePart = when {
            sheet.contains("weekly", ignoreCase = true) -> " 위클리"
            sheet.contains("daily",  ignoreCase = true) -> " 데일리"
            else -> ""
        }
        val base = if (isXrp) "베다점성술로 예측하는 XRP 전망"
                   else       "베다점성술로 둘러보는 크립토 갤러리"
        return if (datePart.isNotBlank()) "$base | $datePart$typePart" else "$base$typePart"
    }

    private fun buildYouTubeDescription(isXrp: Boolean): String = buildString {
        append("🌟 ASTERION | 베다점성술 기반 암호화폐 분석\n\n")
        if (isXrp) {
            append("베다점성술의 행성 에너지를 통해 XRP(리플)의 흐름을 분석합니다.\n")
            append("나브암샤 차트, 다샤 주기, 트랜짓을 종합한 에너지 예측입니다.\n\n")
        } else {
            append("베다점성술의 행성 에너지로 다양한 암호화폐의 흐름을 탐구합니다.\n\n")
        }
        append("⚠️ 본 영상은 투자 권유 또는 투자 조언이 아닙니다.\n")
        append("모든 투자 결정은 시청자 본인의 판단과 책임 하에 이루어져야 합니다.\n\n")
        append("#ASTERION #베다점성술 #암호화폐 ")
        if (isXrp) append("#XRP #리플") else append("#크립토")
    }

    private fun buildYouTubeTags(isXrp: Boolean): List<String> = buildList {
        addAll(listOf("ASTERION", "베다점성술", "암호화폐", "코인전망", "점성술"))
        if (isXrp) addAll(listOf("XRP", "리플", "XRP전망", "리플전망", "리플점성술"))
        else       addAll(listOf("크립토갤러리", "비트코인", "이더리움", "알트코인"))
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
