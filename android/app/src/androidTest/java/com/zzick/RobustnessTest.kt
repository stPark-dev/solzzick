package com.zzick

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.decode.FrameDecoder
import com.zzick.pipeline.ClimbPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** 리뷰 H2/M1 반영 확인: 실패·취소 후에도 디코더 자원이 남지 않고, 분석은 바로 멈춘다. */
@RunWith(AndroidJUnit4::class)
class RobustnessTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val video = File(ctx.filesDir, "videos/20260927_113733.mp4")

    private fun decoderThreads() = Thread.getAllStackTraces().keys.count { it.name == "FrameDecoder" && it.isAlive }

    private fun settle() = repeat(20) { if (decoderThreads() > 0) Thread.sleep(50) }

    @Test
    fun failingSourceInsideDecodeReleasesEverything() {
        val before = decoderThreads()
        repeat(10) {
            var calls = 0
            // 생성자(메타데이터)에서는 성공, decode() 안에서 두 번째 호출은 실패
            val dec = FrameDecoder({ ex -> if (calls++ == 0) ex.setDataSource(video.path) else error("소스 열기 실패") })
            try {
                dec.decode { _, _, _ -> }
                fail("예외가 나야 함")
            } catch (e: IllegalStateException) {
                assertEquals("소스 열기 실패", e.message)
            }
        }
        settle()
        assertEquals(before, decoderThreads())
        // 자원이 새지 않았으면 이후 정상 디코드가 된다
        assertEquals(686, FrameDecoder(video.path).decode { _, _, _ -> })
    }

    @Test
    fun exceptionFromCallbackPropagatesAndReleases() {
        val before = decoderThreads()
        repeat(10) {
            try {
                FrameDecoder(video.path).decode { i, _, _ -> if (i == 5) throw IllegalArgumentException("콜백 실패") }
                fail("예외가 나야 함")
            } catch (e: IllegalArgumentException) {
                assertEquals("콜백 실패", e.message)
            }
        }
        settle()
        assertEquals(before, decoderThreads())
    }

    @Test
    fun analysisStopsPromptlyWhenCancelled() {
        var checks = 0 // isCancelled는 프레임마다 확인된다
        val t0 = System.currentTimeMillis()
        try {
            ClimbPipeline(ctx).analyze(Uri.fromFile(video), tap = null, isCancelled = { ++checks > 100 }) {}
            fail("취소 예외가 나야 함")
        } catch (e: CancellationException) {
            // 기대한 동작
        }
        // 전체 분석(686프레임)보다 훨씬 빨리 끝나야 한다
        assertTrue("취소 후에도 계속 처리함: ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 8000)
    }
}
