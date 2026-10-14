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

    /** 2초 서 있다가 왼쪽으로 일정한 속도(px/s)로 4초 이동, 다시 멈춤. (prototype tests/test_vcam.py traverse) */
    private fun traverse(n: Int = 300, x0: Double = 1100.0, speed: Double = -180.0) =
        Array(n) { i -> doubleArrayOf(x0 + speed * ((i / fps) - 2.0).coerceIn(0.0, 4.0), 720.0) }

    private val still = CameraParams(driftX = 0.0, driftY = 0.0, shakeGain = 0.0, motionZoom = false)

    /** 사용자 제보(20260801_161145): 줄을 잡고 옆으로 이동할 때 등반자가 늘 가운데가 아니다 — 반응 지연+스프링으로 0.3초쯤 뒤처짐. */
    @Test
    fun steadyTraverseLagsWithoutLead() {
        val track = traverse()
        val path = VirtualCamera.solvePath(track, fps, w, h, still.copy(leadS = 0.0))
        val lag = (120 until 180).map { path[it][0] - track[it][0] }.average()
        assertTrue("lag=$lag", lag > 50)
    }

    /** 오프라인이라 앞으로 갈 곳을 안다 — 그만큼 앞을 겨누면 일정한 속도의 이동에서 가운데를 지킨다(데드존만큼만 차이). */
    @Test
    fun steadyTraverseIsCenteredWithLead() {
        val track = traverse()
        val path = VirtualCamera.solvePath(track, fps, w, h, still)
        val dz = still.deadzoneX * h / still.zoom
        for (i in 100 until 165) assertTrue("frame $i off=${path[i][0] - track[i][0]}", abs(path[i][0] - track[i][0]) <= dz + 5)
    }

    private val handheld = CameraParams(shakeGain = 0.0, motionZoom = false, seed = 4) // 드리프트는 켠 상태(이동 구간에서 드리프트가 큰 시드)

    private fun fastTraverse(): Array<DoubleArray> =
        traverse(speed = -450.0, x0 = 1250.0).also { t -> t.forEach { it[0] = maxOf(it[0], 400.0) } } // 원본 가장자리에 막히지 않게

    /** 사용자 제보(20260801_161145): 손으로 찍은 느낌의 느린 드리프트(±3%) 때문에 빠르게 옮겨 갈 때도 가운데에서 벗어난다. */
    @Test
    fun driftPullsClimberOffCenterDuringFastMove() {
        val track = fastTraverse()
        val plain = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftCalmSpeed = 0.0))
        val nodrift = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftX = 0.0, driftY = 0.0))
        assertTrue((75 until 100).minOf { abs(plain[it][0] - nodrift[it][0]) } > 20)
    }

    /** 빠른 이동 중(기준 속도 이상)엔 드리프트를 빼 가운데를 지키고, 서 있을 땐 손으로 찍은 느낌을 그대로 둔다. */
    @Test
    fun driftFadesDuringFastMoveAndStaysWhenStill() {
        val track = fastTraverse()
        val calm = VirtualCamera.solvePath(track, fps, w, h, handheld)
        val nodrift = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftX = 0.0, driftY = 0.0))
        for (i in 75 until 100) for (c in 0..1) assertEquals("frame $i", nodrift[i][c], calm[i][c], 1.0)
        val plain = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftCalmSpeed = 0.0))
        for (i in 0 until 20) assertArrayEquals(plain[i], calm[i], 1e-9)
    }

    /** 기준의 절반 속도면 드리프트도 절반쯤 남는다(서서히 줄어든다). */
    @Test
    fun driftIsPartlyKeptAtModerateSpeed() {
        val track = traverse() // 180px/s = 0.125 H/s, 기준(0.25)의 절반
        val calm = VirtualCamera.solvePath(track, fps, w, h, handheld)
        val plain = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftCalmSpeed = 0.0))
        val nodrift = VirtualCamera.solvePath(track, fps, w, h, handheld.copy(driftX = 0.0, driftY = 0.0))
        for (i in 100 until 150) {
            val kept = (calm[i][0] - nodrift[i][0]) / (plain[i][0] - nodrift[i][0])
            assertEquals("frame $i", 0.5, kept, 0.05)
        }
    }

    /** 미리 겨누기(음수 지연): 앞의 목표를 보고, 끝을 넘으면 마지막 값으로 유지. */
    @Test
    fun negativeDelayLooksAheadAndHoldsLastValue() {
        val x = DoubleArray(10) { it.toDouble() }
        assertArrayEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 9.0, 9.0), com.zzick.core.vcam.Follow.delayed(x, fps, -2 / fps), 1e-9)
    }

    /** 처음부터 움직이는 목표를 앞당겨 따라갈 때, 시작에서 목표와 어긋난 채 출발해 출렁이지 않는다. */
    @Test
    fun followWithLookAheadHasNoStartWobble() {
        val target = DoubleArray(60) { 100 + 10.0 * it }
        val y = com.zzick.core.vcam.Follow.simulate(target, fps, omega = 8.0, zeta = 0.9, delayS = -0.2)
        val lag = DoubleArray(60) { target[it] + 10.0 * 0.2 * fps - y[it] }
        for (i in 1 until 20) assertTrue("frame $i", lag[i] - lag[i - 1] >= -1e-9)
    }
}
