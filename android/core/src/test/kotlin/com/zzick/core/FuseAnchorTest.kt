package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 분석기가 이어 따라간 박스(anchors)로 포즈 공백을 메울 때. */
class FuseAnchorTest {
    private val fps = 30.0

    /**
     * 점검에서 찾음(20260914_160135 8~12s): 이어 따라간 등반자 박스가 있는 프레임은 그 박스를, 없는 프레임은 직선 예상 위치
     * 근처의 박스(사람으로 잡힌 큰 홀드)를 써서 카메라가 둘 사이를 오감. 이어 따라간 박스를 받으면 그것만 쓴다.
     */
    @Test
    fun withAnchorsOnlyAnchoredBoxesFillTheGap() {
        val n = 90
        val pose = Array(n) { i -> if (i == 0 || i == n - 1) doubleArrayOf(0.4, 0.8) else doubleArrayOf(Double.NaN, Double.NaN) }
        val climber = Box(0.45, 0.3, 0.65, 0.8) // 위에서 매달린 등반자
        val hold = Box(0.3, 0.7, 0.5, 0.95) // 직선 예상 위치(0.4, 0.8) 바로 옆의 홀드
        val boxes = List(n) { i -> if (i % 3 == 0) listOf(hold, climber) else emptyList() }
        val anchors = List(n) { i -> if (i % 6 == 0 && i in 1 until n - 1) climber else null } // 절반만 이어 따라감
        val r = Fuse.fuseTrack(pose, boxes, fps, anchors = anchors)
        for (i in 6 until n - 6) assertTrue("frame $i 홀드로 감 y=${r.track[i][1]}", r.track[i][1] < 0.65)
        for (i in 6 until n - 6 step 6) assertEquals("frame $i src", Fuse.BOX, r.source[i])
    }
}
