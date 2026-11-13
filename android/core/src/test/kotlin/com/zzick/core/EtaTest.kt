package com.zzick.core

import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.progress.Eta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 분석·만들기 중 남은 시간(개선 후보 5): 진행률이 오르는 속도로 추정한다. */
class EtaTest {
    @Test
    fun noGuessUntilEnoughTimeAndProgress() {
        val e = Eta()
        assertNull(e.update(0L, 0f))
        assertNull(e.update(1_000L, 0.1f)) // 3초 전
        assertNull(e.update(4_000L, 0.03f)) // 진행이 너무 적음(되돌아간 값도 무시)
    }

    @Test
    fun remainingFromRateSinceFirstSeen() {
        val e = Eta()
        e.update(0L, 0.2f) // 중간부터 봐도(화면을 나중에 열어도) 그때부터의 속도로
        assertEquals(22.0, e.update(10_000L, 0.45f)!!, 1e-4) // 10초에 25% → 남은 55%는 22초
    }

    @Test
    fun labelsRoundUpAndSaySoonAtTheEnd() {
        assertEquals("곧 끝나요", Eta.label(7.0))
        assertEquals("약 25초 남음", Eta.label(21.0))
        assertEquals("약 1분 10초 남음", Eta.label(62.0))
        assertEquals("약 2분 남음", Eta.label(115.0))
    }

    /** 일괄 편집에서 기다리는 영상은 앞에 몇 개가 있는지(분석이 먼저, 그다음 만들기 — nextWork 순서). */
    @Test
    fun aheadCountsWorkThatRunsFirst() {
        var q = BatchQueue<String>().add(listOf("a", "b", "c", "d", "e"))
        val (a, b, c, d, e) = q.items.map { it.id }
        q = q.onStarted(a) // 사람 찾는 중
        q = q.onScanned(b, 2) // 선택 대기 — 차례에 안 듦
        q = q.copy(items = q.items.map { if (it.id == e) it.copy(stage = Stage.WAITING_RENDER) else it })
        assertEquals(0, q.aheadOf(a))
        assertEquals(1, q.aheadOf(c))
        assertEquals(2, q.aheadOf(d))
        assertEquals(3, q.aheadOf(e)) // 분석할 a·c·d 다음
    }

    /** 리뷰: 돌고 있는 일은 한 번에 하나이고 끝날 때까지 계속된다 — 목록 순서가 뒤여도 앞에 센다. */
    @Test
    fun aheadIncludesTheRunningItemWhereverItIs() {
        var q = BatchQueue<String>().add(listOf("a")).add(listOf("b"))
        val (a, b) = q.items.map { it.id }
        q = q.copy(items = q.items.map { if (it.id == a) it.copy(stage = Stage.RENDERING) else it })
        assertEquals(1, q.aheadOf(b)) // 다른 작업의 만들기가 끝나야 분석 시작

        var r = BatchQueue<String>().add(listOf("x", "y"))
        val (x, y) = r.items.map { it.id }
        r = r.onScanned(y, 1).onStarted(y) // 아래 영상 분석 중
        r = r.onStarted(x).onFailed(x, "e").retry(x) // 위 영상을 다시 시도
        assertEquals(1, r.aheadOf(x))
    }

    @Test
    fun restartsWhenProgressFallsBack() {
        val e = Eta()
        e.update(0L, 0f)
        e.update(10_000L, 0.5f)
        assertNull(e.update(60_000L, 0f)) // 다시 처음부터(재시도): 예전 시작 시각을 쓰지 않는다
        assertEquals(10.0, e.update(70_000L, 0.5f)!!, 1e-4)
    }
}
