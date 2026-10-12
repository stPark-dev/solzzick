package com.zzick.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.zzick.core.analysis.AppearanceExtractor
import com.zzick.core.tracking.Box

/**
 * 몸통 영역의 색 분포(HSV 히스토그램, 합 1). 채도가 낮은 색(흰·회·검 옷)은 밝기로 3칸,
 * 나머지는 색상 12칸 × 채도 2칸 = 총 27칸. 흰 티셔츠와 빨간 홀드처럼 색이 다르면 교집합이 0에 가깝다.
 */
class BitmapAppearance : AppearanceExtractor<Bitmap> {
    private val hsv = FloatArray(3)

    override fun describe(frame: Bitmap, box: Box): FloatArray? {
        val x0 = (box.x0 * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (box.x1 * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y0 = (box.y0 * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (box.y1 * frame.height).toInt().coerceIn(0, frame.height - 1)
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, x0, y0, w, h)
        val hist = FloatArray(BINS)
        var n = 0
        for (i in px.indices step 2) { // 절반만 봐도 분포는 충분
            Color.colorToHSV(px[i], hsv)
            val bin = if (hsv[1] < 0.25f) {
                when {
                    hsv[2] < 0.35f -> 0
                    hsv[2] < 0.7f -> 1
                    else -> 2
                }
            } else {
                val hue = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                3 + hue * 2 + if (hsv[1] < 0.6f) 0 else 1
            }
            hist[bin]++
            n++
        }
        if (n == 0) return null
        for (i in hist.indices) hist[i] /= n
        return hist
    }

    private companion object {
        const val BINS = 3 + 12 * 2
    }
}
