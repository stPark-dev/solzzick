package com.zzick.core

import com.zzick.core.analysis.Analyzer
import com.zzick.core.analysis.PersonDetector
import com.zzick.core.analysis.PoseCandidate
import com.zzick.core.analysis.PoseEstimator
import com.zzick.core.tracking.Box
import com.zzick.core.tracking.Fuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private val w = 1440.0
    private val h = 1440.0
    private val fps = 30.0

    /** 몸통 중심이 (x, y)(0..1 정규화)인 포즈. 머리 0.1 위, 발 0.1 아래, 손은 좌우 0.09. */
    private fun person(x: Float, y: Float, vis: Float = 0.9f): PoseCandidate {
        val xs = FloatArray(33) { x }
        val ys = FloatArray(33) { y }
        ys[0] = y - 0.1f
        for (i in 27..32) ys[i] = y + 0.1f
        xs[15] = x - 0.09f
        xs[16] = x + 0.09f
        return PoseCandidate(xs, ys, FloatArray(33) { vis })
    }

    private class FakePose(
        val crop: (Int, Box) -> List<PoseCandidate> = { _, _ -> emptyList() },
        val byFrame: (Int) -> List<PoseCandidate>,
    ) : PoseEstimator<Int> {
        val calls = mutableListOf<Int>()
        val cropCalls = mutableListOf<Int>()
        override fun estimate(frame: Int, timestampMs: Long): List<PoseCandidate> {
            calls += frame
            return byFrame(frame)
        }

        override fun estimateCrop(frame: Int, box: Box): List<PoseCandidate> {
            cropCalls += frame
            return crop(frame, box)
        }
    }

    private class FakeDetector(val byFrame: (Int) -> List<Box> = { emptyList() }) : PersonDetector<Int> {
        val calls = mutableListOf<Int>()
        override fun detect(frame: Int): List<Box> {
            calls += frame
            return byFrame(frame)
        }
    }

    private fun run(
        n: Int, pose: FakePose, det: FakeDetector, stride: Int,
        tap: Pair<Double, Double>? = null, calibEverySamples: Int = 5,
    ) =
        Analyzer(pose, det, w, h, fps, stride = stride, calibEverySamples = calibEverySamples, tap = tap).apply {
            for (i in 0 until n) onFrame(i, (i * 1_000_000L / fps).toLong(), i)
        }.finish(fps)

    @Test
    fun poseRunsOnlyOnStrideFramesAndOthersAreInterpolated() {
        val pose = FakePose { listOf(person(0.5f, 0.5f)) }
        val r = run(30, pose, FakeDetector(), stride = 3)
        assertEquals((0 until 30 step 3).toList(), pose.calls)
        assertEquals(Fuse.POSE, r.source[3])
        assertEquals(Fuse.INTERP, r.source[4])
        assertEquals(720.0, r.track[4][0], 1e-9)
    }

    @Test
    fun detectorRunsOnMissedSampledFramesAndCalibrationFrames() {
        val pose = FakePose { i -> if (i in 9..12) emptyList() else listOf(person(0.5f, 0.5f)) }
        val det = FakeDetector()
        run(40, pose, det, stride = 3)
        // 포즈가 빈 샘플 프레임(9, 12) + 보정 프레임(0, 15, 30)
        assertEquals(listOf(0, 9, 12, 15, 30), det.calls)
    }

    @Test
    fun followsTappedPersonNotTheFirstCandidate() {
        // 후보 순서는 매번 바뀌지만 탭한 오른쪽 사람(x=0.7)을 계속 따라가야 한다
        val pose = FakePose { i ->
            val climber = person(0.7f + i * 0.001f, 0.5f - i * 0.002f)
            val belayer = person(0.2f, 0.85f)
            if (i % 2 == 0) listOf(belayer, climber) else listOf(climber, belayer)
        }
        val r = run(30, pose, FakeDetector(), stride = 1, tap = 0.7 to 0.5)
        for (i in 0 until 30) assertEquals(((0.7 + i * 0.001) * w), r.track[i][0], 1.0)
    }

    @Test
    fun doesNotSwitchToBelayerWhenClimberIsMissedForOneSample() {
        // 리뷰 H1: 등반자를 한 샘플 놓친 사이 빌레이어만 보이면, 이후에도 빌레이어를 따라가면 안 된다
        val climber = person(0.7f, 0.4f)
        val belayer = person(0.3f, 0.85f)
        val pose = FakePose { i -> if (i == 5) listOf(belayer) else listOf(belayer, climber) }
        val r = run(20, pose, FakeDetector(), stride = 1, tap = 0.7 to 0.4)
        for (i in 6 until 20) assertEquals("frame $i", 0.7 * w, r.track[i][0], 1.0)
        assertEquals(Fuse.INTERP, r.source[5])
    }

    @Test
    fun boxesKeepTheClimberAnchoredDuringLongPoseGap() {
        // 사용자 제보(그네 영상) 재현: 포즈가 1.8초 끊긴 동안 검출기는 등반자를 계속 찾는데,
        // 다시 잡힌 포즈는 화면 왼쪽 끝의 가짜 — 이를 등반자로 받아들이면 안 된다
        fun climberX(i: Int) = 0.80 - i * 0.0005
        val pose = FakePose { i ->
            when {
                i < 30 -> listOf(person(climberX(i).toFloat(), 0.48f))
                i < 84 -> emptyList()
                i < 96 -> listOf(person(0.15f, 0.45f)) // 가짜(왼쪽 끝)
                else -> listOf(person(climberX(i).toFloat(), 0.48f))
            }
        }
        val det = FakeDetector { i ->
            val cx = climberX(i)
            listOf(Box(cx - 0.05, 0.38, cx + 0.05, 0.62), Box(0.10, 0.40, 0.20, 0.50)) // 등반자 + 왼쪽 가짜 박스
        }
        val r = run(120, pose, det, stride = 3)
        for (i in 84 until 96 step 3) assertTrue("frame $i src=${r.source[i]}", r.source[i] != Fuse.POSE)
        for (i in 0 until 120) assertEquals("frame $i", climberX(i) * w, r.track[i][0], 0.05 * h)
    }

    /** 위치로 색 특징을 흉내: x<0.4 는 빨간 홀드(가짜), 그 외는 흰 티셔츠(등반자). */
    private class FakeLook : com.zzick.core.analysis.AppearanceExtractor<Int> {
        override fun describe(frame: Int, box: Box): FloatArray =
            if (box.cx < 0.4) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
    }

    @Test
    fun reacquiredPoseMustLookLikeTheClimber() {
        // 앱 실측 재현(다중 인물 모드): 등반자가 화면 밖으로 그네 → 포즈 공백 1.8초 → 왼쪽 빨간 홀드를 사람으로 0.4초간 연속 인식
        fun cx(i: Int) = if (i < 190) 0.85 else 0.88
        val pose = FakePose { i ->
            when {
                i < 190 -> listOf(person(cx(i).toFloat(), 0.48f))
                i < 250 -> emptyList()
                i < 265 -> listOf(person(0.145f, 0.46f)) // 가짜 5샘플 연속
                i < 318 -> emptyList()
                else -> listOf(person(cx(i).toFloat(), 0.42f))
            }
        }
        val r = Analyzer(pose, FakeDetector(), w, h, fps, stride = 3, tap = 0.85 to 0.5, appearance = FakeLook())
            .apply { for (i in 0 until 360) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 250 until 265 step 3) assertTrue("frame $i src=${r.source[i]}", r.source[i] != Fuse.POSE)
        assertEquals(Fuse.POSE, r.source[318])
        for (i in 0 until 360) assertTrue("frame $i x=${r.track[i][0]}", r.track[i][0] > 0.8 * w)
    }

    @Test
    fun nearbyPoseThatLooksDifferentIsAcceptedWhenDetectorSeesAPersonThere() {
        // 실측(그네 영상 22.7s): 공백 뒤 등반자를 다시 잡았는데 몸통 추정이 바지 쪽으로 내려가 색이 달라 보임 —
        // 직전 위치 바로 근처이고 사람 검출기도 그 자리에 사람을 보면 받아들인다
        val pose = FakePose { i ->
            when {
                i < 30 -> listOf(person(0.5f, 0.5f))
                i < 45 -> emptyList()
                else -> listOf(person(0.53f, 0.47f))
            }
        }
        val look = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box) = if (frame < 30) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
        val det = FakeDetector { i -> if (i >= 45) listOf(Box(0.45, 0.33, 0.61, 0.62)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, appearance = look)
            .apply { for (i in 0 until 60) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        assertEquals(Fuse.POSE, r.source[45])
    }

    @Test
    fun differentLookIsKeptWhileDetectorKeepsSeeingThePerson() {
        // 조명·가림으로 색이 달라 보여도, 사람 검출기가 그 자리에 사람을 보는 동안은 버리지 않는다
        fun x(i: Int) = 0.5 + i * 0.001
        val pose = FakePose { i -> listOf(person(x(i).toFloat(), 0.5f)) }
        val flipLook = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box) = if (frame < 30) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
        val det = FakeDetector { i -> listOf(Box(x(i) - 0.08, 0.38, x(i) + 0.08, 0.62)) }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, appearance = flipLook)
            .apply { for (i in 0 until 60) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 0 until 60 step 3) assertEquals("frame $i", Fuse.POSE, r.source[i])
    }

    /** 위치로 색 특징을 흉내: x<0.3 은 빨강·파랑 큰 홀드 덩어리, 그 외는 흰 티셔츠 등반자. */
    private class HoldClusterLook : com.zzick.core.analysis.AppearanceExtractor<Int> {
        override fun describe(frame: Int, box: Box): FloatArray =
            if (box.cx < 0.3) floatArrayOf(0.15f, 0.85f) else floatArrayOf(1f, 0f)
    }

    @Test
    fun lookalikeHoldNextToClimberIsRejectedAndClimberIsFoundAroundPersonBox() {
        // 사용자 제보(20260914_145634): 등반자가 오른쪽으로 크게 움직이자 포즈 모델은 등반자를 놓치고
        // 바로 옆(직전 위치에서 0.09)의 빨강·파랑 홀드 덩어리를 사람으로 계속 낸다(공백 없이 이어져서 생김새 확인을 건너뜀).
        // 사람 검출기는 홀드를 사람으로 보지 않고, 6초 뒤부터 등반자를 찾는다 — 그 박스 주변을 잘라 포즈를 찾으면 등반자가 나온다.
        fun climberX(i: Int) = if (i < 75) 0.36 else minOf(0.54, 0.36 + (i - 75) * 0.002)
        val pose = FakePose(crop = { i, b -> if (b.cx > 0.45) listOf(person(climberX(i).toFloat(), 0.54f)) else emptyList() }) { i ->
            if (i < 75) listOf(person(0.36f, 0.54f)) else listOf(person(0.27f, 0.48f))
        }
        val det = FakeDetector { i -> if (i >= 180) listOf(Box(climberX(i) - 0.1, 0.44, climberX(i) + 0.1, 0.70)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, tap = 0.36 to 0.54, appearance = HoldClusterLook())
            .apply { for (i in 0 until 270) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 75 until 270) assertTrue("frame $i 홀드를 따라감 x=${r.track[i][0] / w}", r.track[i][0] > 0.32 * w)
        for (i in 180 until 270 step 3) {
            assertEquals("frame $i src", Fuse.POSE, r.source[i])
            assertEquals("frame $i x", climberX(i) * w, r.track[i][0], 0.02 * w)
        }
        assertTrue("잘라서 다시 찾기는 사람 박스가 있을 때만", pose.cropCalls.all { it >= 180 })
    }

    @Test
    fun opticalFlowFollowsClimberThroughPoseGap() {
        // 텍스처 패치(등반자)가 오른쪽으로 꾸준히 이동. 포즈는 30~59프레임 동안 비어 있다(빠른 동작으로 놓친 상황).
        // 흐름이 공백을 메워 실제 위치를 따라가야 한다(보간이 아니라 실제 움직임 — 여기선 중간에 방향이 바뀜).
        val gw = 320
        val gh = 320
        fun cx(i: Int) = 0.40 + 0.002 * i + (if (i in 30..59) 0.0015 * (i - 30) else if (i >= 60) 0.045 else 0.0)
        val tex = java.util.Random(7).let { r -> Array(40) { FloatArray(40) { r.nextInt(256).toFloat() } } }
        val gray = object : com.zzick.core.analysis.GrayProvider<Int> {
            override fun gray(frame: Int): com.zzick.core.flow.GrayImage {
                val px0 = cx(frame) * gh - 30 // 패치 60x60, 높이 단위 → 픽셀
                return com.zzick.core.flow.GrayImage(gw, gh, FloatArray(gw * gh) { j ->
                    val x = j % gw
                    val y = j / gw
                    if (x >= px0 && x < px0 + 60 && y in 130 until 190) {
                        val u = ((x - px0) / 6).toInt().coerceIn(0, 39)
                        val v = (y - 130) / 6
                        tex[v][u]
                    } else 100f + 20f * kotlin.math.sin(x / 9.0).toFloat() * kotlin.math.cos(y / 7.0).toFloat()
                })
            }
        }
        val pose = FakePose { i -> if (i in 30..59) emptyList() else listOf(person(cx(i).toFloat() / (w / h).toFloat(), 0.5f)) }
        // 몸 박스가 패치를 덮도록 관절을 패치 범위에 둔다
        val r = Analyzer(pose, FakeDetector(), w, h, fps, stride = 3, gray = gray)
            .apply { for (i in 0 until 90) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 33..57) {
            assertEquals("frame $i src", Fuse.FLOW, r.source[i])
            assertEquals("frame $i x", cx(i) * h, r.track[i][0], 0.01 * h)
        }
    }

    @Test
    fun opticalFlowStopsAfterMaxGapSoPixelsAreNotReadForNothing() {
        // 마지막 포즈 뒤로 포즈가 다시 안 잡히는 경우(등반자가 화면 밖으로 나감): 흐름은 최대 3초까지만
        val gray = object : com.zzick.core.analysis.GrayProvider<Int> {
            val rnd = java.util.Random(3).let { r -> FloatArray(320 * 320) { r.nextInt(256).toFloat() } }
            override fun gray(frame: Int) = com.zzick.core.flow.GrayImage(320, 320, rnd) // 정지 영상 — 흐름은 계속 '살아' 있음
        }
        val pose = FakePose { i -> if (i < 30) listOf(person(0.5f, 0.5f)) else emptyList() }
        val a = Analyzer(pose, FakeDetector(), w, h, fps, stride = 3, gray = gray)
        val needed = mutableListOf<Int>()
        for (i in 0 until 240) {
            if (a.needsPixels(i) && i % 3 != 0) needed += i
            a.onFrame(i, (i * 1_000_000L / fps).toLong(), i)
        }
        assertTrue("3초(90프레임) 넘어서도 픽셀을 읽음: ${needed.maxOrNull()}", (needed.maxOrNull() ?: 0) <= 27 + 90 + 3)
    }

    @Test
    fun calibrationIntervalCountsSamplesNotFrames() {
        // 리뷰 M4: stride 2여도 5샘플(=10프레임)마다 보정
        val det = FakeDetector()
        run(25, FakePose { listOf(person(0.5f, 0.5f)) }, det, stride = 2, calibEverySamples = 5)
        assertEquals(listOf(0, 10, 20), det.calls)
    }

    @Test
    fun bodyBoxIsVisibleJointExtentInPixels() {
        val pose = FakePose { listOf(person(0.5f, 0.5f)) }
        val r = run(6, pose, FakeDetector(), stride = 1)
        assertEquals(0.4 * h, r.body[0][1], 1e-3) // 머리 y = 0.5 - 0.1
        assertEquals(0.6 * h, r.body[0][3], 1e-3) // 발 y = 0.5 + 0.1
    }

    @Test
    fun ptsAreKeptPerFrame() {
        val r = run(5, FakePose { listOf(person(0.5f, 0.5f)) }, FakeDetector(), stride = 1)
        assertEquals(5, r.ptsUs.size)
        assertEquals(133_333L, r.ptsUs[4])
    }

    @Test(expected = IllegalStateException::class)
    fun noClimberAnywhereFailsClearly() {
        run(10, FakePose { emptyList() }, FakeDetector(), stride = 1)
    }

    @Test
    fun recommendedStrideKeepsAboutTenAnalysesPerSecond() {
        assertEquals(3, Analyzer.strideFor(30.0))
        assertEquals(6, Analyzer.strideFor(60.0))
        assertEquals(1, Analyzer.strideFor(8.0))
        assertTrue(Analyzer.strideFor(29.97) == 3)
    }

    @Test
    fun calibrationEveryHalfSecondOfSamples() {
        assertEquals(5, Analyzer.calibSamplesFor(30.0, stride = 3))
        assertEquals(5, Analyzer.calibSamplesFor(60.0, stride = 6))
        assertEquals(6, Analyzer.calibSamplesFor(24.0, stride = 2))
    }
}
