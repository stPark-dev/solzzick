package com.zzick

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.core.batch.BatchQueue
import com.zzick.pipeline.Analysis
import com.zzick.ui.BatchActions
import com.zzick.ui.BatchList
import com.zzick.ui.LocalClock
import com.zzick.ui.ProgressPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 개선 후보 5: 분석·만들기 중 남은 시간, 일괄 편집에서 기다리는 영상의 차례. */
@RunWith(AndroidJUnit4::class)
class ProgressUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun progressScreenShowsRemainingTimeOnceItCanGuess() {
        var now = 0L
        var progress by mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(LocalClock provides { now }) { Column { ProgressPanel("등반자 추적 중", progress) {} } }
        }
        compose.onNodeWithText("남음", substring = true).assertDoesNotExist()
        now = 10_000L
        progress = 0.5f
        compose.waitForIdle()
        compose.onNodeWithText("약 10초 남음").assertExists()
    }

    private object NoActions : BatchActions {
        override fun select(id: Int) = Unit
        override fun edit(id: Int) = Unit
        override fun retry(id: Int) = Unit
        override fun remove(id: Int) = Unit
        override fun open(id: Int) = Unit
        override fun renderAll() = Unit
        override fun commonChanged(s: com.zzick.core.vcam.EditSettings) = Unit
        override fun rename(name: String) = Unit
        override fun close() = Unit
    }

    @Test
    fun batchRowsShowTurnAndRemainingTime() {
        var now = 0L
        var q = BatchQueue<Analysis>().add(listOf("file:///a.mp4", "file:///b.mp4", "file:///c.mp4"))
        val a = q.items[0].id
        q = q.onScanned(a, 1).onStarted(a)
        val start = q
        var shown by androidx.compose.runtime.mutableStateOf(start)
        compose.setContent {
            CompositionLocalProvider(LocalClock provides { now }) { BatchList(shown, shown.jobs[0].id, NoActions) }
        }
        compose.onNodeWithText("대기 중 · 앞에 1개").assertExists()
        compose.onNodeWithText("대기 중 · 앞에 2개").assertExists()
        now = 20_000L
        shown = start.onProgress(a, 0.8f)
        compose.waitForIdle()
        compose.onNodeWithText("분석 중 80% · 곧 끝나요").assertExists()
    }
}
