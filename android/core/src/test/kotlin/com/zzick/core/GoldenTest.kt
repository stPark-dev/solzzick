package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import com.zzick.core.vcam.CameraParams
import com.zzick.core.vcam.VirtualCamera
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Python 프로토타입(prototype/tools/export_golden.py)이 실제 영상 추적 데이터로 만든 기준값과
 * Kotlin 구현의 출력이 같은지 검증한다.
 */
class GoldenTest {
    private fun load(name: String) =
        JSONObject(javaClass.getResource("/golden/$name.json")!!.readText())

    private fun matrix(a: JSONArray) = Array(a.length()) { i ->
        val row = a.getJSONArray(i)
        DoubleArray(row.length()) { j -> if (row.isNull(j)) Double.NaN else row.getDouble(j) }
    }

    private fun boxes(a: JSONArray) = List(a.length()) { i ->
        val fr = a.getJSONArray(i)
        List(fr.length()) { j ->
            val b = fr.getJSONArray(j)
            Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }

    private fun params(o: JSONObject) = CameraParams(
        aspect = o.getDouble("aspect"),
        outHeight = o.getInt("out_height"),
        zoom = o.getDouble("zoom"),
        maxUpscale = o.getDouble("max_upscale"),
        headroom = o.getDouble("headroom"),
        deadzoneX = o.getDouble("deadzone_x"),
        deadzoneY = o.getDouble("deadzone_y"),
        omega = o.getDouble("omega"),
        zeta = o.getDouble("zeta"),
        delayS = o.getDouble("delay_s"),
        driftX = o.getDouble("drift_x"),
        driftY = o.getDouble("drift_y"),
        driftHz = o.getDouble("drift_hz"),
        shakeGain = o.getDouble("shake_gain"),
        shakeX = o.getDouble("shake_x"),
        shakeY = o.getDouble("shake_y"),
        shakeRollDeg = o.getDouble("shake_roll_deg"),
        shakeHz = o.getDouble("shake_hz"),
        fitMargin = o.getDouble("fit_margin"),
        zoomLeadS = o.getDouble("zoom_lead_s"),
        motionZoom = o.getBoolean("motion_zoom"),
        cruxZoom = o.getDouble("crux_zoom"),
        cruxWindowS = o.getDouble("crux_window_s"),
        cruxRange = o.getDouble("crux_range"),
        cruxMinS = o.getDouble("crux_min_s"),
        moveSpeed = o.getDouble("move_speed"),
        moveZoom = o.getDouble("move_zoom"),
        moveHoldS = o.getDouble("move_hold_s"),
        speedSmoothS = o.getDouble("speed_smooth_s"),
        seed = o.getInt("seed"),
    )

    private fun assertMatrixEquals(what: String, expected: Array<DoubleArray>, actual: Array<DoubleArray>, tol: Double) {
        assertEquals("$what 행 수", expected.size, actual.size)
        var worst = 0.0
        var at = ""
        for (i in expected.indices) for (j in expected[i].indices) {
            val e = expected[i][j]
            val a = actual[i][j]
            val d = if (e.isNaN() && a.isNaN()) 0.0 else abs(e - a)
            if (!(d <= worst)) {
                worst = d
                at = "[$i][$j] expected=$e actual=$a"
            }
        }
        assert(worst <= tol) { "$what 최대 오차 $worst > $tol at $at" }
    }

    private fun checkVideo(name: String) {
        val g = load(name)
        val fps = g.getDouble("fps")
        val w = g.getInt("src_w").toDouble()
        val h = g.getInt("src_h").toDouble()

        val flow = if (g.isNull("flow")) null else matrix(g.getJSONArray("flow"))
        val flowSeed = if (g.isNull("flow_seed")) null else g.getJSONArray("flow_seed").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val fused = Fuse.fuseTrack(matrix(g.getJSONArray("pose")), boxes(g.getJSONArray("boxes")), fps, flow = flow, flowSeed = flowSeed)
        assertMatrixEquals("$name fused track", matrix(g.getJSONArray("fused_track")), fused.track, 1e-9)
        val expectedSource = g.getJSONArray("fused_source")
        for (i in 0 until expectedSource.length()) assertEquals("$name source[$i]", expectedSource.getInt(i), fused.source[i])

        val body = Fuse.bodyBoxes(matrix(g.getJSONArray("raw_body")), fused.track, fused.source)
        assertMatrixEquals("$name body", matrix(g.getJSONArray("body")), body, 1e-9)

        val trackPx = Array(fused.track.size) { i -> doubleArrayOf(fused.track[i][0] * h, fused.track[i][1] * h) }
        val bodyPx = Array(body.size) { i -> DoubleArray(4) { j -> body[i][j] * h } }
        val cases = g.getJSONObject("cases")
        for (case in cases.keys()) {
            val c = cases.getJSONObject(case)
            val p = params(c.getJSONObject("params"))
            val path = VirtualCamera.solvePath(trackPx, fps, w, h, p, if (case.endsWith("no_body")) null else bodyPx)
            assertMatrixEquals("$name/$case path", matrix(c.getJSONArray("path")), path, 1e-6)
        }
    }

    @Test
    fun matchesPythonOnClimbWithCloseStart() = checkVideo("v113733")

    @Test
    fun matchesPythonOnClimbWithDetectorGaps() = checkVideo("v110227")

    /** 사용자 제보 영상(그네 동작 중 가짜 포즈) — 이상값 제거까지 Python과 같아야 한다. */
    @Test
    fun matchesPythonOnSwingWithSpuriousPose() = checkVideo("vswing")
}
