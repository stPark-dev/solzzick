package com.zzick

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.CameraParams
import com.zzick.pipeline.ClimbPipeline
import kotlinx.coroutines.runBlocking
import com.zzick.pipeline.Gallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PipelineHelpersTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findPeopleSkipsEmptyOpeningAndReturnsBoxes() {
        // 이 영상은 첫 5초 동안 등반자가 화면 밖에 있다(Phase 0 진단)
        val src = File(ctx.filesDir, "videos/20261011_105654.mp4")
        val preview = ClimbPipeline(ctx).findPeople(Uri.fromFile(src))
        assertNotNull("사람을 찾지 못함", preview)
        preview!!
        assertTrue("빈 프레임을 건너뛰어야 함: ${preview.frameIndex}", preview.frameIndex > 0)
        assertTrue(preview.boxes.isNotEmpty())
        for (b in preview.boxes) assertTrue(b.x0 in 0.0..1.0 && b.y1 in 0.0..1.0 && b.x0 < b.x1 && b.y0 < b.y1)
        assertEquals(preview.bitmap.width.toDouble() / preview.bitmap.height, 1.0, 0.01)
    }

    @Test
    fun savedVideoAppearsInGallery() {
        val src = File(ctx.filesDir, "videos/20260927_113733.mp4")
        val uri = Gallery.saveVideo(ctx, src, "zzick_test_${System.currentTimeMillis()}.mp4")
        try {
            val cols = arrayOf(MediaStore.Video.Media.SIZE, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.RELATIVE_PATH)
            ctx.contentResolver.query(uri, cols, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(src.length(), it.getLong(0))
                assertEquals(0, it.getInt(1))
                assertEquals("Movies/솔찍/", it.getString(2))
            }
        } finally {
            ctx.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun renderUsesRequestedVideoBitrate() {
        val src = File(ctx.filesDir, "videos/20260927_113733.mp4")
        val pipeline = ClimbPipeline(ctx)
        val analysis = pipeline.analyze(Uri.fromFile(src), tap = null) {}
        val out = File(ctx.cacheDir, "bitrate_test.mp4").apply { delete() }
        val window = TrimWindow(analysis.firstPtsUs + 5_000_000, analysis.firstPtsUs + 10_000_000)
        runBlocking { pipeline.render(Uri.fromFile(src), analysis, CameraParams(), out, window, videoBitrate = 20_000_000) {} }
        val r = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val durationS = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
        r.release()
        val mbps = out.length() * 8 / durationS / 1e6
        out.delete()
        // 오디오(256kbps) 포함 전체 비트레이트 — 요청 20Mbps의 ±25%
        assertTrue("결과 비트레이트 ${"%.1f".format(mbps)}Mbps", mbps in 15.0..25.5)
    }

    /**
     * 사용자 제보 회귀 테스트(20261011_110708~2), 앱과 같은 경로로:
     * 첫 장면에서 사람이 둘 잡혀 등반자를 탭 → 다중 인물 모드로 분석.
     * 그네 중 등반자가 화면 밖으로 나간 사이 왼쪽 빨간 홀드(약 x 208)를 사람으로 연속 인식했었다.
     * 추적 위치는 내내 등반자 쪽(x 1000 이상)에 있어야 한다.
     */
    @Test
    fun swingVideoIgnoresSpuriousPose() {
        val src = Uri.fromFile(File(ctx.filesDir, "videos/user_110708.mp4"))
        val pipeline = ClimbPipeline(ctx)
        val preview = pipeline.findPeople(src)!!
        val tap = if (preview.boxes.size > 1) preview.boxes.maxBy { it.height }.center else null
        val a = pipeline.analyze(src, tap) {}
        val pts = a.result.ptsUs
        for (i in pts.indices) {
            val t = (pts[i] - pts[0]) / 1e6
            if (t < 4.0 || t > 11.0) continue
            val (x, y) = a.result.track[i]
            assertTrue("%.2fs 추적 위치 (%.0f, %.0f)".format(t, x, y), x > 1000.0)
        }
    }

    /** 빠른 동작 추적 개선: 그네 영상 8.3~10.4초(포즈가 거의 안 잡히는 구간)를 광학 흐름이 메워야 한다. */
    @Test
    fun swingGapIsFilledByOpticalFlow() {
        val src = Uri.fromFile(File(ctx.filesDir, "videos/user_110708.mp4"))
        val pipeline = ClimbPipeline(ctx)
        val preview = pipeline.findPeople(src)!!
        val tap = if (preview.boxes.size > 1) preview.boxes.maxBy { it.height }.center else null
        val a = pipeline.analyze(src, tap) {}
        val pts = a.result.ptsUs
        val inGap = pts.indices.filter { ((pts[it] - pts[0]) / 1e6) in 8.3..10.4 }
        val flowFrac = inGap.count { a.result.source[it] == com.zzick.core.tracking.Fuse.FLOW }.toDouble() / inGap.size
        assertTrue("흐름으로 메운 비율 ${"%.0f".format(flowFrac * 100)}%", flowFrac >= 0.5)
    }
}
