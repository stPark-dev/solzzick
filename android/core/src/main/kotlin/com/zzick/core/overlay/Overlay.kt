package com.zzick.core.overlay

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 암장에서 흔히 쓰는 색 난이도 — 화면에서는 색깔 하트 이모지, 결과 영상에는 argb 색의 하트 모양으로 그린다
 * (🩵🩷🩶는 안드로이드 14 미만 글꼴에 없어 영상에 □로 새겨지므로).
 */
enum class Grade(val emoji: String, val label: String, val argb: Int) {
    RED("❤️", "빨강", 0xFFE53935.toInt()),
    ORANGE("🧡", "주황", 0xFFFB8C00.toInt()),
    YELLOW("💛", "노랑", 0xFFFDD835.toInt()),
    GREEN("💚", "초록", 0xFF43A047.toInt()),
    SKY("🩵", "하늘", 0xFF4FC3F7.toInt()),
    BLUE("💙", "파랑", 0xFF1E88E5.toInt()),
    PURPLE("💜", "보라", 0xFF8E24AA.toInt()),
    PINK("🩷", "분홍", 0xFFF48FB1.toInt()),
    BROWN("🤎", "갈색", 0xFF8D6E63.toInt()),
    GRAY("🩶", "회색", 0xFF9E9E9E.toInt()),
    BLACK("🖤", "검정", 0xFF212121.toInt()),
    WHITE("🤍", "흰색", 0xFFFAFAFA.toInt()),
}

/** 표시를 넣을 구석(사용자 요청: 네 구석 중 선택, 기본은 왼쪽 아래). */
enum class Corner(val label: String) {
    TOP_LEFT("왼쪽 위"),
    TOP_RIGHT("오른쪽 위"),
    BOTTOM_LEFT("왼쪽 아래"),
    BOTTOM_RIGHT("오른쪽 아래"),
    ;

    /** w×h 화면에서 bw×bh 표시의 왼쪽 위 좌표. 여백은 가로 m, 세로 1.5m. */
    fun place(w: Float, h: Float, bw: Float, bh: Float, m: Float): Pair<Float, Float> {
        val left = if (this == TOP_LEFT || this == BOTTOM_LEFT) m else w - m - bw
        val top = if (this == TOP_LEFT || this == TOP_RIGHT) m * 1.5f else h - m * 1.5f - bh
        return left to top
    }
}

/** 결과 영상 구석에 넣을 정보. 모두 선택 사항 — 아무것도 고르지 않으면 표시하지 않는다. */
data class OverlayInfo(
    val gym: String = "",
    val showDate: Boolean = false, // 원본을 찍은 날짜
    val grade: Grade? = null,
    val corner: Corner = Corner.BOTTOM_LEFT,
) {
    /** 표시할 한 줄("❤️ 더클라임 강남 · 2026.10.11"). 표시할 게 없으면 null. */
    fun text(captureDate: LocalDate?): String? =
        listOfNotNull(grade?.emoji, words(captureDate)).joinToString(" ").ifEmpty { null }

    /** 하트를 뺀 글자 부분("더클라임 강남 · 2026.10.11"). 없으면 null. */
    fun words(captureDate: LocalDate?): String? = listOfNotNull(
        gym.trim().take(MAX_GYM).ifEmpty { null },
        captureDate?.takeIf { showDate }?.format(DATE),
    ).joinToString(" · ").ifEmpty { null }

    companion object {
        /** 구석에 한 줄로 들어가게 */
        const val MAX_GYM = 20
        private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    }
}

/** 영상 메타데이터의 촬영 시각(예: 20260927T023733.000Z, UTC)을 그 지역의 날짜로. */
object CaptureDate {
    private val FORM = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")

    fun parse(raw: String?, zone: java.time.ZoneId): LocalDate? = parseDateTime(raw, zone)?.toLocalDate()

    /** 촬영 일시(그 지역 시각). 결과 파일명에 쓴다. */
    fun parseDateTime(raw: String?, zone: java.time.ZoneId): java.time.LocalDateTime? {
        val m = FORM.matchEntire(raw?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val offset = m.groupValues[7].let { if (it.isEmpty() || it == "Z") java.time.ZoneOffset.UTC else java.time.ZoneOffset.of(it) }
        return runCatching {
            java.time.OffsetDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), 0, offset)
                .atZoneSameInstant(zone).toLocalDateTime()
        }.getOrNull()?.takeIf { it.year >= 2000 } // 1904·0000 같은 빈 값 제외
    }
}
