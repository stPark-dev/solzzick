package com.zzick.pipeline

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.zzick.core.render.CropMatrix
import com.zzick.core.render.FrameIndexMap

/**
 * 프레임마다 가상 카메라 크롭을 적용하는 Media3 효과. 출력 크기를 outW×outH로 바꾼다.
 * 렌더러의 첫 프레임을 트리밍 시작(startUs)의 분석 프레임에 맞추고 이후는 상대 시간으로 대응시킨다(FrameIndexMap).
 */
@UnstableApi
class CropEffect(
    private val path: Array<DoubleArray>,
    ptsUs: LongArray,
    startUs: Long,
    private val srcW: Double,
    private val srcH: Double,
    private val outW: Int,
    private val outH: Int,
) : MatrixTransformation {
    private val frames = FrameIndexMap(ptsUs, startUs)
    private val matrix = Matrix()
    private val values = FloatArray(9)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        check(inputWidth == srcW.toInt() && inputHeight == srcH.toInt()) {
            "렌더 입력 크기 ${inputWidth}x$inputHeight 가 분석 크기 ${srcW.toInt()}x${srcH.toInt()} 와 다릅니다(회전 처리 확인 필요)"
        }
        return Size(outW, outH)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val r = path[frames.indexFor(presentationTimeUs)]
        val m = CropMatrix.ndc(r[0], r[1], r[2], r[3], r[4], srcW, srcH)
        for (i in 0 until 9) values[i] = m[i].toFloat()
        matrix.setValues(values)
        return matrix
    }
}
