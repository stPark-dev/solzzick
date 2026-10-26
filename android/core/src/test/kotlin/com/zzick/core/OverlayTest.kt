package com.zzick.core

import com.zzick.core.batch.BatchQueue
import com.zzick.core.overlay.CaptureDate
import com.zzick.core.overlay.Corner
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo
import com.zzick.core.store.BatchQueueCodec
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.time.ZoneId

class OverlayTest {
    private val day = LocalDate.of(2026, 10, 11)

    @Test
    fun nothingChosenMeansNoOverlay() {
        assertNull(OverlayInfo().text(day))
        assertNull(EditSettings().overlay.text(day))
        assertNull("공백만 있는 암장 이름", OverlayInfo(gym = "   ").text(day))
        assertNull("날짜를 켰지만 촬영 날짜를 모름", OverlayInfo(showDate = true).text(null))
    }

    @Test
    fun partsAppearInFixedOrder() {
        assertEquals("❤️ 더클라임 강남 · 2026.10.11", OverlayInfo(gym = " 더클라임 강남 ", showDate = true, grade = Grade.RED).text(day))
        assertEquals("💙", OverlayInfo(grade = Grade.BLUE).text(day))
        assertEquals("2026.10.11", OverlayInfo(showDate = true).text(day))
        assertEquals("🖤 더클라임", OverlayInfo(gym = "더클라임", grade = Grade.BLACK).text(null))
    }

    @Test
    fun gymNameIsCappedSoItFitsInACorner() {
        val long = "가".repeat(40)
        assertEquals(OverlayInfo.MAX_GYM, OverlayInfo(gym = long).text(day)!!.length)
    }

    @Test
    fun heartsCoverCommonGymColours() {
        assertEquals(
            listOf("❤️", "🧡", "💛", "💚", "🩵", "💙", "💜", "🩷", "🤎", "🩶", "🖤", "🤍"),
            Grade.entries.map { it.emoji },
        )
    }

    @Test
    fun overlaySurvivesBatchListSaveAndRestore() {
        val o = OverlayInfo(gym = "더클라임 강남", showDate = true, grade = Grade.PURPLE)
        val q = BatchQueue<String>().add(listOf("content://a")).let { it.withCommon(it.jobs[0].id, EditSettings(overlay = o)) }
            .withItemSettings(1, EditSettings(aspect = AspectRatio.R4x5, overlay = o.copy(grade = Grade.WHITE)))
        val bytes = ByteArrayOutputStream().also { BatchQueueCodec.write(q, it) }.toByteArray()
        val back = BatchQueueCodec.read<String>(ByteArrayInputStream(bytes))!!
        assertEquals(q.jobs, back.jobs)
        assertEquals(q.items, back.items)
    }

    /** 0.3.3이 저장한 목록(표시 설정 없음)도 그대로 읽힌다. */
    @Test
    fun listSavedByPreviousVersionStillLoads() {
        val bytes = ByteArrayOutputStream().also { o ->
            DataOutputStream(o).apply {
                writeInt(0x534A5A42); writeInt(1); writeInt(2) // 매직, 버전 1, nextId
                writeDouble(1.2); writeDouble(2.0); writeUTF("R4x5"); writeBoolean(false) // 공통 설정
                writeInt(1) // 항목 1개
                writeInt(1); writeUTF("content://a"); writeUTF("QUEUED"); writeFloat(0f)
                repeat(5) { writeBoolean(false) } // tap, window, settings, output, error 없음
                flush()
            }
        }.toByteArray()
        val q = BatchQueueCodec.read<String>(ByteArrayInputStream(bytes))!!
        assertEquals(EditSettings(zoom = 1.2, shake = 2.0, aspect = AspectRatio.R4x5, motionZoom = false), q.jobs[0].common)
        assertEquals(OverlayInfo(), q.jobs[0].common.overlay)
        assertEquals(listOf("content://a"), q.items.map { it.source })
    }

    /** 결과 영상에는 하트를 글자(이모지)가 아니라 색 모양으로 그린다 — 🩵🩷🩶는 안드로이드 14 미만 글꼴에 없어 □로 새겨진다. */
    @Test
    fun wordsLeaveOutTheHeartWhichIsDrawnAsAShape() {
        assertEquals("더클라임 · 2026.10.11", OverlayInfo(gym = "더클라임", showDate = true, grade = Grade.RED).words(day))
        assertNull(OverlayInfo(grade = Grade.SKY).words(day))
        assertEquals("하트마다 다른 색", Grade.entries.size, Grade.entries.map { it.argb }.toSet().size)
        Grade.entries.forEach { assertEquals("불투명: $it", 0xFF, (it.argb ushr 24) and 0xFF) }
    }

    @Test
    fun captureDateParsesCommonMetadataForms() {
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals(LocalDate.of(2026, 9, 27), CaptureDate.parse("20260927T023733.000Z", seoul))
        assertEquals("밀리초 없음", LocalDate.of(2026, 9, 27), CaptureDate.parse("20260927T023733Z", seoul))
        assertEquals("UTC 자정 넘어 한국 날짜는 다음 날", LocalDate.of(2026, 9, 28), CaptureDate.parse("20260927T160000.000Z", seoul))
        assertEquals("시간대 없음 = UTC", LocalDate.of(2026, 9, 28), CaptureDate.parse("20260927T160000", seoul))
        assertNull("빈 값(1904)", CaptureDate.parse("19040101T000000.000Z", seoul))
        assertNull(CaptureDate.parse("not a date", seoul))
        assertNull(CaptureDate.parse(null, seoul))
    }

    /** 사용자 요청: 표시를 네 구석 중에 고른다 — 기본은 지금처럼 왼쪽 아래. */
    @Test
    fun cornerDefaultsToBottomLeft() = assertEquals(Corner.BOTTOM_LEFT, OverlayInfo().corner)

    /** 구석마다 여백(가로 m, 세로 1.5m)을 두고 그 구석에 붙인다. */
    @Test
    fun badgeIsPlacedInTheChosenCorner() {
        val (w, h, bw, bh, m) = listOf(1080f, 1920f, 400f, 60f, 20f)
        assertEquals(20f to 1920f - 30f - 60f, Corner.BOTTOM_LEFT.place(w, h, bw, bh, m))
        assertEquals(1080f - 20f - 400f to 1920f - 30f - 60f, Corner.BOTTOM_RIGHT.place(w, h, bw, bh, m))
        assertEquals(20f to 30f, Corner.TOP_LEFT.place(w, h, bw, bh, m))
        assertEquals(1080f - 20f - 400f to 30f, Corner.TOP_RIGHT.place(w, h, bw, bh, m))
    }

    @Test
    fun cornerSurvivesBatchListSaveAndRestore() {
        val o = OverlayInfo(gym = "암장", corner = Corner.TOP_RIGHT)
        val q = BatchQueue<String>().add(listOf("content://a")).let { it.withCommon(it.jobs[0].id, EditSettings(overlay = o)) }
        val bytes = ByteArrayOutputStream().also { BatchQueueCodec.write(q, it) }.toByteArray()
        assertEquals(Corner.TOP_RIGHT, BatchQueueCodec.read<String>(ByteArrayInputStream(bytes))!!.jobs[0].common.overlay.corner)
    }
}
