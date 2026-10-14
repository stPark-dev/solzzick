package com.zzick

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.zzick.decode.FrameDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 1단계: 실기기에서 분석 패스(디코드 + 포즈/검출) 속도·메모리 측정.
 * 영상은 run-as로 <filesDir>/videos/ 에 넣어둔다. 결과는 <externalFilesDir>/bench/ 에 저장.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBenchmark {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = ctx.getExternalFilesDir(null)!!
    private val out = File(dir, "bench").apply { mkdirs() }

    private fun video(name: String) = File(ctx.filesDir, "videos/$name").also { assertTrue("영상 없음: $it", it.exists()) }

    @Test
    fun decoderKeepsFrameCountAndOrientation() {
        // 프레임 수는 Python(OpenCV)으로 센 값
        for ((name, expected) in listOf("20260927_113733.mp4" to 686, "20261011_105654.mp4" to 1207)) {
            val dec = FrameDecoder(video(name).path)
            val n = dec.decode { i, _, bmp ->
                if (i == 300) File(out, "$name.f300.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Log.i(TAG, "$name rot=${dec.rotation} src=${dec.srcWidth}x${dec.srcHeight} out=${dec.width}x${dec.height} frames=$n")
            assertEquals(expected, n)
        }
    }

    /** 같은 분석·경로로 출력 비트레이트만 바꿔 렌더(화질 비교용). 결과는 bench/br_*.mp4 */
    @Test
    fun renderBitrateVariants() {
        val src = video("user_123105.mp4")
        val pipeline = com.zzick.pipeline.ClimbPipeline(ctx)
        val analysis = pipeline.analyze(android.net.Uri.fromFile(src), tap = null) {}
        val results = JSONArray()
        for (mbps in listOf(0, 12, 20, 30)) {
            val out = File(out, "br_$mbps.mp4").apply { delete() }
            val t0 = SystemClock.elapsedRealtime()
            kotlinx.coroutines.runBlocking {
                pipeline.render(android.net.Uri.fromFile(src), analysis, com.zzick.core.vcam.CameraParams(), out,
                    videoBitrate = if (mbps == 0) null else mbps * 1_000_000) {}
            }
            results.put(JSONObject().put("mbps", mbps).put("render_ms", SystemClock.elapsedRealtime() - t0).put("bytes", out.length()))
        }
        File(out, "bitrate.json").writeText(results.toString(2))
    }

    /** 사용자 제보 영상을 현재 코드로 렌더(수정 전후 육안 비교용). 결과는 bench/swing_fixed.mp4 */
    @Test
    fun renderSwingVideo() {
        val src = android.net.Uri.fromFile(video("user_110708.mp4"))
        val pipeline = com.zzick.pipeline.ClimbPipeline(ctx)
        val preview = pipeline.findPeople(src)!!
        val tap = if (preview.boxes.size > 1) preview.boxes.maxBy { it.height }.center else null // 앱과 같은 경로
        val analysis = pipeline.analyze(src, tap) {}
        val out = File(out, "swing_fixed.mp4").apply { delete() }
        kotlinx.coroutines.runBlocking {
            pipeline.render(src, analysis, com.zzick.core.vcam.CameraParams(), out, analysis.suggestTrim()) {}
        }
    }

    /** 광학 흐름 관련 비용(기기): 회색조 변환 1회, 흐름 한 걸음 */
    @Test
    fun benchmarkFlowCost() {
        val dec = FrameDecoder(video("20261011_105654.mp4").path)
        val gray = com.zzick.pipeline.BitmapGray()
        val grays = ArrayList<com.zzick.core.flow.GrayImage>()
        var grayNs = 0L
        dec.decode(maxFrames = 330) { i, _, bmp ->
            if (i >= 300) {
                val t = System.nanoTime()
                grays += gray.gray(bmp)
                grayNs += System.nanoTime() - t
            }
        }
        val tr = com.zzick.core.flow.FlowTracker()
        val g0 = grays[0]
        tr.seed(g0, com.zzick.core.tracking.Box(g0.w * 0.65, g0.h * 0.35, g0.w * 0.95, g0.h * 0.65))
        val t0 = System.nanoTime()
        var steps = 0
        for (k in 1 until grays.size) { tr.step(grays[k]) ?: break; steps++ }
        val stepMs = (System.nanoTime() - t0) / 1e6 / maxOf(1, steps)
        Log.i(TAG, "flowcost gray=%.2fms/frame step=%.2fms (steps=%d) size=%dx%d".format(grayNs / 1e6 / grays.size, stepMs, steps, g0.w, g0.h))
    }

    /** 광학 흐름 켬/끔 분석 시간 비교(같은 조건에서 번갈아 2회씩) */
    @Test
    fun benchmarkFlowOnOff() {
        val pipeline = com.zzick.pipeline.ClimbPipeline(ctx)
        for (name in listOf("20261011_105654.mp4", "user_110708.mp4")) {
            val uri = android.net.Uri.fromFile(video(name))
            for (round in 0 until 2) for (flow in listOf(false, true)) {
                val t0 = SystemClock.elapsedRealtime()
                val a = pipeline.analyze(uri, tap = null, opticalFlow = flow) {}
                val flowFrames = a.result.source.count { it == com.zzick.core.tracking.Fuse.FLOW }
                Log.i(TAG, "flowonoff $name flow=$flow round=$round ms=${SystemClock.elapsedRealtime() - t0} flowFrames=$flowFrames")
            }
        }
    }

    @Test
    fun benchmarkPoseStride() {
        val name = "20261011_105654.mp4"
        val results = JSONArray()
        for (stride in listOf(2, 3)) {
            results.put(pose(name, "pose_landmarker_full.task", Delegate.GPU, Int.MAX_VALUE, stride))
            File(out, "bench_stride.json").writeText(results.toString(2))
        }
    }

    @Test
    fun benchmarkAnalysisPass() {
        val name = "20261011_105654.mp4"
        val results = JSONArray()
        val stages = listOf(
            { decodeOnly(name) },
            { pose(name, "pose_landmarker_full.task", Delegate.GPU, Int.MAX_VALUE) },
            { pose(name, "pose_landmarker_full.task", Delegate.CPU, 300) },
            { pose(name, "pose_landmarker_lite.task", Delegate.GPU, 300) },
            { detector(name, Delegate.CPU, 300) },
            { detector(name, Delegate.GPU, 300) },
        )
        for (stage in stages) {
            results.put(stage())
            File(out, "bench.json").writeText(results.toString(2)) // 단계마다 저장(네이티브 크래시 대비)
            Log.i(TAG, results.getJSONObject(results.length() - 1).toString())
        }
    }

    private fun decodeOnly(name: String): JSONObject {
        val dec = FrameDecoder(video(name).path)
        val t0 = SystemClock.elapsedRealtime()
        val n = dec.decode { _, _, _ -> }
        val ms = SystemClock.elapsedRealtime() - t0
        return JSONObject().put("stage", "decode_only").put("frames", n).put("total_ms", ms).put("fps", n * 1000.0 / ms)
    }

    private fun pose(name: String, model: String, delegate: Delegate, maxFrames: Int, stride: Int = 1): JSONObject {
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model).setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        val lm = PoseLandmarker.createFromOptions(ctx, opts)
        val dec = FrameDecoder(video(name).path)
        var detected = 0
        var inferred = 0
        var inferMs = 0.0
        var peakPssKb = 0
        val t0 = SystemClock.elapsedRealtime()
        val n = dec.decode(maxFrames) { i, pts, bmp ->
            if (i % stride != 0) return@decode
            inferred++
            val t = SystemClock.elapsedRealtimeNanos()
            val r = lm.detectForVideo(BitmapImageBuilder(bmp).build(), pts / 1000)
            inferMs += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            if (r.landmarks().isNotEmpty()) detected++
            if (i % 100 == 0) peakPssKb = maxOf(peakPssKb, Debug.getPss().toInt())
        }
        val ms = SystemClock.elapsedRealtime() - t0
        lm.close()
        return JSONObject().put("stage", "pose").put("model", model).put("delegate", delegate.name)
            .put("stride", stride).put("frames", n).put("total_ms", ms).put("fps", n * 1000.0 / ms)
            .put("infer_ms_per_frame", inferMs / inferred).put("detect_rate", detected.toDouble() / inferred)
            .put("peak_pss_mb", peakPssKb / 1024)
    }

    private fun detector(name: String, delegate: Delegate, maxFrames: Int): JSONObject {
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").setDelegate(delegate).build())
            .setScoreThreshold(0.2f)
            .setMaxResults(10) // GPU 경로는 category allowlist 미지원(네이티브 abort) → person은 코드에서 거른다
            .build()
        val od = ObjectDetector.createFromOptions(ctx, opts)
        val dec = FrameDecoder(video(name).path)
        var inferMs = 0.0
        var withPerson = 0
        val n = dec.decode(maxFrames) { _, _, bmp ->
            val t = SystemClock.elapsedRealtimeNanos()
            val r = od.detect(BitmapImageBuilder(bmp).build())
            inferMs += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            if (r.detections().any { d -> d.categories().any { it.categoryName() == "person" } }) withPerson++
        }
        od.close()
        return JSONObject().put("stage", "detector").put("delegate", delegate.name).put("frames", n)
            .put("infer_ms_per_frame", inferMs / n).put("person_rate", withPerson.toDouble() / n)
    }

    /** 실제 분석(ClimbPipeline.analyze와 같은 구성)의 단계별 시간 — 어디를 줄여야 빨라지는지 보려고. */
    @Test
    fun benchmarkAnalysisBreakdown() {
        val results = JSONArray()
        for (name in listOf("20261011_105654.mp4", "uhd60.mp4")) {
            results.put(breakdown(name))
            File(out, "breakdown.json").writeText(results.toString(2))
            Log.i(TAG, results.getJSONObject(results.length() - 1).toString())
        }
    }

    private class Timer {
        val ns = mutableMapOf<String, Long>()
        val calls = mutableMapOf<String, Int>()
        inline fun <T> time(key: String, block: () -> T): T {
            val t = SystemClock.elapsedRealtimeNanos()
            try { return block() } finally {
                ns[key] = (ns[key] ?: 0L) + (SystemClock.elapsedRealtimeNanos() - t)
                calls[key] = (calls[key] ?: 0) + 1
            }
        }
    }

    private fun breakdown(name: String): JSONObject {
        val tm = Timer()
        val dec = FrameDecoder(video(name).path)
        val stride = com.zzick.core.analysis.Analyzer.strideFor(dec.frameRate)
        val t0 = SystemClock.elapsedRealtime()
        var frames = 0
        com.zzick.pipeline.MpPoseEstimator(ctx, numPoses = 1).use { mp ->
            com.zzick.pipeline.MpPersonDetector(ctx).use { md ->
                val pose = object : com.zzick.core.analysis.PoseEstimator<Bitmap> {
                    override fun estimate(frame: Bitmap, timestampMs: Long) = tm.time("pose") { mp.estimate(frame, timestampMs) }
                    override fun estimateCrop(frame: Bitmap, box: com.zzick.core.tracking.Box) = tm.time("pose_crop") { mp.estimateCrop(frame, box) }
                }
                val det = object : com.zzick.core.analysis.PersonDetector<Bitmap> {
                    override fun detect(frame: Bitmap) = tm.time("detector") { md.detect(frame) }
                }
                val look = com.zzick.pipeline.BitmapAppearance()
                val app = object : com.zzick.core.analysis.AppearanceExtractor<Bitmap> {
                    override fun describe(frame: Bitmap, box: com.zzick.core.tracking.Box) = tm.time("appearance") { look.describe(frame, box) }
                }
                val g = com.zzick.pipeline.BitmapGray()
                val gray = object : com.zzick.core.analysis.GrayProvider<Bitmap> {
                    override fun gray(frame: Bitmap) = tm.time("gray") { g.gray(frame) }
                }
                val a = com.zzick.core.analysis.Analyzer(
                    pose, det, dec.srcWidth.toDouble(), dec.srcHeight.toDouble(), dec.frameRate, stride,
                    appearance = app, gray = gray, confirmStart = true,
                )
                frames = dec.decode(wantPixels = { i -> tm.time("needs_pixels") { a.needsPixels(i) } }) { i, pts, bmp ->
                    tm.time("on_frame") { a.onFrame(i, pts, bmp) }
                }
                a.finish(dec.frameRate)
            }
        }
        val total = SystemClock.elapsedRealtime() - t0
        val o = JSONObject().put("video", name).put("size", "${dec.srcWidth}x${dec.srcHeight}").put("fps", dec.frameRate)
            .put("stride", stride).put("frames", frames).put("total_ms", total)
            .put("duration_s", frames / dec.frameRate)
        for ((k, v) in tm.ns) o.put("${k}_ms", v / 1_000_000).put("${k}_calls", tm.calls[k])
        val onFrame = (tm.ns["on_frame"] ?: 0L) / 1_000_000
        o.put("decode_and_other_ms", total - onFrame)
        return o
    }

    companion object {
        private const val TAG = "zzick-bench"
    }
}
