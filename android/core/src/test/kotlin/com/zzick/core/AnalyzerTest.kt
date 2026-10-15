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

    /** 점검·문제 신고용: 샘플 프레임마다 후보 수, 채택 결과(위치·옷 색 유사도·잘라서 찾았는지), 사람 박스를 알려 준다. */
    @Test
    fun reportsEverySampleForDiagnostics() {
        val pose = FakePose(crop = { _, _ -> listOf(person(0.6f, 0.5f)) }) { i -> if (i < 30) listOf(person(0.5f, 0.5f)) else emptyList() }
        val det = FakeDetector { i -> if (i >= 30) listOf(Box(0.52, 0.38, 0.68, 0.62)) else emptyList() }
        val samples = mutableListOf<com.zzick.core.analysis.SampleReport>()
        Analyzer(pose, det, w, h, fps, stride = 3, appearance = HoldClusterLook(), onSample = { samples += it })
            .apply { for (i in 0 until 45) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
        assertEquals((0 until 45 step 3).toList(), samples.map { it.index })
        val first = samples.first()
        assertEquals(1, first.candidates)
        assertEquals(0.5 * w, first.chosen!![0], 1e-3) // 관절 좌표가 float
        assertTrue("익히기 전엔 유사도 없음", first.similarity == null)
        val later = samples.first { it.index == 30 }
        assertTrue("잘라서 찾음", later.viaCrop)
        assertEquals(0.6 * w, later.chosen!![0], 1e-3)
        assertEquals(1.0, later.similarity!!, 1e-9)
        val box = later.boxes.single()
        listOf(0.52 * w, 0.38 * h, 0.68 * w, 0.62 * h).forEachIndexed { k, v -> assertEquals(v, box[k], 1e-6) }
    }

    /**
     * 점검에서 찾음(20260612_184742): 첫 장면에 등반자가 없어 포즈 모델이 홀드를 사람으로 한 번 냄 → 기준 위치가 그 홀드.
     * 사람 검출기가 홀드를 사람으로 잘못 잡은 박스(20초 동안 좌표 그대로)가 기준 위치를 계속 새로 고쳐,
     * 화면 가운데의 진짜 등반자가 늘 '너무 멀다'며 거절됨. 움직이지 않는 박스는 사람으로 보지 않는다.
     */
    @Test
    fun motionlessFalsePersonBoxDoesNotTrapTheAnchor() {
        fun cx(i: Int) = 0.45 + 0.0003 * i
        val pose = FakePose { i ->
            when {
                i == 6 -> listOf(person(0.92f, 0.64f)) // 홀드를 사람으로
                i < 60 -> emptyList()
                else -> listOf(person(cx(i).toFloat(), 0.62f))
            }
        }
        val det = FakeDetector { i ->
            val wall = Box(0.84, 0.26, 0.99, 0.35) // 홀드를 사람으로 잘못 잡은 박스 — 계속 그대로
            if (i >= 45) listOf(wall, Box(cx(i) - 0.12, 0.40, cx(i) + 0.12, 0.95)) else listOf(wall)
        }
        val r = run(450, pose, det, stride = 3)
        for (i in 150 until 450 step 3) {
            assertEquals("frame $i src", Fuse.POSE, r.source[i])
            assertEquals("frame $i x", cx(i) * w, r.track[i][0], 0.02 * w)
        }
    }

    /** 점검에서 찾음(20260612_184742 1.5s·25s): 카메라 앞을 지나는 손·몸은 포즈 모델이 몸통을 화면 밖(y<0)으로 추정 — 받지 않는다. */
    @Test
    fun poseWithTorsoOutsideFrameIsIgnored() {
        // 1초 공백 뒤(이동 가능 거리가 넉넉해진 때) 몸통이 화면 위 밖인 포즈가 나온다
        val pose = FakePose { i ->
            when {
                i < 30 -> listOf(person(0.5f, 0.5f))
                i < 60 -> emptyList()
                i < 66 -> listOf(person(0.3f, -0.3f)) // 영상 끝 무렵 — 뒤로 포즈가 없다
                else -> emptyList()
            }
        }
        val r = run(90, pose, FakeDetector(), stride = 3)
        for (i in 0 until 90) assertTrue("frame $i y=${r.track[i][1]}", r.track[i][1] > 0.3 * h)
    }

    /**
     * 점검에서 찾음(20260612_184742): 등반자가 나오기 전 첫 포즈 셋이 분홍 홀드라 옷 색 기억이 홀드 색으로 굳음.
     * 이후 진짜 등반자는 '근처 + 사람 검출기 확인' 예외로만 받아졌다. 사람 검출기가 확인한 등반자가 계속 색이 다르면
     * 기억이 틀린 것 — 다시 익힌다. 그래야 공백 뒤 먼 곳에서 다시 잡을 때 등반자를 알아본다.
     */
    @Test
    fun wrongFirstLookIsRelearnedFromDetectorConfirmedClimber() {
        val holdLook = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box) = if (box.cx > 0.8) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
        }
        fun cx(i: Int) = if (i < 150) 0.45 + 0.0005 * (i - 30) else 0.75
        val pose = FakePose { i ->
            when {
                i < 10 -> listOf(person(0.92f, 0.64f)) // 등반자 나오기 전: 분홍 홀드를 사람으로
                i < 30 -> emptyList()
                i < 150 -> listOf(person(cx(i).toFloat(), 0.5f))
                i < 170 -> emptyList() // 큰 동작으로 놓침
                else -> listOf(person(0.75f, 0.5f)) // 먼 곳에서 다시 잡힘(사람 박스 없음)
            }
        }
        val det = FakeDetector { i -> if (i in 30 until 150) listOf(Box(cx(i) - 0.1, 0.36, cx(i) + 0.1, 0.64)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, appearance = holdLook)
            .apply { for (i in 0 until 210) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 171 until 210 step 3) {
            assertEquals("frame $i src", Fuse.POSE, r.source[i])
            assertEquals("frame $i x", 0.75 * w, r.track[i][0], 0.02 * w)
        }
    }

    /**
     * 점검에서 찾음(20260801_155833): 쉬는 등반자는 포즈가 3초간 안 잡혀도 사람 박스는 조금씩 흔들린다(가장자리 수 px~수십 px).
     * '움직이지 않는 박스'로 빼면 카메라가 등반자를 놓친다 — 홀드를 잘못 잡은 박스는 1~3px만 다르다.
     */
    @Test
    fun restingClimberWithJitteringBoxIsKept() {
        val pose = FakePose { i -> if (i in 30 until 120) emptyList() else listOf(person(0.4f, 0.5f)) }
        val jitter = java.util.Random(5)
        val det = FakeDetector { _ ->
            val j = { (jitter.nextDouble() - 0.5) * 0.03 }
            listOf(Box(0.28 + j(), 0.30 + j(), 0.52 + j(), 0.72 + j()))
        }
        val r = run(150, pose, det, stride = 3)
        for (i in 30 until 120) assertEquals("frame $i x", 0.4 * w, r.track[i][0], 0.05 * w)
        for (i in 33 until 117 step 3) assertEquals("frame $i src", Fuse.BOX, r.source[i])
    }

    /**
     * 점검에서 찾음(20260821_155813): 등반자를 놓친 사이 매트에 앉은 작은 사람(등반자의 1/8)의 박스를 잘라 포즈를 찾아
     * 그 사람을 따라감. 잘라서 다시 찾기·기준 위치 갱신은 등반자 몸 크기(0.5~2배)의 박스만 쓴다.
     */
    @Test
    fun tinyPersonBoxIsNotUsedToRecoverTheClimber() {
        val pose = FakePose(crop = { _, b -> listOf(person(b.cx.toFloat(), b.cy.toFloat())) }) { i ->
            if (i < 30 || i >= 150) listOf(person(0.5f, 0.45f)) else emptyList()
        }
        val det = FakeDetector { i -> if (i in 30 until 150) listOf(Box(0.60, 0.78, 0.63, 0.83)) else emptyList() } // 작은 사람(높이 0.05)
        val r = run(180, pose, det, stride = 3)
        for (i in 0 until 180) assertEquals("frame $i x", 0.5 * w, r.track[i][0], 0.05 * w)
        assertTrue("작은 사람 박스를 잘라 찾음", pose.cropCalls.isEmpty())
    }

    /**
     * 코드 리뷰 지적: 등반자가 사라진 사이 바로 옆 홀드(색 다름)가 '근처 + 사람 박스' 예외로 한 번 받아지면,
     * 그 박스가 '최근 등반자가 들어 있던 박스'로 계속 살아남고, 1초 뒤엔 색 기억까지 홀드로 다시 익혀 버린다.
     * 색이 확인된 포즈만 그 예외를 만들고, 다시 익히기는 실제로 움직인 포즈(홀드는 안 움직임)로만.
     */
    @Test
    fun holdAcceptedOnceDoesNotTakeOverTheClimbersLook() {
        val look = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box) = if (box.cx > 0.53 && box.cx < 0.6) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
        }
        val pose = FakePose { i ->
            when {
                i < 60 -> listOf(person(0.5f, 0.5f)) // 등반자(색 A)
                i < 300 -> listOf(person(0.56f, 0.5f)) // 등반자는 사라지고 바로 옆 홀드(색 B)를 사람으로
                else -> listOf(person(0.8f, 0.5f)) // 등반자가 먼 곳에서 다시(사람 박스 없음)
            }
        }
        val det = FakeDetector { i -> if (i in 60 until 300) listOf(Box(0.48, 0.36, 0.64, 0.64)) else emptyList() } // 홀드를 사람으로 잘못 잡은 박스
        val r = Analyzer(pose, det, w, h, fps, stride = 3, appearance = look)
            .apply { for (i in 0 until 330) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 303 until 330 step 3) {
            assertEquals("frame $i src", Fuse.POSE, r.source[i])
            assertEquals("frame $i x", 0.8 * w, r.track[i][0], 0.02 * w)
        }
    }

    /**
     * 점검에서 찾음(20260914_153037, 20260612_184742): 등반자가 화면에 들어오기 전, 포즈 모델이 홀드를 사람으로 냄 —
     * 비교할 기준이 없어 그대로 첫 포즈가 되고, 들어온 등반자는 '너무 멀다'며 한참 못 받음.
     * 한 명 모드의 첫 포즈는 사람 검출기 박스 안이어야 한다(앱에서 켜는 설정).
     */
    @Test
    fun firstPoseMustBeAPersonTheDetectorSees() {
        fun cx(i: Int) = 0.15 + 0.002 * (i - 60)
        val pose = FakePose { i ->
            when {
                i < 60 -> listOf(person(0.64f, 0.5f)) // 홀드를 사람으로(등반자 입장 전)
                else -> listOf(person(cx(i).toFloat(), 0.5f), person(0.64f, 0.5f))
            }
        }
        val det = FakeDetector { i -> if (i >= 60) listOf(Box(cx(i) - 0.1, 0.36, cx(i) + 0.1, 0.64)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true)
            .apply { for (i in 0 until 150) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 0 until 60 step 3) assertTrue("frame $i 홀드를 첫 포즈로 받음", r.source[i] != Fuse.POSE)
        for (i in 60 until 150 step 3) {
            assertEquals("frame $i src", Fuse.POSE, r.source[i])
            assertEquals("frame $i x", cx(i) * w, r.track[i][0], 0.02 * w)
        }
    }

    /**
     * 점검에서 찾음(20260607_151150): 탭한 사람(벽 아래)이 아니라 0.43H 떨어진 매트의 작은 사람이 첫 포즈로 잡힘 —
     * 첫 포즈는 거리 제한 없이 탭에 가장 가까운 후보였다. 탭 모드의 첫 포즈는 탭 근처(0.2H)여야 한다.
     */
    @Test
    fun firstPoseMustBeNearTheTap() {
        val pose = FakePose { i ->
            if (i < 60) listOf(person(0.99f, 0.86f)) // 탭과 먼 작은 사람만 잡힘
            else listOf(person(0.60f, 0.70f), person(0.99f, 0.86f))
        }
        val det = FakeDetector { i ->
            listOf(Box(0.97, 0.80, 1.01, 0.92)) + if (i >= 60) listOf(Box(0.5, 0.56, 0.7, 0.84)) else emptyList()
        }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, tap = 0.59 to 0.76, confirmStart = true)
            .apply { for (i in 0 until 120) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 0 until 120) assertTrue("frame $i x=${r.track[i][0] / w}", r.track[i][0] < 0.8 * w)
        for (i in 60 until 120 step 3) assertEquals("frame $i src", Fuse.POSE, r.source[i])
    }

    /**
     * 점검에서 찾음(20260607_151150 v5): 등반자가 들어오기 전, 탭 자리 벽의 홀드를 포즈 모델이 사람으로 냄 —
     * 탭 근처라 첫 포즈가 되고 그 색을 익혀, 들어온 등반자를 영상 내내 놓침.
     * 탭 모드의 첫 포즈도 사람 검출기 박스 안이어야 한다.
     */
    @Test
    fun firstPoseNearTheTapMustAlsoBeAPersonTheDetectorSees() {
        val pose = FakePose { i ->
            if (i < 60) listOf(person(0.59f, 0.74f)) // 탭 자리 홀드를 사람으로(등반자 입장 전)
            else listOf(person(0.62f, 0.70f))
        }
        val det = FakeDetector { i -> if (i >= 60) listOf(Box(0.52, 0.56, 0.72, 0.84)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, tap = 0.59 to 0.76, confirmStart = true)
            .apply { for (i in 0 until 120) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 0 until 60 step 3) assertTrue("frame $i 홀드를 첫 포즈로 받음", r.source[i] != Fuse.POSE)
        for (i in 60 until 120 step 3) assertEquals("frame $i src", Fuse.POSE, r.source[i])
    }

    /**
     * 점검에서 찾음(20261011_105654): 등반자가 오른쪽으로 나간 뒤 4초쯤, 포즈 모델이 벽의 홀드를 사람으로 드문드문 냄 —
     * 색이 비슷하고(0.7대) 오래 비어 속도 제한도 넉넉해 채택됨 → 카메라가 빈 벽으로 끌려가고 자동 트리밍 끝이 늘어남.
     * 오래(2초 이상) 놓친 뒤 다시 잡는 포즈도 첫 포즈처럼 사람 검출기 박스 안이어야 한다.
     */
    @Test
    fun poseAfterLongGapMustBeAPersonTheDetectorSees() {
        val pose = FakePose { i ->
            when {
                i < 100 -> listOf(person(0.5f, 0.5f))
                i in 220 until 240 -> listOf(person(0.3f, 0.5f)) // 빈 벽의 홀드
                else -> emptyList()
            }
        }
        val det = FakeDetector { i -> if (i < 100) listOf(Box(0.4, 0.36, 0.6, 0.64)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true)
            .apply { for (i in 0 until 270) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 0 until 100 step 3) assertEquals("frame $i src", Fuse.POSE, r.source[i])
        for (i in 222 until 240 step 3) assertTrue("frame $i 빈 벽 포즈를 받음", r.source[i] != Fuse.POSE)
    }

    /** 잠깐 놓쳤다 다시 잡을 때 검출기가 없더라도(1초) 그대로 받는다 — 위 규칙은 오래 놓친 뒤에만. */
    @Test
    fun poseAfterShortGapIsAcceptedWithoutDetector() {
        val pose = FakePose { i -> if (i in 60 until 90) emptyList() else listOf(person(0.5f, 0.5f)) }
        val det = FakeDetector { i -> if (i < 30) listOf(Box(0.4, 0.36, 0.6, 0.64)) else emptyList() }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true)
            .apply { for (i in 0 until 150) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 90 until 150 step 3) assertEquals("frame $i src", Fuse.POSE, r.source[i])
    }

    /**
     * 점검에서 찾음(20260821_155813 v7 23.8s): 등반자를 놓친 사이 멀리 있는 작은 사람 박스 주변을 잘라 포즈를 찾았는데,
     * 몸통 칸이 너무 작아 색을 못 구함(null) → 비교 없이 받아 그 사람을 끝까지 따라감.
     * 색을 익힌 뒤 잘라서 찾은 포즈는 색이 맞는다고 확인될 때만 받는다.
     */
    @Test
    fun cropPoseWhoseLookCannotBeMeasuredIsRejected() {
        val pose = FakePose(crop = { _, b -> if (b.cx > 0.6) listOf(person(0.64f, 0.78f)) else emptyList() }) { i ->
            if (i < 60) listOf(person(0.5f, 0.5f)) else emptyList()
        }
        val look = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box): FloatArray? = if (box.cx > 0.6) null else floatArrayOf(1f, 0f)
        }
        val det = FakeDetector { i ->
            listOf(Box(0.62, 0.72, 0.66, 0.83)) + if (i < 60) listOf(Box(0.4, 0.36, 0.6, 0.64)) else emptyList()
        }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, appearance = look, confirmStart = true)
            .apply { for (i in 0 until 120) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 60 until 120 step 3) assertTrue("frame $i 작은 사람을 받음", r.source[i] != Fuse.POSE)
    }

    /**
     * 점검에서 찾음(20260821_155813 v7 23.1~24.0s): 상체만 잡힌 포즈(키 0.16)가 한 번 들어오자 크기 기준이 그 값으로 줄어,
     * 멀리 있는 작은 사람 박스(0.09)도 '등반자 크기'로 통과 → 그 주변을 잘라 찾은 포즈를 받고 끝까지 따라감.
     * 크기 기준은 최근 몇 초 포즈 키의 중앙값이어야 한다.
     */
    @Test
    fun onePartialPoseDoesNotShrinkTheClimberSize() {
        fun tall(x: Float, y: Float) = person(x, y).let { p ->
            PoseCandidate(p.x, FloatArray(33) { i -> if (i == 0) y - 0.2f else if (i in 27..32) y + 0.2f else p.y[i] }, p.visibility)
        }
        fun short(x: Float, y: Float) = person(x, y).let { p ->
            PoseCandidate(p.x, FloatArray(33) { i -> if (i == 0) y - 0.08f else if (i in 27..32) y + 0.08f else p.y[i] }, p.visibility)
        }
        val pose = FakePose(crop = { i, b ->
            when {
                b.cx > 0.75 -> listOf(person(0.84f, 0.78f)) // 멀리 있는 작은 사람
                i == 63 -> listOf(short(0.5f, 0.4f)) // 상체만 잡힌 등반자
                else -> emptyList()
            }
        }) { i -> if (i < 60) listOf(tall(0.5f, 0.45f)) else emptyList() }
        val det = FakeDetector { i ->
            val j = if (i % 2 == 0) 0.01 else -0.01 // 실제 검출처럼 가장자리가 흔들림(움직이지 않는 박스로 빠지지 않게)
            listOf(Box(0.35 + j, 0.2, 0.65, 0.75 - j), Box(0.82 + j, 0.73, 0.86, 0.82 + j))
        }
        val chosen = mutableMapOf<Int, DoubleArray>()
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true, onSample = { s -> s.chosen?.let { chosen[s.index] = it } })
            .apply { for (i in 0 until 120) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        assertTrue("상체만 잡힌 등반자는 받음", 63 in chosen)
        for ((i, c) in chosen) assertTrue("frame $i 작은 사람을 받음 x=${c[0] / w}", c[0] < 0.7 * w)
        for (i in 0 until 120) assertTrue("frame $i 작은 사람으로 감 x=${r.track[i][0] / w}", r.track[i][0] < 0.7 * w)
    }

    /**
     * 리뷰에서 찾음: 가만히 쉬는 등반자를 2초 넘게 놓치면, 그 사이 박스가 '움직이지 않는 박스'로 빠지고 다시 잡을 때는
     * 사람 박스 확인이 필요해 영영 못 잡았다. 다시 잡을 때의 확인은 움직임 필터 전 박스(등반자 크기)로 한다.
     */
    @Test
    fun stillClimberIsReacquiredAfterLongGap() {
        val pose = FakePose { i -> if (i in 60 until 150) emptyList() else listOf(person(0.5f, 0.5f)) }
        val det = FakeDetector { _ -> listOf(Box(0.4, 0.36, 0.6, 0.64)) }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true)
            .apply { for (i in 0 until 200) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 150 until 200 step 3) assertEquals("frame $i src", Fuse.POSE, r.source[i])
    }

    /** 리뷰에서 찾음: 3초 넘게 놓친 뒤 처음 잡은 포즈가 상체뿐이면 크기 기준이 그 하나로 줄던 문제 — 최근 몇 개는 남긴다. */
    @Test
    fun partialPoseAfterLongGapDoesNotShrinkTheClimberSize() {
        fun tall(x: Float, y: Float) = person(x, y).let { p ->
            PoseCandidate(p.x, FloatArray(33) { i -> if (i == 0) y - 0.2f else if (i in 27..32) y + 0.2f else p.y[i] }, p.visibility)
        }
        fun short(x: Float, y: Float) = person(x, y).let { p ->
            PoseCandidate(p.x, FloatArray(33) { i -> if (i == 0) y - 0.08f else if (i in 27..32) y + 0.08f else p.y[i] }, p.visibility)
        }
        val pose = FakePose(crop = { i, b ->
            when {
                b.cx > 0.75 -> listOf(person(0.84f, 0.78f)) // 멀리 있는 작은 사람
                i == 171 -> listOf(short(0.5f, 0.4f)) // 오래 놓친 뒤 상체만 잡힌 등반자
                else -> emptyList()
            }
        }) { i -> if (i < 60) listOf(tall(0.5f, 0.45f)) else emptyList() }
        val det = FakeDetector { i ->
            val j = if (i % 2 == 0) 0.01 else -0.01
            listOf(Box(0.35 + j, 0.2, 0.65, 0.75 - j), Box(0.82 + j, 0.73, 0.86, 0.82 + j))
        }
        val chosen = mutableMapOf<Int, DoubleArray>()
        Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true, onSample = { s -> s.chosen?.let { chosen[s.index] = it } })
            .apply { for (i in 0 until 230) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        assertTrue("상체만 잡힌 등반자는 받음", 171 in chosen)
        for ((i, c) in chosen) assertTrue("frame $i 작은 사람을 받음 x=${c[0] / w}", c[0] < 0.7 * w)
    }

    /**
     * 분석 속도: 사람 검출기(1회 약 90ms)가 분석 시간의 약 35% — 그중 42%가 포즈를 1초 넘게 못 찾는 동안(등반 전후 빈 화면)의 호출.
     * 오래 못 찾는 동안은 0.3초마다만 찾는다(다시 잡는 포즈 확인은 필요할 때 바로 검출).
     */
    @Test
    fun detectorRunsLessOftenWhileClimberIsLongGone() {
        val pose = FakePose { i -> if (i < 30) listOf(person(0.5f, 0.5f)) else emptyList() }
        val det = FakeDetector()
        Analyzer(pose, det, w, h, fps, stride = 3)
            .apply { for (i in 0 until 300) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        val late = det.calls.count { it >= 60 } // 80샘플
        assertTrue("검출 $late 번", late <= 40)
    }

    /**
     * 사용자 제보(20260525_093059): 포즈를 놓친 사이 광학 흐름이 벽 쪽으로 미끄러지고, 그 끝에서 몸통 칸이 너무 작아 색을 못 잰
     * 가짜 포즈를 화면 전체 포즈라는 이유로 색 확인 없이 받음 → 벽에 붙어 30초 동안 등반자를 놓침.
     * 색을 익힌 뒤에는 화면 전체 포즈도 색을 잴 수 있고 맞아야 받는다(잘라서 찾은 포즈와 같은 규칙).
     */
    @Test
    fun wholeFramePoseWhoseLookCannotBeMeasuredIsRejected() {
        val pose = FakePose { i -> if (i < 60) listOf(person(0.5f, 0.5f)) else listOf(person(0.56f, 0.58f)) }
        val look = object : com.zzick.core.analysis.AppearanceExtractor<Int> {
            override fun describe(frame: Int, box: Box): FloatArray? = if (frame >= 60) null else floatArrayOf(1f, 0f)
        }
        val chosen = mutableMapOf<Int, DoubleArray>()
        Analyzer(pose, FakeDetector(), w, h, fps, stride = 3, appearance = look, confirmStart = false,
            onSample = { s -> s.chosen?.let { chosen[s.index] = it } })
            .apply { for (i in 0 until 120) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        assertTrue("색을 익힌 동안은 받음", (0 until 60 step 3).all { it in chosen })
        for (i in 60 until 120 step 3) assertTrue("frame $i 색을 못 잰 포즈를 받음", i !in chosen)
    }

    /**
     * 사용자 제보(20260525_093059 46.1s): 포즈 모델이 20초 동안 등반자를 못 찾는 사이 검출기 박스로 위치를 따라가다가,
     * 검출기가 등반자를 잠깐(0.2초) 놓친 순간 0.6H 떨어진 바닥의 다른 사람 박스를 잘라 찾은 포즈를 받음(속도 상한 3H/s면
     * 0.3초에 0.95H까지 허용) → 24~46초가 그쪽으로 끌려감. 잘라서 다시 찾기는 현실적인 속도(1H/s)로 거리를 제한한다.
     */
    @Test
    fun cropRecoveryIgnoresFarPersonRightAfterLosingTheClimberBox() {
        fun cx(i: Int) = 0.45 + 0.0005 * i
        val pose = FakePose(crop = { _, b -> if (b.cx > 0.8) listOf(person(0.87f, 0.8f)) else emptyList() }) { i ->
            if (i < 30) listOf(person(cx(i).toFloat(), 0.4f)) else emptyList()
        }
        val det = FakeDetector { i ->
            val j = if (i % 2 == 0) 0.01 else -0.01
            when {
                i in 90 until 96 -> emptyList() // 등반자 박스를 잠깐 놓침
                i == 96 -> listOf(Box(0.82, 0.7, 0.92, 0.9)) // 바닥의 다른 사람만
                else -> listOf(Box(cx(i) - 0.1 + j, 0.2, cx(i) + 0.1, 0.62 - j))
            }
        }
        val chosen = mutableMapOf<Int, DoubleArray>()
        Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true, onSample = { s -> s.chosen?.let { chosen[s.index] = it } })
            .apply { for (i in 0 until 150) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for ((i, c) in chosen) assertTrue("frame $i 멀리 있는 다른 사람을 받음 x=${c[0] / w}", c[0] < 0.7 * w)
    }

    /**
     * 사용자 제보(20260525_093059 24~48s): 포즈 모델이 24초 동안 등반자를 못 찾는 사이 등반자는 위로 올라갔다 내려옴 —
     * 검출기는 매 샘플 등반자 박스를 잡지만, 두 포즈 사이 직선 예상 위치에서 멀어 버려지고 화면이 엉뚱한 곳으로 끌려감.
     * 분석기가 이어서 따라간 박스(anchorToBox)는 직선 예상과 멀어도 쓴다.
     */
    @Test
    fun boxesFollowedThroughLongPoseGapAreUsedEvenFarFromStraightLine() {
        fun cy(i: Int) = if (i < 150) 0.6 - 0.003 * (i - 30) else 0.24 + 0.003 * (i - 150) // 위로 갔다 내려옴
        val pose = FakePose { i -> if (i < 30 || i >= 270) listOf(person(0.5f, cy(i).toFloat())) else emptyList() }
        val det = FakeDetector { i ->
            val j = if (i % 2 == 0) 0.01 else -0.01
            listOf(Box(0.4 + j, cy(i) - 0.2, 0.6, cy(i) + 0.2 - j))
        }
        val r = Analyzer(pose, det, w, h, fps, stride = 3, confirmStart = true)
            .apply { for (i in 0 until 300) onFrame(i, (i * 1_000_000L / fps).toLong(), i) }
            .finish(fps)
        for (i in 120 until 180 step 3) {
            assertEquals("frame $i src", Fuse.BOX, r.source[i])
            assertEquals("frame $i y", cy(i) * h, r.track[i][1], 0.05 * h)
        }
    }
}
