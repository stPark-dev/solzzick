package com.zzick.core

import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.batch.Work
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchQueueTest {
    private val w = TrimWindow(0, 10_000_000)

    private fun q(vararg src: String) = BatchQueue<String>().add(src.toList())

    @Test
    fun addedItemsWaitForScanInOrder() {
        val b = q("a", "b")
        assertEquals(listOf(Stage.QUEUED, Stage.QUEUED), b.items.map { it.stage })
        assertEquals(Work.Scan(b.items[0].id), b.nextWork())
    }

    @Test
    fun singlePersonGoesStraightToAnalysis() {
        var b = q("a")
        val id = b.items[0].id
        b = b.onScanned(id, people = 1)
        assertEquals(Stage.WAITING_ANALYSIS, b.item(id).stage)
        assertEquals(Work.Analyze(id, tap = null), b.nextWork())
    }

    @Test
    fun severalPeopleNeedSelectionAndDoNotBlockOthers() {
        var b = q("a", "b")
        val (a, c) = b.items.map { it.id }
        b = b.onScanned(a, people = 3)
        assertEquals(Stage.NEEDS_SELECTION, b.item(a).stage)
        assertEquals(Work.Scan(c), b.nextWork()) // 선택을 기다리는 동안 다음 영상 진행
        b = b.onSelected(a, 0.7 to 0.4)
        assertEquals(Stage.WAITING_ANALYSIS, b.item(a).stage)
    }

    @Test
    fun workIsTakenInListOrder() {
        var b = q("a", "b")
        val (a, c) = b.items.map { it.id }
        b = b.onScanned(a, people = 3).onSelected(a, 0.7 to 0.4)
        assertEquals(Work.Analyze(a, tap = 0.7 to 0.4), b.nextWork())
        b = b.onStarted(a).onAnalyzed(a, "analysis-a", w)
        assertEquals(Work.Scan(c), b.nextWork())
    }

    @Test
    fun noPersonFails() {
        var b = q("a")
        val id = b.items[0].id
        b = b.onScanned(id, people = 0)
        assertEquals(Stage.FAILED, b.item(id).stage)
        assertNull(b.nextWork())
    }

    @Test
    fun failureOfOneDoesNotStopOthers() {
        var b = q("a", "b")
        val (a, c) = b.items.map { it.id }
        b = b.onStarted(a).onFailed(a, "디코딩 실패")
        assertEquals(Stage.FAILED, b.item(a).stage)
        assertEquals("디코딩 실패", b.item(a).error)
        assertEquals(Work.Scan(c), b.nextWork())
    }

    @Test
    fun renderAllQueuesOnlyReadyItemsAfterAnalysisWork() {
        var b = q("a", "b", "c")
        val (a, c, d) = b.items.map { it.id }
        b = b.onScanned(a, 1).onStarted(a).onAnalyzed(a, "A", w)
        b = b.onScanned(c, 2) // 선택 필요
        b = b.renderAll(b.jobs[0].id)
        assertEquals(Stage.WAITING_RENDER, b.item(a).stage)
        assertEquals(Stage.NEEDS_SELECTION, b.item(c).stage)
        assertEquals(Stage.QUEUED, b.item(d).stage)
        // 분석 작업이 렌더보다 먼저(사용자가 결과 목록을 빨리 보도록)
        assertEquals(Work.Scan(d), b.nextWork())
        b = b.onScanned(d, 0)
        assertEquals(Work.Render(a), b.nextWork())
        b = b.onStarted(a).onRendered(a, "content://out/a")
        assertEquals(Stage.DONE, b.item(a).stage)
        assertEquals("content://out/a", b.item(a).output)
    }

    @Test
    fun itemSettingsOverrideCommonSettings() {
        var b = q("a", "b")
        val (a, c) = b.items.map { it.id }
        b = b.withCommon(b.jobs[0].id, EditSettings(zoom = 1.1))
        b = b.withItemSettings(a, EditSettings(aspect = AspectRatio.R16x9))
        assertEquals(EditSettings(aspect = AspectRatio.R16x9), b.settingsFor(a))
        assertEquals(EditSettings(zoom = 1.1), b.settingsFor(c))
        b = b.withCommon(b.jobs[0].id, EditSettings(shake = 3.0)) // 공통 변경은 개별 설정을 덮지 않는다
        assertEquals(EditSettings(aspect = AspectRatio.R16x9), b.settingsFor(a))
    }

    @Test
    fun progressAndWindowEditsAreTracked() {
        var b = q("a")
        val id = b.items[0].id
        b = b.onScanned(id, 1).onStarted(id).onProgress(id, 0.4f)
        assertEquals(Stage.ANALYZING, b.item(id).stage)
        assertEquals(0.4f, b.item(id).progress, 0f)
        b = b.onAnalyzed(id, "A", w).withWindow(id, TrimWindow(1_000_000, 5_000_000))
        assertEquals(TrimWindow(1_000_000, 5_000_000), b.item(id).window)
        assertEquals("A", b.item(id).analysis)
    }

    @Test
    fun cancelStopsPendingWorkAndRetryRequeues() {
        var b = q("a", "b")
        val (a, c) = b.items.map { it.id }
        b = b.onStarted(a).cancel(a)
        assertEquals(Stage.FAILED, b.item(a).stage)
        assertEquals(Work.Scan(c), b.nextWork())
        b = b.retry(a)
        assertEquals(Stage.QUEUED, b.item(a).stage)
    }
}
