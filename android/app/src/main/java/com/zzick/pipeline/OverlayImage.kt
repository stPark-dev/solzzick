package com.zzick.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.zzick.core.overlay.Corner
import com.zzick.core.overlay.Grade
import kotlin.math.min

/**
 * 영상 구석 표시(하트 + "더클라임 강남 · 2026.10.11")를 출력 프레임 크기의 투명 그림으로 그린다.
 * 렌더(Media3 오버레이)와 미리보기가 같은 그림을 써서 보이는 그대로 나온다. 왼쪽 아래, 반투명 알약 위 흰 글자.
 * 하트는 이모지 글자가 아니라 난이도 색의 모양으로 그린다(옛 안드로이드 글꼴에 없는 하트가 □로 새겨지지 않게).
 */
object OverlayImage {
    /** 0..1 상자 안의 하트 */
    private val HEART = Path().apply {
        moveTo(0.5f, 0.3f)
        cubicTo(0.5f, 0.27f, 0.45f, 0.15f, 0.25f, 0.15f)
        cubicTo(0f, 0.15f, 0f, 0.4f, 0f, 0.4f)
        cubicTo(0f, 0.55f, 0.1f, 0.77f, 0.5f, 0.95f)
        cubicTo(0.9f, 0.77f, 1f, 0.55f, 1f, 0.4f)
        cubicTo(1f, 0.4f, 1f, 0.15f, 0.75f, 0.15f)
        cubicTo(0.6f, 0.15f, 0.5f, 0.27f, 0.5f, 0.3f)
        close()
    }

    fun render(grade: Grade?, words: String?, outW: Int, outH: Int, corner: Corner = Corner.BOTTOM_LEFT): Bitmap? {
        val text = words?.takeIf { it.isNotBlank() }
        if (grade == null && text == null) return null
        val unit = min(outW, outH).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = unit * 0.036f
        }
        val margin = unit * 0.045f
        val padX = paint.textSize * 0.6f
        val padY = paint.textSize * 0.35f
        val heartSize = paint.textSize * 1.05f
        val gap = if (grade != null && text != null) paint.textSize * 0.4f else 0f
        val heartW = if (grade != null) heartSize else 0f
        // 긴 암장 이름도 화면 폭의 70% 안에 들어가게 글자를 줄인다
        if (text != null) {
            val maxText = outW * 0.7f - 2 * padX - heartW - gap
            val w = paint.measureText(text)
            if (w > maxText) paint.textSize *= maxText / w
        }
        val fm = paint.fontMetrics
        val textW = text?.let(paint::measureText) ?: 0f
        val contentH = maxOf(fm.descent - fm.ascent, if (grade != null) heartSize else 0f)
        val pillW = 2 * padX + heartW + gap + textW
        val pillH = contentH + 2 * padY
        val (left, top) = corner.place(outW.toFloat(), outH.toFloat(), pillW, pillH, margin)
        val pill = RectF(left, top, left + pillW, top + pillH)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawRoundRect(pill, pill.height() / 2, pill.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) })
            var x = pill.left + padX
            if (grade != null) {
                val heart = Path(HEART).apply {
                    transform(Matrix().apply { setScale(heartSize, heartSize); postTranslate(x, pill.centerY() - heartSize * 0.55f) })
                }
                drawPath(heart, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grade.argb })
                // 검정·흰 하트도 어떤 배경에서든 보이게 얇은 테두리
                drawPath(heart, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = heartSize * 0.05f
                    color = Color.argb(150, 255, 255, 255)
                })
                x += heartSize + gap
            }
            if (text != null) drawText(text, x, pill.centerY() - (fm.ascent + fm.descent) / 2, paint)
        }
        return bmp
    }
}
