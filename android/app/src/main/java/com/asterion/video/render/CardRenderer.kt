package com.asterion.video.render

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
 * buildCardVf 디자인 매칭:
 *   위치  : holdX/holdY (AsterionRenderEngine에서 전달)
 *   크기  : w=860 h=340
 *   텍스트: main=52px bold 흰, sub=38px 0xCCCCCC, desc=32px 0xAAAAAA
 *   세노  : argb(180,64,64,64) dx=2 dy=2
 *   중앙  : center = holdX + 430 (화면 중앙)
 *   알파 : CardStyle.alpha (SRC 모드로 마젠타 대체)
 */
object CardRenderer {

    const val VW             = 1920
    const val VH             = 1080
    const val CARD_W         = 860
    const val CARD_H         = 340
    private const val CARD_X_DEFAULT = (VW - CARD_W) / 2   // 530
    private const val CARD_Y_DEFAULT = 680
    private const val PAD_H  = 40   // 카드 내부 좌우 패딩
    private const val PAD_V  = 28   // 카드 내부 상단 패딩

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

        val bitmap = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 마젠타 배경 (colorkey=0xFF00FF 에서 투명화)
        canvas.drawColor(Color.rgb(0xFF, 0x00, 0xFF))

        // 카드 배경: SRC 모드로 마젠타 대체 + style.alpha 반영
        // buildCardVf 호환: topColor/bottomColor 에서 RGB만 추출 (A순 데이터 무시)
        val topR = (gradient.topColor shr 16) and 0xFF
        val topG = (gradient.topColor shr 8)  and 0xFF
        val topB =  gradient.topColor and 0xFF
        val botR = (gradient.bottomColor shr 16) and 0xFF
        val botG = (gradient.bottomColor shr 8)  and 0xFF
        val botB =  gradient.bottomColor and 0xFF
        val styleAlpha = (255 * style.alpha).coerceIn(0f, 255f).toInt()

        val cL = cardX.toFloat()
        val cT = cardY.toFloat()
        val cR = (cardX + CARD_W).toFloat()
        val cB = (cardY + CARD_H).toFloat()

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            shader   = LinearGradient(
                cL, cT, cL, cB,
                Color.argb(styleAlpha, topR, topG, topB),
                Color.argb(styleAlpha, botR, botG, botB),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(cL, cT, cR, cB, bgPaint)
        // 이후 텍스트는 SRC_OVER(기본값)으로 자동 취리

        // 텍스트 영역 (center = cardX + 430 = 화면 중앙 when cardX=530)
        val textLeft = cardX + PAD_H
        val maxW     = CARD_W - PAD_H * 2   // 780px
        var curY     = cardY + PAD_V

        // highlightWord — 금색 소형 레이블
        if (row.highlightWord.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color         = Color.parseColor("#FFD700")
                textSize      = 28f
                typeface      = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
            }
            canvas.drawText(
                "◆ ${row.highlightWord}",
                textLeft.toFloat(),
                (curY + tp.textSize.toInt()).toFloat(),
                tp
            )
            curY += (tp.textSize * 1.6f).toInt()
        }

        // cardMain — fontsize=52, white bold, 중앙정렬
        if (pm.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color    = Color.WHITE
                textSize = 52f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(5f, 2f, 2f, Color.argb(180, 64, 64, 64))
            }
            val sl = StaticLayout.Builder.obtain(pm, 0, pm.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(4f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
            sl.draw(canvas); canvas.restore()
            curY += sl.height + 12
        }

        // cardSub — fontsize=38, 0xCCCCCC, 중앙정렬
        if (ps.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color    = Color.argb(220, 204, 204, 204)
                textSize = 38f
                setShadowLayer(4f, 2f, 2f, Color.argb(160, 64, 64, 64))
            }
            val sl = StaticLayout.Builder.obtain(ps, 0, ps.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(2f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
            sl.draw(canvas); canvas.restore()
            curY += sl.height + 8
        }

        // cardDesc — fontsize=32, 0xAAAAAA, 중앙정렬
        if (pd.isNotBlank() && curY < cardY + CARD_H - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color    = Color.argb(180, 170, 170, 170)
                textSize = 32f
                setShadowLayer(3f, 1f, 1f, Color.argb(140, 64, 64, 64))
            }
            val sl = StaticLayout.Builder.obtain(pd, 0, pd.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(2f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save(); canvas.translate(textLeft.toFloat(), curY.toFloat())
            sl.draw(canvas); canvas.restore()
        }

        return try {
            outputPng.outputStream().buffered().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}
