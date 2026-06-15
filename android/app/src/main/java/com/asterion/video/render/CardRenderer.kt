package com.asterion.video.render

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.asterion.video.model.CardStyle
import com.asterion.video.model.GradientPreset
import com.asterion.video.model.ScriptDataRow
import java.io.File

/**
 * Android Canvas → 1920×1080 ARGB PNG 카드 오버레이 (ASTERION 표준 카드 디자인)
 *
 * 배경: 마젠타(0xFF00FF) — Step4 colorkey=0xFF00FF 에서 투명화
 * 카드: holdX/holdY 위치에 860×340 그라디언트 박스 + 중앙정렬 텍스트
 *
 * [v3.29.1 수정]
 *   ■ 2단계 줄바꿈 수정:
 *     Stage 1) 리터럴 \n 정규화 — 시트에 저장된 텍스트는 백슬래시+n(2글자) 관례.
 *              splitToLines()와 동일한 변환: replace("\\N","\n") + replace("\\n","\n")
 *     Stage 2) StaticLayout 강제 라우팅 — drawText()는 실제 \n도 무시하므로
 *              '\n' in text 이면 무조건 StaticLayout 경로
 *   ■ maxLines 2→3
 *   ■ 디버그 로그: 정규화 전/후 비교 출력
 */
object CardRenderer {

    private const val TAG     = "CardRenderer"
    const  val VW             = 1920
    const  val VH             = 1080
    const  val CARD_W         = 860
    const  val CARD_H         = 340
    private const val CARD_X_DEFAULT = (VW - CARD_W) / 2   // 530
    private const val CARD_Y_DEFAULT = 680
    private const val PAD_H   = 40
    private const val PAD_V   = 28

    /**
     * splitToLines()와 동일한 관례: 리터럴 \N / \n → 실제 개행문자
     * 이미 실제 \n(Alt+Enter 셀)이면 replace가 영향 없으므로 양쪽 모두 안전.
     */
    private fun normalizeNewlines(text: String): String =
        text.replace("\\N", "\n").replace("\\n", "\n")

    fun render(
        row      : ScriptDataRow,
        outputPng: File,
        cardX    : Int = CARD_X_DEFAULT,
        cardY    : Int = CARD_Y_DEFAULT
    ): Boolean {
        val style = CardStyle.from(row.cardStyle.trim())
        val gradientKey = row.gradientPreset.trim()
            .takeIf { it.isNotBlank() && it.uppercase() != "DEFAULT" }
            ?: row.cardStyle.trim()
        val gradient = GradientPreset.from(gradientKey)

        if (style == CardStyle.MINIMAL || style == CardStyle.NONE) return false

        // Stage 1: 리터럴 \n 정규화
        val rawMain = row.cardMain.trim()
        val rawSub  = row.cardSub.trim()
        val rawDesc = row.cardDesc.trim()
        val pm = normalizeNewlines(rawMain)
        val ps = normalizeNewlines(rawSub)
        val pd = normalizeNewlines(rawDesc)

        if (pm.isBlank() && ps.isBlank() && pd.isBlank()) return false

        // 정규화 전/후 비교 로그 — 리터럴 \n이 실제 개행으로 바뀌었는지 확인
        Log.d(TAG, "[정규화 전] main=${rawMain.replace("\n","↵")} | sub=${rawSub.replace("\n","↵")} | desc=${rawDesc.replace("\n","↵")}")
        Log.d(TAG, "[정규화 후] main=${pm.replace("\n","↵")} | sub=${ps.replace("\n","↵")} | desc=${pd.replace("\n","↵")}")

        // 알파 선처리 (pre-multiply on black)
        val a    = style.alpha
        val topR = (gradient.topColor shr 16) and 0xFF
        val topG = (gradient.topColor shr  8) and 0xFF
        val topB =  gradient.topColor         and 0xFF
        val botR = (gradient.bottomColor shr 16) and 0xFF
        val botG = (gradient.bottomColor shr  8) and 0xFF
        val botB =  gradient.bottomColor         and 0xFF
        val effTop = Color.rgb(topR, topG, topB)
        val effBot = Color.rgb(botR, botG, botB)

        Log.d(TAG, "[✓ 알파선처리] style=${style.name} alpha=$a cardX=$cardX cardY=$cardY")

        val bitmap = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 마젠타 배경
        canvas.drawColor(Color.rgb(0xFF, 0x00, 0xFF))

        // 카드 배경 그라디언트
        val cL = cardX.toFloat()
        val cT = cardY.toFloat()
        val cR = (cardX + CARD_W).toFloat()
        val cB = (cardY + CARD_H).toFloat()
        canvas.drawRect(cL, cT, cR, cB, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cL, cT, cL, cB, effTop, effBot, Shader.TileMode.CLAMP)
        })

        val boxCenterX = cardX + CARD_W / 2f
        val textLeft   = cardX + PAD_H
        val maxW       = CARD_W - PAD_H * 2   // 780px
        var curY       = cardY + PAD_V

        // highlightWord — 금색 레이블 (개행 없는 단어 필드, drawText 유지)
        if (row.highlightWord.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD700"); textSize = 28f
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("◆ ${row.highlightWord}", boxCenterX, curY + tp.textSize, tp)
            curY += (tp.textSize * 1.6f).toInt()
        }

        // ── 공통 렌더 헬퍼 ─────────────────────────────────────────────────────
        // Stage 2: measureText <= maxW 라도 실제 \n 있으면 StaticLayout 강제
        fun drawField(
            text     : String,
            tp       : TextPaint,
            maxLines : Int,
            lineSpacing: Float,
            gapBelow : Int
        ): Int {   // 반환값: 소비한 높이 (gapBelow 포함)
            if (text.isBlank() || curY >= cardY + CARD_H - PAD_V) return 0
            return if (tp.measureText(text) <= maxW && '\n' !in text) {
                // 한 줄 확실 + 개행 없음 → drawText (FFmpeg cx-tw/2 동일 수식)
                canvas.drawText(text, boxCenterX, curY + tp.fontMetrics.let { -it.ascent }, tp)
                val h = tp.fontMetrics.let { (-it.ascent + it.descent).toInt() } + gapBelow
                h
            } else {
                // 개행 포함 또는 긴 텍스트 → StaticLayout
                Log.d(TAG, "[StaticLayout] hasNewline=${'\n' in text} len=${text.length} maxLines=$maxLines")
                val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(lineSpacing, 1.0f)
                    .setMaxLines(maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                canvas.save()
                canvas.translate(textLeft.toFloat(), curY.toFloat())
                sl.draw(canvas)
                canvas.restore()
                sl.height + gapBelow
            }
        }

        // cardMain — 52px bold white
        if (pm.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(5f, 2f, 2f, Color.argb(180, 64, 64, 64))
            }
            curY += drawField(pm, tp, maxLines = 3, lineSpacing = 4f, gapBelow = 12)
        }

        // cardSub — 38px, 0xCCCCCC
        if (ps.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 204, 204, 204); textSize = 38f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.argb(160, 64, 64, 64))
            }
            curY += drawField(ps, tp, maxLines = 3, lineSpacing = 2f, gapBelow = 8)
        }

        // cardDesc — 32px, 0xAAAAAA
        if (pd.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 170, 170, 170); textSize = 32f
                textAlign = Paint.Align.CENTER
                setShadowLayer(3f, 1f, 1f, Color.argb(140, 64, 64, 64))
            }
            drawField(pd, tp, maxLines = 3, lineSpacing = 2f, gapBelow = 0)
        }

        return try {
            outputPng.outputStream().buffered().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.d(TAG, "[✓ PNG저장] ${outputPng.name} (${outputPng.length()/1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PNG 저장 실패: ${e.message}"); false
        } finally {
            bitmap.recycle()
        }
    }
}
