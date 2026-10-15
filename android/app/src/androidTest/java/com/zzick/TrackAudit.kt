package com.zzick

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.analysis.SampleReport
import com.zzick.decode.FrameDecoder
import com.zzick.pipeline.ClimbPipeline
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * 추적 점검(개발 도구). 갤러리의 등반 영상(1440×1440)을 앱과 같은 경로로 분석해 추적 기록과 1초 간격 썸네일을 남긴다.
 * 평소 테스트에선 건너뛰고, 명시할 때만 돈다:
 *   adb shell pm grant com.zzick.debug android.permission.READ_MEDIA_VIDEO
 *   adb shell am instrument -w -e class com.zzick.TrackAudit -e audit sample|all|<이름,이름> -e out <폴더> [-e hours 9-18] \
 *     com.zzick.debug.test/androidx.test.runner.AndroidJUnitRunner
 * 결과: /sdcard/Android/data/com.zzick.debug/files/<폴더>/<영상>.json, <영상>/t000.jpg …  (PC: prototype/tools/audit_tracks.py)
 * 이미 결과가 있는 영상은 건너뛴다(끊겨도 이어서).
 */
@RunWith(AndroidJUnit4::class)
class TrackAudit {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private class Video(val id: Long, val name: String, val durationMs: Long)

    private fun gallery(): List<Video> {
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = ArrayList<Video>()
        ctx.contentResolver.query(
            col,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Video.Media.DURATION),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.WIDTH} = 1440 AND ${MediaStore.MediaColumns.HEIGHT} = 1440",
            arrayOf("DCIM/Camera/"), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
        )!!.use { c -> while (c.moveToNext()) out += Video(c.getLong(0), c.getString(1), c.getLong(2)) }
        return out
    }

    /** 파일명(YYYYMMDD_HHMMSS) 시각이 등반 시간대(-e hours 9-18, 기본) 안인지 */
    private fun inHours(v: Video): Boolean {
        val (from, to) = (args.getString("hours") ?: "9-18").split('-').map(String::toInt)
        val hh = v.name.substringAfter('_', "").take(2).toIntOrNull() ?: return true
        return hh in from until to || (to == 24 && hh >= from)
    }

    /** sample: 날짜마다 첫·마지막 영상 + 문제가 있었던 영상(모두 등반 시간대 안, 2분 이하) */
    private fun pick(all: List<Video>, what: String): List<Video> =
        pickAll(all.filter { inHours(it) && it.durationMs <= MAX_CLIMB_MS }, what)

    private fun pickAll(all: List<Video>, what: String): List<Video> = when (what) {
        "all" -> all
        "sample" -> {
            val byDay = all.groupBy { it.name.take(8) }.values.flatMap { listOf(it.first(), it.last()).distinct() }
            (byDay + all.filter { it.name in KNOWN }).distinctBy { it.id }.sortedBy { it.name }
        }
        else -> what.split(',').map(String::trim).let { names -> all.filter { it.name in names || it.name.removeSuffix(".mp4") in names } }
    }

    @Test
    fun audit() {
        val what = args.getString("audit")
        assumeTrue("점검은 -e audit 으로 명시할 때만", what != null)
        val dir = File(ctx.getExternalFilesDir(null), args.getString("out") ?: "audit").apply { mkdirs() }
        val pipeline = ClimbPipeline(ctx)
        val videos = pick(gallery(), what!!)
        Log.i(TAG, "videos=${videos.size}")
        for ((k, v) in videos.withIndex()) {
            val base = v.name.removeSuffix(".mp4")
            val json = File(dir, "$base.json")
            if (json.exists()) continue
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), v.id)
            val root = JSONObject().put("name", v.name).put("durationMs", v.durationMs)
            try {
                val t0 = System.currentTimeMillis()
                val preview = pipeline.findPeople(uri)
                val tap = preview?.let { p -> if (p.boxes.size > 1) p.boxes.maxBy { it.height }.center else null }
                root.put("people", preview?.boxes?.size ?: 0).put("tap", tap?.let { JSONArray(listOf(it.first, it.second)) })
                val samples = JSONArray()
                val a = pipeline.analyze(uri, tap, onSample = { s: SampleReport ->
                    samples.put(JSONArray().put(s.index).put(s.candidates)
                        .put(s.chosen?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                        .put(s.similarity ?: JSONObject.NULL).put(s.viaCrop)
                        .put(JSONArray(s.boxes.map { JSONArray(it.toList()) })))
                }) {}
                root.put("analyzeMs", System.currentTimeMillis() - t0).put("fps", a.fps)
                    .put("trim", JSONArray(a.suggestTrim().let { listOf(it.startUs, it.endUs) }))
                    .put("pts", JSONArray(a.result.ptsUs.toList()))
                    .put("src", JSONArray(a.result.source.toList()))
                    .put("track", JSONArray(a.result.track.map { JSONArray(it.toList()) }))
                    .put("body", JSONArray(a.result.body.map { JSONArray(it.toList()) }))
                    .put("samples", samples)
                thumbnails(pipeline, uri, a.fps, File(dir, base))
            } catch (e: Exception) {
                root.put("error", e.toString())
            }
            json.writeText(root.toString())
            Log.i(TAG, "${k + 1}/${videos.size} ${v.name} ${root.optLong("analyzeMs")}ms ${root.optString("error")}")
        }
    }

    private fun thumbnails(pipeline: ClimbPipeline, uri: android.net.Uri, fps: Double, dir: File) {
        dir.mkdirs()
        val every = fps.roundToInt().coerceAtLeast(1)
        pipeline.decoder(uri, targetHeight = 320).decode(wantPixels = { it % every == 0 }) { i, _, bmp ->
            if (i % every == 0) File(dir, "t%03d.jpg".format(i / every)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
    }

    private companion object {
        const val TAG = "zzick-audit"
        const val MAX_CLIMB_MS = 120_000L
        // 사용자 제보로 고친 영상(회귀 확인용)
        val KNOWN = setOf("20260914_145634.mp4", "20261011_110708~2.mp4", "20261011_105654.mp4", "20260927_113733.mp4", "20261011_123105~2.mp4")
    }
}
