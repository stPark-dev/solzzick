package com.zzick

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.pipeline.AnalysisStore
import com.zzick.pipeline.BatchProcessor
import com.zzick.pipeline.Gallery
import com.zzick.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 갤러리에서 "공유 → 솔찍"과 같은 경로: MediaStore content:// 주소를 공유 인텐트로 앱에 보낸다.
 * (앱이 갤러리에 저장한 영상이라 따로 권한 없이 읽을 수 있다.)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ShareFlowE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shared = mutableListOf<Uri>()

    @Before
    fun setUp() {
        AnalysisStore(ctx).clear() // 저장본이 있으면 선택 화면을 건너뛰므로
        val src = File(ctx.filesDir, "videos/20260927_113733.mp4")
        repeat(2) { shared += Gallery.saveVideo(ctx, src, "zzick_share_test_${System.currentTimeMillis()}_$it.mp4") }
    }

    @After
    fun tearDown() {
        val p = BatchProcessor.get(ctx)
        runBlocking(Dispatchers.Main) {
            p.state.value.items.filter { Uri.parse(it.source) in shared }.forEach { p.remove(it.id) }
        }
        shared.forEach { ctx.contentResolver.delete(it, null, null) }
    }

    private fun share(intent: Intent) = ActivityScenario.launch<MainActivity>(
        intent.setClass(ctx, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )

    @Test
    fun sharingOneVideoOpensClimberSelection() {
        share(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, shared[0])
            .apply { clipData = ClipData.newRawUri("", shared[0]) }).use {
            compose.waitUntilAtLeastOneExists(hasText("이 사람을 따라갈게요").or(hasText("따라갈 등반자를 탭하세요")), 60_000)
        }
    }

    @Test
    fun sharingSeveralVideosOpensBatch() {
        share(Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shared))).use {
            // 공유한 영상들이 새 작업 하나로 들어가고 그 작업 화면이 바로 열린다
            compose.waitUntilAtLeastOneExists(hasText("작업 목록"), 30_000)
            val q = BatchProcessor.get(ctx).state.value
            val sources = q.itemsOf(q.jobs.last().id).map { Uri.parse(it.source) }
            assertEquals("새 작업에 공유한 영상만 들어가야 함", shared.toSet(), sources.toSet())
        }
    }
}
