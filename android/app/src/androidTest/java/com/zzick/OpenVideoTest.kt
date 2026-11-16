package com.zzick

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.ui.openVideo
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenVideoTest {
    /** 사용자 제보(0.4.9): 만든 영상을 갤러리에서 지운 뒤 '보기'를 누르면 앱이 강제 종료됐다. */
    @Test
    fun deletedVideoIsNotOpenedAndDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(openVideo(context, "content://media/external/video/media/999999999999"))
    }
}
