package com.zzick

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo
import com.zzick.ui.OverlayControls
import com.zzick.ui.PlayerControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 미리보기 탐색 막대와 영상 구석 표시 설정. */
@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun seekBarShowsTimeTogglesAndSeeks() {
        var playing by mutableStateOf(true)
        var seekedTo = -1L
        compose.setContent { PlayerControls(positionMs = 3_000, durationMs = 72_000, playing = playing, onToggle = { playing = !playing }, onSeek = { seekedTo = it }) }
        compose.onNodeWithText("0:03 / 1:12").assertExists()
        compose.onNodeWithContentDescription("일시정지").performClick()
        compose.onNodeWithContentDescription("재생").assertExists()
        compose.onNodeWithTag("seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(36_000L, seekedTo)
    }

    @Test
    fun overlayControlsEditEachPart() {
        var info by mutableStateOf(OverlayInfo())
        compose.setContent { OverlayControls(info) { info = it } }
        compose.onNodeWithText("💙").performClick()
        assertEquals(Grade.BLUE, info.grade)
        compose.onNodeWithText("💙").performClick() // 다시 누르면 해제
        assertEquals(null, info.grade)
        compose.onNodeWithTag("gym").performTextInput("더클라임")
        assertEquals("더클라임", info.gym)
        compose.onNodeWithTag("date").assertIsOff().performClick()
        compose.onNodeWithTag("date").assertIsOn()
        assertEquals(true, info.showDate)
    }
}
