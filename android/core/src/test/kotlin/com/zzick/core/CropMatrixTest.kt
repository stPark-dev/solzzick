package com.zzick.core

import com.zzick.core.render.CropMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 크롭 사각형(원본 px) → 렌더러(Media3)용 3x3 행렬: 입력 NDC → 출력 NDC.
 * NDC는 y가 위(+1)이고 픽셀 좌표는 y가 아래라는 점이 핵심.
 */
class CropMatrixTest {
    private val srcW = 1440.0
    private val srcH = 1440.0

    private fun ndcIn(px: Double, py: Double) = doubleArrayOf(2 * px / srcW - 1, 1 - 2 * py / srcH)

    private fun apply(m: DoubleArray, p: DoubleArray): DoubleArray {
        val x = m[0] * p[0] + m[1] * p[1] + m[2]
        val y = m[3] * p[0] + m[4] * p[1] + m[5]
        val w = m[6] * p[0] + m[7] * p[1] + m[8]
        return doubleArrayOf(x / w, y / w)
    }

    private fun assertPoint(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected[0], actual[0], 1e-9)
        assertEquals(expected[1], actual[1], 1e-9)
    }

    @Test
    fun unrotatedCropCornersMapToOutputCorners() {
        val (cx, cy, cw, ch) = listOf(700.0, 600.0, 621.0, 1104.0)
        val m = CropMatrix.ndc(cx, cy, cw, ch, 0.0, srcW, srcH)
        // 크롭 좌상단(픽셀) → 출력 좌상단 = NDC (-1, +1)
        assertPoint(doubleArrayOf(-1.0, 1.0), apply(m, ndcIn(cx - cw / 2, cy - ch / 2)))
        assertPoint(doubleArrayOf(1.0, -1.0), apply(m, ndcIn(cx + cw / 2, cy + ch / 2)))
        assertPoint(doubleArrayOf(0.0, 0.0), apply(m, ndcIn(cx, cy)))
    }

    @Test
    fun rotatedCropCornersMapToOutputCorners() {
        val cx = 720.0
        val cy = 700.0
        val cw = 600.0
        val ch = 1000.0
        val rot = 3.0
        val m = CropMatrix.ndc(cx, cy, cw, ch, rot, srcW, srcH)
        // Python 렌더러(cv2.getRotationMatrix2D)와 같은 규약: 출력 오프셋 (u, v) ← 원본 c + R⁻¹(u, v)
        val t = Math.toRadians(rot)
        fun corner(u: Double, v: Double) = ndcIn(cx + u * cos(t) - v * sin(t), cy + u * sin(t) + v * cos(t))
        assertPoint(doubleArrayOf(-1.0, 1.0), apply(m, corner(-cw / 2, -ch / 2)))
        assertPoint(doubleArrayOf(1.0, 1.0), apply(m, corner(cw / 2, -ch / 2)))
        assertPoint(doubleArrayOf(1.0, -1.0), apply(m, corner(cw / 2, ch / 2)))
    }
}
