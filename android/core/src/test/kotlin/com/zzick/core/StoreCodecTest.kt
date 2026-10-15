package com.zzick.core

import com.zzick.core.analysis.AnalysisResult
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.core.quality.SourceInfo
import com.zzick.core.store.AnalysisCodec
import com.zzick.core.store.AnalysisRecord
import com.zzick.core.store.BatchQueueCodec
import com.zzick.core.trim.TrimWindow
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StoreCodecTest {
    private fun record(tap: Pair<Double, Double>?, source: SourceInfo?) = AnalysisRecord(
        AnalysisResult(
            track = arrayOf(doubleArrayOf(1.5, 2.25), doubleArrayOf(Double.NaN, 3.0)),
            body = arrayOf(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0, 7.125)),
            source = intArrayOf(1, 4),
            ptsUs = longArrayOf(0L, 33_366L),
        ),
        srcW = 1440.0, srcH = 1440.0, fps = 29.97, source = source, tap = tap,
    )

    private fun AnalysisRecord.roundTrip(): AnalysisRecord? {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(this, it) }.toByteArray()
        return AnalysisCodec.read(ByteArrayInputStream(bytes))
    }

    @Test
    fun analysisRoundTripIsExact() {
        for (r in listOf(record(0.25 to 0.75, SourceInfo(1440, 1440, 29.97, 14_500_000, true)), record(null, null))) {
            val back = r.roundTrip()!!
            assertEquals(r.result.track.size, back.result.track.size)
            r.result.track.indices.forEach { assertArrayEquals(r.result.track[it], back.result.track[it], 0.0) }
            r.result.body.indices.forEach { assertArrayEquals(r.result.body[it], back.result.body[it], 0.0) }
            assertArrayEquals(r.result.source, back.result.source)
            assertArrayEquals(r.result.ptsUs, back.result.ptsUs)
            assertEquals(listOf(r.srcW, r.srcH, r.fps), listOf(back.srcW, back.srcH, back.fps))
            assertEquals(r.source, back.source)
            assertEquals(r.tap, back.tap)
        }
    }

    @Test
    fun corruptOrOtherVersionReadsAsNull() {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(record(null, null), it) }.toByteArray()
        assertNull("잘린 파일", AnalysisCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))))
        assertNull("빈 파일", AnalysisCodec.read(ByteArrayInputStream(ByteArray(0))))
        val otherVersion = bytes.copyOf().also { it[7] = (it[7] + 1).toByte() } // 매직(4) 다음 int 버전의 마지막 바이트
        assertNull("다른 분석 버전", AnalysisCodec.read(ByteArrayInputStream(otherVersion)))
    }

    /** 0.3.5에서 등반자 고르는 방식이 바뀌었다 — 그 전(버전 1)에 저장한 분석은 버리고 다시 분석해야 한다. */
    @Test
    fun analysesSavedBeforeTrackingFixAreDiscarded() {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(record(null, null), it) }.toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 1) // 버전 1로 저장된 파일
        assertNull(AnalysisCodec.read(ByteArrayInputStream(bytes)))
    }

    /** 0.4.0에서 등반자 고르는 방식이 또 바뀌었다(첫 포즈·다시 잡기 확인, 고르기 화면 프레임) — 버전 2 저장본도 버린다. */
    @Test
    fun analysesSavedBefore040AreDiscarded() {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(record(null, null), it) }.toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 2) // 버전 2로 저장된 파일
        assertNull(AnalysisCodec.read(ByteArrayInputStream(bytes)))
    }

    /** 0.4.4에서 추적이 바뀌었다(색 못 잰 포즈 거절·잘라 찾기 거리·이어 따라간 박스 사용) — 버전 3 저장본도 버린다. */
    @Test
    fun analysesSavedBefore044AreDiscarded() {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(record(null, null), it) }.toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 3) // 버전 3으로 저장된 파일
        assertNull(AnalysisCodec.read(ByteArrayInputStream(bytes)))
    }

    /** 깨진 저장본의 프레임 수가 터무니없으면 메모리를 잡기 전에 거절(1시간 60fps = 21.6만 프레임). */
    @Test
    fun absurdFrameCountIsRejected() {
        val bytes = ByteArrayOutputStream().also { AnalysisCodec.write(record(null, null), it) }.toByteArray()
        // 헤더(매직·버전 8) + 크기·fps(24) + 탭 없음(1) + 원본 정보 없음(1) 다음이 프레임 수
        java.nio.ByteBuffer.wrap(bytes).putInt(34, 5_000_000)
        assertNull(AnalysisCodec.read(ByteArrayInputStream(bytes)))
    }

    private fun sampleQueue(): BatchQueue<String> {
        var q = BatchQueue<String>().add(listOf("content://a", "content://b", "content://c", "content://d", "content://e", "content://f"), createdAtMs = 1_791_000_000_000L)
            .let { it.withCommon(it.jobs[0].id, EditSettings(zoom = 1.2, shake = 2.0, aspect = AspectRatio.R4x5, motionZoom = false)) }
            .let { it.rename(it.jobs[0].id, "강남") }
            .add(listOf("content://h"), createdAtMs = 1_791_000_600_000L) // 두 번째 작업(분석 전)
        // renderAll은 준비된 항목을 모두 넘기므로, 준비됨으로 남길 1번은 마지막에 분석
        q = q.onStarted(6).onScanned(6, 1).onStarted(6).onAnalyzed(6, "A6", TrimWindow(0, 1)).renderAll(1).onStarted(6).onRendered(6, "content://out/6")
        q = q.onStarted(2).onScanned(2, 1).onStarted(2).onAnalyzed(2, "A2", TrimWindow(0, 500)).renderAll(1).onStarted(2) // 렌더 중
        q = q.onStarted(3).onScanned(3, 3) // 선택 대기
        q = q.onStarted(4).onScanned(4, 1).onStarted(4).onProgress(4, 0.4f) // 분석 중
        q = q.onFailed(5, "등반자를 찾지 못했어요")
        q = q.onStarted(1).onScanned(1, 2).onSelected(1, 0.3 to 0.6).onStarted(1).onAnalyzed(1, "A1", TrimWindow(100, 900))
            .withWindow(1, TrimWindow(200, 800)).withItemSettings(1, EditSettings(zoom = 1.5))
        return q
    }

    private fun BatchQueue<String>.roundTrip(): BatchQueue<String>? {
        val bytes = ByteArrayOutputStream().also { BatchQueueCodec.write(this, it) }.toByteArray()
        return BatchQueueCodec.read<String>(ByteArrayInputStream(bytes))
    }

    @Test
    fun queueRoundTripKeepsEverythingButAnalysis() {
        val q = sampleQueue()
        val back = q.roundTrip()!!
        assertEquals(q.jobs, back.jobs)
        assertEquals(q.items.map { it.copy(analysis = null) }, back.items)
        // 다음 항목·작업 번호도 이어져야 한다
        val more = back.add(listOf("content://g"), createdAtMs = 1_791_001_000_000L)
        assertEquals(8, more.items.last().id)
        assertEquals(3, more.jobs.last().id)
        assertNull(BatchQueueCodec.read<String>(ByteArrayInputStream(ByteArray(3))))
    }

    @Test
    fun restoreResumesInterruptedWorkAndReattachesAnalyses() {
        val cached = mapOf("content://a" to "A1")
        val r = sampleQueue().roundTrip()!!.restored { cached[it.source] }
        val byId = r.items.associateBy { it.id }
        // 분석 저장본이 있으면 준비됨 그대로(사용자가 고친 구간·설정 유지)
        assertEquals(Stage.READY, byId[1]!!.stage)
        assertEquals("A1", byId[1]!!.analysis)
        assertEquals(TrimWindow(200, 800), byId[1]!!.window)
        assertEquals(EditSettings(zoom = 1.5), byId[1]!!.settings)
        // 렌더 중이었지만 분석 저장본이 없으면 다시 분석(탭 위치 유지)
        assertEquals(Stage.WAITING_ANALYSIS, byId[2]!!.stage)
        // 선택 화면용 첫 장면은 저장하지 않으므로 다시 사람 찾기부터
        assertEquals(Stage.QUEUED, byId[3]!!.stage)
        assertEquals(Stage.WAITING_ANALYSIS, byId[4]!!.stage)
        assertEquals(0f, byId[4]!!.progress)
        assertEquals(Stage.FAILED, byId[5]!!.stage)
        assertEquals(Stage.DONE, byId[6]!!.stage)
        assertEquals("content://out/6", byId[6]!!.output)
    }

    @Test
    fun restoreKeepsRenderRequestWhenAnalysisIsCached() {
        val cached = mapOf("content://b" to "A2")
        val r = sampleQueue().roundTrip()!!.restored { cached[it.source] }
        val b = r.item(2)
        assertEquals(Stage.WAITING_RENDER, b.stage)
        assertEquals("A2", b.analysis)
    }

    /** 작업 개념 전(버전 2) 저장본: 목록 전체를 "이전 작업" 하나로 읽고 공통 설정을 그 작업에 둔다. */
    @Test
    fun queueSavedBeforeJobsIsReadAsOnePreviousJob() {
        val bytes = ByteArrayOutputStream().also { o ->
            java.io.DataOutputStream(o).apply {
                writeInt(0x534A5A42); writeInt(2) // 매직, 버전 2
                writeInt(3) // 다음 항목 번호
                writeDouble(1.2); writeDouble(2.0); writeUTF("R4x5"); writeBoolean(false) // 공통 설정
                writeUTF("강남"); writeBoolean(true); writeBoolean(false) // 영상 구석 표시
                writeInt(2)
                for ((id, src) in listOf(1 to "content://a", 2 to "content://b")) {
                    writeInt(id); writeUTF(src); writeUTF("DONE"); writeFloat(1f)
                    writeBoolean(false); writeBoolean(false); writeBoolean(false) // 탭·구간·개별 설정 없음
                    writeBoolean(true); writeUTF("content://out/$id"); writeBoolean(false) // 결과, 오류 없음
                }
                flush()
            }
        }.toByteArray()
        val q = BatchQueueCodec.read<String>(ByteArrayInputStream(bytes))!!
        assertEquals(1, q.jobs.size)
        assertEquals("이전 작업", q.jobs[0].name)
        assertEquals(EditSettings(zoom = 1.2, shake = 2.0, aspect = AspectRatio.R4x5, motionZoom = false, overlay = com.zzick.core.overlay.OverlayInfo("강남", true, null)), q.jobs[0].common)
        assertEquals(listOf("content://a", "content://b"), q.itemsOf(q.jobs[0].id).map { it.source })
        val more = q.add(listOf("content://c"), createdAtMs = 1L)
        assertEquals(3, more.items.last().id)
        assertEquals(2, more.jobs.last().id)
    }

    /** 리뷰에서 찾음: 깨진 저장본에서 작업이 없는 영상은 빼고 읽는다(작업을 못 찾아 설정 조회가 죽지 않게). */
    @Test
    fun videosWithoutTheirJobAreDropped() {
        val q = BatchQueue<String>().add(listOf("content://a"), createdAtMs = 1L).add(listOf("content://b"), createdAtMs = 2L)
        val broken = q.copy(jobs = q.jobs.take(1)) // 두 번째 작업 정보가 없음
        val back = ByteArrayOutputStream().also { BatchQueueCodec.write(broken, it) }.toByteArray()
            .let { BatchQueueCodec.read<String>(ByteArrayInputStream(it))!! }
        assertEquals(listOf("content://a"), back.items.map { it.source })
    }
}
