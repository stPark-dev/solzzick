package com.zzick

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.vcam.CameraParams
import com.zzick.decode.FrameDecoder
import com.zzick.pipeline.ClimbPipeline
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** MVP 핵심 흐름(분석 → 경로 → 렌더)을 실기기에서 끝까지 돌린다. 영상은 run-as로 filesDir/videos 에 넣어둔다. */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }

    @Test
    fun rotatedVideoBecomesVertical1080pWithAudio() {
        val name = "20261011_105654.mp4"
        val src = File(ctx.filesDir, "videos/$name")
        assertTrue("영상 없음: $src", src.exists())
        val pipeline = ClimbPipeline(ctx)
        val params = CameraParams()

        val t0 = SystemClock.elapsedRealtime()
        var lastProgress = 0f
        val analysis = pipeline.analyze(Uri.fromFile(src), tap = null) { lastProgress = it }
        val analyzeMs = SystemClock.elapsedRealtime() - t0
        assertEquals(1207, analysis.result.ptsUs.size)
        assertEquals(1f, lastProgress, 1e-6f)

        val out = File(outDir, "$name.out.mp4").apply { delete() }
        val t1 = SystemClock.elapsedRealtime()
        runBlocking { pipeline.render(Uri.fromFile(src), analysis, params, out) {} }
        val renderMs = SystemClock.elapsedRealtime() - t1

        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        ex.release()
        val video = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) video.getInteger(MediaFormat.KEY_ROTATION) else 0
        val (vw, vh) = video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
        val (dispW, dispH) = if (rotation % 180 == 0) vw to vh else vh to vw
        assertEquals(1080, dispW)
        assertEquals(1920, dispH)
        assertTrue("오디오 트랙 없음", formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
        val srcDur = FrameDecoder(src.path).durationUs
        val outDur = video.getLong(MediaFormat.KEY_DURATION)
        assertTrue("길이 불일치 src=$srcDur out=$outDur", abs(srcDur - outDur) < 200_000)

        // 눈으로 확인할 샘플 프레임
        FrameDecoder(out.path, targetHeight = 960).decode(wantPixels = { it % 200 == 0 }) { i, _, bmp ->
            if (i % 200 == 0) File(outDir, "$name.out.f$i.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        val report = JSONObject().put("video", name).put("frames", analysis.result.ptsUs.size)
            .put("analyze_ms", analyzeMs).put("render_ms", renderMs)
            .put("out_size", "${dispW}x$dispH").put("out_rotation_meta", rotation)
        File(outDir, "e2e.json").writeText(report.toString(2))
        Log.i("zzick-e2e", report.toString())
    }
}
