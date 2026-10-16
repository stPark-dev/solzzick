package com.zzick.core

import com.zzick.core.overlay.CaptureDate
import com.zzick.core.store.OutputName
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 결과 영상 파일명: solzzick_<원본 촬영 일시>.mp4 — 원본과 짝을 찾고 촬영일 순으로 정렬되게(사용자 요청). */
class OutputNameTest {
    private val now = LocalDateTime.of(2026, 10, 16, 9, 1, 2)
    private val taken = LocalDateTime.of(2026, 5, 25, 9, 30, 59)

    @Test fun galaxyFileNameTimeComesFirst() =
        assertEquals("solzzick_20260525_093059.mp4", OutputName.forSource(LocalDateTime.of(2026, 10, 1, 0, 0), "20260525_093059.mp4", now))

    @Test fun editedGalleryCopyKeepsTheOriginalTime() =
        assertEquals("solzzick_20261011_110708.mp4", OutputName.forSource(null, "20261011_110708~2.mp4", now))

    @Test fun captureTimeWhenFileNameHasNone() =
        assertEquals("solzzick_20260525_093059.mp4", OutputName.forSource(taken, "KakaoTalk_Video_abc.mp4", now))

    @Test fun nowWhenNothingIsKnown() =
        assertEquals("solzzick_20261016_090102.mp4", OutputName.forSource(null, null, now))

    @Test fun captureTimeIsReadInLocalTime() =
        assertEquals(taken, CaptureDate.parseDateTime("20260525T003059.000Z", ZoneId.of("Asia/Seoul")))

    /** 리뷰에서 찾음: 더 긴 숫자열의 일부를 일시로 잡지 않는다. */
    @Test fun digitsInsideLongerNumbersAreNotATime() =
        assertEquals("solzzick_20260525_093059.mp4", OutputName.forSource(taken, "IMG_123456789_1234567.mp4", now))
}
