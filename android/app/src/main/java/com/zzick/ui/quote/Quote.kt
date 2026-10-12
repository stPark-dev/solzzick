package com.zzick.ui.quote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Quote(val message: String, val author: String)

/**
 * 랜덤 한국어 명언. 무료 공개 API(korean-advice-open-api, 키 불필요)를 부르고,
 * 응답이 없거나 이상하면 앱에 들어 있는 명언 중 하나를 고른다(오프라인·서비스 중단 대비).
 */
class QuoteRepository(
    private val fetch: suspend () -> String = { httpGet(API_URL, DEFAULT_TIMEOUT_MS.toInt()) },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun random(): Quote =
        withTimeoutOrNull(timeoutMs) { runCatching { parse(fetch()) }.getOrNull() } ?: FALLBACK.random()

    companion object {
        const val API_URL = "https://korean-advice-open-api.vercel.app/api/advice"
        private const val DEFAULT_TIMEOUT_MS = 3_000L

        suspend fun httpGet(url: String, timeoutMs: Int): String = runInterruptible(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** {"message": "...", "author": "..."} — 둘 중 하나라도 비면 null. */
        fun parse(json: String): Quote? {
            val o = JSONObject(json)
            val message = o.optString("message").trim()
            val author = o.optString("author").trim()
            return if (message.isEmpty() || author.isEmpty()) null else Quote(message, author)
        }

        /** 오프라인용 기본 명언(출처가 비교적 분명한 것만). */
        val FALLBACK = listOf(
            Quote("천 리 길도 한 걸음부터 시작된다.", "노자"),
            Quote("우리가 정복하는 것은 산이 아니라 우리 자신이다.", "에드먼드 힐러리"),
            Quote("그것이 거기 있기 때문이다.", "조지 맬러리"),
            Quote("세상에서 가장 뛰어난 클라이머는 가장 즐기는 사람이다.", "알렉스 로우"),
            Quote("위대한 일은 작은 일들이 모여 이루어진다.", "빈센트 반 고흐"),
            Quote("지금 하지 않으면 언제 하겠는가?", "힐렐"),
            Quote("고통은 잠깐이지만 포기는 영원히 남는다.", "랜스 암스트롱"),
            Quote("아는 것을 안다 하고 모르는 것을 모른다 하는 것, 이것이 아는 것이다.", "공자"),
        )
    }
}

/** 첫 화면용: 스플래시 동안 미리 하나 받아두고, 이후엔 화면에 올 때마다 새로 받는다. */
object QuoteFeed {
    private val repo = QuoteRepository()
    private var pending: Deferred<Quote>? = null

    fun prefetch(scope: CoroutineScope) {
        if (pending == null) pending = scope.async { repo.random() }
    }

    suspend fun next(): Quote {
        val p = pending
        pending = null
        return p?.let { runCatching { it.await() }.getOrNull() } ?: repo.random()
    }
}
