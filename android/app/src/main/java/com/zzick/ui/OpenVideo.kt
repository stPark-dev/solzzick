package com.zzick.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 만든 영상을 다른 앱(갤러리 등)으로 연다. 열었으면 true.
 * 갤러리에서 지운 영상이면 읽기 권한을 넘길 수 없어 SecurityException이 나므로(앱 강제 종료, 0.4.9 제보) false.
 */
fun openVideo(context: Context, uri: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (e: SecurityException) {
    false
} catch (e: ActivityNotFoundException) {
    false
}
