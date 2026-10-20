package com.zzick

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.ui.HomeScreen
import com.zzick.ui.SplashGate
import com.zzick.ui.quote.Quote
import com.zzick.ui.quote.QuoteView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun splashShowsLogoThenContent() {
        compose.mainClock.autoAdvance = false
        compose.setContent { SplashGate(durationMs = 1500) { Text("메인") } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("솔찍 로고").assertExists()
        compose.onNodeWithText("메인").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("메인").assertExists()
        compose.onNodeWithContentDescription("솔찍 로고").assertDoesNotExist()
    }

    @Test
    fun quoteShowsMessageAndAuthorLine() {
        compose.setContent { QuoteView(Quote("위대한 일은 작은 일들의 축적으로 이루어진다.", "빈센트 반 고흐")) }
        compose.onNodeWithText("위대한 일은 작은 일들의 축적으로 이루어진다.").assertExists()
        compose.onNodeWithText("-빈센트 반 고흐-").assertExists()
    }

    /**
     * 사용자 요청: 명언이 한가운데라 클라이밍 편집 앱의 정체성이 흐려진다 — 로고 → 영상 선택 → 작업 목록 → 명언 순.
     * 영상 선택을 누르면 1개 편집인지 여러 개(일괄 편집)인지 고르는 창.
     */
    @Test
    fun homeOrdersActionsAboveQuoteAndAsksOneOrMany() {
        val calls = mutableListOf<String>()
        compose.setContent {
            HomeScreen(jobCount = 2, quote = Quote("위대한 일은 작은 일들의 축적으로 이루어진다.", "빈센트 반 고흐"),
                onPickOne = { calls += "one" }, onPickMany = { calls += "many" }, onOpenJobs = { calls += "jobs" })
        }
        val pick = compose.onNodeWithText("영상 선택").getBoundsInRoot()
        val jobs = compose.onNodeWithText("작업 목록", substring = true).getBoundsInRoot()
        val quote = compose.onNodeWithText("위대한 일은 작은 일들의 축적으로 이루어진다.").getBoundsInRoot()
        assertTrue("영상 선택 → 작업 목록 → 명언 순", pick.bottom <= jobs.top && jobs.bottom <= quote.top)
        compose.onNodeWithText("작업 목록", substring = true).performClick()
        compose.onNodeWithText("영상 선택").performClick()
        compose.onNodeWithText("영상 1개 편집").performClick()
        compose.onNodeWithText("영상 선택").performClick()
        compose.onNodeWithText("여러 영상 일괄 편집").performClick()
        assertEquals(listOf("jobs", "one", "many"), calls)
    }
}
