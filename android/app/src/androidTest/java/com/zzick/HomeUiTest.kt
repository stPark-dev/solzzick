package com.zzick

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.ui.SplashGate
import com.zzick.ui.quote.Quote
import com.zzick.ui.quote.QuoteView
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
}
