package com.zzick.core.progress

import kotlin.math.ceil

/**
 * 진행률(0~1)이 오르는 속도로 남은 시간을 추정한다(상태 있음, 시각은 부르는 쪽이 준다).
 * 처음 본 때(진행이 되돌아가면 그때)부터의 평균 속도 — 화면을 중간에 열어도 그때부터 잰다. 너무 이르면(시간·진행이 적으면) 추정하지 않는다.
 */
class Eta {
    private var t0 = -1L
    private var p0 = 0f
    private var last = 0f

    /** @return 남은 초, 아직 모르면 null */
    fun update(nowMs: Long, progress: Float): Double? {
        if (t0 < 0 || progress < last) { // 처음이거나 진행이 되돌아감(다시 시작) — 그때부터 다시 잰다
            t0 = nowMs
            p0 = progress
            last = progress
            return null
        }
        last = progress
        val dt = (nowMs - t0) / 1000.0
        val dp = (progress - p0).toDouble()
        if (dt < MIN_S || dp < MIN_PROGRESS) return null
        return (1.0 - progress).coerceAtLeast(0.0) * dt / dp
    }

    companion object {
        private const val MIN_S = 3.0
        private const val MIN_PROGRESS = 0.05

        /** "약 25초 남음"·"약 1분 10초 남음" — 짧게 잡지 않게 올림(1분 아래는 5초, 위는 10초 단위). 10초 아래는 "곧 끝나요". */
        fun label(seconds: Double): String {
            if (seconds < 10) return "곧 끝나요"
            if (seconds < 60) return "약 ${(ceil(seconds / 5) * 5).toInt()}초 남음"
            val s = (ceil(seconds / 10) * 10).toInt()
            return if (s % 60 == 0) "약 ${s / 60}분 남음" else "약 ${s / 60}분 ${s % 60}초 남음"
        }
    }
}
