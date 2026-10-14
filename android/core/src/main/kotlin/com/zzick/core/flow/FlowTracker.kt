package com.zzick.core.flow

import com.zzick.core.tracking.Box
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/** 회색조 영상(0..255). px는 행 우선. */
class GrayImage(val w: Int, val h: Int, val px: FloatArray) {
    operator fun get(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** 실수 좌표 쌍선형 보간 */
    fun sample(x: Double, y: Double): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val top = this[x0, y0] * (1 - fx) + this[x0 + 1, y0] * fx
        val bot = this[x0, y0 + 1] * (1 - fx) + this[x0 + 1, y0 + 1] * fx
        return top * (1 - fy) + bot * fy
    }

    /** 가우시안(1,4,6,4,1)으로 흐린 뒤 절반 크기(OpenCV pyrDown과 같은 방식). 안쪽은 직접 인덱싱(기기에서 빠르게). */
    fun half(): GrayImage {
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                tmp[r + x] = if (x >= 2 && x < w - 2) {
                    (px[r + x - 2] + 4 * px[r + x - 1] + 6 * px[r + x] + 4 * px[r + x + 1] + px[r + x + 2]) / 16f
                } else {
                    (this[x - 2, y] + 4 * this[x - 1, y] + 6 * this[x, y] + 4 * this[x + 1, y] + this[x + 2, y]) / 16f
                }
            }
        }
        val hw = (w + 1) / 2
        val hh = (h + 1) / 2
        val out = FloatArray(hw * hh)
        for (yy in 0 until hh) {
            val y = yy * 2
            for (xx in 0 until hw) {
                val x = xx * 2
                out[yy * hw + xx] = if (y >= 2 && y < h - 2) {
                    (tmp[(y - 2) * w + x] + 4 * tmp[(y - 1) * w + x] + 6 * tmp[y * w + x] + 4 * tmp[(y + 1) * w + x] + tmp[(y + 2) * w + x]) / 16f
                } else {
                    var acc = 0f
                    val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
                    for (i in -2..2) acc += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + 2]
                    acc / 16f
                }
            }
        }
        return GrayImage(hw, hh, out)
    }

    /**
     * 창(왼쪽 위 실수 좌표 (x, y), 한 변 size) 안을 쌍선형으로 읽어 out에 채운다. 소수부가 창 전체에서 같으므로
     * 가중치는 한 번만 계산. 창이 영상 안이면 직접 인덱싱, 가장자리에 걸치면 느린 경로.
     */
    fun window(src: FloatArray, x: Double, y: Double, size: Int, out: FloatArray) {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val w00 = (1 - fx) * (1 - fy)
        val w01 = fx * (1 - fy)
        val w10 = (1 - fx) * fy
        val w11 = fx * fy
        if (x0 >= 0 && y0 >= 0 && x0 + size < w && y0 + size < h) {
            var j = 0
            for (dy in 0 until size) {
                var i = (y0 + dy) * w + x0
                for (dx in 0 until size) {
                    out[j++] = src[i] * w00 + src[i + 1] * w01 + src[i + w] * w10 + src[i + w + 1] * w11
                    i++
                }
            }
        } else {
            var j = 0
            for (dy in 0 until size) for (dx in 0 until size) {
                val xa = (x0 + dx).coerceIn(0, w - 1)
                val xb = (x0 + dx + 1).coerceIn(0, w - 1)
                val ya = (y0 + dy).coerceIn(0, h - 1) * w
                val yb = (y0 + dy + 1).coerceIn(0, h - 1) * w
                out[j++] = src[ya + xa] * w00 + src[ya + xb] * w01 + src[yb + xa] * w10 + src[yb + xb] * w11
            }
        }
    }
}

/** 한 단계 영상 + 기울기 */
private class Level(val img: GrayImage) {
    val gx = FloatArray(img.w * img.h)
    val gy = FloatArray(img.w * img.h)

    init {
        // Scharr(3,10,3)/32 — OpenCV calcOpticalFlowPyrLK와 같은 미분. 안쪽은 직접 인덱싱.
        val w = img.w
        val p = img.px
        for (y in 0 until img.h) for (x in 0 until w) {
            val i = y * w + x
            if (x in 1 until w - 1 && y in 1 until img.h - 1) {
                gx[i] = (3 * (p[i - w + 1] - p[i - w - 1]) + 10 * (p[i + 1] - p[i - 1]) + 3 * (p[i + w + 1] - p[i + w - 1])) / 32f
                gy[i] = (3 * (p[i + w - 1] - p[i - w - 1]) + 10 * (p[i + w] - p[i - w]) + 3 * (p[i + w + 1] - p[i - w + 1])) / 32f
            } else {
                gx[i] = (3 * (img[x + 1, y - 1] - img[x - 1, y - 1]) + 10 * (img[x + 1, y] - img[x - 1, y]) +
                    3 * (img[x + 1, y + 1] - img[x - 1, y + 1])) / 32f
                gy[i] = (3 * (img[x - 1, y + 1] - img[x - 1, y - 1]) + 10 * (img[x, y + 1] - img[x, y - 1]) +
                    3 * (img[x + 1, y + 1] - img[x + 1, y - 1])) / 32f
            }
        }
    }
}

private class Pyramid(img: GrayImage, levels: Int) {
    val levels: List<Level> = generateSequence(img) { it.half() }.take(levels + 1).map(::Level).toList()
}

/**
 * 몸 박스 안의 특징점(Shi–Tomasi)을 피라미드 Lucas–Kanade로 프레임마다 따라가
 * '몸이 얼마나 움직였는지'(살아남은 점 이동량의 중앙값)를 준다. 앞뒤 확인(forward-backward)으로 튄 점은 버린다.
 * prototype/tracking/flow.py(OpenCV)와 같은 방법을 순수 Kotlin으로 구현 — 앱에 OpenCV를 넣지 않기 위해.
 */
class FlowTracker(
    private val maxPoints: Int = 60,
    private val pyramidLevels: Int = 3,
    private val halfWin: Int = 10, // 21x21 창
    private val maxIter: Int = 30, // OpenCV 기본(30회, 0.01px)과 같게
    private val fbTol: Double = 1.0,
    private val minPoints: Int = 5,
) {
    private var prev: Pyramid? = null
    private var start = DoubleArray(0) // x0,y0,x1,y1,... 시작 위치
    private var cur = DoubleArray(0)
    private var alive = BooleanArray(0)

    /** 박스(픽셀 좌표)의 안쪽(테두리 15% 제외 — 배경 벽이 섞이지 않게)에서 점을 고른다. 점이 너무 적으면 false. */
    fun seed(img: GrayImage, box: Box): Boolean {
        val ix = (box.x1 - box.x0) * 0.15
        val iy = (box.y1 - box.y0) * 0.15
        val pts = goodFeatures(img, (box.x0 + ix).toInt(), (box.y0 + iy).toInt(), (box.x1 - ix).toInt(), (box.y1 - iy).toInt())
        prev = Pyramid(img, pyramidLevels)
        start = pts
        cur = pts.copyOf()
        alive = BooleanArray(pts.size / 2) { true }
        return alive.size >= minPoints
    }

    /** 주어진 점(x0,y0,x1,y1,...)으로 시작 — 검증용(OpenCV와 같은 시작점으로 비교). */
    internal fun seedPoints(img: GrayImage, pts: DoubleArray) {
        prev = Pyramid(img, pyramidLevels)
        start = pts.copyOf()
        cur = pts.copyOf()
        alive = BooleanArray(pts.size / 2) { true }
    }

    /** 검증용: a→b 한 번 추적한 각 점의 이동량(실패 시 NaN) — OpenCV와 점별 비교에 쓴다 */
    internal fun probeOneStep(a: GrayImage, b: GrayImage, pts: DoubleArray): List<DoubleArray> {
        val pa = Pyramid(a, pyramidLevels)
        val pb = Pyramid(b, pyramidLevels)
        return (0 until pts.size / 2).map { k ->
            track(pa, pb, pts[2 * k], pts[2 * k + 1])?.let { doubleArrayOf(it[0] - pts[2 * k], it[1] - pts[2 * k + 1]) }
                ?: doubleArrayOf(Double.NaN, Double.NaN)
        }
    }

    /** 다음 프레임으로 한 걸음. 반환: 시작 이후 누적 이동량(px) 또는 놓쳤으면 null(이후로도 null). */
    fun step(next: GrayImage): DoubleArray? {
        val p = prev ?: return null
        if (alive.count { it } < minPoints) return null
        val np = Pyramid(next, pyramidLevels)
        val moved = cur.copyOf()
        for (k in alive.indices) {
            if (!alive[k]) continue
            val fwd = track(p, np, cur[2 * k], cur[2 * k + 1])
            val back = fwd?.let { track(np, p, it[0], it[1]) }
            if (fwd == null || back == null || hypot(back[0] - cur[2 * k], back[1] - cur[2 * k + 1]) >= fbTol) {
                alive[k] = false
                continue
            }
            moved[2 * k] = fwd[0]
            moved[2 * k + 1] = fwd[1]
        }
        prev = np
        cur = moved
        dropWallPoints()
        if (alive.count { it } < minPoints) return null
        val dx = alive.indices.filter { alive[it] }.map { cur[2 * it] - start[2 * it] }
        val dy = alive.indices.filter { alive[it] }.map { cur[2 * it + 1] - start[2 * it + 1] }
        return doubleArrayOf(median(dx), median(dy))
    }

    private val side = 2 * halfWin + 1
    private val winI = FloatArray(side * side)
    private val winGx = FloatArray(side * side)
    private val winGy = FloatArray(side * side)
    private val winJ = FloatArray(side * side)

    /** 한 점을 a→b로 추적(거친 단계부터). 실패하면 null. */
    private fun track(a: Pyramid, b: Pyramid, x: Double, y: Double): DoubleArray? {
        var gx = 0.0
        var gy = 0.0
        val n = side * side
        for (lv in pyramidLevels downTo 0) {
            val s = 1.0 / (1 shl lv)
            val la = a.levels[lv]
            val lb = b.levels[lv]
            val px = x * s - halfWin // 창 왼쪽 위
            val py = y * s - halfWin
            // 창 안의 밝기·기울기(이전 영상) — 반복 동안 고정
            la.img.window(la.img.px, px, py, side, winI)
            la.img.window(la.gx, px, py, side, winGx)
            la.img.window(la.gy, px, py, side, winGy)
            var gxx = 0.0
            var gxy = 0.0
            var gyy = 0.0
            for (j in 0 until n) {
                val ix = winGx[j]
                val iy = winGy[j]
                gxx += ix * ix
                gxy += ix * iy
                gyy += iy * iy
            }
            val det = gxx * gyy - gxy * gxy
            val minEig = (gxx + gyy - sqrt((gxx - gyy) * (gxx - gyy) + 4 * gxy * gxy)) / 2 / n
            if (minEig < MIN_EIG || abs(det) < 1e-9) {
                // OpenCV와 같게: 무늬가 부족하면 마지막(원본) 단계에서만 실패, 거친 단계에선 추정값을 그대로 다음 단계로
                if (lv == 0) return null
                gx *= 2
                gy *= 2
                continue
            }
            var vx = 0.0
            var vy = 0.0
            for (it in 0 until maxIter) {
                lb.img.window(lb.img.px, px + gx + vx, py + gy + vy, side, winJ)
                var bx = 0.0
                var by = 0.0
                for (j in 0 until n) {
                    val diff = winI[j] - winJ[j]
                    bx += diff * winGx[j]
                    by += diff * winGy[j]
                }
                val ddx = (gyy * bx - gxy * by) / det
                val ddy = (gxx * by - gxy * bx) / det
                vx += ddx
                vy += ddy
                if (ddx * ddx + ddy * ddy < 0.01 * 0.01) break
            }
            if (lv > 0) {
                gx = 2 * (gx + vx)
                gy = 2 * (gy + vy)
            } else {
                gx += vx
                gy += vy
            }
        }
        val nx = x + gx
        val ny = y + gy
        if (nx < 0 || ny < 0 || nx > b.levels[0].img.w - 1 || ny > b.levels[0].img.h - 1) return null
        return doubleArrayOf(nx, ny)
    }

    /** Shi–Tomasi: 3x3 구조 텐서의 작은 고유값이 큰 점, 서로 4px 이상 떨어지게 최대 maxPoints개. */
    private fun goodFeatures(img: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray {
        val bx0 = x0.coerceIn(1, img.w - 2)
        val by0 = y0.coerceIn(1, img.h - 2)
        val bx1 = x1.coerceIn(1, img.w - 2)
        val by1 = y1.coerceIn(1, img.h - 2)
        if (bx1 - bx0 < 3 || by1 - by0 < 3) return DoubleArray(0)
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        val score = FloatArray(bw * bh)
        for (y in by0..by1) for (x in bx0..bx1) {
            var sxx = 0f
            var sxy = 0f
            var syy = 0f
            for (v in -1..1) for (u in -1..1) {
                val gx = (img[x + u + 1, y + v] - img[x + u - 1, y + v]) / 2f
                val gy = (img[x + u, y + v + 1] - img[x + u, y + v - 1]) / 2f
                sxx += gx * gx
                sxy += gx * gy
                syy += gy * gy
            }
            score[(y - by0) * bw + (x - bx0)] = ((sxx + syy - sqrt((sxx - syy) * (sxx - syy) + 4 * sxy * sxy)) / 2)
        }
        val best = score.maxOrNull() ?: return DoubleArray(0)
        if (best <= 0f) return DoubleArray(0)
        val thr = best * 0.01f
        val cands = ArrayList<Int>()
        for (j in score.indices) {
            val s = score[j]
            if (s < thr) continue
            val x = j % bw
            val y = j / bw
            var isMax = true
            loop@ for (v in -1..1) for (u in -1..1) {
                val xx = x + u
                val yy = y + v
                if ((u != 0 || v != 0) && xx in 0 until bw && yy in 0 until bh && score[yy * bw + xx] > s) {
                    isMax = false
                    break@loop
                }
            }
            if (isMax) cands += j
        }
        cands.sortByDescending { score[it] }
        val picked = ArrayList<Pair<Int, Int>>()
        for (j in cands) {
            val x = j % bw + bx0
            val y = j / bw + by0
            if (picked.any { (px, py) -> (px - x) * (px - x) + (py - y) * (py - y) < MIN_DIST * MIN_DIST }) continue
            picked += x to y
            if (picked.size >= maxPoints) break
        }
        return DoubleArray(picked.size * 2) { if (it % 2 == 0) picked[it / 2].first.toDouble() else picked[it / 2].second.toDouble() }
    }

    /**
     * 고정 카메라에서 벽(배경)은 움직이지 않는다. 몸 박스에서 고른 점 중 일부가 뚜렷이 움직이기 시작하면(등반자),
     * 시작 위치에 그대로인 점(벽)은 버린다 — 박스 대부분이 벽이면 중앙값이 0에 붙어 카메라가 멈추던 문제.
     * 모두 그대로면(등반자가 쉬는 중) 아무것도 버리지 않는다.
     */
    private fun dropWallPoints() {
        val live = alive.indices.filter { alive[it] }
        fun shift(k: Int) = hypot(cur[2 * k] - start[2 * k], cur[2 * k + 1] - start[2 * k + 1])
        val moving = live.map(::shift).filter { it >= MOVING_PX }
        if (moving.size < minPoints || moving.size < live.size * MOVING_FRACTION) return
        // 움직이는 점들보다 한참 덜 움직인 점(벽, 등반자 가장자리에 살짝 끌린 벽 포함)
        val still = maxOf(STILL_PX, median(moving) * STILL_RATIO)
        val drop = live.filter { shift(it) < still }
        if (live.size - drop.size < minPoints) return // 버리고 나면 너무 적으면 그대로(추적이 끊기지 않게)
        for (k in drop) alive[k] = false
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private companion object {
        // OpenCV minEigThreshold(1e-4)는 Scharr 미분(÷32 안 함)·2^-20 스케일 기준 — 여기 단위(Scharr/32, 밝기 0..255)로 환산하면 1e-4 × 1024
        const val MIN_EIG = 1e-4 * 1024
        const val MIN_DIST = 4
        private const val MOVING_PX = 2.0 // 시작 이후 이만큼 움직였으면 '움직이는 점'
        private const val STILL_PX = 0.5 // 이보다 덜 움직였으면 벽(제자리)
        private const val STILL_RATIO = 0.3 // 움직이는 점들의 이동량 중앙값 대비 이보다 덜 움직였으면 벽
        private const val MOVING_FRACTION = 0.1 // 살아 있는 점 중 이 비율 이상이 움직여야 벽 점을 버린다
    }
}
