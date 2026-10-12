package com.zzick.core.vcam

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 사람 카메라맨 추종 모델: 반응 지연 + 데드존 + 2차 스프링-댐퍼. (prototype/vcam/follow.py) */
object Follow {
    /** target을 delayS초 늦춘 신호. 시작 이전은 target[0]으로 유지. */
    internal fun delayed(target: DoubleArray, fps: Double, delayS: Double): DoubleArray {
        val shift = delayS * fps
        val last = target.size - 1
        return DoubleArray(target.size) { i ->
            val pos = i - shift
            when {
                pos <= 0.0 -> target[0]
                pos >= last -> target[last]
                else -> {
                    val k = floor(pos).toInt()
                    val f = pos - k
                    target[k] * (1 - f) + target[k + 1] * f
                }
            }
        }
    }

    /**
     * 목표 g는 지연된 target이 g±deadzone 밖으로 나갈 때만 경계까지 끌려가고,
     * 카메라는 y'' = ω²(g - y) - 2ζω y' 로 g를 따라간다.
     */
    fun simulate(
        target: DoubleArray,
        fps: Double,
        omega: Double,
        zeta: Double,
        delayS: Double,
        deadzone: Double = 0.0,
        substeps: Int = 8,
    ): DoubleArray {
        val xd = delayed(target, fps, delayS)
        val steps = max(substeps, ceil(omega / fps / 0.25).toInt()) // ω·dt ≤ 0.25 로 적분 안정성 확보
        val dt = 1.0 / fps / steps
        var g = target[0]
        var y = target[0]
        var v = 0.0
        val out = DoubleArray(xd.size)
        out[0] = y
        for (i in 1 until xd.size) {
            val x = xd[i]
            if (x - g > deadzone) g = x - deadzone else if (x - g < -deadzone) g = x + deadzone
            repeat(steps) {
                v += (omega * omega * (g - y) - 2 * zeta * omega * v) * dt
                y += v * dt
            }
            out[i] = y
        }
        return out
    }
}
