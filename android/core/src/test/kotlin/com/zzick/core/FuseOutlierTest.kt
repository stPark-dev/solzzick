package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Python tests/test_fuse.py 의 '짧게 튀는 가짜 포즈' 테스트와 같은 내용(사용자 제보: 그네 동작 중 카메라가 엉뚱하게 이동). */
class FuseOutlierTest {
    private val fps = 30.0
    private val nan = doubleArrayOf(Double.NaN, Double.NaN)
    private fun noBoxes(n: Int) = List(n) { emptyList<Box>() }

    private fun swing(n: Int = 300) = Array(n) {
        val t = it / fps
        doubleArrayOf(0.75 + 0.12 * sin(2 * PI * 0.5 * t), 0.5 + 0.02 * cos(2 * PI * 0.5 * t))
    }

    @Test
    fun shortSpuriousPoseBetweenGapsIsRejected() {
        val truth = swing()
        val pose = Array(truth.size) { truth[it].copyOf() }
        for (i in 113 until 121) pose[i] = nan
        pose[121] = doubleArrayOf(0.63, 0.62)
        pose[122] = doubleArrayOf(0.62, 0.67)
        for (i in 123 until 129) pose[i] = nan
        val r = Fuse.fuseTrack(pose, noBoxes(pose.size), fps)
        assertEquals(Fuse.INTERP, r.source[121])
        assertEquals(Fuse.INTERP, r.source[122])
        for (i in 110 until 132) assertTrue("frame $i", hypot(r.track[i][0] - truth[i][0], r.track[i][1] - truth[i][1]) < 0.05)
    }

    @Test
    fun fastRealMotionIsKept() {
        val n = 120
        val pose = Array(n) { i ->
            val y = when {
                i < 50 -> 0.7
                i < 62 -> 0.7 - 0.3 * (i - 50) / 11.0
                else -> 0.4
            }
            if (i % 2 == 0) nan else doubleArrayOf(0.5, y)
        }
        val r = Fuse.fuseTrack(pose, noBoxes(n), fps)
        for (i in 1 until n step 2) assertEquals("frame $i", Fuse.POSE, r.source[i])
    }

    @Test
    fun sparseSamplesStillRejectIsolatedOutlier() {
        val truth = swing()
        val pose = Array(truth.size) { if (it % 3 == 0) truth[it].copyOf() else nan }
        pose[120] = doubleArrayOf(0.62, 0.67)
        assertEquals(Fuse.INTERP, Fuse.fuseTrack(pose, noBoxes(pose.size), fps).source[120])
    }

    @Test
    fun fastDropAtEndOfTrackIsKept() {
        val n = 90
        val pose = Array(n) { i ->
            val y = if (i < 80) 0.3 else 0.3 + 0.5 * (i - 80) / 9.0
            if (i % 3 != 0) nan else doubleArrayOf(0.5, y)
        }
        assertEquals(Fuse.POSE, Fuse.fuseTrack(pose, noBoxes(n), fps).source[87])
    }

    private fun sparse(n: Int, points: Map<Int, DoubleArray>) = Array(n) { points[it]?.copyOf() ?: nan }

    @Test
    fun spuriousRunInsideLongGapIsRejected() {
        val pts = mutableMapOf<Int, DoubleArray>()
        for (i in listOf(150, 165, 180, 192)) pts[i] = doubleArrayOf(0.90, 0.48)
        pts[250] = doubleArrayOf(0.145, 0.45)
        pts[256] = doubleArrayOf(0.145, 0.47)
        for (i in listOf(318, 321, 330, 345)) pts[i] = doubleArrayOf(0.92, 0.41)
        val r = Fuse.fuseTrack(sparse(360, pts), noBoxes(360), fps)
        assertEquals(Fuse.INTERP, r.source[250])
        assertEquals(Fuse.INTERP, r.source[256])
        for (i in 192 until 318) assertTrue("frame $i", r.track[i][0] > 0.85)
    }

    @Test
    fun steadyMoveWithSparseSamplesIsKept() {
        val idx = listOf(150, 165, 180, 250, 256, 318, 330)
        val r = Fuse.fuseTrack(sparse(360, idx.associateWith { doubleArrayOf(0.9 - 0.004 * (it - 150), 0.5) }), noBoxes(360), fps)
        for (i in idx) assertEquals("frame $i", Fuse.POSE, r.source[i])
    }

    @Test
    fun realLungeBetweenSparseClustersIsKept() {
        val pts = mutableMapOf<Int, DoubleArray>()
        for (i in listOf(240, 241, 242)) pts[i] = doubleArrayOf(0.92, 0.45)
        pts[319] = doubleArrayOf(0.918, 0.406)
        pts[322] = doubleArrayOf(0.923, 0.404)
        for (i in 352 until 380) pts[i] = doubleArrayOf(0.62 - 0.001 * (i - 352), 0.47)
        val r = Fuse.fuseTrack(sparse(400, pts), noBoxes(400), fps)
        assertEquals(Fuse.POSE, r.source[319])
        assertEquals(Fuse.POSE, r.source[322])
    }

    @Test
    fun devicePatternSingleRealBeforeFakePairThenReal() {
        val pts = mapOf(
            193 to doubleArrayOf(0.903, 0.489),
            250 to doubleArrayOf(0.144, 0.450),
            256 to doubleArrayOf(0.145, 0.476),
            319 to doubleArrayOf(0.922, 0.412),
            322 to doubleArrayOf(0.918, 0.409),
        )
        val r = Fuse.fuseTrack(sparse(400, pts), noBoxes(400), fps)
        assertEquals(Fuse.INTERP, r.source[250])
        assertEquals(Fuse.INTERP, r.source[256])
        for (i in listOf(193, 319, 322)) assertEquals("frame $i", Fuse.POSE, r.source[i])
    }
}
