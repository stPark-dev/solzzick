package com.zzick.core

import com.zzick.core.quality.QualityIssue
import com.zzick.core.quality.SourceInfo
import com.zzick.core.quality.SourceQuality
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 실제 갤러리 영상의 해상도·비트레이트(MediaStore 값)로 판정 기준을 고정한다. */
class SourceQualityTest {
    private val cameraOriginal = SourceInfo(1440, 1440, 30.0, 14_500_000, hevc = true) // 갤럭시 1:1 카메라 원본
    private val galleryEdit = SourceInfo(1440, 1440, 30.0, 9_000_000, hevc = false) // 갤러리에서 자른 '~2' 편집본
    private val kakao = SourceInfo(720, 1280, 30.0, 2_000_000, hevc = false) // 카톡으로 받은 영상(일반 화질)
    private val uhd60 = SourceInfo(2160, 3840, 60.0, 50_000_000, hevc = true)
    private val fhd60 = SourceInfo(1920, 1080, 60.0, 24_000_000, hevc = false) // 가로 1080p60

    private fun issues(s: SourceInfo, settings: EditSettings = EditSettings()) = SourceQuality.assess(s, settings.toParams())

    @Test
    fun cameraOriginalAtDefaultZoomIsFine() {
        assertEquals(emptySet<QualityIssue>(), issues(cameraOriginal))
        assertEquals(1920 / (1440 / 1.3), SourceQuality.upscale(cameraOriginal, EditSettings().toParams()), 1e-9)
    }

    @Test
    fun highZoomOnSquareOriginalWarnsAboutUpscale() {
        assertEquals(setOf(QualityIssue.UPSCALE), issues(cameraOriginal, EditSettings(zoom = 1.5)))
    }

    @Test
    fun kakaoVideoWarnsBothRecompressedAndUpscale() {
        assertEquals(setOf(QualityIssue.UPSCALE, QualityIssue.RECOMPRESSED), issues(kakao))
    }

    /** 확대를 최소로 해도 많이 늘려야 하는 작은 원본은 '확대를 줄이세요'가 아니라 저해상도로 알린다 */
    @Test
    fun tinySourceIsLowResolutionNotUpscale() {
        val tiny = SourceInfo(720, 720, 30.0, 1_500_000, hevc = false)
        assertEquals(setOf(QualityIssue.LOW_RESOLUTION, QualityIssue.RECOMPRESSED), issues(tiny))
        assertEquals(setOf(QualityIssue.LOW_RESOLUTION, QualityIssue.RECOMPRESSED), issues(tiny, EditSettings(zoom = 1.0)))
    }

    @Test
    fun galleryEditIsNotFlaggedAsRecompressed() {
        assertEquals(emptySet<QualityIssue>(), issues(galleryEdit))
    }

    @Test
    fun uhdSourceNeverWarns() {
        assertEquals(emptySet<QualityIssue>(), issues(uhd60, EditSettings(zoom = 1.5)))
        assertEquals(emptySet<QualityIssue>(), issues(uhd60, EditSettings(aspect = AspectRatio.R16x9)))
    }

    @Test
    fun landscape1080pCroppedToPortraitWarnsUpscale() {
        assertEquals(setOf(QualityIssue.UPSCALE), issues(fhd60))
        assertEquals(emptySet<QualityIssue>(), issues(fhd60, EditSettings(zoom = 1.0, aspect = AspectRatio.R16x9)))
    }

    @Test
    fun unknownBitrateSkipsRecompressionCheck() {
        assertEquals(setOf(QualityIssue.UPSCALE), issues(kakao.copy(bitrate = 0)))
    }
}
