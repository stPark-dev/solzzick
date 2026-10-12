package com.zzick.core.render

/**
 * 렌더러가 넘기는 프레임 pts → 분석 프레임 인덱스.
 * 렌더러의 첫 프레임을 트리밍 시작(startUs 이상인 첫 분석 프레임)에 맞추고, 이후는 상대 시간으로 가장 가까운 프레임을 찾는다.
 * (렌더러가 클리핑 뒤 pts를 0부터 주든 원래 시간축으로 주든 같은 결과)
 */
class FrameIndexMap(private val ptsUs: LongArray, startUs: Long) {
    private val startIndex = ptsUs.indexOfFirst { it >= startUs }.let { if (it < 0) ptsUs.size - 1 else it }
    private var anchor = Long.MIN_VALUE

    fun indexFor(renderPtsUs: Long): Int {
        if (anchor == Long.MIN_VALUE) anchor = renderPtsUs
        return nearest(ptsUs, ptsUs[startIndex] + (renderPtsUs - anchor))
    }

    companion object {
        /** 원본 시간축 pts에 가장 가까운 프레임(미리보기 플레이어는 절대 pts를 준다). */
        fun nearest(ptsUs: LongArray, target: Long): Int {
            var i = ptsUs.binarySearch(target)
            if (i >= 0) return i
            i = -i - 1
            if (i == 0) return 0
            if (i >= ptsUs.size) return ptsUs.size - 1
            return if (target - ptsUs[i - 1] < ptsUs[i] - target) i - 1 else i
        }
    }
}
