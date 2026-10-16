package com.zzick

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.decode.decodeAhead
import com.zzick.pipeline.ClimbPipeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/**
 * 디코딩을 별도 스레드에서 미리 돌려 추론과 겹치기(분석 시간의 약 20%가 디코딩) — 결과는 순차 처리와 완전히 같아야 한다.
 * 광학 흐름이 쓰이는 영상(포즈 공백)과 60fps 영상으로 확인.
 */
@RunWith(AndroidJUnit4::class)
class DecodeAheadTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun video(name: String) = Uri.fromFile(File(ctx.filesDir, "videos/$name"))

    private fun sameAnalysis(name: String) {
        val p = ClimbPipeline(ctx)
        val seq = p.analyze(video(name), null, decodeAhead = false) {}
        val ahead = p.analyze(video(name), null) {}
        assertArrayEquals("$name pts", seq.result.ptsUs, ahead.result.ptsUs)
        assertArrayEquals("$name source", seq.result.source, ahead.result.source)
        for (i in seq.result.track.indices) {
            assertArrayEquals("$name track[$i]", seq.result.track[i], ahead.result.track[i], 1e-9)
            assertArrayEquals("$name body[$i]", seq.result.body[i], ahead.result.body[i], 1e-9)
        }
        assertEquals(seq.fps, ahead.fps, 1e-9)
    }

    @Test fun sameResultOnClimbWithPoseGaps() = sameAnalysis("user_110708.mp4")

    @Test fun sameResultOn60fps() = sameAnalysis("fps60.mp4")

    /** 취소하면 디코딩 스레드도 멈추고 CancellationException으로 끝난다(멈춘 스레드가 남지 않게). */
    @Test
    fun cancelStopsDecodingThread() {
        val p = ClimbPipeline(ctx)
        var frames = 0
        val before = Thread.getAllStackTraces().keys.count { it.name.startsWith("DecodeAhead") }
        // 진행률은 30프레임마다 — 두 번 받은 뒤 취소
        val e = runCatching { p.analyze(video("20261011_105654.mp4"), null, isCancelled = { frames >= 2 }) { frames++ } }.exceptionOrNull()
        assertTrue("취소 예외: $e", e is CancellationException)
        Thread.sleep(500)
        assertEquals(before, Thread.getAllStackTraces().keys.count { it.name.startsWith("DecodeAhead") && it.isAlive })
    }

    /** 리뷰에서 찾음: 분석 쪽(onFrame)에서 예외가 나도 그 예외가 그대로 나오고 디코딩 스레드가 남지 않는다. */
    @Test
    fun consumerFailureStopsDecodingThread() {
        val before = Thread.getAllStackTraces().keys.count { it.name.startsWith("DecodeAhead") && it.isAlive }
        val e = runCatching {
            com.zzick.decode.FrameDecoder(File(ctx.filesDir, "videos/20261011_105654.mp4").path).decodeAhead { i, _, _ ->
                if (i == 10) throw IllegalStateException("분석 실패")
            }
        }.exceptionOrNull()
        assertEquals("분석 실패", e?.message)
        Thread.sleep(500)
        assertEquals(before, Thread.getAllStackTraces().keys.count { it.name.startsWith("DecodeAhead") && it.isAlive })
    }
}
