package com.zzick

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
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
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
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
        // 설명은 처음엔 숨기고, 일괄 편집 버튼 1시 방향의 i를 누르면 말풍선으로(사용자 요청)
        compose.onNodeWithText("백그라운드", substring = true).assertDoesNotExist()
        val many = compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText("여러 영상 일괄 편집")).getBoundsInRoot()
        val info = compose.onNodeWithContentDescription("일괄 편집 안내").getBoundsInRoot()
        assertTrue("i는 일괄 편집 버튼의 1시 방향(오른쪽 위)", (info.left + info.right) / 2 > (many.left + many.right) / 2 && info.top <= many.top)
        compose.onNodeWithContentDescription("일괄 편집 안내").performClick()
        compose.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasText("백그라운드", substring = true), 3_000)
        compose.onNodeWithText("영상 1개 편집").performClick()
        compose.onNodeWithText("영상 선택").performClick()
        compose.onNodeWithText("여러 영상 일괄 편집").performClick()
        assertEquals(listOf("jobs", "one", "many"), calls)
    }

    /** 사용자 요청: 첫 화면 버튼이 너무 작다 — 두 버튼 모두 크게(높이 64dp 이상). */
    @Test
    fun homeButtonsAreLarge() {
        compose.setContent { HomeScreen(jobCount = 0, quote = null, onPickOne = {}, onPickMany = {}, onOpenJobs = {}) }
        for (label in listOf("영상 선택", "작업 목록")) {
            // 버튼은 안의 글자를 한 노드로 합친다 — 글자와 클릭이 함께 있는 노드가 버튼
            val b = compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText(label, substring = true))
                .getBoundsInRoot()
            assertTrue("$label 높이 ${b.bottom - b.top}", b.bottom - b.top >= 64.dp)
        }
    }

    /** 사용자 요청: 명언은 한 줄로 — 넘치면 전광판처럼 흘러간다(줄바꿈하지 않는다). */
    @Test
    fun longQuoteStaysOnOneLine() {
        val long = "위대한 일은 작은 일들의 축적으로 이루어진다. 오늘 한 번 더 붙어 보는 그 시도가 결국 완등을 만든다."
        compose.setContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { QuoteView(Quote(long, "누군가")) } }
        val b = compose.onNodeWithText(long).getBoundsInRoot()
        assertTrue("한 줄 높이여야 함: ${b.bottom - b.top}", b.bottom - b.top < 48.dp)
    }
}
