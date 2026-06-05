package com.asterion.video

import android.os.Environment
import java.io.File

// ============================================================
// ASTERION AppConfig v3.1
// [버그⑦] TEMP_SCENES_DIR 추가 (씬 임시파일 격리)
// [버그⑨] resolveBgm() 추가 (Activity File 직접 생성 제거)
// [신규]   Supertonic-3 sherpa-onnx 모델 정보 상수화
// ============================================================

object AppConfig {

    private val WORK_ROOT: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "work/ASTERION/YouTube"
        )

    val BGV_DIR:    File get() = File(WORK_ROOT, "BGV")
    val BGM_DIR:    File get() = File(WORK_ROOT, "bgm")
    val OUTPUT_DIR: File get() = File(WORK_ROOT, "output")

    /** 씬 임시파일 격리 폴더 (concat 후 자동 삭제, '.'으로 파일관리앱 숨김) */
    val TEMP_SCENES_DIR: File get() = File(OUTPUT_DIR, ".temp_scenes")

    const val SPREADSHEET_ID = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"
    const val DEFAULT_BGV    = "VedicEnergyByPlanet_XRP_MovingChart.mp4"

    // ── VoiceConfig.kt 에서 이 이름으로 참조하는 상수 (Build #91 호환) ────────────
    const val TTS_MODEL_SUBDIR   = "tts_model"               // filesDir 하위 모델 저장 폴더명
    const val TTS_READY_MARKER   = ".ready_supertonic3_int8" // 압축 해제 완료 마커 파일명
    const val TTS_TAR_URL        =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    // ── 내부 참조용 (TTS_TAR_URL 과 동일 URL) ────────────────────────────────────
    const val TTS_MODEL_DIR_NAME    = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val TTS_MODEL_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    /** 씬 MP4 캐시 디렉토리 — 시트명별 영구 보관, 재렌더링 시 재사용 */
    fun sceneCacheDir(sheetName: String): File {
        val safe = sheetName.replace(Regex("[^\\w가-힣]"), "_").take(40)
        return File(OUTPUT_DIR, "scene_cache/$safe").also { it.mkdirs() }
    }

    fun ensureDirs() {
        listOf(BGV_DIR, BGM_DIR, OUTPUT_DIR, TEMP_SCENES_DIR).forEach { it.mkdirs() }
    }

    fun resolveBgv(bgFileName: String): File {
        val name = bgFileName.split("|").firstOrNull()?.trim() ?: DEFAULT_BGV
        val file = File(BGV_DIR, name)
        return if (file.exists()) file else {
            android.util.Log.w("AppConfig", "BGV 없음: $name → fallback 사용")
            File(BGV_DIR, DEFAULT_BGV)
        }
    }

    /** [버그⑨] Activity가 File 직접 생성하던 로직 일원화 */
    fun resolveBgm(bgmFilename: String): File? {
        if (bgmFilename.isBlank()) return null
        return File(BGM_DIR, bgmFilename.trim()).takeIf { it.exists() }
    }
}