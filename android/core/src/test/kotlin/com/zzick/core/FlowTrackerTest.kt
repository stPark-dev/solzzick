package com.zzick.core

import com.zzick.core.flow.FlowTracker
import com.zzick.core.flow.GrayImage
import com.zzick.core.tracking.Box
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

class FlowTrackerTest {
    /**
     * Python 테스트의 textured와 같은 텍스처: 8px 블록 무늬를 가우시안(5x5, σ1.5)으로 흐린 영상.
     * 실수 좌표는 쌍선형 보간으로 읽는다.
     */
    private class Texture(seed: Int, private val size: Int = 512) {
        private val img: GrayImage

        init {
            val r = Random(seed)
            val cells = Array(size / 8) { FloatArray(size / 8) { r.nextInt(256).toFloat() } }
            val raw = FloatArray(size * size) { j -> cells[(j / size) / 8][(j % size) / 8] }
            val k = (-2..2).map { kotlin.math.exp(-it * it / (2 * 1.5 * 1.5)) }.let { v -> v.map { (it / v.sum()).toFloat() } }
            fun blur(a: FloatArray, horiz: Boolean) = FloatArray(size * size) { j ->
                val x = j % size
                val y = j / size
                var acc = 0f
                for (i in -2..2) {
                    val xx = if (horiz) (x + i).coerceIn(0, size - 1) else x
                    val yy = if (horiz) y else (y + i).coerceIn(0, size - 1)
                    acc += a[yy * size + xx] * k[i + 2]
                }
                acc
            }
            img = GrayImage(size, size, blur(blur(raw, true), false))
        }

        fun at(x: Double, y: Double): Float = img.sample(x, y)
    }

    /** 정지 배경 위에 패치(60x60)가 (px, py)에 놓인 프레임 */
    private fun frame(px: Double, py: Double, w: Int = 320, h: Int = 240): GrayImage {
        val bg = Texture(1)
        val patch = Texture(2)
        return GrayImage(w, h, FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x >= px && x < px + 60 && y >= py && y < py + 60) patch.at(x - px, y - py) else bg.at(x.toDouble(), y.toDouble())
        })
    }

    @Test
    fun followsMovingPatchOverStaticBackground() {
        val t = FlowTracker()
        assertTrue(t.seed(frame(100.0, 80.0), Box(100.0, 80.0, 160.0, 140.0)))
        var d: DoubleArray? = null
        for (k in 1..9) d = t.step(frame(100.0 + 4 * k, 80.0 + 2 * k))
        assertNotNull(d)
        assertEquals(36.0, d!![0], 1.5)
        assertEquals(18.0, d[1], 1.5)
    }

    /**
     * 사용자 영상(20260914_145634 8~11초): 등반자가 크게 움직이는데 몸 박스 안 점 대부분이 벽 위라 중앙값이 0 — 카메라가 멈춤.
     * 고정 카메라에서 벽은 움직이지 않으니, 일부 점이 뚜렷이 움직이기 시작하면 제자리 점은 버리고 움직이는 쪽을 따라간다.
     */
    @Test
    fun followsClimberEvenWhenMostSeedPointsAreOnTheWall() {
        val t = FlowTracker()
        assertTrue(t.seed(frame(100.0, 80.0), Box(55.0, 35.0, 205.0, 185.0))) // 패치(60x60)는 박스 면적의 약 16%
        var d: DoubleArray? = null
        for (k in 1..9) d = t.step(frame(100.0 + 4 * k, 80.0 + 2 * k))
        assertNotNull(d)
        // 패치 가장자리 점은 창 안의 벽 무늬에 끌려 덜 움직인다(실측 10~30px) — 안쪽 점은 정확(36px). 수정 전엔 8px(멈춤)
        assertEquals(36.0, d!![0], 6.0)
        assertEquals(18.0, d[1], 4.0)
    }

    @Test
    fun stillClimberStaysStillWithWallPoints() {
        val t = FlowTracker()
        t.seed(frame(100.0, 80.0), Box(55.0, 35.0, 205.0, 185.0))
        var d: DoubleArray? = null
        for (k in 1..9) d = t.step(frame(100.0, 80.0))
        assertEquals(0.0, d!![0], 0.2)
        assertEquals(0.0, d[1], 0.2)
    }

    @Test
    fun detectsSubpixelMotion() {
        val t = FlowTracker()
        t.seed(frame(100.0, 80.0), Box(100.0, 80.0, 160.0, 140.0))
        val d = t.step(frame(100.5, 80.0))!!
        assertEquals(0.5, d[0], 0.15)
        assertEquals(0.0, d[1], 0.15)
    }

    /** 한 프레임에 6px(30fps에서 빠른 동작 수준) — 작은 패치가 정지 배경 위에서 움직여도 따라간다.
     *  (15px 같은 큰 한 걸음은 OpenCV도 같은 영상에서 실패함을 확인 — 실사용 범위 밖) */
    @Test
    fun tracksFastSingleStep() {
        val t = FlowTracker()
        t.seed(frame(100.0, 80.0), Box(100.0, 80.0, 160.0, 140.0))
        val d = t.step(frame(106.0, 83.0))!!
        assertEquals(6.0, d[0], 0.5)
        assertEquals(3.0, d[1], 0.5)
    }

    @Test
    fun reportsLostWhenTextureDisappears() {
        val t = FlowTracker()
        t.seed(frame(100.0, 80.0), Box(100.0, 80.0, 160.0, 140.0))
        assertNull(t.step(GrayImage(320, 240, FloatArray(320 * 240) { 128f })))
    }

    /** 실제 그네 프레임에서 OpenCV와 같은 시작점으로 한 걸음(0→3프레임) 추적 — 점마다 결과가 거의 같아야 한다. */
    @Test
    fun matchesOpenCvPerPointOnRealSwingFrames() {
        val meta = JSONObject(javaClass.getResource("/flow/swing.json")!!.readText())
        val sp = meta.getJSONArray("seed_points")
        val ref = meta.getJSONArray("opencv_step0to3")
        fun load(i: Int) = ImageIO.read(javaClass.getResource("/flow/swing_%02d.png".format(i))).let { img ->
            GrayImage(img.width, img.height, FloatArray(img.width * img.height) { j -> (img.getRGB(j % img.width, j / img.width) and 0xFF).toFloat() })
        }
        val pts = DoubleArray(sp.length() * 2) { sp.getJSONArray(it / 2).getDouble(it % 2) }
        val mine = FlowTracker().probeOneStep(load(0), load(3), pts)
        val diffs = mine.indices.mapNotNull { k ->
            if (ref.isNull(k) || mine[k][0].isNaN()) null
            else kotlin.math.hypot(mine[k][0] - ref.getJSONArray(k).getDouble(0), mine[k][1] - ref.getJSONArray(k).getDouble(1))
        }.sorted()
        assertTrue("비교한 점이 너무 적음: ${diffs.size}", diffs.size >= 40)
        assertTrue("점별 차이 중앙값 ${"%.2f".format(diffs[diffs.size / 2])}px", diffs[diffs.size / 2] <= 0.5)
    }

    @Test
    fun pureShiftOfRealFrameIsExact() {
        val img = ImageIO.read(javaClass.getResource("/flow/swing_00.png"))
        val w = img.width
        val h = img.height
        fun shifted(sx: Int) = GrayImage(w, h, FloatArray(w * h) { j -> (img.getRGB(((j % w) - sx).coerceIn(0, w - 1), j / w) and 0xFF).toFloat() })
        for (sx in listOf(1, 3, 6, 12)) {
            val t = FlowTracker()
            t.seed(shifted(0), Box(265.0, 130.0, 340.0, 210.0))
            val d = t.step(shifted(sx))!!
            assertEquals(sx.toDouble(), d[0], 0.05) // 순수 평행이동은 정확해야 한다
            assertEquals(0.0, d[1], 0.05)
        }
    }
}
