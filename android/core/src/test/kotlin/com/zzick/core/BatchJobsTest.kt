package com.zzick.core

import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.batch.Work
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 일괄 편집 작업: 영상을 골라 넣을 때마다 작업 하나(만든 시각·이름·공통 설정) — 목록을 작업별로 묶어 본다. */
class BatchJobsTest {
    private val w = TrimWindow(0, 10_000_000)
    private val t1 = 1_791_000_000_000L
    private val t2 = t1 + 3_600_000L

    @Test
    fun eachAddMakesANewJobWithItsTimeAndVideos() {
        val q = BatchQueue<String>().add(listOf("a", "b"), createdAtMs = t1).add(listOf("c"), createdAtMs = t2)
        assertEquals(2, q.jobs.size)
        val (j1, j2) = q.jobs
        assertEquals(t1, j1.createdAtMs)
        assertEquals(t2, j2.createdAtMs)
        assertNull(j1.name)
        assertEquals(listOf("a", "b"), q.itemsOf(j1.id).map { it.source })
        assertEquals(listOf("c"), q.itemsOf(j2.id).map { it.source })
    }

    @Test
    fun newJobStartsWithLastJobsCommonSettings() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1)
        q = q.withCommon(q.jobs[0].id, EditSettings(zoom = 1.4))
        q = q.add(listOf("b"), createdAtMs = t2)
        assertEquals(EditSettings(zoom = 1.4), q.jobs[1].common)
    }

    @Test
    fun commonSettingsArePerJob() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1).add(listOf("b"), createdAtMs = t2)
        val (j1, j2) = q.jobs.map { it.id }
        q = q.withCommon(j1, EditSettings(aspect = AspectRatio.R4x5)).withCommon(j2, EditSettings(shake = 0.0))
        val (a, b) = q.items.map { it.id }
        assertEquals(EditSettings(aspect = AspectRatio.R4x5), q.settingsFor(a))
        assertEquals(EditSettings(shake = 0.0), q.settingsFor(b))
    }

    @Test
    fun renderAllOnlyQueuesThatJobsReadyVideos() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1).add(listOf("b"), createdAtMs = t2)
        val (a, b) = q.items.map { it.id }
        q = q.onScanned(a, 1).onStarted(a).onAnalyzed(a, "A", w).onScanned(b, 1).onStarted(b).onAnalyzed(b, "B", w)
        q = q.renderAll(q.jobs[1].id)
        assertEquals(Stage.READY, q.item(a).stage)
        assertEquals(Stage.WAITING_RENDER, q.item(b).stage)
    }

    @Test
    fun earlierJobsAreProcessedFirst() {
        val q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1).add(listOf("b"), createdAtMs = t2)
        assertEquals(Work.Scan(q.itemsOf(q.jobs[0].id)[0].id), q.nextWork())
    }

    @Test
    fun jobCanBeRenamedAndNameCleared() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1)
        val j = q.jobs[0].id
        q = q.rename(j, "  강남 볼더 데이 ")
        assertEquals("강남 볼더 데이", q.job(j).name)
        q = q.rename(j, "   ")
        assertNull("빈 이름이면 날짜·시간으로", q.job(j).name)
    }

    @Test
    fun removingAJobRemovesItsVideosOnly() {
        var q = BatchQueue<String>().add(listOf("a", "b"), createdAtMs = t1).add(listOf("c"), createdAtMs = t2)
        q = q.removeJob(q.jobs[0].id)
        assertEquals(listOf("c"), q.items.map { it.source })
        assertEquals(1, q.jobs.size)
    }

    @Test
    fun jobDisappearsWhenItsLastVideoIsRemoved() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1).add(listOf("b"), createdAtMs = t2)
        q = q.remove(q.items[0].id)
        assertEquals(listOf(t2), q.jobs.map { it.createdAtMs })
    }

    @Test
    fun jobIdsKeepIncreasingAfterRemoval() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1)
        val first = q.jobs[0].id
        q = q.removeJob(first).add(listOf("b"), createdAtMs = t2)
        assertTrue(q.jobs[0].id > first)
    }

    /** 리뷰에서 찾음: 고른 영상이 없으면(권한 대화상자 중 화면이 다시 만들어짐) 빈 작업을 만들지 않는다. */
    @Test
    fun addingNothingMakesNoJob() {
        val q = BatchQueue<String>().add(emptyList(), createdAtMs = t1)
        assertTrue(q.jobs.isEmpty())
    }

    /** 리뷰에서 찾음: 다시 시도한 영상은 원래 작업에 남아 그 작업의 공통 설정을 따른다. */
    @Test
    fun retryKeepsTheVideoInItsJob() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1).add(listOf("b"), createdAtMs = t2)
        val j2 = q.jobs[1].id
        q = q.withCommon(j2, EditSettings(zoom = 1.5))
        val b = q.itemsOf(j2)[0].id
        q = q.onStarted(b).onFailed(b, "실패").retry(b)
        assertEquals(j2, q.item(b).jobId)
        assertEquals(EditSettings(zoom = 1.5), q.settingsFor(b))
    }

    /** 리뷰에서 찾음: 아주 긴 이름은 저장 형식(writeUTF 64KB)을 넘겨 저장이 조용히 멈출 수 있다 — 40자로 자른다. */
    @Test
    fun longNameIsCut() {
        var q = BatchQueue<String>().add(listOf("a"), createdAtMs = t1)
        q = q.rename(q.jobs[0].id, "가".repeat(10_000))
        assertEquals(40, q.jobs[0].name!!.length)
    }
}

