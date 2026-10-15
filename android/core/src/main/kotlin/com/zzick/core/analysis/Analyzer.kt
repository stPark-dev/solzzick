package com.zzick.core.analysis

import com.zzick.core.flow.FlowTracker
import com.zzick.core.flow.GrayImage
import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import kotlin.math.abs
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
/**
 * 샘플(포즈 추정) 프레임 하나의 진단 기록 — 추적 점검과 문제 신고용. 좌표는 원본 픽셀.
 * @param chosen 채택한 몸통 중심(없으면 null) @param similarity 채택한 포즈와 익힌 옷 색의 유사도(익히기 전이면 null)
 * @param viaCrop 화면 전체가 아니라 사람 박스 주변을 잘라서 찾았는지 @param boxes 이 프레임에서 구한 사람 박스(x0,y0,x1,y1)
 */
class SampleReport(
    val index: Int,
    val candidates: Int,
    val chosen: DoubleArray?,
    val similarity: Double?,
    val viaCrop: Boolean,
    val boxes: List<DoubleArray>,
)

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
    private val onSample: ((SampleReport) -> Unit)? = null, // 진단 기록(점검·문제 신고)
    private val confirmStart: Boolean = false, // 첫 포즈·오래 놓친 뒤 포즈 확인(사람 박스 안, 탭 모드 첫 포즈는 탭 근처도) — 앱은 켬
) {
    private val aspect = srcW / srcH
    private var last: DoubleArray? = tap?.let { doubleArrayOf(it.first * aspect, it.second) }
    private val tapPoint = last
    private var lastIndex = -1 // 마지막으로 포즈를 채택한 프레임(-1: 아직 없음 → 탭 위치는 거리 제한 없이 기준으로만)
    private var lastPoseIndex = -1 // 박스 갱신과 별개로, 마지막으로 '포즈'를 채택한 프레임
    private var look: FloatArray? = null // 등반자 몸통 색 분포(최근 채택 포즈의 이동 평균)
    private var lookSamples = 0
    private var offLookStreak = 0 // 색이 다른데 받은 포즈가 연속 몇 번째인지
    private var offLookStart: DoubleArray? = null // 그 연속이 시작된 몸통 위치
    private var lastPoseTrusted = false // 마지막으로 받은 포즈가 색까지 맞았는지(색을 익히기 전이면 참)
    private val torso = ArrayList<DoubleArray>()
    private val raw = ArrayList<DoubleArray>()
    private val boxes = ArrayList<List<Box>>()
    private val anchors = ArrayList<Box?>() // 포즈가 없을 때 이어 따라간 등반자 박스(프레임별)
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
    private var detectedRaw: List<Box> = emptyList() // 같은 프레임의 움직임 필터 전 박스
    private val detections = ArrayDeque<Pair<Int, List<Box>>>() // 최근 검출(움직이지 않는 박스 판별용)
    private val poseHeights = ArrayDeque<Pair<Int, Double>>() // 최근 채택한 포즈의 키(등반자 크기 기준)

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
            anchors.add(null)
            stepFlow(index, frame)
            return
        }
        val cands = pose.estimate(frame, ptsUs / 1000)
        val whole = choose(cands, index, frame)
        val search = searchNow(index)
        val chosen = whole ?: if (search) aroundPersonBoxes(index, frame) else null
        // 색 특징은 한 번만 구해 유사도(기억 갱신 전 값)와 기억 갱신에 함께 쓴다
        val desc = chosen?.let { appearance?.describe(frame, torsoBox(it)) }
        val sim = desc?.let(::similarityToLook)
        if (chosen != null) {
            lastPoseTrusted = sim == null || sim >= minSimilarity
            relearnIfLookIsWrong(sim, torsoOf(chosen))
        }
        if (chosen != null) {
            val t = torsoOf(chosen)
            torso += t
            raw += extentOf(chosen)
            last = t
            lastIndex = index
            lastPoseIndex = index
            if (desc != null) remember(desc)
            tracker = null
            lastPoseGray = gray?.gray(frame)
            lastPoseTorso = t
            lastPoseExtent = raw.last()
            raw.last().let { e -> if (!e[1].isNaN()) poseHeights.addLast(index to e[3] - e[1]) }
            while (poseHeights.size > SIZE_KEEP && poseHeights.first().first < index - SIZE_WINDOW_S * fps) poseHeights.removeFirst()
        } else {
            torso += NAN2
            raw += NAN4
            if (tracker != null) stepFlow(index, frame) else startFlow(index, frame)
        }
        val frameBoxes = if ((chosen == null && search) || (index / stride) % calibEverySamples == 0 || detectedAt == index) {
            detected(frame, index)
        } else {
            emptyList()
        }
        boxes.add(frameBoxes)
        anchors.add(if (chosen == null) anchorToBox(frameBoxes, index) else null)
        onSample?.invoke(
            SampleReport(
                index, cands.size, chosen?.let { torsoOf(it).map { v -> v * srcH }.toDoubleArray() }, sim, whole == null && chosen != null,
                frameBoxes.map { doubleArrayOf(it.x0 * srcH, it.y0 * srcH, it.x1 * srcH, it.y1 * srcH) },
            ),
        )
    }

    /**
     * 포즈가 비는 동안에도 직전 위치 근처의 사람 박스로 기준 위치를 갱신한다.
     * 그래야 포즈가 다시 잡힐 때 '오래 전 위치' 기준으로 거리 제한이 사실상 무제한이 되어
     * 화면 다른 곳의 가짜 포즈를 받아들이는 일이 없다(사용자 제보: 그네 영상).
     */
    /** @return 이어 따라간 박스(없으면 null) — Fuse가 긴 포즈 공백을 이 박스로 메운다. */
    private fun anchorToBox(frameBoxes: List<Box>, index: Int): Box? {
        val ref = last ?: return null
        if (lastIndex < 0) return null
        val allowed = jumpBase + maxSpeed * (index - lastIndex) / fps
        val best = frameBoxes.filter(::climberSized).minByOrNull { hypot(it.cx - ref[0], it.cy - ref[1]) } ?: return null
        if (hypot(best.cx - ref[0], best.cy - ref[1]) > allowed) return null
        last = doubleArrayOf(best.cx, best.cy)
        lastIndex = index
        return best
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
            flow = flowPos.toTypedArray(), flowSeed = flowSeed.toIntArray(), anchors = anchors,
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

    /**
     * 포즈가 없을 때 사람 박스로 찾아볼 샘플인지. 포즈도 등반자 박스도 1초 넘게 못 찾는 동안(대개 등반 전후 빈 화면)은 0.3초마다만 —
     * 사람 검출기(1회 약 90ms)가 분석 시간의 약 35%이고 그중 절반 가까이가 이런 호출이었다. 박스로 붙잡고 있는 동안(그네·쉬는 자세)은
     * 매 샘플 그대로 찾고, 다시 잡는 포즈의 박스 확인은 그때 바로 한다.
     */
    private fun searchNow(index: Int): Boolean {
        val longGone = lastIndex < 0 || index - lastIndex > LONG_MISS_S * fps
        return !longGone || (index / stride) % LONG_MISS_EVERY == 0
    }

    /** 몸통이 화면 안인지 — 카메라 앞을 지나는 손·몸은 포즈 모델이 몸통을 화면 밖으로 추정한다(점검: 20260612_184742). */
    private fun inFrame(p: PoseCandidate) = torsoOf(p).let { (x, y) -> x in -FRAME_MARGIN..aspect + FRAME_MARGIN && y in -FRAME_MARGIN..1 + FRAME_MARGIN }

    /**
     * 첫 포즈 확인(confirmStart): 등반자가 들어오기 전 포즈 모델이 홀드·작은 사람을 내면 비교할 기준이 없어 그대로 첫 포즈가
     * 되던 문제(점검: 20260914_153037, 20260612_184742, 20260607_151150). 첫 포즈는 사람 박스 안이어야 하고, 탭 모드는 탭 근처이기도 해야 한다.
     * 오래 놓친 뒤 다시 잡는 포즈도 사람 박스 안이어야 한다 — 등반자가 나간 뒤 빈 벽의 홀드를 받아 카메라가 끌려가던 문제(점검: 20261011_105654).
     */
    private fun startOk(p: PoseCandidate, index: Int, frame: F): Boolean {
        val first = lastPoseIndex < 0
        if (!confirmStart || (!first && index - lastPoseIndex < REACQUIRE_S * fps)) return true
        val t = torsoOf(p)
        val tp = tapPoint
        if (first && tp != null && hypot(t[0] - tp[0], t[1] - tp[1]) > START_RADIUS) return false
        // 다시 잡을 때는 움직임 필터 전 박스(등반자 크기) — 가만히 쉬던 등반자의 박스가 '움직이지 않는 박스'로 빠져도 잡게
        val boxes = if (first) detected(frame, index) else detected(frame, index).let { detectedRaw.filter(::climberSized) }
        return boxes.any { t[0] in it.x0..it.x1 && t[1] in it.y0..it.y1 }
    }

    private fun choose(all: List<PoseCandidate>, index: Int, frame: F): PoseCandidate? {
        val cands = all.filter { inFrame(it) && startOk(it, index, frame) }
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
        // 색을 익힌 뒤 색을 못 잰 포즈(몸통 칸이 너무 작음)는 받지 않는다 — 흐름이 미끄러진 끝의 벽 위 가짜 포즈를
        // 확인 없이 받아 30초 동안 등반자를 놓쳤다(사용자 제보: 20260525_093059)
        val sim = lookSimilarity(frame, p) ?: return !lookReady()
        if (sim >= minSimilarity) return true
        if (dist > nearTol) return false
        val t = torsoOf(p)
        return detected(frame, index).any { t[0] in it.x0..it.x1 && t[1] in it.y0..it.y1 }
    }

    /**
     * 등반자 몸 크기에 맞는 사람 박스인지(최근 포즈 키 대비). 모르면 모두 맞다고 본다.
     * 매트에 앉은 작은 사람·카메라 앞을 지나는 큰 사람을 등반자로 착각하지 않게(점검: 20260821_155813).
     * 기준은 최근 몇 초 포즈 키의 중앙값 — 마지막 포즈 하나로 재면 상체만 잡힌 포즈 뒤에 기준이 줄어 작은 사람도 통과했다.
     */
    private fun climberSized(b: Box): Boolean {
        if (poseHeights.isEmpty()) return true
        val bodyH = poseHeights.map { it.second }.sorted()[poseHeights.size / 2]
        if (bodyH <= 0) return true
        return (b.y1 - b.y0) / bodyH in SIZE_RANGE_MIN..SIZE_RANGE_MAX
    }

    /**
     * 화면 전체에서 맞는 포즈가 없을 때: 갈 수 있는 거리 안의 사람 박스 주변만 잘라 가까운 순으로 다시 찾는다.
     * 거리는 현실적인 등반자 속도(CROP_SPEED)로 — 검출기가 등반자를 0.2초 놓친 순간 바닥의 다른 사람(0.6H)을 받던 문제
     * (사용자 제보: 20260525_093059). 빠른 동작은 박스 따라가기(anchorToBox, maxSpeed)가 맡고, 오래 놓치면 허용 거리가 늘어난다.
     */
    private fun aroundPersonBoxes(index: Int, frame: F): PoseCandidate? {
        val ref = last ?: return null
        val allowed = if (lastIndex < 0) Double.MAX_VALUE else jumpBase + CROP_SPEED * (index - lastIndex) / fps
        fun dist(b: Box) = hypot(b.cx - ref[0], b.cy - ref[1])
        return detected(frame, index)
            .filter { dist(it) <= allowed && climberSized(it) }
            .sortedBy(::dist)
            .take(MAX_CROPS)
            .firstNotNullOfOrNull { b ->
                pose.estimateCrop(frame, Box(b.x0 / aspect, b.y0, b.x1 / aspect, b.y1))
                    .firstOrNull { c -> inFrame(c) && startOk(c, index, frame) && cropLooksRight(frame, c) }
            }
    }

    /**
     * 이 프레임의 사람 박스(높이 정규화 좌표). 검출기는 프레임마다 많아야 한 번.
     * 2초 넘게 제자리인 박스(네 변이 1~3px 안)는 뺀다 — 홀드를 사람으로 잘못 잡은 박스는 좌표가 그대로라, 기준 위치를 붙잡아
     * 진짜 등반자를 못 받던 문제(점검: 20260612_184742). 쉬는 등반자의 박스는 가장자리가 수~수십 px 흔들려 해당하지 않고
     * (점검: 20260801_155833), 최근 채택한 등반자가 들어 있는 박스도 그대로 둔다.
     */
    private fun detected(frame: F, index: Int): List<Box> {
        if (detectedAt != index) {
            val raw = detector.detect(frame).map { Box(it.x0 * aspect, it.y0, it.x1 * aspect, it.y1) }
            // 최근 2초 검출(충분히 오래 쌓였을 때)의 대부분에서 네 변이 거의 그대로인 박스
            val window = detections.filter { (k, _) -> index - k <= STATIC_S * fps }
            val longEnough = window.size >= STATIC_MIN_DETECTIONS && index - window.first().first >= STATIC_S * fps * 0.9
            fun same(a: Box, b: Box) = abs(a.x0 - b.x0) <= STATIC_TOL && abs(a.y0 - b.y0) <= STATIC_TOL &&
                abs(a.x1 - b.x1) <= STATIC_TOL && abs(a.y1 - b.y1) <= STATIC_TOL
            fun motionless(b: Box) = longEnough && window.count { (_, bs) -> bs.any { same(it, b) } } >= STATIC_SHARE * window.size
            // 최근(2초 안) 채택한 등반자 몸통이 들어 있는 박스는 쉬고 있는 등반자 — 움직이지 않아도 사람
            // 색이 확인된 포즈만 — 색이 다른데 예외로 받은 포즈(홀드일 수 있음)가 자기 박스를 살려 두지 않게
            val climber = lastPoseTorso?.takeIf { lastPoseTrusted && lastPoseIndex >= 0 && index - lastPoseIndex <= STATIC_S * fps }
            fun holdsClimber(b: Box) = climber != null && climber[0] in b.x0..b.x1 && climber[1] in b.y0..b.y1
            detectedBoxes = raw.filter { b -> holdsClimber(b) || !motionless(b) }
            detectedRaw = raw
            detections.addLast(index to raw)
            while (detections.isNotEmpty() && index - detections.first().first > STATIC_KEEP_S * fps) detections.removeFirst()
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

    /**
     * 잘라서 찾은 포즈의 색 확인. 색을 익혔으면 색을 구할 수 있고 맞아야 한다 — 몸통 칸이 너무 작아 색을 못 구한 작은 사람을
     * 비교 없이 받아 끝까지 따라가던 문제(점검: 20260821_155813). 익히기 전이면 통과.
     */
    private fun cropLooksRight(frame: F, p: PoseCandidate): Boolean {
        if (!lookReady()) return true
        val sim = lookSimilarity(frame, p) ?: return false
        return sim >= minSimilarity
    }

    private fun lookReady() = appearance != null && look != null && lookSamples >= MIN_LOOK_SAMPLES

    /** 익힌 등반자 색과의 유사도. 아직 못 익혔거나 특징을 못 구하면 null(확인 생략). */
    private fun lookSimilarity(frame: F, p: PoseCandidate): Double? {
        if (appearance == null || look == null || lookSamples < MIN_LOOK_SAMPLES) return null
        return appearance.describe(frame, torsoBox(p))?.let(::similarityToLook)
    }

    private fun similarityToLook(f: FloatArray): Double? {
        val ref = look ?: return null
        if (lookSamples < MIN_LOOK_SAMPLES) return null
        return similarity(ref, f)
    }

    /**
     * 색이 다른데도(근처 + 사람 검출기 확인으로) 받은 포즈가 1초 넘게 이어지면 색 기억이 틀린 것 — 버리고 다시 익힌다.
     * 등반자가 나오기 전 홀드를 사람으로 잡아 기억이 홀드 색으로 굳던 문제(점검: 20260612_184742).
     */
    private fun relearnIfLookIsWrong(sim: Double?, torso: DoubleArray) {
        if (sim == null || sim >= minSimilarity) {
            offLookStreak = 0
            return
        }
        if (offLookStreak++ == 0) offLookStart = torso
        // 실제로 움직인 포즈여야 — 홀드는 움직이지 않는다(코드 리뷰 지적: 홀드로 다시 익히지 않게)
        val moved = offLookStart?.let { hypot(torso[0] - it[0], torso[1] - it[1]) } ?: 0.0
        if (offLookStreak < RELEARN_SAMPLES || moved < RELEARN_MOVE) return
        look = null
        lookSamples = 0
        offLookStreak = 0
    }

    /** 채택한 포즈의 색 특징으로 기억 갱신 — 익히는 중이거나 확실히 맞을 때만(애매한 포즈로 조금씩 오염되지 않게). */
    private fun remember(f: FloatArray) {
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
        private const val FRAME_MARGIN = 0.02 // 몸통이 화면 밖으로 이만큼(높이 비율)까지는 봐준다
        private const val RELEARN_SAMPLES = 10 // 샘플 10회(초당 약 10회 추정이면 약 1초)
        private const val RELEARN_MOVE = 0.05 // 그동안 몸통이 이만큼(높이 비율) 움직였어야 다시 익힌다
        private const val START_RADIUS = 0.2 // 탭 모드 첫 포즈: 탭에서 이 거리(높이 비율) 안
        private const val REACQUIRE_S = 2.0 // 이만큼 포즈를 놓친 뒤 다시 잡을 때도 사람 박스 확인
        private const val STATIC_S = 2.0 // 이만큼 전 박스와 거의 같은 자리면 움직이지 않는 박스(사람 아님)
        private const val STATIC_TOL = 0.006 // 네 변 모두 이 안(높이 비율, 1440px에서 약 9px)이면 '그대로'
        private const val STATIC_SHARE = 0.8 // 최근 검출의 이 비율 이상에서 그대로면 움직이지 않는 박스
        private const val STATIC_MIN_DETECTIONS = 5
        private const val SIZE_RANGE_MIN = 0.4 // 잘라서 찾기·기준 갱신에 쓰는 사람 박스: 등반자 몸 높이의 이 배율 안
        private const val SIZE_RANGE_MAX = 2.5
        private const val SIZE_WINDOW_S = 3.0 // 등반자 크기 기준: 최근 이만큼의 포즈 키 중앙값
        private const val CROP_SPEED = 1.0 // 프레임 높이/초 — 잘라서 다시 찾을 때 마지막 위치에서 갈 수 있는 속도
        private const val LONG_MISS_S = 1.0 // 이만큼 포즈를 못 찾으면
        private const val LONG_MISS_EVERY = 3 // 이 샘플마다만 사람 박스로 찾는다
        private const val SIZE_KEEP = 5 // 오래 놓친 뒤에도 크기 기준에 남기는 최근 포즈 수(상체만 잡힌 포즈 하나로 줄지 않게)
        private const val STATIC_KEEP_S = 2.5 // 판별에 쓰는 검출 기록 보관 기간

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
