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

    /**
     * 사람 박스(프레임 폭/높이 기준 0..1) 주변만 잘라 포즈를 찾는다. 결과 좌표는 프레임 전체 기준 0..1.
     * 화면 전체에서는 포즈 모델이 다른 것(사람처럼 보이는 홀드 등)을 잡느라 등반자를 못 낼 때 쓴다.
     */
    fun estimateCrop(frame: F, box: Box): List<PoseCandidate> = emptyList()
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
 * - 등반자 몸통 색 분포를 익힌 뒤에는 포즈마다 색이 맞는지 확인한다. 색이 다르면 직전 위치 바로 근처이면서
 *   사람 검출기도 그 자리에 사람을 볼 때만 받는다(사용자 제보: 그네 영상의 빨간 홀드, 20260914_145634의 빨강·파랑 홀드 덩어리 —
 *   후자는 등반자 바로 옆이라 끊김 없이 이어져 들어왔다). 색 기억은 확실히 맞는 포즈로만 갱신(가짜로 오염되지 않게).
 * - 화면 전체에서 맞는 포즈가 없으면 사람 검출기 박스 주변만 잘라 다시 찾는다 — 벽을 보고 선 등반자는 포즈 모델이
 *   화면 전체에선 못 찾고 사람처럼 보이는 홀드를 대신 내는 경우가 있다.
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
    private val nearTol: Double = 0.15, // 프레임 높이 — 색이 달라도 이 안이고 사람 검출기가 보면 받는다
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
    private var detectedAt = -1 // 아래 detectedBoxes를 구한 프레임(한 프레임에 검출기는 한 번만)
    private var detectedBoxes: List<Box> = emptyList()

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
        val chosen = choose(pose.estimate(frame, ptsUs / 1000), index, frame) ?: aroundPersonBoxes(index, frame)
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
        val frameBoxes = if (chosen == null || (index / stride) % calibEverySamples == 0 || detectedAt == index) {
            detected(frame, index)
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
        return cands
            .filter { lastIndex < 0 || dist(it) <= jumpBase + maxSpeed * (index - lastIndex) / fps }
            .sortedBy(::dist)
            .firstOrNull { fits(frame, index, it, dist(it)) }
    }

    /**
     * 색이 맞거나(익히기 전이면 무조건), 색이 달라도 직전 위치 바로 근처이면서 사람 검출기가 그 자리에 사람을 볼 때.
     * 후자는 몸통 추정이 바지 쪽으로 내려가 색이 달라 보이는 진짜 등반자(그네 영상 22.7s) 같은 경우.
     */
    private fun fits(frame: F, index: Int, p: PoseCandidate, dist: Double): Boolean {
        val sim = lookSimilarity(frame, p) ?: return true
        if (sim >= minSimilarity) return true
        if (dist > nearTol) return false
        val t = torsoOf(p)
        return detected(frame, index).any { t[0] in it.x0..it.x1 && t[1] in it.y0..it.y1 }
    }

    /** 화면 전체에서 맞는 포즈가 없을 때: 갈 수 있는 거리 안의 사람 박스 주변만 잘라 가까운 순으로 다시 찾는다. */
    private fun aroundPersonBoxes(index: Int, frame: F): PoseCandidate? {
        val ref = last ?: return null
        val allowed = if (lastIndex < 0) Double.MAX_VALUE else jumpBase + maxSpeed * (index - lastIndex) / fps
        fun dist(b: Box) = hypot(b.cx - ref[0], b.cy - ref[1])
        return detected(frame, index)
            .filter { dist(it) <= allowed }
            .sortedBy(::dist)
            .take(MAX_CROPS)
            .firstNotNullOfOrNull { b ->
                pose.estimateCrop(frame, Box(b.x0 / aspect, b.y0, b.x1 / aspect, b.y1))
                    .firstOrNull { c -> lookSimilarity(frame, c)?.let { it >= minSimilarity } ?: true }
            }
    }

    /** 이 프레임의 사람 박스(높이 정규화 좌표). 검출기는 프레임마다 많아야 한 번. */
    private fun detected(frame: F, index: Int): List<Box> {
        if (detectedAt != index) {
            detectedBoxes = detector.detect(frame).map { Box(it.x0 * aspect, it.y0, it.x1 * aspect, it.y1) }
            detectedAt = index
        }
        return detectedBoxes
    }

    /** 몸통(어깨·골반) 사각형의 안쪽 — 배경 벽이 섞이지 않게 줄여서 옷 색만 보도록. */
    private fun torsoBox(p: PoseCandidate): Box {
        val xs = TORSO.map { p.x[it].toDouble() }
        val ys = TORSO.map { p.y[it].toDouble() }
        val inX = (xs.max() - xs.min()) * 0.2
        val inY = (ys.max() - ys.min()) * 0.2
        return Box(xs.min() + inX, ys.min() + inY, xs.max() - inX, ys.max() - inY)
    }

    /** 익힌 등반자 색과의 유사도. 아직 못 익혔거나 특징을 못 구하면 null(확인 생략). */
    private fun lookSimilarity(frame: F, p: PoseCandidate): Double? {
        val ref = look ?: return null
        if (appearance == null || lookSamples < MIN_LOOK_SAMPLES) return null
        val f = appearance.describe(frame, torsoBox(p)) ?: return null
        return similarity(ref, f)
    }

    /** 채택한 포즈로 색 기억 갱신 — 익히는 중이거나 확실히 맞을 때만(애매한 포즈로 조금씩 오염되지 않게). */
    private fun remember(frame: F, p: PoseCandidate) {
        val f = appearance?.describe(frame, torsoBox(p)) ?: return
        val ref = look
        if (ref != null && lookSamples >= MIN_LOOK_SAMPLES && similarity(ref, f) < LEARN_SIMILARITY) return
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
        private const val LEARN_SIMILARITY = 0.6 // 실측: 진짜 등반자 0.7~0.94, 가짜 0.15~0.5
        private const val MAX_CROPS = 2 // 한 프레임에 잘라서 다시 찾는 사람 박스 수

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
