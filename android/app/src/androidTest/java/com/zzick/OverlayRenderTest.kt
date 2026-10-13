package com.zzick

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.EditSettings
import com.zzick.decode.FrameDecoder
import com.zzick.pipeline.ClimbPipeline
import com.zzick.pipeline.OverlayImage
import com.zzick.pipeline.OverlayPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/** 영상 구석 표시(암장·날짜·색깔 하트): 촬영 날짜 읽기, 표시 그림, 결과 영상에 실제로 들어가는지. */
@RunWith(AndroidJUnit4::class)
class OverlayRenderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pipeline = ClimbPipeline(ctx)
    private val src = Uri.fromFile(File(ctx.filesDir, "videos/20260927_113733.mp4"))
    private val outDir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    @Test
    fun readsCaptureDateFromVideo() {
        assertEquals(LocalDate.of(2026, 9, 27), pipeline.captureDate(src))
    }

    /** 암장 이름·날짜 표시는 다음 영상에도, 난이도(문제마다 다름)는 기억하지 않는다. */
    @Test
    fun remembersGymAndDateButNotGrade() {
        OverlayPrefs(ctx).save(OverlayInfo(gym = "더클라임", showDate = true, grade = Grade.RED))
        assertEquals(OverlayInfo(gym = "더클라임", showDate = true), OverlayPrefs(ctx).load())
        OverlayPrefs(ctx).save(OverlayInfo())
    }

    @Test
    fun overlayImageDrawsOnlyInBottomLeftCorner() {
        assertNull(OverlayImage.render(null, null, 1080, 1920))
        val bmp = OverlayImage.render(Grade.SKY, "더클라임 강남 · 2026.10.11", 1080, 1920)!!
        assertEquals(1080 to 1920, bmp.width to bmp.height)
        var inside = 0
        var outside = 0
        var heart = 0
        for (y in 0 until 1920 step 2) for (x in 0 until 1080 step 2) {
            val p = bmp.getPixel(x, y)
            if (p ushr 24 == 0) continue
            if (x < 1080 * 0.75 && y > 1920 * 0.85) inside++ else outside++
            if (p ushr 24 == 0xFF && abs((p and 0xFFFFFF) - (Grade.SKY.argb and 0xFFFFFF)) == 0) heart++
        }
        assertTrue("표시가 그려지지 않음", inside > 500)
        assertEquals("왼쪽 아래 밖에 그려짐", 0, outside)
        // 하트는 글자가 아니라 난이도 색 모양 — 🩵가 없는 옛 글꼴에서도 그대로 나온다
        assertTrue("하늘색 하트가 없음: $heart", heart > 40)
        assertTrue("하트만 있어도 표시", OverlayImage.render(Grade.PINK, null, 1080, 1920) != null)
    }

    @Test
    fun renderedVideoCarriesOverlay() {
        val analysis = pipeline.analyze(src, tap = null) {}
        val window = TrimWindow(analysis.firstPtsUs + 3_000_000, analysis.firstPtsUs + 5_000_000)
        fun renderWith(o: OverlayInfo, name: String): Bitmap {
            val out = File(outDir, name).apply { delete() }
            runBlocking { pipeline.render(src, analysis, EditSettings(overlay = o).toParams(), out, window, overlay = o) {} }
            var frame: Bitmap? = null
            FrameDecoder(out.path, targetHeight = 1920).decode(maxFrames = 31) { i, _, bmp -> if (i == 30) frame = bmp.copy(bmp.config!!, false) }
            return frame!!
        }
        val plain = renderWith(OverlayInfo(), "overlay_none.mp4")
        val marked = renderWith(OverlayInfo(gym = "솔찍 테스트암장", showDate = true, grade = Grade.RED), "overlay_on.mp4")
        File(outDir, "overlay_on.jpg").outputStream().use { marked.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        fun diff(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in y0 until y1 step 4) for (x in x0 until x1 step 4) {
                val a = plain.getPixel(x, y)
                val b = marked.getPixel(x, y)
                sum += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
                n++
            }
            return sum / n / 3
        }
        val w = plain.width
        val h = plain.height
        val corner = diff(0, (h * 0.88).toInt(), (w * 0.6).toInt(), h)
        val elsewhere = diff((w * 0.4).toInt(), 0, w, (h * 0.6).toInt())
        assertTrue("왼쪽 아래에 표시가 없음: 차이 $corner", corner > 8)
        assertTrue("다른 곳까지 달라짐: 차이 $elsewhere", elsewhere < 3)
    }
}
