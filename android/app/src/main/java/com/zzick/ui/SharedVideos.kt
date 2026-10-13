package com.zzick.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build

/** 갤러리·카톡 등의 "공유 → 솔찍"으로 받은 인텐트에서 영상 주소만 순서대로 꺼낸다. */
object SharedVideos {
    /**
     * content:// 만 받는다 — 외부 앱이 file:// 로 이 앱의 내부 파일을 가리키게 할 수 없도록.
     * @param mimeOf 주소별 형식(ContentResolver.getType). 여러 종류를 함께 공유하면 영상만 남기는 데 쓴다.
     */
    fun from(intent: Intent, mimeOf: (Uri) -> String?): List<Uri> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.stream() ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.streams().ifEmpty {
                intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
            }
            else -> emptyList()
        }
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT && (mimeOf(it) ?: intent.type)?.startsWith("video/") == true }
    }

    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
