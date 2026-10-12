package com.zzick.pipeline

import android.graphics.Bitmap
import com.zzick.core.analysis.GrayProvider
import com.zzick.core.flow.GrayImage

/** 분석 프레임(640px) → 2x2 평균으로 절반 크기 회색조(광학 흐름용). 버퍼는 재사용한다. */
class BitmapGray : GrayProvider<Bitmap> {
    private var px = IntArray(0)

    override fun gray(frame: Bitmap): GrayImage {
        val w = frame.width
        val h = frame.height
        if (px.size != w * h) px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)
        val gw = w / 2
        val gh = h / 2
        val out = FloatArray(gw * gh)
        for (y in 0 until gh) {
            var i0 = 2 * y * w
            var i1 = i0 + w
            var o = y * gw
            for (x in 0 until gw) {
                // BT.601 정수 근사(77, 150, 29)/256 — 4픽셀 합을 한 번에 나눈다
                val a = px[i0]
                val b = px[i0 + 1]
                val c = px[i1]
                val d = px[i1 + 1]
                val r = ((a shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((c shr 16) and 0xFF) + ((d shr 16) and 0xFF)
                val g = ((a shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((c shr 8) and 0xFF) + ((d shr 8) and 0xFF)
                val bl = (a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF)
                out[o++] = (77 * r + 150 * g + 29 * bl) / 1024f
                i0 += 2
                i1 += 2
            }
        }
        return GrayImage(gw, gh, out)
    }
}
