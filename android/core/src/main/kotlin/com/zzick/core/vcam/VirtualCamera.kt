package com.zzick.core.vcam

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 가상 카메라 파라미터. 비율 값은 크롭 높이 대비.
 * 기본값은 핸드헬드 레퍼런스 분석에서 승인된 값. (prototype/vcam/camera.py)
 */
data class CameraParams(
    val aspect: Double = 9.0 / 16.0, // 출력 폭/높이
    val outHeight: Int = 1920,
    val zoom: Double = 1.3, // 가장 넓은 크롭 높이 / 크롭 높이
    val maxUpscale: Double = 2.0, // 출력 높이 / 크롭 높이 상한
    val headroom: Double = 0.12, // 카메라를 몸통보다 이만큼 위에 겨눔
    val deadzoneX: Double = 0.02,
    val deadzoneY: Double = 0.18,
    val omega: Double = 8.0,
    val zeta: Double = 0.9,
    val delayS: Double = 0.1,
    val leadS: Double = 0.3, // 앞으로 갈 곳을 이만큼 미리 겨눔 — 반응 지연+스프링의 뒤처짐(약 0.3초)을 상쇄(오프라인이라 가능)
    val driftX: Double = 0.03, // 느린 프레이밍 드리프트 RMS
    val driftY: Double = 0.02,
    val driftHz: Double = 0.1,
    val driftCalmSpeed: Double = 0.25, // 몸통 속도(프레임 높이/초)가 빠를수록 드리프트를 줄여 이 속도 이상이면 뺀다(절반 속도면 절반). 0이면 끔
    val shakeGain: Double = 1.0, // 0이면 짐벌 느낌
    val shakeX: Double = 0.0007, // 손떨림 RMS (gain 1 기준)
    val shakeY: Double = 0.0003,
    val shakeRollDeg: Double = 0.017,
    val shakeHz: Double = 1.2,
    val fitMargin: Double = 0.04, // 자동 줌: 몸 전체 박스 바깥 여유
    val zoomLeadS: Double = 0.3, // 자동 줌: 필요 시점보다 최대 2배 이 시간만큼 먼저 줌아웃
    val motionZoom: Boolean = true, // 동작에 따른 줌: 크럭스(한자리 오래)엔 타이트, 큰 동작(다이노·추락)엔 넓게
    val cruxZoom: Double = 1.12,
    val cruxWindowS: Double = 3.0, // 이 시간 동안
    val cruxRange: Double = 0.06, // 몸통이 이만큼(프레임 높이 비율) 이하로만 움직이면 크럭스
    val cruxMinS: Double = 3.0, // 크럭스가 이만큼 이어질 때만 타이트(짧게 멈출 때마다 줌이 들썩이지 않게)
    val moveSpeed: Double = 0.3, // 몸통 속도(프레임 높이/초)가 이보다 빠르면 큰 동작
    val moveZoom: Double = 0.85,
    val moveHoldS: Double = 0.6, // 큰 동작 판정 유지 시간
    val speedSmoothS: Double = 0.3, // 속도 계산 전 몸통 위치 평활
    val seed: Int = 0,
)

/** 등반자 트랙 → 프레임별 크롭 사각형. UI·영상·ML에 의존하지 않는 순수 계산. */
object VirtualCamera {
    /** (크롭 폭, 크롭 높이). zoom은 '이 비율로 원본에서 가장 넓게 잡은 크롭' 대비 배율이고 확대 상한(maxUpscale)이 우선. */
    fun cropSize(srcW: Double, srcH: Double, p: CameraParams): Pair<Double, Double> {
        val widest = min(srcH, srcW / p.aspect)
        val ch = min(max(widest / p.zoom, p.outHeight / p.maxUpscale), widest)
        return ch * p.aspect to ch
    }

    /** 출력 해상도(폭, 높이). 인코더 호환을 위해 폭은 짝수. */
    fun outputSize(p: CameraParams): Pair<Int, Int> = (p.outHeight * p.aspect / 2).roundToInt() * 2 to p.outHeight

    /**
     * @param track (n,2) 등반자 몸통 중심(원본 px, 결측 없음)
     * @param body (n,4) 몸 전체 박스 x0,y0,x1,y1(원본 px). 주면 몸이 잘리지 않게 자동 줌아웃.
     * @return (n,5) cx, cy, w, h, rotDeg
     */
    fun solvePath(
        track: Array<DoubleArray>,
        fps: Double,
        srcW: Double,
        srcH: Double,
        p: CameraParams = CameraParams(),
        body: Array<DoubleArray>? = null,
    ): Array<DoubleArray> {
        require(track.size >= 2 && track.all { it[0].isFinite() && it[1].isFinite() }) {
            "track은 2프레임 이상이고 결측(NaN) 없이 채워져 있어야 합니다"
        }
        val ch0 = cropSize(srcW, srcH, p).second
        val base = if (p.motionZoom) motionHeights(track, fps, srcW, srcH, p, ch0) else DoubleArray(track.size) { ch0 }
        var ch = if (p.motionZoom) smoothHeights(base, fps, p) else base
        var path = solve(track, fps, srcW, srcH, p, ch, ch0)
        if (body != null) {
            ch = fitHeights(path, body, fps, srcW, srcH, p, base)
            path = solve(track, fps, srcW, srcH, p, ch, ch0)
        }
        return path
    }

    /** 몸통 움직임으로 프레임별 목표 크롭 높이: 크럭스면 ch0/cruxZoom(타이트), 큰 동작이면 ch0/moveZoom(넓게). */
    internal fun motionHeights(track: Array<DoubleArray>, fps: Double, srcW: Double, srcH: Double, p: CameraParams, ch0: Double): DoubleArray {
        val n = track.size
        val k = max(1, Math.rint(p.speedSmoothS * fps).toInt())
        val sx = uniform(DoubleArray(n) { track[it][0] / srcH }, k)
        val sy = uniform(DoubleArray(n) { track[it][1] / srcH }, k)
        val gx = gradient(sx)
        val gy = gradient(sy)
        val speed = DoubleArray(n) { hypot(gx[it], gy[it]) * fps }
        val hold = slide(speed, max(1, Math.rint(p.moveHoldS * fps).toInt()), ::max)
        val big = BooleanArray(n) { hold[it] > p.moveSpeed }
        val w = max(1, Math.rint(p.cruxWindowS * fps).toInt())
        val maxX = slide(sx, w, ::max)
        val minX = slide(sx, w, ::min)
        val maxY = slide(sy, w, ::max)
        val minY = slide(sy, w, ::min)
        val crux = dropShortRuns(
            BooleanArray(n) { max(maxX[it] - minX[it], maxY[it] - minY[it]) < p.cruxRange && !big[it] },
            Math.rint(p.cruxMinS * fps).toInt(),
        )
        val widest = min(srcH, srcW / p.aspect)
        val lo = min(p.outHeight / p.maxUpscale, widest)
        return DoubleArray(n) {
            val mult = if (big[it]) p.moveZoom else if (crux[it]) p.cruxZoom else 1.0
            (ch0 / mult).coerceIn(lo, widest)
        }
    }

    /** 넓어질 땐 미리(최대 필터), 좁아질 땐 늦게, 그리고 부드럽게(이동평균 두 번). */
    private fun smoothHeights(target: DoubleArray, fps: Double, p: CameraParams): DoubleArray {
        val r = max(1, Math.rint(p.zoomLeadS * fps).toInt()) // Python round와 같은 짝수 반올림
        var ch = maxFilter(target, 2 * r)
        repeat(2) { ch = meanFilter(ch, r) }
        return ch
    }

    /** 연속 true 구간 중 minLen보다 짧은 것은 false로. */
    private fun dropShortRuns(mask: BooleanArray, minLen: Int): BooleanArray {
        val out = mask.copyOf()
        var i = 0
        while (i < mask.size) {
            if (!mask[i]) {
                i++
                continue
            }
            var j = i
            while (j < mask.size && mask[j]) j++
            if (j - i < minLen) for (k in i until j) out[k] = false
            i = j
        }
        return out
    }

    /** numpy.gradient: 안쪽은 중앙 차분, 양 끝은 한쪽 차분 */
    private fun gradient(x: DoubleArray) = DoubleArray(x.size) { i ->
        when {
            x.size < 2 -> 0.0
            i == 0 -> x[1] - x[0]
            i == x.size - 1 -> x[i] - x[i - 1]
            else -> (x[i + 1] - x[i - 1]) / 2
        }
    }

    /** scipy *_filter1d(size, mode="nearest", origin=0) 창: [i - size/2, i + (size-1)/2] (짝수 크기도 같은 규칙) */
    private inline fun slide(x: DoubleArray, size: Int, op: (Double, Double) -> Double) = DoubleArray(x.size) { i ->
        var acc = x[(i - size / 2).coerceIn(0, x.size - 1)]
        for (k in i - size / 2 + 1..i + (size - 1) / 2) acc = op(acc, x[k.coerceIn(0, x.size - 1)])
        acc
    }

    private fun uniform(x: DoubleArray, size: Int) = DoubleArray(x.size) { i ->
        var s = 0.0
        for (k in i - size / 2..i + (size - 1) / 2) s += x[k.coerceIn(0, x.size - 1)]
        s / size
    }

    /**
     * 몸 박스를 담는 데 필요한 크롭 높이(최소 base — 동작 줌 목표) → 미리 줌아웃·유지·부드럽게 복귀하는 높이 곡선.
     * max 필터 반경(2r)이 이동평균 두 번의 총 반경(2r) 이상이라 결과는 항상 필요값 이상.
     */
    private fun fitHeights(
        path: Array<DoubleArray>, body: Array<DoubleArray>, fps: Double,
        srcW: Double, srcH: Double, p: CameraParams, base: DoubleArray,
    ): DoubleArray {
        val m = 1 + 2 * p.fitMargin
        val limit = min(srcH, srcW / p.aspect)
        val need = DoubleArray(path.size) { i ->
            val (cx, cy) = path[i]
            val b = body[i]
            val needH = 2 * max(cy - b[1], b[3] - cy) * m
            val needW = 2 * max(cx - b[0], b[2] - cx) * m
            min(max(max(needH, needW / p.aspect), base[i]), limit) // 불가능한 요구는 먼저 자른다(급경사 방지)
        }
        return smoothHeights(need, fps, p)
    }

    /**
     * 프레임별 드리프트 배율(0..1, null이면 1): 빠르게 움직일수록 줄여 가운데를 지킨다.
     * 움직이기 조금 전(미리 겨누는 만큼)부터 줄이고 1초에 걸쳐 부드럽게.
     */
    private fun driftScale(track: Array<DoubleArray>, fps: Double, srcH: Double, p: CameraParams): DoubleArray? {
        if (p.driftCalmSpeed <= 0) return null
        val n = track.size
        val k = max(1, Math.rint(p.speedSmoothS * fps).toInt())
        val gx = gradient(uniform(DoubleArray(n) { track[it][0] / srcH }, k))
        val gy = gradient(uniform(DoubleArray(n) { track[it][1] / srcH }, k))
        val scale = DoubleArray(n) { (1 - hypot(gx[it], gy[it]) * fps / p.driftCalmSpeed).coerceIn(0.0, 1.0) }
        val early = slide(scale, 2 * Math.rint(p.leadS * fps).toInt() + 1, ::min)
        return uniform(early, max(1, Math.rint(fps).toInt()))
    }

    private fun solve(
        track: Array<DoubleArray>, fps: Double, srcW: Double, srcH: Double,
        p: CameraParams, ch: DoubleArray, ch0: Double,
    ): Array<DoubleArray> {
        val n = track.size
        val tx = DoubleArray(n) { track[it][0] }
        val ty = DoubleArray(n) { track[it][1] - p.headroom * ch[it] }
        // 미리 겨누기는 지연을 그만큼 당기는 것과 같다(음수면 앞의 목표를 본다)
        val cx = Follow.simulate(tx, fps, p.omega, p.zeta, p.delayS - p.leadS, deadzone = p.deadzoneX * ch0)
        val cy = Follow.simulate(ty, fps, p.omega, p.zeta, p.delayS - p.leadS, deadzone = p.deadzoneY * ch0)

        fun addNoise(dst: DoubleArray, amp: Double, hz: Double, channel: Int, scale: DoubleArray? = null) {
            if (amp <= 0) return
            val nz = Noise.smooth(n, fps, hz, seed = p.seed * 16L + channel)
            for (i in 0 until n) dst[i] += (scale?.get(i) ?: 1.0) * amp * ch[i] * nz[i]
        }
        val calm = driftScale(track, fps, srcH, p)
        addNoise(cx, p.driftX, p.driftHz, 0, calm)
        addNoise(cx, p.shakeGain * p.shakeX, p.shakeHz, 2)
        addNoise(cy, p.driftY, p.driftHz, 1, calm)
        addNoise(cy, p.shakeGain * p.shakeY, p.shakeHz, 3)
        val rollAmp = p.shakeGain * p.shakeRollDeg
        val roll = if (rollAmp > 0) Noise.smooth(n, fps, p.shakeHz, seed = p.seed * 16L + 4) else null

        return Array(n) { i ->
            val chi = ch[i]
            val cw = chi * p.aspect
            // 회전해도 원본 안에 들어가도록: sinθ ≤ 남는 여유 / 반대 변 (cosθ ≤ 1이므로 보수적)
            val room = min((srcW - cw) / chi, (srcH - chi) / cw)
            val maxRot = Math.toDegrees(asin(room.coerceIn(0.0, 1.0)))
            val rot = if (roll == null) 0.0 else (rollAmp * roll[i]).coerceIn(-maxRot, maxRot)
            val t = Math.toRadians(abs(rot))
            val bw = (cw * cos(t) + chi * sin(t)) / 2
            val bh = (cw * sin(t) + chi * cos(t)) / 2
            doubleArrayOf(clip(cx[i], bw, srcW - bw), clip(cy[i], bh, srcH - bh), cw, chi, rot)
        }
    }

    /** numpy.clip 과 같은 의미: min(max(v, lo), hi) */
    private fun clip(v: Double, lo: Double, hi: Double) = min(max(v, lo), hi)

    /** scipy maximum_filter1d(mode="nearest"), 반경 radius */
    private fun maxFilter(x: DoubleArray, radius: Int) = DoubleArray(x.size) { i ->
        var m = Double.NEGATIVE_INFINITY
        for (k in i - radius..i + radius) m = max(m, x[k.coerceIn(0, x.size - 1)])
        m
    }

    /** scipy uniform_filter1d(mode="nearest"), 반경 radius */
    private fun meanFilter(x: DoubleArray, radius: Int) = DoubleArray(x.size) { i ->
        var s = 0.0
        for (k in i - radius..i + radius) s += x[k.coerceIn(0, x.size - 1)]
        s / (2 * radius + 1)
    }
}
