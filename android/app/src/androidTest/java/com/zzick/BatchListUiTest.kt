package com.zzick

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.core.batch.BatchQueue
import com.zzick.core.trim.TrimWindow
import com.zzick.pipeline.Analysis
import com.zzick.ui.BatchActions
import com.zzick.ui.BatchList
import com.zzick.ui.JobActions
import com.zzick.ui.JobList
import com.zzick.ui.jobTitle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchListUiTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : BatchActions {
        val calls = mutableListOf<String>()
        override fun select(id: Int) { calls += "select:$id" }
        override fun edit(id: Int) { calls += "edit:$id" }
        override fun retry(id: Int) { calls += "retry:$id" }
        override fun remove(id: Int) { calls += "remove:$id" }
        override fun open(id: Int) { calls += "open:$id" }
        override fun renderAll() { calls += "renderAll" }
        override fun commonChanged(s: com.zzick.core.vcam.EditSettings) { calls += "common" }
        override fun rename(name: String) { calls += "rename:$name" }
        override fun close() { calls += "close" }
    }

    private class JobRecorder : JobActions {
        val calls = mutableListOf<String>()
        override fun open(jobId: Int) { calls += "open:$jobId" }
        override fun rename(jobId: Int, name: String) { calls += "rename:$jobId:$name" }
        override fun remove(jobId: Int) { calls += "remove:$jobId" }
        override fun close() { calls += "close" }
    }

    private fun queue(): BatchQueue<Analysis> {
        var q = BatchQueue<Analysis>().add(listOf("file:///a.mp4", "file:///b.mp4", "file:///c.mp4"))
        val (a, b, c) = q.items.map { it.id }
        q = q.onScanned(a, 2) // 선택 필요
        q = q.onStarted(b).onFailed(b, "디코딩 실패")
        q = q.onScanned(c, 1).onStarted(c).onProgress(c, 0.5f)
        return q
    }

    @Test
    fun showsStatusPerItemAndRenderAllDisabledWithoutReadyItems() {
        val r = Recorder()
        val q = queue()
        compose.setContent { BatchList(q, q.jobs[0].id, r) }
        compose.onNodeWithText("등반자 선택 필요").assertExists()
        compose.onNodeWithText("실패: 디코딩 실패").assertExists()
        compose.onNodeWithText("분석 중 50%").assertExists()
        compose.onNodeWithText("모두 만들기", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("등반자 선택").performClick()
        compose.onNodeWithText("다시 시도").performClick()
        assertEquals(listOf("select:1", "retry:2"), r.calls)
    }

    @Test
    fun renderAllEnabledWhenSomethingIsReady() {
        val r = Recorder()
        var q = BatchQueue<Analysis>().add(listOf("file:///a.mp4"))
        val id = q.items[0].id
        q = q.onScanned(id, 1).onStarted(id).copy(items = q.items.map { it.copy(stage = com.zzick.core.batch.Stage.READY, window = TrimWindow(0, 5_000_000)) })
        compose.setContent { BatchList(q, q.jobs[0].id, r) }
        compose.onNodeWithText("준비됨 · 0:05.0").assertExists()
        compose.onNodeWithText("모두 만들기 (1)").assertIsEnabled().performClick()
        compose.onNodeWithText("편집").performClick()
        assertEquals(listOf("renderAll", "edit:1"), r.calls)
    }

    /** 사용자 요청: 일괄 편집을 작업(골라 넣은 때)별로 묶어 본다 — 작업 목록에 이름(없으면 날짜·시간)과 진행 요약. */
    @Test
    fun jobListShowsEachJobWithSummaryAndActions() {
        val r = JobRecorder()
        var q = BatchQueue<Analysis>().add(listOf("file:///a.mp4", "file:///b.mp4"), createdAtMs = 1_791_000_000_000L)
        q = q.add(listOf("file:///c.mp4"), createdAtMs = 1_791_003_600_000L)
        val (j1, j2) = q.jobs.map { it.id }
        val (a, b) = q.itemsOf(j1).map { it.id }
        q = q.onScanned(a, 1).onStarted(a).copy(items = q.items.map { if (it.id == a) it.copy(stage = com.zzick.core.batch.Stage.DONE) else it })
        q = q.onScanned(b, 2).rename(j2, "강남 볼더 데이")
        compose.setContent { JobList(q, r) }
        compose.onNodeWithText(jobTitle(q.job(j1))).assertExists()
        compose.onNodeWithText("영상 2개 · 완료 1 · 선택 필요 1").assertExists()
        compose.onNodeWithText("강남 볼더 데이").assertExists().performClick()
        assertEquals(listOf("open:$j2"), r.calls)
    }

    @Test
    fun jobCanBeRenamedAndRemovedAfterConfirm() {
        val r = JobRecorder()
        val q = BatchQueue<Analysis>().add(listOf("file:///a.mp4"), createdAtMs = 1_791_000_000_000L)
        val j = q.jobs[0].id
        compose.setContent { JobList(q, r) }
        compose.onNodeWithText("이름").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("서울숲")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("삭제").performClick()
        assertEquals(listOf("rename:$j:서울숲"), r.calls) // 확인 전에는 지우지 않는다
        compose.onNodeWithText("빼기").performClick()
        assertEquals(listOf("rename:$j:서울숲", "remove:$j"), r.calls)
    }
}
