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

// ============================================================
// CardRenderer — Android Canvas → 1920x1080 ARGB PNG 카드 오버레이
//
// v3.31 수정:
//   - 줄바쫚(StaticLayout) 경로 중앙정렬 버그 수정:
//       StaticLayout은 paint가 Align.LEFT라고 가정. paint가 Align.CENTER면
//       Layout이 계산한 줄 시작 x를 Canvas가 '중앙 x'로 재해석 → 각 줄이 우측으로 밀림.
//       해결: StaticLayout 분기에서 tp.textAlign = Align.LEFT 전환 후 ALIGN_CENTER 사용.
//       (한 줄 drawText 경로는 Align.CENTER 유지 — 두 분기는 필드별 상호배타)
//   - CARD_H 340 → 460: 4개 항목 중 3개가 2줄이 되어 누적 높이가 카드를 넘는 문제 해결
//   - 텍스트 블록 세로 중앙정렬: 콘텐츠 총 높이를 먼저 측정해 카드 안에서 수직 가운데 배치
//   - 카드 외곽 크기는 고정(동적 아님) — 키프레임/합성 파이프라인 불변 → 안정성 우선
//
// v3.29.1: normalizeCardText로 \N, \n, \r\n 처리 / drawField maxLines 2→3
// ============================================================
object CardRenderer {

    private const val TAG          = "CardRenderer"
    const  val VW                  = 1920
    const  val VH                  = 1080
    const  val CARD_W              = 860
    const  val CARD_H              = 460          // v3.31: 340 -> 460 (다중 2줄 항목 누적 높이 수용)
    private const val CARD_X_DEFAULT = (VW - CARD_W) / 2   // 530
    private const val CARD_Y_DEFAULT = 680
    private const val PAD_H        = 40
    private const val PAD_V        = 28

    private fun normalizeCardText(raw: String): String =
        raw.replace("\r\n", "\n")
           .replace("\\N",  "\n")
           .replace("\\n",  "\n")

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

        val rawHl   = row.highlightWord.trim()
        val rawMain = row.cardMain.trim()
        val rawSub  = row.cardSub.trim()
        val rawDesc = row.cardDesc.trim()

        val hl = normalizeCardText(rawHl)
        val pm = normalizeCardText(rawMain)
        val ps = normalizeCardText(rawSub)
        val pd = normalizeCardText(rawDesc)

        if (pm.isBlank() && ps.isBlank() && pd.isBlank() && hl.isBlank()) return false

        Log.d(TAG, "[정규화 전] hl=${rawHl.replace("\n","↵")} main=${rawMain.replace("\n","↵")} sub=${rawSub.replace("\n","↵")} desc=${rawDesc.replace("\n","↵")}")
        Log.d(TAG, "[정규화 후] hl=${hl.replace("\n","↵")} main=${pm.replace("\n","↵")} sub=${ps.replace("\n","↵")} desc=${pd.replace("\n","↵")}")

        val topR = (gradient.topColor shr 16) and 0xFF
        val topG = (gradient.topColor shr  8) and 0xFF
        val topB =  gradient.topColor         and 0xFF
        val botR = (gradient.bottomColor shr 16) and 0xFF
        val botG = (gradient.bottomColor shr  8) and 0xFF
        val botB =  gradient.bottomColor         and 0xFF
        val effTop = Color.rgb(topR, topG, topB)
        val effBot = Color.rgb(botR, botG, botB)

        Log.d(TAG, "style=${style.name} alpha=${style.alpha} cardX=$cardX cardY=$cardY CARD_H=$CARD_H")

        val bitmap = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 마젠타 배경 (후단 colorkey=0xFF00FF 제거용 — 파이프라인 불변)
        canvas.drawColor(Color.rgb(0xFF, 0x00, 0xFF))

        // 카드 배경 그라디언트 (외곽 고정 크기 CARD_H)
        val cL = cardX.toFloat()
        val cT = cardY.toFloat()
        val cR = (cardX + CARD_W).toFloat()
        val cB = (cardY + CARD_H).toFloat()
        canvas.drawRect(cL, cT, cR, cB, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cL, cT, cL, cB, effTop, effBot, Shader.TileMode.CLAMP)
        })

        val boxCenterX  = cardX + CARD_W / 2f
        val textLeft    = cardX + PAD_H
        val maxW        = CARD_W - PAD_H * 2   // 780px
        val bottomLimit = cardY + CARD_H - PAD_V

        // ── 텍스트 페인트 + 표시 문자열 (측정/그리기 공용) ──
        val hlText = if (hl.isNotBlank()) "◆ $hl" else ""
        val hlPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD700"); textSize = 28f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
            textAlign = Paint.Align.CENTER
        }
        val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(5f, 2f, 2f, Color.argb(180, 64, 64, 64))
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 204, 204, 204); textSize = 38f
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 2f, 2f, Color.argb(160, 64, 64, 64))
        }
        val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 170, 170, 170); textSize = 32f
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 1f, 1f, Color.argb(140, 64, 64, 64))
        }
        val hlGap = (hlPaint.textSize * 0.6f).toInt()

        // 필드 높이 측정 (그리기와 동일한 분기 로직, 그리지 않음)
        fun fieldHeight(text: String, tp: TextPaint, maxLines: Int, lineSpacing: Float, gapBelow: Int): Int {
            if (text.isBlank()) return 0
            return if (tp.measureText(text) <= maxW && '\n' !in text) {
                tp.fontMetrics.let { (-it.ascent + it.descent).toInt() } + gapBelow
            } else {
                val sl = StaticLayout.Builder
                    .obtain(text, 0, text.length, tp, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(lineSpacing, 1.0f)
                    .setMaxLines(maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                sl.height + gapBelow
            }
        }

        // 콘텐츠 총 높이 → 카드 내 세로 중앙 시작점 (콘텐츠가 더 크면 PAD_V로 클램 → 상단정렬)
        val totalH =
            fieldHeight(hlText, hlPaint, 2, 2f, hlGap) +
            fieldHeight(pm, mainPaint, 3, 4f, 12) +
            fieldHeight(ps, subPaint, 3, 2f, 8) +
            fieldHeight(pd, descPaint, 3, 2f, 0)
        var curY = cardY + ((CARD_H - totalH) / 2).coerceAtLeast(PAD_V)

        // ── 공통 렌더 헬퍼 ──
        // 한 줄+개행없음 → drawText(중앙). 그 외 → StaticLayout(중앙) + paint를 LEFT로(중앙정렬 버그 회피)
        fun drawField(text: String, tp: TextPaint, maxLines: Int, lineSpacing: Float, gapBelow: Int): Int {
            if (text.isBlank() || curY >= bottomLimit) return 0
            return if (tp.measureText(text) <= maxW && '\n' !in text) {
                canvas.drawText(text, boxCenterX, curY + tp.fontMetrics.let { -it.ascent }, tp)
                tp.fontMetrics.let { (-it.ascent + it.descent).toInt() } + gapBelow
            } else {
                tp.textAlign = Paint.Align.LEFT   // v3.31: StaticLayout 중앙정렬 정상화 (핵심 수정)
                Log.d(TAG, "[StaticLayout] hasNL=${'\n' in text} len=${text.length} maxLines=$maxLines")
                val sl = StaticLayout.Builder
                    .obtain(text, 0, text.length, tp, maxW)
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

        curY += drawField(hlText, hlPaint, 2, 2f, hlGap)
        curY += drawField(pm, mainPaint, 3, 4f, 12)
        curY += drawField(ps, subPaint, 3, 2f, 8)
        drawField(pd, descPaint, 3, 2f, 0)

        return try {
            outputPng.outputStream().buffered().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.d(TAG, "[✓ PNG저장] ${outputPng.name} (${outputPng.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PNG 저장 실패: ${e.message}"); false
        } finally {
            bitmap.recycle()
        }
    }
}
