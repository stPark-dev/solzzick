package com.zzick.core

import com.zzick.core.vcam.CameraParams
import com.zzick.core.vcam.SplitMix64
import com.zzick.core.vcam.VirtualCamera
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 기준값 비교 외에 포팅 쪽에서 직접 지켜야 하는 성질들. */
class VirtualCameraTest {
    private val fps = 30.0
    private val w = 1440.0
    private val h = 1440.0

    @Test
    fun splitMix64MatchesReferenceVectors() {
        val r = SplitMix64(0)
        assertArrayEquals(
            longArrayOf(0xE220A8397B1DCDAFuL.toLong(), 0x6E789E6AA1B965F4uL.toLong(), 0x06C45D188009454FuL.toLong()),
            longArrayOf(r.nextU64(), r.nextU64(), r.nextU64()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSingleFrame() {
        VirtualCamera.solvePath(arrayOf(doubleArrayOf(720.0, 720.0)), fps, w, h)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTrack() {
        VirtualCamera.solvePath(Array(10) { doubleArrayOf(Double.NaN, Double.NaN) }, fps, w, h)
    }

    @Test
    fun cropNeverLeavesSourceWithMaxShakeAndEdgeClimber() {
        val rnd = Random(0)
        var x = 720.0
        var y = 720.0
        val track = Array(900) { i ->
            if (i < 150) doubleArrayOf(5.0, 5.0) else {
                x += rnd.nextDouble(-30.0, 30.0)
                y += rnd.nextDouble(-30.0, 30.0)
                doubleArrayOf(x, y)
            }
        }
        val body = Array(900) { i -> doubleArrayOf(track[i][0] - 300, track[i][1] - 400, track[i][0] + 300, track[i][1] + 200) }
        val path = VirtualCamera.solvePath(track, fps, w, h, CameraParams(shakeGain = 10.0), body)
        for (r in path) {
            val t = Math.toRadians(abs(r[4]))
            val bw = (r[2] * cos(t) + r[3] * sin(t)) / 2
            val bh = (r[2] * sin(t) + r[3] * cos(t)) / 2
            assertTrue(r[0] - bw >= -1e-6 && r[0] + bw <= w + 1e-6)
            assertTrue(r[1] - bh >= -1e-6 && r[1] + bh <= h + 1e-6)
        }
    }

    @Test
    fun staticClimberIsCenteredWithHeadroomWhenStill() {
        val p = CameraParams(driftX = 0.0, driftY = 0.0, shakeGain = 0.0, motionZoom = false)
        val path = VirtualCamera.solvePath(Array(300) { doubleArrayOf(700.0, 800.0) }, fps, w, h, p)
        val ch = h / p.zoom
        for (r in path) {
            assertEquals(700.0, r[0], 1e-9)
            assertEquals(800.0 - p.headroom * ch, r[1], 1e-9)
            assertEquals(ch, r[3], 1e-9)
            assertEquals(0.0, r[4], 0.0)
        }
    }

    // --- 동작에 따른 자동 줌 (Python tests/test_vcam.py 와 같은 내용) ---
    private val motion = CameraParams(driftX = 0.0, driftY = 0.0, shakeGain = 0.0)

    @Test
    fun cruxTightensThenReturnsWhenMovingAgain() {
        val track = Array(360) { i -> doubleArrayOf(720.0, if (i < 180) 900.0 else 900.0 - 0.1 * 1440 * (i - 179) / fps) }
        val path = VirtualCamera.solvePath(track, fps, w, h, motion)
        val base = h / motion.zoom
        assertTrue(path[90][3] < base * 0.95)
        assertEquals(base, path[330][3], base * 0.01)
    }

    @Test
    fun bigMoveWidensSlightlyBeforeItHappens() {
        val track = Array(300) { i ->
            val y = when {
                i < 150 -> 1000.0
                i < 165 -> 1000.0 - 700.0 * (i - 150) / 14.0
                else -> 300.0
            }
            doubleArrayOf(720.0 + 2.0 * i, y)
        }
        val path = VirtualCamera.solvePath(track, fps, w, h, motion)
        val base = h / motion.zoom
        assertTrue(path[157][3] > base * 1.08)
        assertTrue(path[144][3] > base * 1.02)
    }

    @Test
    fun motionZoomRespectsUpscaleLimitAndSource() {
        val p = motion.copy(zoom = 1.5, maxUpscale = 2.0, cruxZoom = 1.5)
        val path = VirtualCamera.solvePath(Array(300) { doubleArrayOf(720.0, 900.0) }, fps, w, h, p)
        assertTrue(path.minOf { it[3] } >= 1920 / 2.0 - 1e-6)
        assertTrue(path.maxOf { it[3] } <= h + 1e-6)
    }

    @Test
    fun shortPauseDoesNotPulseTheZoom() {
        val v = 0.1 * 1440 / fps
        val seg = DoubleArray(360) { i -> if (i < 120) i * v else if (i < 240) 119 * v else 119 * v + (i - 239) * v }
        val track = Array(360) { doubleArrayOf(720.0, 1100.0 - seg[it]) }
        val base = h / motion.zoom
        val bumpy = VirtualCamera.solvePath(track, fps, w, h, motion.copy(cruxMinS = 0.0))
        assertTrue(bumpy.minOf { it[3] } < base * 0.97)
        assertTrue(VirtualCamera.solvePath(track, fps, w, h, motion).minOf { it[3] } > base * 0.98)
    }
}
