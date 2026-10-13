package com.zzick.core.quality

import com.zzick.core.vcam.CameraParams
import com.zzick.core.vcam.VirtualCamera

/**
 * 원본 영상 정보(회전 적용 후 크기).
 * @param bitrate 전체 비트레이트(bps). 모르면 0 — 재압축 판정을 건너뛴다.
 */
data class SourceInfo(val width: Int, val height: Int, val fps: Double, val bitrate: Long, val hevc: Boolean)

enum class QualityIssue {
    /** 원본을 많이 늘려서 출력 — 확대를 줄이면 나아진다 */
    UPSCALE,

    /** 확대를 최소로 해도 많이 늘려야 하는 작은 원본 — 더 좋은 원본이 필요하다 */
    LOW_RESOLUTION,

    /** 비트레이트가 낮아 이미 한 번 이상 압축된 영상(카톡 등)으로 보임 */
    RECOMPRESSED,
}

/** 결과가 흐릴 만한 원본인지 미리 알려 주기 위한 판정. */
object SourceQuality {
    /** 이보다 많이 늘리면 경고. 1:1 카메라 원본(1440)은 기본 확대 1.3에서 1.73배라 경고하지 않는다. */
    const val MAX_UPSCALE = 1.8

    /** 픽셀·프레임당 비트(H.264 기준). 카메라 원본은 0.19~0.37, 갤러리 편집본 0.14, 카톡 0.07 안팎. */
    const val MIN_BITS_PER_PIXEL = 0.1

    /** HEVC는 같은 화질에 H.264보다 비트를 덜 쓴다 */
    private const val HEVC_EFFICIENCY = 1.6

    /** 기본 확대 기준 출력 높이 / 크롭 높이 (자동 줌의 순간적인 변화는 빼고). */
    fun upscale(s: SourceInfo, p: CameraParams): Double =
        p.outHeight / VirtualCamera.cropSize(s.width.toDouble(), s.height.toDouble(), p).second

    fun bitsPerPixel(s: SourceInfo): Double =
        s.bitrate / (s.width.toDouble() * s.height * s.fps) * (if (s.hevc) HEVC_EFFICIENCY else 1.0)

    fun assess(s: SourceInfo, p: CameraParams): Set<QualityIssue> = buildSet {
        if (upscale(s, p.copy(zoom = 1.0)) > MAX_UPSCALE) add(QualityIssue.LOW_RESOLUTION)
        else if (upscale(s, p) > MAX_UPSCALE) add(QualityIssue.UPSCALE)
        if (s.bitrate > 0 && s.fps > 0 && bitsPerPixel(s) < MIN_BITS_PER_PIXEL) add(QualityIssue.RECOMPRESSED)
    }
}
