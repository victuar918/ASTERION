package com.asterion.video.tts

// TODO: sherpa-onnx 의존성 추가 후 구현
// 현재는 빌드 확인용 stub

data class SpeakerConfig(val sid: Int, val speed: Float, val label: String)
data class VoiceConfig(
    val speaker1: SpeakerConfig,
    val speaker2: SpeakerConfig,
    val speaker3: SpeakerConfig
) {
    fun forSpeaker(num: Int) = when(num) { 2->speaker2; 3->speaker3; else->speaker1 }
    companion object {
        val DEFAULT = VoiceConfig(
            SpeakerConfig(0, 1.0f, "아스터"),
            SpeakerConfig(1, 0.95f, "리언"),
            SpeakerConfig(2, 1.05f, "나레이터")
        )
    }
}

class SupertonicTtsEngine(private val context: android.content.Context) {
    suspend fun init(onProgress: (String)->Unit = {}) {
        onProgress("TTS stub — sherpa-onnx 추후 연결")
    }
    fun synthesize(text: String, sid: Int, speed: Float, outputFile: java.io.File): Boolean = false
    fun estimateDurationFromWav(f: java.io.File): Float = 3.0f
    fun release() {}
}
