package com.zzick

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.ui.SharedVideos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 갤러리·카톡의 "공유 → 솔찍": 공유 메뉴에 뜨는지, 받은 인텐트에서 영상만 골라내는지. */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = Uri.parse("content://media/external/video/media/1")
    private val b = Uri.parse("content://media/external/video/media/2")

    private fun resolvesToUs(intent: Intent) =
        ctx.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == ctx.packageName }

    @Test
    fun appearsInShareSheetForVideosOnly() {
        assertTrue(resolvesToUs(Intent(Intent.ACTION_SEND).setType("video/mp4")))
        assertTrue(resolvesToUs(Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")))
        assertTrue("사진 공유에는 뜨지 않아야 함", !resolvesToUs(Intent(Intent.ACTION_SEND).setType("image/jpeg")))
    }

    @Test
    fun singleShareGivesOneVideo() {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(listOf(a), SharedVideos.from(i) { "video/mp4" })
    }

    @Test
    fun singleShareViaClipDataOnly() {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").apply { clipData = ClipData.newRawUri("", a) }
        assertEquals(listOf(a), SharedVideos.from(i) { "video/mp4" })
    }

    @Test
    fun multipleShareKeepsOnlyVideosInOrder() {
        val photo = Uri.parse("content://media/external/images/media/9")
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, photo, b))
        assertEquals(listOf(a, b), SharedVideos.from(i) { if (it == photo) "image/jpeg" else "video/mp4" })
    }

    /** 외부 앱이 file:// 로 앱 내부 파일을 가리키게 해도 받지 않는다(content:// 만). */
    @Test
    fun fileUrisAreRejected() {
        val inside = Uri.parse("file:///data/data/${ctx.packageName}/files/videos/x.mp4")
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(inside, a))
        assertEquals(listOf(a), SharedVideos.from(i) { null })
    }

    @Test
    fun otherIntentsGiveNothing() {
        assertEquals(emptyList<Uri>(), SharedVideos.from(Intent(Intent.ACTION_MAIN)) { "video/mp4" })
        assertEquals(emptyList<Uri>(), SharedVideos.from(Intent(Intent.ACTION_SEND).setType("video/mp4")) { "video/mp4" })
    }
}
