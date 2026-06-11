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
 * buildCardVf 정확 매칭:
 *
 *  [1] 알파 선처리 (pre-multiply on black):
 *      원본 drawbox color=0xRRGGBB@alpha 효과
 *      = gradient_RGB * style.alpha  (검은배경=0 이므로)
 *      → 완전 불투명으로 그려서 마젠타 혼합 방지
 *
 *  [2] 중앙정렬:
 *      single-line → measureText 기반 cx - tw/2  (FFmpeg 동일 수식)
 *      multi-line  → StaticLayout ALIGN_CENTER (center = cardX+430 동일)
 *
 *  [3] 카드 크기/위치: 860×340, holdX/holdY (AsterionRenderEngine 에서 전달)
 *  [4] 폰트: main=52px bold, sub=38px, desc=32px
 *  [5] 그림자: argb(180,64,64,64) dx=2 dy=2
 */
object CardRenderer {

    private const val TAG     = "CardRenderer"
    const  val VW             = 1920
    const  val VH             = 1080
    const  val CARD_W         = 860
    const  val CARD_H         = 340
    private const val CARD_X_DEFAULT = (VW - CARD_W) / 2   // 530
    private const val CARD_Y_DEFAULT = 680
    private const val PAD_H   = 40    // 카드 내부 좌우 패딩
    private const val PAD_V   = 28    // 카드 내부 상단 패딩

    fun render(
        row      : ScriptDataRow,
        outputPng: File,
        cardX    : Int = CARD_X_DEFAULT,
        cardY    : Int = CARD_Y_DEFAULT
    ): Boolean {
        val style    = CardStyle.from(row.cardStyle)
        val gradient = GradientPreset.from(row.gradientPreset)

        if (style == CardStyle.MINIMAL || style == CardStyle.NONE) return false

        val pm = row.cardMain.trim()
        val ps = row.cardSub.trim()
        val pd = row.cardDesc.trim()
        if (pm.isBlank() && ps.isBlank() && pd.isBlank()) return false

        // ── [1] 알파 선처리 ───────────────────────────────────
        // 원본: drawbox color=0xRRGGBB@alpha → 검은배경 위에 alpha 합성
        // 결과: R_eff = R * alpha + 0 * (1-alpha) = R * alpha
        // 마젠타 배경과 혼합되는 문제 방지: 완전 불투명(alpha=255)으로 정확히 그림
        val a    = style.alpha
        val topR = (gradient.topColor shr 16) and 0xFF
        val topG = (gradient.topColor shr  8) and 0xFF
        val topB =  gradient.topColor         and 0xFF
        val botR = (gradient.bottomColor shr 16) and 0xFF
        val botG = (gradient.bottomColor shr  8) and 0xFF
        val botB =  gradient.bottomColor         and 0xFF
        val effTop = Color.rgb((topR * a).toInt(), (topG * a).toInt(), (topB * a).toInt())
        val effBot = Color.rgb((botR * a).toInt(), (botG * a).toInt(), (botB * a).toInt())

        Log.d(TAG, "[✓ 알파선처리] style=${style.name} alpha=$a " +
            "top=(${topR},${topG},${topB})→eff=(${(topR*a).toInt()},${(topG*a).toInt()},${(topB*a).toInt()}) " +
            "cardX=$cardX cardY=$cardY")

        val bitmap = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 마젠타 배경
        canvas.drawColor(Color.rgb(0xFF, 0x00, 0xFF))

        // 카드 배경 (선처리된 효과색, 완전 불투명)
        val cL = cardX.toFloat()
        val cT = cardY.toFloat()
        val cR = (cardX + CARD_W).toFloat()
        val cB = (cardY + CARD_H).toFloat()
        canvas.drawRect(cL, cT, cR, cB, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cL, cT, cL, cB, effTop, effBot, Shader.TileMode.CLAMP)
        })

        // ── [2] 텍스트 ────────────────────────────────────────
        // boxCenterX = cardX + CARD_W/2 = cardX + 430
        // FFmpeg: cx = holdX + 430, x = cx - tw/2  ⇔  drawText(text, boxCenterX, y, paint[CENTER])
        val boxCenterX = cardX + CARD_W / 2f   // = cardX + 430
        val textLeft   = cardX + PAD_H
        val maxW       = CARD_W - PAD_H * 2     // 780px
        var curY       = cardY + PAD_V

        // highlightWord — 금색 레이블
        if (row.highlightWord.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD700"); textSize = 28f
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("◆ ${row.highlightWord}", boxCenterX, curY + tp.textSize, tp)
            curY += (tp.textSize * 1.6f).toInt()
        }

        // cardMain — 52px bold white
        if (pm.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(5f, 2f, 2f, Color.argb(180, 64, 64, 64))
            }
            if (tp.measureText(pm) <= maxW) {
                // single-line: FFmpeg cx-tw/2 동일 수식
                canvas.drawText(pm, boxCenterX, curY + tp.fontMetrics.let { -it.ascent }, tp)
                curY += tp.fontMetrics.let { (-it.ascent + it.descent).toInt() } + 12
            } else {
                val sl = StaticLayout.Builder.obtain(pm, 0, pm.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(4f, 1.0f).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
                sl.draw(canvas); canvas.restore(); curY += sl.height + 12
            }
        }

        // cardSub — 38px, 0xCCCCCC
        if (ps.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 204, 204, 204); textSize = 38f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.argb(160, 64, 64, 64))
            }
            if (tp.measureText(ps) <= maxW) {
                canvas.drawText(ps, boxCenterX, curY + tp.fontMetrics.let { -it.ascent }, tp)
                curY += tp.fontMetrics.let { (-it.ascent + it.descent).toInt() } + 8
            } else {
                val sl = StaticLayout.Builder.obtain(ps, 0, ps.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(2f, 1.0f).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
                sl.draw(canvas); canvas.restore(); curY += sl.height + 8
            }
        }

        // cardDesc — 32px, 0xAAAAAA
        if (pd.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 170, 170, 170); textSize = 32f
                textAlign = Paint.Align.CENTER
                setShadowLayer(3f, 1f, 1f, Color.argb(140, 64, 64, 64))
            }
            if (tp.measureText(pd) <= maxW) {
                canvas.drawText(pd, boxCenterX, curY + tp.fontMetrics.let { -it.ascent }, tp)
            } else {
                val sl = StaticLayout.Builder.obtain(pd, 0, pd.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(2f, 1.0f).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
                sl.draw(canvas); canvas.restore()
            }
        }

        return try {
            outputPng.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "[✓ PNG저장] ${outputPng.name} (${outputPng.length()/1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PNG 저장 실패: ${e.message}"); false
        } finally {
            bitmap.recycle()
        }
    }
}
