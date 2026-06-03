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
 * Android Canvas → 1920x1080 ARGB PNG 소드 카드 오버레이
 *
 * 구조:
 *   - 상단 62%: 완전 투명 (BGV 영상만 보임)
 *   - 하단 38% (y=670부터): gradientPreset 배경 + 텍스트 레이어
 *
 * 스킵 조건: cardStyle == MINIMAL/NONE 또는 텍스트 모두 비어있음
 */
object CardRenderer {

    private const val VW = 1920
    private const val VH = 1080
    private const val CARD_TOP_RATIO = 0.62f   // 화면 하단 38%에 카드 패널
    private const val PAD_H = 80               // 좌우 충여(px)
    private const val PAD_V = 40               // 상하 충여(px)

    /**
     * 카드 PNG를 렌더링하여 outputPng에 저장.
     * @return true = PNG 생성 성공, false = 카드 스킵(오버레이 불필요)
     */
    fun render(row: ScriptDataRow, outputPng: File): Boolean {
        val style    = CardStyle.from(row.cardStyle)
        val gradient = GradientPreset.from(row.gradientPreset)

        // 카드 표시 안 하는 케이스
        if (style == CardStyle.MINIMAL || style == CardStyle.NONE) return false
        if (row.cardMain.isBlank() && row.cardSub.isBlank() && row.highlightWord.isBlank()) return false

        val cardTopY = (VH * CARD_TOP_RATIO).toInt()   // y = 670
        val bitmap   = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888)
        val canvas   = Canvas(bitmap)

        // 전체 투명 초기화
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // ── 너러지 배경 (gradientPreset 색상은 이미 알파 포함) ─────────
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // CardStyle.alpha 는 추가 전체 투명도 조절
            alpha = (255 * style.alpha).coerceIn(0f, 255f).toInt()
            shader = LinearGradient(
                0f, cardTopY.toFloat(), 0f, VH.toFloat(),
                gradient.topColor, gradient.bottomColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, cardTopY.toFloat(), VW.toFloat(), VH.toFloat(), bgPaint)

        // ── 텍스트 레이어 ──────────────────────────────────────
        val maxW = VW - PAD_H * 2
        var curY = cardTopY + PAD_V

        // 1. highlightWord — 금색 라벨
        if (row.highlightWord.isNotBlank() && curY < VH - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD700")
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
            }
            val labelY = curY + tp.textSize.toInt()
            canvas.drawText("◆ ${row.highlightWord}", PAD_H.toFloat(), labelY.toFloat(), tp)
            curY += (tp.textSize * 1.5f).toInt()
        }

        // 2. cardMain — 대형 볼드 제목
        if (row.cardMain.isNotBlank() && curY < VH - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 68f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(6f, 2f, 3f, Color.argb(140, 0, 0, 0))
            }
            val sl = StaticLayout.Builder.obtain(row.cardMain, 0, row.cardMain.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(PAD_H.toFloat(), curY.toFloat())
            sl.draw(canvas)
            canvas.restore()
            curY += sl.height + 14
        }

        // 3. cardSub — 서브타이틀
        if (row.cardSub.isNotBlank() && curY < VH - PAD_V) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255)
                textSize = 40f
                typeface = Typeface.DEFAULT
            }
            val sl = StaticLayout.Builder.obtain(row.cardSub, 0, row.cardSub.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(PAD_H.toFloat(), curY.toFloat())
            sl.draw(canvas)
            canvas.restore()
            curY += sl.height + 10
        }

        // 4. cardDesc — 설명 (각조 여백 있을 때만)
        if (row.cardDesc.isNotBlank() && curY < VH - PAD_V - 40) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 210, 210, 210)
                textSize = 30f
                typeface = Typeface.DEFAULT
            }
            val sl = StaticLayout.Builder.obtain(row.cardDesc, 0, row.cardDesc.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.0f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(PAD_H.toFloat(), curY.toFloat())
            sl.draw(canvas)
            canvas.restore()
        }

        // ── PNG 저장 ────────────────────────────────────────────────
        return try {
            outputPng.outputStream().buffered().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            bitmap.recycle()  // OOM 방지 — 항상 해제
        }
    }
}
