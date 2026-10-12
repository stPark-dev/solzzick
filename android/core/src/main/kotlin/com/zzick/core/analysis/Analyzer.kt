package com.zzick.core.analysis

import com.zzick.core.flow.FlowTracker
import com.zzick.core.flow.GrayImage
import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** MediaPipe 등 포즈 모델 한 명분 결과. 33개 관절, 좌표는 프레임 폭/높이 기준 0..1. */
class PoseCandidate(val x: FloatArray, val y: FloatArray, val visibility: FloatArray)

interface PoseEstimator<F> {
    fun estimate(frame: F, timestampMs: Long): List<PoseCandidate>
}

/** 사람 박스. 좌표는 프레임 폭/높이 기준 0..1. */
interface PersonDetector<F> {
    fun detect(frame: F): List<Box>
}

/** 사람 영역의 생김새(색 분포 등) 특징. box는 프레임 폭/높이 기준 0..1. 합이 1인 히스토그램을 권장. */
interface AppearanceExtractor<F> {
    fun describe(frame: F, box: Box): FloatArray?
}

/** 프레임 → 회색조 영상(광학 흐름용, 높이 360 정도 권장). */
interface GrayProvider<F> {
    fun gray(frame: F): GrayImage
}

/** 분석 결과(원본 px). path 계산과 렌더에 그대로 쓴다. */
class AnalysisResult(
    val track: Array<DoubleArray>,
    val body: Array<DoubleArray>,
    val source: IntArray,
    val ptsUs: LongArray,
)

/**
 * 분석 패스: 프레임을 한 장씩 받아 등반자 몸통·몸 박스를 모으고, 끝나면 Fuse로 끊김 없이 메운다.
 * - 포즈는 stride 프레임마다만 추정(나머지는 보간) — 카메라 반응이 느려 ~10fps면 충분.
 * - 사람 검출기는 포즈가 빈 샘플 프레임과 calibEverySamples 샘플마다(박스↔몸통 오프셋 보정용) 실행.
 * - 여러 명이면 직전 위치(처음엔 사용자가 탭한 위치)에 가장 가까운 사람을 따라간다.
 *   단, 직전 채택 위치에서 물리적으로 불가능한 거리면 버린다 — 등반자를 잠깐 놓친 사이 다른 사람으로 갈아타지 않도록.
 * - 포즈가 비면(빠른 동작·흐린 자세) 마지막 포즈의 몸 박스에서 광학 흐름으로 매 프레임 따라간다(gray가 있을 때).
 *   공백이 시작된 걸 알게 되는 샘플 프레임에서 마지막 포즈 프레임부터 한 번에 따라잡고, 이후로는 매 프레임.
 * - 포즈가 한동안(reacquireGapS 이상) 끊겼다가 다시 잡히면 몸통 색 분포가 등반자와 비슷한지 확인한다
 *   (사용자 제보: 등반자가 화면 밖으로 그네 타는 사이 빨간 홀드를 사람으로 0.4초간 연속 인식).
 * 내부 좌표는 Python 프로토타입과 같은 '프레임 높이 = 1' 단위.
 */
class Analyzer<F>(
    private val pose: PoseEstimator<F>,
    private val detector: PersonDetector<F>,
    private val srcW: Double,
    private val srcH: Double,
    private val fps: Double,
    private val stride: Int,
    private val calibEverySamples: Int = calibSamplesFor(fps, stride),
    tap: Pair<Double, Double>? = null,
    private val visThreshold: Float = 0.5f,
    private val maxSpeed: Double = 3.0, // 프레임 높이/초 — Fuse.rejectJumps 와 같은 기준
    private val jumpBase: Double = 0.05,
    private val appearance: AppearanceExtractor<F>? = null,
    private val minSimilarity: Double = 0.5,
    private val reacquireGapS: Double = 0.3,
    private val nearTol: Double = 0.15, // 프레임 높이 — 이 안이면 생김새 확인 생략
    private val gray: GrayProvider<F>? = null,
    private val maxFlowS: Double = 3.0, // 이보다 긴 공백은 다음 포즈와 이어질 가능성이 낮아 흐름을 멈춘다(비용 절약)
) {
    private val aspect = srcW / srcH
    private var last: DoubleArray? = tap?.let { doubleArrayOf(it.first * aspect, it.second) }
    private var lastIndex = -1 // 마지막으로 포즈를 채택한 프레임(-1: 아직 없음 → 탭 위치는 거리 제한 없이 기준으로만)
    private var lastPoseIndex = -1 // 박스 갱신과 별개로, 마지막으로 '포즈'를 채택한 프레임
    private var look: FloatArray? = null // 등반자 몸통 색 분포(최근 채택 포즈의 이동 평균)
    private var lookSamples = 0
    private val torso = ArrayList<DoubleArray>()
    private val raw = ArrayList<DoubleArray>()
    private val boxes = ArrayList<List<Box>>()
    private val pts = ArrayList<Long>()
    private val flowPos = ArrayList<DoubleArray>()
    private val flowSeed = ArrayList<Int>()
    private var lastPoseGray: GrayImage? = null
    private var lastPoseTorso: DoubleArray? = null
    private var lastPoseExtent: DoubleArray? = null
    private var tracker: FlowTracker? = null
    private var trackerSeed = -1
    private var gapTried = -1 // 이 포즈 프레임에서 시작하는 공백은 이미 흐름을 시도함

    /** 이 프레임의 픽셀이 필요한지 — 포즈 추정 프레임이거나 흐름으로 따라가는 중일 때만(나머지는 GPU 읽기 생략). */
    fun needsPixels(index: Int) = index % stride == 0 || tracker != null

    fun onFrame(index: Int, ptsUs: Long, frame: F) {
        pts += ptsUs
        flowPos += NAN2
        flowSeed += -1
        if (index % stride != 0) {
            torso += NAN2
            raw += NAN4
            boxes.add(emptyList())
            stepFlow(index, frame)
            return
        }
        val chosen = choose(pose.estimate(frame, ptsUs / 1000), index, frame)
        if (chosen != null) {
            val t = torsoOf(chosen)
            torso += t
            raw += extentOf(chosen)
            last = t
            lastIndex = index
            lastPoseIndex = index
            remember(frame, chosen)
            tracker = null
            lastPoseGray = gray?.gray(frame)
            lastPoseTorso = t
            lastPoseExtent = raw.last()
        } else {
            torso += NAN2
            raw += NAN4
            if (tracker != null) stepFlow(index, frame) else startFlow(index, frame)
        }
        val frameBoxes = if (chosen == null || (index / stride) % calibEverySamples == 0) {
            detector.detect(frame).map { Box(it.x0 * aspect, it.y0, it.x1 * aspect, it.y1) }
        } else {
            emptyList()
        }
        boxes.add(frameBoxes)
        if (chosen == null) anchorToBox(frameBoxes, index)
    }

    /**
     * 포즈가 비는 동안에도 직전 위치 근처의 사람 박스로 기준 위치를 갱신한다.
     * 그래야 포즈가 다시 잡힐 때 '오래 전 위치' 기준으로 거리 제한이 사실상 무제한이 되어
     * 화면 다른 곳의 가짜 포즈를 받아들이는 일이 없다(사용자 제보: 그네 영상).
     */
    private fun anchorToBox(frameBoxes: List<Box>, index: Int) {
        val ref = last ?: return
        if (lastIndex < 0) return
        val allowed = jumpBase + maxSpeed * (index - lastIndex) / fps
        val best = frameBoxes.minByOrNull { hypot(it.cx - ref[0], it.cy - ref[1]) } ?: return
        if (hypot(best.cx - ref[0], best.cy - ref[1]) > allowed) return
        last = doubleArrayOf(best.cx, best.cy)
        lastIndex = index
    }

    /** 공백이 시작된 걸 안 샘플 프레임: 마지막 포즈 프레임의 몸 박스에서 점을 고르고 이 프레임까지 한 번에 따라간다. */
    private fun startFlow(index: Int, frame: F) {
        val g0 = lastPoseGray ?: return
        val ext = lastPoseExtent ?: return
        val t0 = lastPoseTorso ?: return
        val provider = gray ?: return
        if (gapTried == lastPoseIndex || ext[0].isNaN()) return
        gapTried = lastPoseIndex
        val s = g0.h.toDouble() // 높이 정규화 → 흐름 영상 픽셀
        val ft = FlowTracker()
        if (!ft.seed(g0, Box(ext[0] * s, ext[1] * s, ext[2] * s, ext[3] * s))) return
        val d = ft.step(provider.gray(frame)) ?: return
        tracker = ft
        trackerSeed = lastPoseIndex
        val p = doubleArrayOf(t0[0] + d[0] / s, t0[1] + d[1] / s)
        // 마지막 포즈와 이 프레임 사이(못 본 프레임)는 둘 사이 선형으로
        val a = lastPoseIndex
        for (k in a + 1 until index) {
            val w = (k - a).toDouble() / (index - a)
            flowPos[k] = doubleArrayOf(t0[0] + (p[0] - t0[0]) * w, t0[1] + (p[1] - t0[1]) * w)
            flowSeed[k] = a
        }
        flowPos[index] = p
        flowSeed[index] = a
    }

    /** 흐름으로 따라가는 중이면 한 프레임 진행. 놓치면 이번 공백에선 그만. */
    private fun stepFlow(index: Int, frame: F) {
        val ft = tracker ?: return
        val provider = gray ?: return
        val t0 = lastPoseTorso ?: return
        if (index - trackerSeed > maxFlowS * fps) {
            tracker = null
            return
        }
        val d = ft.step(provider.gray(frame))
        if (d == null) {
            tracker = null
            return
        }
        val s = lastPoseGray!!.h.toDouble()
        flowPos[index] = doubleArrayOf(t0[0] + d[0] / s, t0[1] + d[1] / s)
        flowSeed[index] = trackerSeed
    }

    fun finish(fps: Double): AnalysisResult {
        val fused = Fuse.fuseTrack(
            torso.toTypedArray(), boxes, fps,
            flow = flowPos.toTypedArray(), flowSeed = flowSeed.toIntArray(),
        )
        check(fused.track.any { !it[0].isNaN() }) { "영상에서 등반자를 찾지 못했습니다" }
        val body = Fuse.bodyBoxes(raw.toTypedArray(), fused.track, fused.source)
        return AnalysisResult(
            track = Array(fused.track.size) { i -> DoubleArray(2) { fused.track[i][it] * srcH } },
            body = Array(body.size) { i -> DoubleArray(4) { body[i][it] * srcH } },
            source = fused.source,
            ptsUs = pts.toLongArray(),
        )
    }

    private fun choose(cands: List<PoseCandidate>, index: Int, frame: F): PoseCandidate? {
        val ref = last ?: return cands.firstOrNull()
        fun dist(p: PoseCandidate) = torsoOf(p).let { hypot(it[0] - ref[0], it[1] - ref[1]) }
        val reachable = cands
            .filter { lastIndex < 0 || dist(it) <= jumpBase + maxSpeed * (index - lastIndex) / fps }
            .sortedBy(::dist)
        // 공백 뒤 직전 위치에서 먼 곳에서 다시 잡을 때는 생김새도 맞아야 한다(가까운 순으로 처음 맞는 사람).
        // 직전 위치 근처면 가짜일 가능성이 낮고, 포즈가 살짝 어긋나 색이 달라 보이는 경우가 있어 확인하지 않는다.
        val reacquiring = lastPoseIndex >= 0 && (index - lastPoseIndex) > reacquireGapS * fps
        return if (reacquiring) {
            reachable.firstOrNull { dist(it) <= nearTol || looksLikeClimber(frame, it) }
        } else {
            reachable.firstOrNull()
        }
    }

    /** 몸통(어깨·골반) 사각형의 안쪽 — 배경 벽이 섞이지 않게 줄여서 옷 색만 보도록. */
    private fun torsoBox(p: PoseCandidate): Box {
        val xs = TORSO.map { p.x[it].toDouble() }
        val ys = TORSO.map { p.y[it].toDouble() }
        val inX = (xs.max() - xs.min()) * 0.2
        val inY = (ys.max() - ys.min()) * 0.2
        return Box(xs.min() + inX, ys.min() + inY, xs.max() - inX, ys.max() - inY)
    }

    private fun looksLikeClimber(frame: F, p: PoseCandidate): Boolean {
        val ref = look ?: return true
        if (appearance == null || lookSamples < MIN_LOOK_SAMPLES) return true
        val f = appearance.describe(frame, torsoBox(p)) ?: return true
        return similarity(ref, f) >= minSimilarity
    }

    private fun remember(frame: F, p: PoseCandidate) {
        val f = appearance?.describe(frame, torsoBox(p)) ?: return
        val ref = look
        look = if (ref == null) f.copyOf() else FloatArray(f.size) { ref[it] * (1 - LOOK_ALPHA) + f[it] * LOOK_ALPHA }
        lookSamples++
    }

    private fun torsoOf(p: PoseCandidate): DoubleArray {
        var x = 0.0
        var y = 0.0
        for (i in TORSO) {
            x += p.x[i]
            y += p.y[i]
        }
        return doubleArrayOf(x / TORSO.size * aspect, y / TORSO.size)
    }

    private fun extentOf(p: PoseCandidate): DoubleArray {
        var x0 = Double.POSITIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (i in p.x.indices) {
            if (p.visibility[i] < visThreshold) continue
            x0 = minOf(x0, p.x[i] * aspect)
            x1 = maxOf(x1, p.x[i] * aspect)
            y0 = minOf(y0, p.y[i].toDouble())
            y1 = maxOf(y1, p.y[i].toDouble())
        }
        return if (x0.isInfinite()) NAN4 else doubleArrayOf(x0, y0, x1, y1)
    }

    companion object {
        private val TORSO = intArrayOf(11, 12, 23, 24) // 어깨 L/R, 골반 L/R
        private val NAN2 = doubleArrayOf(Double.NaN, Double.NaN)
        private val NAN4 = DoubleArray(4) { Double.NaN }

        private const val MIN_LOOK_SAMPLES = 3
        private const val LOOK_ALPHA = 0.2f

        /** 히스토그램 교집합(둘 다 합 1일 때 0..1). */
        fun similarity(a: FloatArray, b: FloatArray): Double {
            val sa = a.sum().toDouble().takeIf { it > 0 } ?: return 0.0
            val sb = b.sum().toDouble().takeIf { it > 0 } ?: return 0.0
            return a.indices.sumOf { minOf(a[it] / sa, b[it] / sb) }
        }

        /** 초당 ~10회 포즈 추정이 되도록 하는 stride. */
        fun strideFor(fps: Double) = max(1, (fps / 10).roundToInt())

        /** 박스↔몸통 오프셋 보정을 ~0.5초마다 하도록 하는 샘플 간격. */
        fun calibSamplesFor(fps: Double, stride: Int) = max(1, (0.5 * fps / stride).roundToInt())
    }
}
