package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Python tests/test_fuse.py 의 '광학 흐름으로 포즈 공백 메우기' 테스트와 같은 내용. */
class FuseFlowTest {
    private val nan = doubleArrayOf(Double.NaN, Double.NaN)
    private fun noBoxes(n: Int) = List(n) { emptyList<Box>() }

    @Test
    fun flowFillsGapAndIsCorrectedToNextPose() {
        val n = 40
        val truth = Array(n) { doubleArrayOf(0.3 + 0.4 * it / (n - 1), 0.5) }
        val pose = Array(n) { if (it in 11 until 30) nan else truth[it].copyOf() }
        val flow = Array(n) { if (it in 11 until 30) doubleArrayOf(truth[10][0] + (truth[it][0] - truth[10][0]) * 0.9, 0.5) else nan }
        val seed = IntArray(n) { if (it in 11 until 30) 10 else -1 }
        val r = Fuse.fuseTrack(pose, noBoxes(n), flow = flow, flowSeed = seed)
        for (i in 11 until 30) assertEquals(Fuse.FLOW, r.source[i])
        assertEquals(truth[29][0], r.track[29][0], 0.004)
        for (i in 11 until 30) assertTrue(abs(r.track[i][0] - truth[i][0]) < 0.01)
    }

    @Test
    fun flowFromRejectedSeedIsIgnored() {
        val n = 300
        val pose = Array(n) { i ->
            val t = i / 30.0
            doubleArrayOf(0.75 + 0.12 * sin(PI * t), 0.5 + 0.02 * cos(PI * t))
        }
        for (i in 113 until 121) pose[i] = nan
        pose[121] = doubleArrayOf(0.63, 0.62)
        for (i in 122 until 129) pose[i] = nan
        val flow = Array(n) { if (it in 122 until 129) doubleArrayOf(0.63, 0.62) else nan }
        val seed = IntArray(n) { if (it in 122 until 129) 121 else -1 }
        val r = Fuse.fuseTrack(pose, noBoxes(n), flow = flow, flowSeed = seed)
        for (i in 122 until 129) assertTrue("frame $i", r.source[i] != Fuse.FLOW)
    }

    @Test
    fun flowLostMidwayThenInterpolatesToNextPose() {
        val n = 40
        val pose = Array(n) { i -> if (i < 11) doubleArrayOf(0.3, 0.5) else if (i >= 30) doubleArrayOf(0.7, 0.5) else nan }
        val flow = Array(n) { if (it in 11 until 18) doubleArrayOf(0.3 + 0.01 * (it - 10), 0.5) else nan }
        val seed = IntArray(n) { if (it in 11 until 18) 10 else -1 }
        val r = Fuse.fuseTrack(pose, noBoxes(n), flow = flow, flowSeed = seed)
        for (i in 11 until 18) assertEquals(Fuse.FLOW, r.source[i])
        for (i in 18 until 30) assertEquals(Fuse.INTERP, r.source[i])
        assertTrue(r.track[18][0] > r.track[17][0] - 1e-9 && r.track[29][0] < 0.7)
    }
}
