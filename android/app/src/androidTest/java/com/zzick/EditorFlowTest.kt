package com.zzick

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.vcam.AspectRatio
import com.zzick.core.vcam.EditSettings
import com.zzick.pipeline.AnalysisStore
import com.zzick.ui.EditorState
import com.zzick.ui.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 화면 상태 흐름(선택 → 분석 → 렌더 → 저장)과 임시 파일 정리(리뷰 M3), 처리 중 취소(M1). */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val uri = Uri.fromFile(File(app.filesDir, "videos/20260927_113733.mp4"))

    private fun cacheVideos() = app.cacheDir.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().toList()

    private fun newVm() = runBlocking(Dispatchers.Main) { EditorViewModel(app) }

    @Before
    fun clearSavedAnalyses() = AnalysisStore(app).clear() // 저장본이 있으면 선택·분석 단계를 건너뛰므로

    /** 한 번 분석한 영상을 다시 열면 바로 편집 화면. '다시 분석'은 저장본을 무시하고 등반자 선택부터. */
    @Test
    fun reopeningSameVideoSkipsAnalysis() = runBlocking<Unit> {
        val vm = newVm()
        withContext(Dispatchers.Main) { vm.onVideoPicked(uri) }
        withTimeout(60_000) { vm.state.first { it is EditorState.Select || it is EditorState.Failed } }
        withContext(Dispatchers.Main) { vm.onStart() }
        val first = withTimeout(180_000) { vm.state.first { it is EditorState.Edit || it is EditorState.Failed } } as EditorState.Edit
        withContext(Dispatchers.Main) { vm.reset() }

        val seen = mutableListOf<EditorState>()
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { vm.onVideoPicked(uri) }
        val again = withTimeout(10_000) { vm.state.first { seen += it; it is EditorState.Edit || it is EditorState.Failed } }
        assertTrue("실패: $again", again is EditorState.Edit)
        again as EditorState.Edit
        assertTrue("저장본이 있으면 선택·분석을 건너뛰어야 함: $seen", seen.none { it is EditorState.Select || it is EditorState.Analyzing })
        assertTrue("느림: ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 5_000)
        assertEquals(first.analysis.result.ptsUs.size, again.analysis.result.ptsUs.size)
        assertEquals(first.window, again.window)

        withContext(Dispatchers.Main) { vm.onReanalyze() }
        val select = withTimeout(60_000) { vm.state.first { it is EditorState.Select || it is EditorState.Failed } }
        assertTrue("다시 분석은 등반자 선택부터: $select", select is EditorState.Select)
    }

    @Test
    fun fullFlowSavesToGalleryAndLeavesNoTempFile() = runBlocking<Unit> {
        val vm = newVm()
        withContext(Dispatchers.Main) { vm.onVideoPicked(uri) }
        withTimeout(60_000) { vm.state.first { it is EditorState.Select || it is EditorState.Failed } }
        withContext(Dispatchers.Main) { vm.onStart() }
        val edit = withTimeout(180_000) { vm.state.first { it is EditorState.Edit || it is EditorState.Failed } }
        assertTrue("실패: $edit", edit is EditorState.Edit)
        edit as EditorState.Edit
        // 이 영상은 끝 2.8초 동안 등반자가 없다 → 끝이 잘린 구간을 제안해야 한다
        assertTrue("트리밍 제안이 없음: ${edit.window}", edit.window.endUs < edit.durationUs - 500_000)
        // 비율·확대·흔들림을 바꾸면 경로가 다시 계산된다
        withContext(Dispatchers.Main) { vm.onSettingsChange(EditSettings(zoom = 1.1, shake = 3.0, aspect = AspectRatio.R4x5)) }
        val edited = withTimeout(10_000) {
            vm.state.first { it is EditorState.Edit && it.settings.aspect == AspectRatio.R4x5 && it.path !== edit.path }
        } as EditorState.Edit
        assertEquals(1080.0 / 1350.0, edited.path[0][2] / edited.path[0][3], 1e-6)
        withContext(Dispatchers.Main) { vm.onRender() }
        val end = withTimeout(180_000) { vm.state.first { it is EditorState.Done || it is EditorState.Failed } }
        assertTrue("실패: $end", end is EditorState.Done)
        end as EditorState.Done
        assertEquals("임시 파일이 남음: ${cacheVideos()}", emptyList<File>(), cacheVideos())
        val outUs = MediaMetadataRetriever().run {
            setDataSource(app, end.galleryUri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000
        }
        assertEquals("결과 길이가 구간과 다름", edit.window.durationUs.toDouble(), outUs.toDouble(), 200_000.0)
        val (w, h) = MediaMetadataRetriever().run {
            setDataSource(app, end.galleryUri)
            val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
            val vw = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val vh = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            if (rot % 180 == 0) vw to vh else vh to vw
        }
        assertEquals(1080 to 1350, w to h)
        app.contentResolver.delete(end.galleryUri, null, null)
    }

    @Test
    fun cancelDuringAnalysisReturnsToStartAndCleansUp() = runBlocking<Unit> {
        val vm = newVm()
        withContext(Dispatchers.Main) { vm.onVideoPicked(uri) }
        withTimeout(60_000) { vm.state.first { it is EditorState.Select } }
        withContext(Dispatchers.Main) { vm.onStart() }
        withTimeout(60_000) { vm.state.first { it is EditorState.Analyzing && it.progress > 0f } }
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { vm.cancel() }
        assertEquals(EditorState.Idle, vm.state.value)
        // 작업 스레드의 디코더도 곧 멈춰야 한다
        while (Thread.getAllStackTraces().keys.any { it.name == "FrameDecoder" && it.isAlive }) {
            assertTrue("취소 후 디코더가 계속 돎", System.currentTimeMillis() - t0 < 5000)
            Thread.sleep(50)
        }
        assertEquals(emptyList<File>(), cacheVideos())
    }
}
