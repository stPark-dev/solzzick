package com.zzick.core

import com.zzick.core.render.PreviewTransform
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 미리보기: 플레이어는 원본 프레임을 뷰 크기로 늘려 그린다. 그 뷰 좌표에 걸 변환(3x3, 행 우선)을 구해
 * 크롭 영역이 뷰를 꽉 채우게 한다. 회전 규약은 렌더(CropMatrix)·Python과 같다.
 */
class PreviewTransformTest {
    private val srcW = 1440.0
    private val srcH = 1440.0
    private val vw = 540.0
    private val vh = 960.0

    /** 원본 픽셀 → (늘린) 뷰 좌표 → 변환 적용 */
    private fun show(m: DoubleArray, px: Double, py: Double): Pair<Double, Double> {
        val x = px * vw / srcW
        val y = py * vh / srcH
        return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
    }

    private fun assertAt(ex: Double, ey: Double, p: Pair<Double, Double>) {
        assertEquals(ex, p.first, 1e-6)
        assertEquals(ey, p.second, 1e-6)
    }

    @Test
    fun cropCornersFillTheView() {
        val (cx, cy, cw, ch) = listOf(700.0, 650.0, 607.5, 1080.0)
        val m = PreviewTransform.matrix(doubleArrayOf(cx, cy, cw, ch, 0.0), srcW, srcH, vw, vh)
        assertAt(0.0, 0.0, show(m, cx - cw / 2, cy - ch / 2))
        assertAt(vw, vh, show(m, cx + cw / 2, cy + ch / 2))
        assertAt(vw / 2, vh / 2, show(m, cx, cy))
    }

    @Test
    fun rotatedCropUsesRendererConvention() {
        val cx = 720.0
        val cy = 700.0
        val cw = 540.0
        val ch = 960.0
        val rot = 2.0
        val m = PreviewTransform.matrix(doubleArrayOf(cx, cy, cw, ch, rot), srcW, srcH, vw, vh)
        val t = Math.toRadians(rot)
        // 출력 오프셋 (u, v) ← 원본 c + R⁻¹(u, v)
        fun src(u: Double, v: Double) = (cx + u * cos(t) - v * sin(t)) to (cy + u * sin(t) + v * cos(t))
        val tl = src(-cw / 2, -ch / 2)
        val br = src(cw / 2, ch / 2)
        assertAt(0.0, 0.0, show(m, tl.first, tl.second))
        assertAt(vw, vh, show(m, br.first, br.second))
    }
}
