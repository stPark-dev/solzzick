package com.zzick.core.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * 미리보기용 뷰 변환(3x3, 행 우선). 플레이어가 원본 프레임을 뷰 크기(vw×vh)로 늘려 그린 뒤
 * 이 변환을 걸면 크롭 영역이 뷰를 꽉 채운다. 회전 규약은 CropMatrix·Python 렌더러와 같다.
 */
object PreviewTransform {
    /** @param crop (cx, cy, w, h, rotDeg) — VirtualCamera.solvePath 한 행(원본 px) */
    fun matrix(crop: DoubleArray, srcW: Double, srcH: Double, vw: Double, vh: Double): DoubleArray {
        val (cx, cy, cw, ch) = crop
        val t = Math.toRadians(crop[4])
        val c = cos(t)
        val s = sin(t)
        val ax = srcW / vw // 뷰 좌표 → 원본 px
        val ay = srcH / vh
        val kx = vw / cw // 크롭 → 뷰
        val ky = vh / ch
        // q(뷰) → d = (ax·qx - cx, ay·qy - cy) → (kx(c·dx + s·dy), ky(-s·dx + c·dy)) + 뷰 중심
        return doubleArrayOf(
            kx * c * ax, kx * s * ay, kx * (-c * cx - s * cy) + vw / 2,
            -ky * s * ax, ky * c * ay, ky * (s * cx - c * cy) + vh / 2,
            0.0, 0.0, 1.0,
        )
    }
}
