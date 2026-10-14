package com.zzick.core.tracking

import kotlin.math.hypot

/** 사람 검출 박스 (높이 정규화 좌표: 프레임 높이 = 1, x도 높이 단위). */
data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
    val height get() = y1 - y0
    val center get() = cx to cy
}

/** 탭 위치(x, y)에 가장 알맞은 박스: 박스 안이면 거리 0, 밖이면 가장 가까운 가장자리까지 거리. 없으면 null. */
fun pickBox(boxes: List<Box>, x: Double, y: Double): Int? = boxes.withIndex().minByOrNull { (_, b) ->
    val dx = x - x.coerceIn(b.x0, b.x1)
    val dy = y - y.coerceIn(b.y0, b.y1)
    dx * dx + dy * dy
}?.index

/**
 * 등반자 고르기 화면에 쓸 만한 프레임인지: 등반자 크기(높이 0.15 이상)의 사람 박스가 있는가.
 * 멀리 있는 작은 사람·홀드 덩어리만 보이는 프레임을 건너뛰려고(점검: 20260821_155813, 20260607_151150).
 */
fun showsClimber(boxes: List<Box>): Boolean = boxes.any { it.height >= MIN_CLIMBER_HEIGHT }

private const val MIN_CLIMBER_HEIGHT = 0.15

class FusedTrack(val track: Array<DoubleArray>, val source: IntArray)

/**
 * 포즈 몸통 중심 + 사람 검출 박스 → 끊김 없는 등반자 트랙. (prototype/tracking/fuse.py)
 * 오프라인이므로 빈 구간의 예상 위치는 앞뒤 포즈 사이 보간으로 구하고, 그 근처 박스만 등반자로 인정한다.
 */
object Fuse {
    const val POSE = 0
    const val BOX = 1
    const val INTERP = 2
    const val HOLD = 3
    const val FLOW = 4

    /** (박스 중심 y - 몸통 y) / 박스 높이 의 중앙값. 포즈와 박스가 겹치는 프레임으로 보정. */
    fun boxOffset(pose: Array<DoubleArray>, boxes: List<List<Box>>): Double {
        val offs = ArrayList<Double>()
        for (i in pose.indices) {
            val (px, py) = pose[i]
            if (px.isNaN() || boxes[i].isEmpty()) continue
            val inside = boxes[i].filter { it.x0 <= px && px <= it.x1 && it.y0 <= py && py <= it.y1 }
            val b = inside.minByOrNull { hypot(it.cx - px, it.cy - py) } ?: continue
            offs.add((b.cy - py) / b.height)
        }
        return median(offs)
    }

    /**
     * @param flow 포즈 공백 동안 광학 흐름으로 따라간 위치(프레임별, 없으면 NaN)
     * @param flowSeed 그 흐름을 시작한 포즈 프레임(-1: 없음). 시작 포즈가 이상값으로 버려지면 그 흐름도 쓰지 않는다.
     */
    fun fuseTrack(
        pose: Array<DoubleArray>,
        boxes: List<List<Box>>,
        fps: Double = 30.0,
        gate: Double = 0.15,
        maxSpeed: Double = 3.0,
        jumpBase: Double = 0.05,
        flow: Array<DoubleArray>? = null,
        flowSeed: IntArray? = null,
    ): FusedTrack {
        val p = rejectOutliers(rejectJumps(pose, fps, maxSpeed, jumpBase), fps)
        val n = p.size
        val ok = BooleanArray(n) { !p[it][0].isNaN() }
        if (ok.none { it }) return FusedTrack(Array(n) { doubleArrayOf(Double.NaN, Double.NaN) }, IntArray(n) { HOLD })
        val first = ok.indexOfFirst { it }
        val last = ok.indexOfLast { it }

        val track = Array(n) { p[it].copyOf() }
        val source = IntArray(n) { if (ok[it]) POSE else INTERP }
        if (flow != null && flowSeed != null) fillFlow(p, ok, flow, flowSeed, track, source)
        val predicted = interpColumns(track)
        val off = boxOffset(p, boxes)
        for (i in first + 1 until last) {
            if (ok[i] || source[i] == FLOW || boxes[i].isEmpty()) continue
            val (ex, ey) = predicted[i]
            val best = boxes[i]
                .map { doubleArrayOf(it.cx, it.cy - off * it.height) }
                .minBy { hypot(it[0] - ex, it[1] - ey) }
            if (hypot(best[0] - ex, best[1] - ey) <= gate) {
                track[i] = best
                source[i] = BOX
            }
        }
        for (i in 0 until first) source[i] = HOLD
        for (i in last + 1 until n) source[i] = HOLD
        return FusedTrack(interpColumns(track), source)
    }

    /**
     * 포즈 공백(a, b) 안에서 a의 포즈로 시작한 흐름 위치를 쓴다. 흐름이 b 직전까지 이어지면 한 걸음 외삽한 b 위치가
     * b의 포즈와 맞도록 어긋남을 선형으로 나눠 보정, 중간에 놓치면 거기까지만 쓴다. (prototype/tracking/fuse.py _fill_flow)
     */
    private fun fillFlow(
        pose: Array<DoubleArray>, ok: BooleanArray, flow: Array<DoubleArray>, seed: IntArray,
        track: Array<DoubleArray>, source: IntArray,
    ) {
        val idx = ok.indices.filter { ok[it] }
        for ((a, b) in idx.zipWithNext()) {
            var c = a
            while (c + 1 < b && seed[c + 1] == a && flow[c + 1][0].isFinite() && flow[c + 1][1].isFinite()) c++
            if (c == a) continue
            var ex = 0.0
            var ey = 0.0
            if (c == b - 1) {
                val prev = if (c - 1 > a) flow[c - 1] else pose[a]
                ex = pose[b][0] - (flow[c][0] + (flow[c][0] - prev[0]))
                ey = pose[b][1] - (flow[c][1] + (flow[c][1] - prev[1]))
            }
            for (k in a + 1..c) {
                val w = (k - a).toDouble() / (b - a)
                track[k] = doubleArrayOf(flow[k][0] + ex * w, flow[k][1] + ey * w)
                source[k] = FLOW
            }
        }
    }

    /**
     * 몸 전체 박스(n,4: x0,y0,x1,y1, 결측 NaN)의 빈 프레임을 몸통 기준 상대 오프셋 보간으로 채운다.
     * source를 주면 채택된 포즈(POSE) 프레임의 박스만 쓴다 — 버린 포즈(다른 사람)의 박스 제외.
     */
    fun bodyBoxes(raw: Array<DoubleArray>, track: Array<DoubleArray>, source: IntArray? = null): Array<DoubleArray> {
        val n = raw.size
        val rel = Array(n) { i ->
            val (tx, ty) = track[i]
            if (source != null && source[i] != POSE) DoubleArray(4) { Double.NaN }
            else doubleArrayOf(raw[i][0] - tx, raw[i][1] - ty, raw[i][2] - tx, raw[i][3] - ty)
        }
        if (rel.all { it[0].isNaN() }) return Array(n) { DoubleArray(4) { Double.NaN } }
        val filled = interpColumns(rel)
        return Array(n) { i ->
            val (tx, ty) = track[i]
            doubleArrayOf(tx + filled[i][0], ty + filled[i][1], tx + filled[i][2], ty + filled[i][3])
        }
    }

    /** 직전 채택 위치에서 물리적으로 불가능하게 튄 포즈(다른 사람)를 결측 처리. */
    private fun rejectJumps(pose: Array<DoubleArray>, fps: Double, maxSpeed: Double, base: Double): Array<DoubleArray> {
        val out = Array(pose.size) { pose[it].copyOf() }
        var last: DoubleArray? = null
        var lastI = 0
        for (i in pose.indices) {
            val q = pose[i]
            if (q[0].isNaN()) continue
            val l = last
            if (l != null && hypot(q[0] - l[0], q[1] - l[1]) > base + maxSpeed * (i - lastI) / fps) {
                out[i] = doubleArrayOf(Double.NaN, Double.NaN)
                continue
            }
            last = q
            lastI = i
        }
        return out
    }

    /**
     * 앞뒤 windowS 안의 다른 포즈들로 직선(시간→위치)을 맞추고, 그 직선에서 혼자 크게 벗어난 포즈를 결측 처리.
     * 그네·스윙 중 홀드 위에 잠깐 생기는 가짜 포즈처럼 '짧게 튀었다 돌아오는' 값을 잡는다.
     * 실제 빠른 이동(다이노·추락)은 주변 포즈들도 함께 움직여 직선에 잘 맞으므로 남는다.
     * 앞뒤 양쪽에 이웃이 있을 때만 판단한다 — 한쪽만으로 외삽하면 등반 끝의 뛰어내림을 오판한다.
     * 포즈가 드문드문해 한쪽 이웃이 windowS 안에 부족하면, 그쪽은 maxGapS 안의 가장 가까운 extendEachSide개로 넓히고
     * 직선 대신 '왕복' 여부로 판단한다: 앞뒤 위치가 서로 일치(agreeTol 이내)하는데 혼자 둘 다에서 멀면 가짜.
     * 앞뒤가 다르면 실제 이동(편도)이므로 남긴다. 버린 포즈를 빼고 최대 3번 반복한다.
     * (prototype/tracking/fuse.py _reject_outliers 와 같은 계산)
     */
    private fun rejectOutliers(pose: Array<DoubleArray>, fps: Double, passes: Int = 3): Array<DoubleArray> {
        // 버린 포즈는 다음 판단의 이웃에서 빼고, 더 버릴 게 없을 때까지 반복(가짜 둘이 서로를 이웃으로 삼아 살아남지 않게)
        var out = pose
        for (pass in 0 until passes) {
            val before = out.count { it[0].isNaN() }
            out = rejectOutliersPass(out, fps)
            if (out.count { it[0].isNaN() } == before) break
        }
        return out
    }

    private fun rejectOutliersPass(
        pose: Array<DoubleArray>, fps: Double,
        windowS: Double = 0.6, k: Double = 4.0, minDev: Double = 0.1, minNeighbors: Int = 4, minEachSide: Int = 2,
        maxGapS: Double = 3.0, extendEachSide: Int = 3, agreeTol: Double = 0.15,
    ): Array<DoubleArray> {
        val out = Array(pose.size) { pose[it].copyOf() }
        val ok = pose.indices.filter { !pose[it][0].isNaN() }
        val w = Math.rint(windowS * fps).toInt()
        val g = Math.rint(maxGapS * fps).toInt()
        for (i in ok) {
            var before = ok.filter { it < i && it >= i - w }
            var after = ok.filter { it > i && it <= i + w }
            if (before.size < minEachSide || after.size < minEachSide) {
                before = ok.filter { it < i && it >= i - g }.takeLast(extendEachSide)
                after = ok.filter { it > i && it <= i + g }.take(extendEachSide)
                if (before.isEmpty() || after.isEmpty()) continue // 드문 구간에선 양쪽에 하나씩만 있어도 왕복 판단
                val mb = medianPos(before, pose)
                val ma = medianPos(after, pose)
                val p = pose[i]
                if (hypot(mb[0] - ma[0], mb[1] - ma[1]) <= agreeTol &&
                    hypot(p[0] - mb[0], p[1] - mb[1]) > agreeTol && hypot(p[0] - ma[0], p[1] - ma[1]) > agreeTol
                ) out[i] = doubleArrayOf(Double.NaN, Double.NaN)
                continue
            }
            val nb = before + after
            if (nb.size < minNeighbors) continue
            var keep = BooleanArray(nb.size) { true }
            var fit = DoubleArray(4) // ax, bx, ay, by
            var mad = 0.0
            for (iter in 0 until 2) { // 이웃 중의 이상값을 한 번 걸러내고 다시 맞춘다
                fit = fitLine(nb, i, pose, keep)
                val resid = DoubleArray(nb.size) { j ->
                    val t = (nb[j] - i).toDouble()
                    hypot(pose[nb[j]][0] - (fit[0] + fit[1] * t), pose[nb[j]][1] - (fit[2] + fit[3] * t))
                }
                mad = median(resid.filterIndexed { j, _ -> keep[j] })
                val thr = maxOf(k * mad, minDev)
                keep = BooleanArray(nb.size) { resid[it] <= thr }
                if (keep.count { it } < minNeighbors) break
            }
            if (hypot(pose[i][0] - fit[0], pose[i][1] - fit[2]) > maxOf(k * mad, minDev)) out[i] = doubleArrayOf(Double.NaN, Double.NaN)
        }
        return out
    }

    /** 축별 중앙값 위치(numpy.median(axis=0)) */
    private fun medianPos(idx: List<Int>, pose: Array<DoubleArray>) =
        doubleArrayOf(median(idx.map { pose[it][0] }), median(idx.map { pose[it][1] }))

    /** 이웃(keep)으로 x(t), y(t) 각각 최소제곱 직선. t는 i 기준 상대 프레임. 반환 (ax, bx, ay, by) */
    private fun fitLine(nb: List<Int>, i: Int, pose: Array<DoubleArray>, keep: BooleanArray): DoubleArray {
        var n = 0.0; var st = 0.0; var stt = 0.0; var sx = 0.0; var stx = 0.0; var sy = 0.0; var sty = 0.0
        for (j in nb.indices) {
            if (!keep[j]) continue
            val t = (nb[j] - i).toDouble()
            val (x, y) = pose[nb[j]]
            n += 1; st += t; stt += t * t; sx += x; stx += t * x; sy += y; sty += t * y
        }
        val det = n * stt - st * st
        val bx = (n * stx - st * sx) / det
        val by = (n * sty - st * sy) / det
        return doubleArrayOf((sx - bx * st) / n, bx, (sy - by * st) / n, by)
    }

    /** 열마다 NaN을 선형 보간으로 채움. 양 끝은 가장 가까운 값 유지(numpy.interp 의미). 첫 열 기준으로 결측 판단. */
    private fun interpColumns(a: Array<DoubleArray>): Array<DoubleArray> {
        val known = a.indices.filter { !a[it][0].isNaN() }
        val out = Array(a.size) { a[it].copyOf() }
        if (known.isEmpty()) return out
        var k = 0
        for (i in a.indices) {
            if (!a[i][0].isNaN()) continue
            while (k < known.size - 1 && known[k + 1] < i) k++
            val lo = known[k]
            when {
                i < known.first() -> out[i] = a[known.first()].copyOf()
                i > known.last() -> out[i] = a[known.last()].copyOf()
                else -> {
                    val hi = known[k + 1]
                    val f = (i - lo).toDouble() / (hi - lo)
                    out[i] = DoubleArray(a[i].size) { c -> a[lo][c] + (a[hi][c] - a[lo][c]) * f }
                }
            }
        }
        return out
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
