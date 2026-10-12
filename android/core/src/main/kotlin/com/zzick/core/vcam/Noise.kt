package com.zzick.core.vcam

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Python 프로토타입과 같은 수열을 내는 SplitMix64 + Box-Muller. (prototype/vcam/noise.py) */
class SplitMix64(seed: Long) {
    private var state = seed

    fun nextU64(): Long {
        state += 0x9E3779B97F4A7C15uL.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** (0, 1] 균등분포 — log(0) 방지. */
    fun nextDouble(): Double = ((nextU64() ushr 11) + 1).toDouble() * TWO_POW_M53

    fun nextGaussian(): Double {
        val u1 = nextDouble()
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    private companion object {
        val TWO_POW_M53 = 2.0.pow(-53)
    }
}

object Noise {
    /** smoothstep 보간 value noise(격자값 ~ N(0,1))의 분산: ∫(1-s)² + s² df = 26/35 */
    private const val OCTAVE_VAR = 26.0 / 35.0

    private fun valueNoise(n: Int, fps: Double, hz: Double, rng: SplitMix64): DoubleArray {
        val t = DoubleArray(n) { it / fps * hz }
        val lattice = DoubleArray(t[n - 1].toInt() + 2) { rng.nextGaussian() }
        return DoubleArray(n) { j ->
            val i = t[j].toInt()
            val f = t[j] - i
            val s = f * f * (3 - 2 * f)
            lattice[i] * (1 - s) + lattice[i + 1] * s
        }
    }

    /** 기본 주파수 hz 근처의 부드러운 노이즈. 이론 RMS 1, 같은 seed면 길이와 무관하게 앞부분이 같다. */
    fun smooth(n: Int, fps: Double, hz: Double, seed: Long, octaves: Int = 2): DoubleArray {
        val x = DoubleArray(n)
        var varSum = 0.0
        for (k in 0 until octaves) {
            val amp = 0.5.pow(k)
            val v = valueNoise(n, fps, hz * 2.0.pow(k), SplitMix64(seed * 1_000_003L + k))
            for (j in 0 until n) x[j] += amp * v[j]
            varSum += 0.25.pow(k)
        }
        val norm = sqrt(OCTAVE_VAR * varSum)
        for (j in 0 until n) x[j] /= norm
        return x
    }
}
