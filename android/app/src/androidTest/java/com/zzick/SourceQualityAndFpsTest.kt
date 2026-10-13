package com.zzick

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.quality.QualityIssue
import com.zzick.core.quality.SourceQuality
import com.zzick.core.vcam.CameraParams
import com.zzick.core.vcam.EditSettings
import com.zzick.pipeline.ClimbPipeline
import com.zzick.ui.QualityNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 원본 화질 판정과 프레임레이트 유지.
 * fps60.mp4(1440² 60fps HEVC), uhd60.mp4(2160×3840 60fps HEVC), kakao.mp4(720² 1.5Mbps H.264)는
 * 20260927_113733.mp4에서 ffmpeg로 만든 테스트 영상이다(README 개발 섹션 참고).
 */
@RunWith(AndroidJUnit4::class)
class SourceQualityAndFpsTest {
    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pipeline = ClimbPipeline(ctx)
    private val outDir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    private fun video(name: String) = File(ctx.filesDir, "videos/$name").also { assertTrue("영상 없음: $it", it.exists()) }.let(Uri::fromFile)

    /** 영상 트랙의 (표시 폭, 표시 높이, 실제 평균 fps) */
    private fun probe(file: File): Triple<Int, Int, Double> {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        try {
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val f = ex.getTrackFormat(t)
            ex.selectTrack(t)
            var n = 0
            var first = -1L
            var last = 0L
            while (ex.sampleTime >= 0) {
                if (first < 0) first = ex.sampleTime
                last = maxOf(last, ex.sampleTime)
                n++
                ex.advance()
            }
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (w, h) = f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
            val (dw, dh) = if (rot % 180 == 0) w to h else h to w
            return Triple(dw, dh, (n - 1) * 1e6 / (last - first))
        } finally {
            ex.release()
        }
    }

    @Test
    fun readsCodecAndBitrateOfCameraOriginalAndGalleryEdit() {
        val original = pipeline.sourceInfo(video("20260927_113733.mp4"))
        assertEquals(1440 to 1440, original.width to original.height)
        assertTrue(original.hevc)
        assertEquals(30.0, original.fps, 0.5)
        assertEquals(14.7e6, original.bitrate.toDouble(), 1.0e6)
        assertEquals(emptySet<QualityIssue>(), SourceQuality.assess(original, EditSettings().toParams()))

        val edit = pipeline.sourceInfo(video("user_123105.mp4"))
        assertTrue(!edit.hevc)
        assertEquals(9.1e6, edit.bitrate.toDouble(), 1.0e6)
        assertEquals(emptySet<QualityIssue>(), SourceQuality.assess(edit, EditSettings().toParams()))
    }

    @Test
    fun kakaoLikeVideoIsFlagged() {
        val s = pipeline.sourceInfo(video("kakao.mp4"))
        assertEquals(setOf(QualityIssue.LOW_RESOLUTION, QualityIssue.RECOMPRESSED), SourceQuality.assess(s, EditSettings().toParams()))
    }

    @Test
    fun noticeShowsOneLinePerIssue() {
        compose.setContent { QualityNotice(QualityIssue.entries.toSet()) }
        compose.onNodeWithText("확대하면 흐려질 수 있어요", substring = true).assertExists()
        compose.onNodeWithText("원본 해상도가 낮아", substring = true).assertExists()
        compose.onNodeWithText("다시 압축된 영상", substring = true).assertExists()
    }

    @Test
    fun sixtyFpsSourceStaysSixtyFps() = render("fps60.mp4") { (w, h, fps), analysis ->
        assertEquals(1080 to 1920, w to h)
        assertEquals(60.0, fps, 1.0)
        assertEquals(60.0, analysis.fps, 1.0)
        assertEquals("화질 판정은 실제로 잰 fps를 써야 함", analysis.fps, analysis.source!!.fps, 0.0)
    }

    @Test
    fun uhd60PortraitRendersAt1080p60() = render("uhd60.mp4") { (w, h, fps), analysis ->
        assertEquals(1080 to 1920, w to h)
        assertEquals(60.0, fps, 1.0)
        assertNotNull(analysis.source)
        val s = analysis.source!!
        assertEquals(2160 to 3840, s.width to s.height)
        assertEquals(emptySet<QualityIssue>(), SourceQuality.assess(s, EditSettings(zoom = 1.5).toParams()))
    }

    private fun render(name: String, check: (Triple<Int, Int, Double>, com.zzick.pipeline.Analysis) -> Unit) {
        val src = video(name)
        val t0 = SystemClock.elapsedRealtime()
        val analysis = pipeline.analyze(src, tap = null) {}
        val analyzeMs = SystemClock.elapsedRealtime() - t0
        val out = File(outDir, "$name.out.mp4").apply { delete() }
        val t1 = SystemClock.elapsedRealtime()
        runBlocking { pipeline.render(src, analysis, CameraParams(), out) {} }
        val renderMs = SystemClock.elapsedRealtime() - t1
        val p = probe(out)
        Log.i("zzick-fps", "$name analyze=${analyzeMs}ms render=${renderMs}ms out=$p")
        check(p, analysis)
    }
}
