package com.zzick

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzick.ui.quote.Quote
import com.zzick.ui.quote.QuoteRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuoteTest {
    @Test
    fun parsesApiResponse() = runBlocking {
        val repo = QuoteRepository(fetch = { """{"author":"빈센트 반 고흐","authorProfile":"네덜란드 화가","message":"위대한 일은 작은 일들의 축적으로 이루어진다."}""" })
        assertEquals(Quote("위대한 일은 작은 일들의 축적으로 이루어진다.", "빈센트 반 고흐"), repo.random())
    }

    @Test
    fun brokenResponseFallsBackToBundledQuote() = runBlocking {
        for (body in listOf("not json", """{"author":"","message":""}""", """{"foo":1}""")) {
            val q = QuoteRepository(fetch = { body }).random()
            assertTrue("기본 명언이어야 함: $q", q in QuoteRepository.FALLBACK)
        }
    }

    @Test
    fun networkFailureFallsBack() = runBlocking {
        val q = QuoteRepository(fetch = { throw java.io.IOException("offline") }).random()
        assertTrue(q in QuoteRepository.FALLBACK)
    }

    @Test
    fun slowApiTimesOutToFallback() = runBlocking {
        val t0 = System.currentTimeMillis()
        val q = QuoteRepository(fetch = { delay(10_000); "{}" }, timeoutMs = 500).random()
        assertTrue(q in QuoteRepository.FALLBACK)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test
    fun realApiReturnsKoreanQuote() = runBlocking {
        val body = runCatching { QuoteRepository.httpGet(QuoteRepository.API_URL, 5_000) }.getOrNull()
        assumeTrue("네트워크/API 불가 — 건너뜀", body != null)
        val q = QuoteRepository(fetch = { body!! }).random()
        assertTrue(q.message.isNotBlank() && q.author.isNotBlank())
    }
}
