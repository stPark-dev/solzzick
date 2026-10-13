package com.zzick.core.vcam

import com.zzick.core.overlay.OverlayInfo

/** 출력 비율과 그 비율의 기준 출력 높이(짧은 변 1080). */
enum class AspectRatio(val aspect: Double, val outHeight: Int, val label: String) {
    R9x16(9.0 / 16.0, 1920, "9:16"),
    R4x5(4.0 / 5.0, 1350, "4:5"),
    R16x9(16.0 / 9.0, 1080, "16:9"),
}

/** 편집 화면 슬라이더·비율 → 가상 카메라 파라미터. 나머지는 승인된 기본값 그대로. */
data class EditSettings(
    val zoom: Double = 1.3,
    val shake: Double = 1.0,
    val aspect: AspectRatio = AspectRatio.R9x16,
    val motionZoom: Boolean = true, // 크럭스엔 타이트, 큰 동작엔 넓게
    val overlay: OverlayInfo = OverlayInfo(), // 영상 구석 표시(카메라와 무관)
) {
    fun toParams(base: CameraParams = CameraParams()) = base.copy(
        motionZoom = motionZoom,
        zoom = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX),
        shakeGain = shake.coerceIn(0.0, SHAKE_MAX),
        aspect = aspect.aspect,
        outHeight = aspect.outHeight,
    )

    companion object {
        const val ZOOM_MIN = 1.0
        const val ZOOM_MAX = 1.5
        const val SHAKE_MAX = 10.0
    }
}
