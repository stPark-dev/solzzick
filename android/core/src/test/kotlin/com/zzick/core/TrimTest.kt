package com.zzick.core

import com.zzick.core.render.FrameIndexMap
import com.zzick.core.tracking.Fuse.BOX
import com.zzick.core.tracking.Fuse.HOLD
import com.zzick.core.tracking.Fuse.INTERP
import com.zzick.core.tracking.Fuse.POSE
import com.zzick.core.trim.Trim
import com.zzick.core.trim.TrimWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimTest {
    private val fps = 30.0
    private fun pts(n: Int) = LongArray(n) { (it * 1_000_000L / fps).toLong() }

    /** HOLD a개, 등반자(POSE/INTERP/BOX 섞임) b개, HOLD c개 */
    private fun source(a: Int, b: Int, c: Int) =
        IntArray(a) { HOLD } + IntArray(b) { listOf(POSE, INTERP, BOX)[it % 3] } + IntArray(c) { HOLD }

    @Test
    fun suggestsVisibleSpanWithPads() {
        val src = source(300, 600, 300) // 0~10s 없음, 10~30s 등반자, 30~40s 없음
        val p = pts(src.size)
        val w = Trim.suggest(src, p)
        assertEquals(TrimWindow(p[300] - 1_000_000L, p[899] + 1_500_000L), w) // 앞 1.0s, 뒤 1.5s 여유
    }

    @Test
    fun padsAreClampedToVideo() {
        val src = source(10, 1000, 5)
        val p = pts(src.size)
        assertEquals(TrimWindow(0L, p.last()), Trim.suggest(src, p))
    }

    @Test
    fun noClimberMeansWholeVideo() {
        val src = IntArray(100) { HOLD }
        val p = pts(100)
        assertEquals(TrimWindow(0L, p.last()), Trim.suggest(src, p))
    }

    @Test
    fun tooShortSpanFallsBackToWholeVideo() {
        val src = source(300, 30, 300) // 등반자 1초 — 오검출 가능성이 커 자르지 않는다
        val p = pts(src.size)
        assertEquals(TrimWindow(0L, p.last()), Trim.suggest(src, p))
    }

    @Test
    fun frameIndexMapAnchorsFirstRenderedFrameToTrimStart() {
        val p = pts(300)
        val map = FrameIndexMap(p, startUs = 2_000_000L) // 2.0s = 60번째 프레임
        // 렌더러가 클리핑 뒤 pts를 0부터 주든 원래 시간축으로 주든, 첫 프레임 = 60
        assertEquals(60, map.indexFor(0L))
        assertEquals(61, map.indexFor(33_333L))
        assertEquals(90, map.indexFor(1_000_000L))
        val map2 = FrameIndexMap(p, startUs = 2_000_000L)
        assertEquals(60, map2.indexFor(2_000_000L))
        assertEquals(90, map2.indexFor(3_000_000L))
        assertEquals(299, map2.indexFor(99_000_000L)) // 끝을 넘으면 마지막 프레임
    }

    @Test
    fun frameIndexMapRoundsToNearestFrame() {
        val map = FrameIndexMap(pts(300), startUs = 0L)
        assertEquals(0, map.indexFor(0L))
        assertEquals(1, map.indexFor(20_000L)) // 33.3ms 프레임 간격의 중간을 넘음
        assertEquals(0, map.indexFor(15_000L))
    }
}

class NearestFrameTest {
    @Test
    fun nearestByAbsolutePts() {
        val p = LongArray(300) { (it * 1_000_000L / 30.0).toLong() }
        assertEquals(0, FrameIndexMap.nearest(p, -5_000L))
        assertEquals(150, FrameIndexMap.nearest(p, 5_000_000L))
        assertEquals(150, FrameIndexMap.nearest(p, 5_010_000L))
        assertEquals(151, FrameIndexMap.nearest(p, 5_020_000L))
        assertEquals(299, FrameIndexMap.nearest(p, 99_000_000L))
    }
}
