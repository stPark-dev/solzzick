package com.zzick.core.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * 크롭 사각형 → 렌더러용 3x3 행렬(행 우선): 입력 프레임 NDC → 출력 프레임 NDC.
 * 회전 규약은 Python 렌더러(cv2.getRotationMatrix2D)와 같다. 출력 비율은 크롭 비율과 같다고 가정.
 */
object CropMatrix {
    fun ndc(cx: Double, cy: Double, cw: Double, ch: Double, rotDeg: Double, srcW: Double, srcH: Double): DoubleArray {
        val t = Math.toRadians(rotDeg)
        val c = cos(t)
        val s = sin(t)
        val hw = srcW / 2
        val hh = srcH / 2
        // NDC_in → 픽셀 오프셋 d = (hw·xn + hw - cx, -hh·yn + hh - cy)
        // 출력 NDC: xo = (2/cw)(c·dx + s·dy), yo = (2/ch)(s·dx - c·dy)
        val kx = 2 / cw
        val ky = 2 / ch
        return doubleArrayOf(
            kx * c * hw, -kx * s * hh, kx * (c * (hw - cx) + s * (hh - cy)),
            ky * s * hw, ky * c * hh, ky * (s * (hw - cx) - c * (hh - cy)),
            0.0, 0.0, 1.0,
        )
    }
}
