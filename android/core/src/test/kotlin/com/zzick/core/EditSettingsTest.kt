package com.zzick.core

import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import com.zzick.core.vcam.VirtualCamera
import org.junit.Assert.assertEquals
import org.junit.Test

class EditSettingsTest {
    @Test
    fun defaultsMatchApprovedCameraDefaults() {
        val p = EditSettings().toParams()
        assertEquals(1.3, p.zoom, 0.0)
        assertEquals(1.0, p.shakeGain, 0.0)
        assertEquals(9.0 / 16.0, p.aspect, 0.0)
        assertEquals(1920, p.outHeight)
    }

    @Test
    fun outputSizesPerAspect() {
        assertEquals(1080 to 1920, VirtualCamera.outputSize(EditSettings(aspect = AspectRatio.R9x16).toParams()))
        assertEquals(1080 to 1350, VirtualCamera.outputSize(EditSettings(aspect = AspectRatio.R4x5).toParams()))
        assertEquals(1920 to 1080, VirtualCamera.outputSize(EditSettings(aspect = AspectRatio.R16x9).toParams()))
    }

    @Test
    fun slidersAreClampedToSupportedRange() {
        val lo = EditSettings(zoom = 0.2, shake = -3.0).toParams()
        assertEquals(EditSettings.ZOOM_MIN, lo.zoom, 0.0)
        assertEquals(0.0, lo.shakeGain, 0.0)
        val hi = EditSettings(zoom = 9.0, shake = 99.0).toParams()
        assertEquals(EditSettings.ZOOM_MAX, hi.zoom, 0.0)
        assertEquals(EditSettings.SHAKE_MAX, hi.shakeGain, 0.0)
    }

    @Test
    fun zoomIsRelativeToWidestCropForThatAspect() {
        // 정사각 1440 원본에서 16:9: 가장 넓은 크롭 높이 810
        val wide = EditSettings(zoom = 1.0, aspect = AspectRatio.R16x9).toParams()
        assertEquals(810.0, VirtualCamera.cropSize(1440.0, 1440.0, wide).second, 1e-9)
        val zoomed = EditSettings(zoom = 1.3, aspect = AspectRatio.R16x9).toParams()
        assertEquals(810.0 / 1.3, VirtualCamera.cropSize(1440.0, 1440.0, zoomed).second, 1e-9)
    }

    @Test
    fun motionZoomToggleIsPassedThrough() {
        assertEquals(true, EditSettings().toParams().motionZoom)
        assertEquals(false, EditSettings(motionZoom = false).toParams().motionZoom)
    }
}
