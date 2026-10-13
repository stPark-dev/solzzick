package com.zzick

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzick.core.analysis.AnalysisResult
import com.zzick.core.quality.SourceInfo
import com.zzick.pipeline.Analysis
import com.zzick.pipeline.AnalysisStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** 분석 결과 저장본: 같은 영상 내용이면 다시 쓰고, 내용이 바뀌면 버린다. 개수 상한. */
@RunWith(AndroidJUnit4::class)
class AnalysisStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "store_test_${UUID.randomUUID()}")
    private val store = AnalysisStore(ctx, dir, maxEntries = 5)

    @Before
    fun setUp() {
        dir.deleteRecursively()
    }

    private fun fakeAnalysis(n: Int) = Analysis(
        AnalysisResult(
            Array(n) { doubleArrayOf(it.toDouble(), it * 2.0) },
            Array(n) { doubleArrayOf(0.0, 1.0, 2.0, it.toDouble()) },
            IntArray(n) { 1 },
            LongArray(n) { it * 33_333L },
        ),
        1440.0, 1440.0, 30.0, SourceInfo(1440, 1440, 30.0, 14_000_000, true),
    )

    private fun randomFile(bytes: Int) = File(dir, "v_${UUID.randomUUID()}.mp4").apply {
        parentFile!!.mkdirs()
        writeBytes(Random.nextBytes(bytes))
    }

    @Test
    fun sameVideoContentLoadsBackExactly() {
        val video = File(ctx.filesDir, "videos/20260927_113733.mp4")
        val a = fakeAnalysis(300)
        store.save(Uri.fromFile(video), a, tap = 0.4 to 0.7)
        val back = store.load(Uri.fromFile(video))
        assertNotNull(back)
        back!!
        assertEquals(0.4 to 0.7, back.tap)
        assertArrayEquals(a.result.ptsUs, back.analysis.result.ptsUs)
        assertArrayEquals(a.result.track[123], back.analysis.result.track[123], 0.0)
        assertEquals(a.source, back.analysis.source)
        // 같은 내용을 다른 경로로 열어도(공유로 받은 사본 등) 같은 저장본
        val copy = File(dir, "copy.mp4").apply { video.copyTo(this, overwrite = true) }
        assertNotNull(store.load(Uri.fromFile(copy)))
    }

    @Test
    fun changedVideoIsNotReused() {
        val f = randomFile(300_000)
        store.save(Uri.fromFile(f), fakeAnalysis(10), tap = null)
        assertNotNull(store.load(Uri.fromFile(f)))
        f.appendBytes(byteArrayOf(1)) // 다시 저장(편집)된 영상
        assertNull(store.load(Uri.fromFile(f)))
        assertNull(store.load(Uri.fromFile(randomFile(300_000))))
        assertNull("읽을 수 없는 영상", store.load(Uri.fromFile(File(dir, "missing.mp4"))))
    }

    @Test
    fun keepsOnlyMostRecentEntries() {
        val files = List(8) { randomFile(1_000 + it) }
        files.forEach {
            store.save(Uri.fromFile(it), fakeAnalysis(5), tap = null)
            Thread.sleep(15) // 파일 시각으로 순서를 매긴다
        }
        assertTrue("상한 초과: ${store.count()}", store.count() <= 5)
        assertNotNull("가장 최근 것은 남아야 함", store.load(Uri.fromFile(files.last())))
        assertNull("가장 오래된 것은 지워져야 함", store.load(Uri.fromFile(files.first())))
    }

    /** 편집 화면과 일괄 편집이 같은 영상을 동시에 저장해도 저장본이 깨지지 않는다. */
    @Test
    fun concurrentSavesOfSameVideoStayReadable() {
        val f = randomFile(200_000)
        val a = fakeAnalysis(2_000)
        val threads = List(4) { Thread { repeat(10) { store.save(Uri.fromFile(f), a, tap = null) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val back = store.load(Uri.fromFile(f))
        assertNotNull(back)
        assertArrayEquals(a.result.ptsUs, back!!.analysis.result.ptsUs)
        assertEquals("임시 파일이 남으면 안 됨", emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }
}
