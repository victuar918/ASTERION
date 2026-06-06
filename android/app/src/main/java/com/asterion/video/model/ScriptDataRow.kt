package com.asterion.video.model

data class VideoMeta(
    val youtubeTitle: String = "",
    val topWatermark: String = "ASTERION",
    val thumbnailText: String = "",
    val mainBgm: String = "default_bgm.mp3",
    // ── 인트로 (15초 특수 처리) ──────────────────────────────────
    val introBgv1: String = "",                   // 첫 번째 BGV (비어있으면 인트로 생략)
    val introBgv2: String = "",                   // 두 번째 BGV (없으면 bgv1만 사용)
    val introText: String = "빛은 선택된 이에게만 닿는다",  // Phase1 상단 1/3 텍스트
    val introDurationSecs: Float = 15f,           // 면첵 TTS 시작 시점(초)
    val introType: String = "",                   // "XRP" 또는 "" (CRYPTO) — Phase2 텍스트 결정
    val disclaimerText: String = ""               // t=introDurationSecs에 시작할 면첵 TTS 텍스트
)

data class ScriptDataRow(
    val rowIndex: Int,
    val section: String,
    val speaker: Int,
    val cardMain: String,
    val cardSub: String,
    val cardDesc: String,
    val highlightWord: String,
    val script: String,
    val bgFile: String,
    val animation: String,
    val cardStyle: String,
    val status: String,
    val note: String,
    val bgEffect: String,
    val bgTransition: String,
    val cardExtraEffect: String,
    val lottieFile: String,
    val stickerFile: String,
    val gradientPreset: String
) {
    val bgFileName: String get() = bgFile.split("|").getOrElse(0) { bgFile }
    val bgTransitionDuration: Float get() = bgFile.split("|").getOrElse(2) { "1.0" }.toFloatOrNull() ?: 1.0f
    val bgEffectCode: String get() = bgFile.split("|").getOrElse(3) { "NONE" }
    val isReady: Boolean get() = status == "READY"
    val sectionType: SectionType get() = when {
        section.startsWith("TPL-INT") -> SectionType.INTRO
        section.startsWith("TPL-PLA") -> SectionType.PLANET
        section.startsWith("TPL-TIM") -> SectionType.TIMING
        section.startsWith("TPL-SUM") -> SectionType.SUMMARY
        section.startsWith("TPL-SYN") -> SectionType.SYNTHESIS
        section.startsWith("TPL-BUF") -> SectionType.BUFFER
        else -> SectionType.UNKNOWN
    }
}

enum class SectionType { INTRO, PLANET, TIMING, SUMMARY, SYNTHESIS, BUFFER, UNKNOWN }
enum class CardStyle(val alpha: Float) {
    DEFAULT(0.75f), TITLE(0.85f), CONCLUSION(0.90f), NOTICE(0.80f), MINIMAL(0f), NONE(0f);
    companion object { fun from(v: String) = values().firstOrNull { it.name == v } ?: DEFAULT }
}
enum class AnimationPattern { A,B,C,D,E,F,G,NONE;
    companion object { fun from(v: String) = values().firstOrNull { it.name == v } ?: NONE }
}
// NONE 추가: 시트에서 전환 없음을 명시할 수 있도록 (from() 기본값: FADE)
enum class BgTransition { NONE,FADE,SLIDE_LEFT,SLIDE_UP,ZOOM_IN,ZOOM_OUT,BLUR_FADE,WIPE_RIGHT;
    companion object { fun from(v: String) = values().firstOrNull { it.name == v } ?: FADE }
}
enum class CardExtraEffect { NONE,HEARTBEAT,VIBRATE;
    companion object { fun from(v: String) = values().firstOrNull { it.name == v } ?: NONE }
}
data class GradientPreset(val topColor: Int, val bottomColor: Int) {
    companion object {
        val DEFAULT = GradientPreset(0xD9000000.toInt(), 0x990A051E.toInt())
        val TITLE   = GradientPreset(0xD91E0032.toInt(), 0xE6000000.toInt())
        val CONCLUSION = GradientPreset(0xE600051E.toInt(), 0xE6000000.toInt())
        val NOTICE  = GradientPreset(0xD9320505.toInt(), 0xE6000000.toInt())
        fun from(v: String) = when(v) { "TITLE"->TITLE; "CONCLUSION"->CONCLUSION; "NOTICE"->NOTICE; else->DEFAULT }
    }
}
