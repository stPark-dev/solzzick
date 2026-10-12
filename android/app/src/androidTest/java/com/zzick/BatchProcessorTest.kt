package com.zzick

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.batch.BatchQueue
import com.zzick.core.batch.Stage
import com.zzick.pipeline.Analysis
import com.zzick.pipeline.BatchProcessor
import com.zzick.pipeline.BatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 일괄 편집 처리기: 실제 영상으로 사람 찾기 → 분석 → 준비 → 모두 만들기 → 갤러리, 실패 격리, 취소, 서비스 수명. */
@RunWith(AndroidJUnit4::class)
class BatchProcessorTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun video(name: String) = Uri.fromFile(File(ctx.filesDir, "videos/$name"))

    private fun newProcessor() = runBlocking(Dispatchers.Main) { BatchProcessor(ctx.applicationContext) }

    private suspend fun BatchProcessor.awaitState(timeoutMs: Long, cond: (BatchQueue<Analysis>) -> Boolean) =
        withTimeout(timeoutMs) { state.first(cond) }

    @Test
    fun analyzesAllThenRendersAllToGallery() = runBlocking<Unit> {
        val p = newProcessor()
        withContext(Dispatchers.Main) { p.add(listOf(video("20260927_113733.mp4"), video("20261011_105654.mp4"))) }
        assertTrue("처리 중 서비스가 떠 있어야 함", withTimeout(10_000) { while (!BatchService.running) kotlinx.coroutines.delay(50); true })
        val ready = p.awaitState(240_000) { q -> q.items.all { it.stage == Stage.READY || it.stage == Stage.FAILED } }
        assertEquals(listOf(Stage.READY, Stage.READY), ready.items.map { it.stage })
        ready.items.forEach { assertNotNull(it.window); assertNotNull(it.analysis) }

        withContext(Dispatchers.Main) { p.renderAll() }
        val done = p.awaitState(240_000) { q -> q.items.all { it.stage == Stage.DONE || it.stage == Stage.FAILED } }
        assertEquals(listOf(Stage.DONE, Stage.DONE), done.items.map { it.stage })
        for (item in done.items) {
            val uri = Uri.parse(item.output)
            ctx.contentResolver.query(uri, null, null, null, null)!!.use { assertTrue(it.moveToFirst()) }
            ctx.contentResolver.delete(uri, null, null)
        }
        withTimeout(10_000) { while (BatchService.running) kotlinx.coroutines.delay(50) }
        assertFalse("일이 끝나면 서비스가 내려가야 함", BatchService.running)
        assertEquals(emptyList<File>(), ctx.cacheDir.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().toList())
    }

    @Test
    fun brokenItemFailsWithoutStoppingOthers() = runBlocking<Unit> {
        val broken = File(ctx.cacheDir, "broken.mp4").apply { writeText("not a video") }
        val p = newProcessor()
        withContext(Dispatchers.Main) { p.add(listOf(Uri.fromFile(broken), video("20260927_113733.mp4"))) }
        val q = p.awaitState(240_000) { q -> q.items.all { it.stage == Stage.READY || it.stage == Stage.FAILED } }
        assertEquals(listOf(Stage.FAILED, Stage.READY), q.items.map { it.stage })
        assertNotNull(q.items[0].error)
        broken.delete()
    }

    @Test
    fun cancelStopsAnalysisPromptly() = runBlocking<Unit> {
        val p = newProcessor()
        withContext(Dispatchers.Main) { p.add(listOf(video("20261011_105654.mp4"))) }
        val id = p.awaitState(120_000) { q -> q.items[0].stage == Stage.ANALYZING && q.items[0].progress > 0f }.items[0].id
        val t0 = System.currentTimeMillis()
        withContext(Dispatchers.Main) { p.cancel(id) }
        assertEquals(Stage.FAILED, p.state.value.item(id).stage)
        while (Thread.getAllStackTraces().keys.any { it.name == "FrameDecoder" && it.isAlive }) {
            assertTrue("취소 후 디코더가 계속 돎", System.currentTimeMillis() - t0 < 5000)
            Thread.sleep(50)
        }
        withTimeout(10_000) { while (BatchService.running) kotlinx.coroutines.delay(50) }
    }
}
