package com.zzick

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 런처 아이콘이 적응형(배경+전경+모노크롬)으로 등록되고 그려지는지. 미리보기 PNG도 남긴다. */
@RunWith(AndroidJUnit4::class)
class AppIconTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherIconIsAdaptiveAndRenders() {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertTrue("아이콘이 지정되지 않음", info.icon != 0)
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue("적응형 아이콘이 아님: ${icon.javaClass}", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull("테마 아이콘용 모노크롬 레이어 없음", icon.monochrome)
        assertTrue("전경이 사용자 아이콘 그림(solzzick.png)이 아님: ${icon.foreground.javaClass}", icon.foreground is BitmapDrawable)

        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        File(ctx.getExternalFilesDir(null), "icon_preview.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 모서리는 마스크 밖이라 투명, 가운데는 그림으로 불투명
        assertEquals(0, bmp.getPixel(0, 0) ushr 24)
        assertEquals(255, bmp.getPixel(size / 2, size / 2) ushr 24)
    }
}
