package com.zzick.core.trim

import com.zzick.core.tracking.Fuse

/** 원본 시간축(µs)의 자를 구간. end는 포함. */
data class TrimWindow(val startUs: Long, val endUs: Long) {
    val durationUs get() = endUs - startUs
}

/**
 * 자동 트리밍 제안: 등반자가 화면에 보이는 구간(포즈가 처음~마지막으로 잡힌 구간) + 앞뒤 여유.
 * '발을 뗀 순간' 감지는 앉은 시작에서 실제 등반을 잘라먹는 문제가 있어 쓰지 않는다 — 나머지는 사용자가 손잡이로 조정.
 */
object Trim {
    fun suggest(
        source: IntArray,
        ptsUs: LongArray,
        preUs: Long = 1_000_000L,
        postUs: Long = 1_500_000L,
        minSpanUs: Long = 2_000_000L,
    ): TrimWindow {
        val whole = TrimWindow(ptsUs.first(), ptsUs.last())
        val first = source.indexOfFirst { it != Fuse.HOLD }
        val last = source.indexOfLast { it != Fuse.HOLD }
        if (first < 0 || ptsUs[last] - ptsUs[first] < minSpanUs) return whole // 너무 짧으면 오검출일 수 있어 자르지 않는다
        return TrimWindow(maxOf(whole.startUs, ptsUs[first] - preUs), minOf(whole.endUs, ptsUs[last] + postUs))
    }
}
