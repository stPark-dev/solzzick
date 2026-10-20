package com.zzick.core.store

import com.zzick.core.overlay.Grade
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 결과 영상 파일명: solzzick_<원본 촬영 일시>[_<난이도 색>].mp4 — 원본(갤럭시 YYYYMMDD_HHMMSS.mp4)과 짝을 찾고 촬영일 순으로 정렬되게.
 * 일시는 원본 파일명 속 일시(갤러리에서 잘라 저장한 ~2 사본도 원래 일시) → 영상의 촬영 일시 → 만든 시각 순.
 * 난이도를 고르면 영문 색 이름을 붙인다(purple 등). 같은 이름이 있으면 안드로이드가 " (1)"을 붙인다.
 * 한글은 넣지 않는다(외부로 옮길 때 깨질 수 있음).
 */
object OutputName {
    private val IN_NAME = Regex("""(?<!\d)(\d{8}_\d{6})(?!\d)""") // 더 긴 숫자열의 일부는 아니게
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun forSource(captureTime: LocalDateTime?, sourceName: String?, now: LocalDateTime, grade: Grade? = null): String {
        val stamp = sourceName?.let { IN_NAME.find(it)?.value } ?: (captureTime ?: now).format(FORMAT)
        val color = grade?.let { "_" + it.name.lowercase(Locale.ROOT) } ?: ""
        return "solzzick_$stamp$color.mp4"
    }
}
