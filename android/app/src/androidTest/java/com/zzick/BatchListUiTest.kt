package com.zzick

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.core.batch.BatchQueue
import com.zzick.core.trim.TrimWindow
import com.zzick.pipeline.Analysis
import com.zzick.ui.BatchActions
import com.zzick.ui.BatchList
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
        compose.setContent { BatchList(queue(), r) }
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
        compose.setContent { BatchList(q, r) }
        compose.onNodeWithText("준비됨 · 0:05.0").assertExists()
        compose.onNodeWithText("모두 만들기 (1)").assertIsEnabled().performClick()
        compose.onNodeWithText("편집").performClick()
        assertEquals(listOf("renderAll", "edit:1"), r.calls)
    }
}
