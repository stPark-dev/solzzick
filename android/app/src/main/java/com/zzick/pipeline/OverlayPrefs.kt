package com.zzick.pipeline

import android.content.Context
import com.zzick.core.overlay.Corner
import com.zzick.core.overlay.OverlayInfo

/** 암장 이름·날짜 표시 여부·구석은 다음 영상에도 그대로(같은 날 같은 암장에서 여러 개 찍으니까). 난이도는 문제마다 달라 기억하지 않는다. */
class OverlayPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun load() = OverlayInfo(
        gym = prefs.getString(GYM, "").orEmpty(),
        showDate = prefs.getBoolean(DATE, false),
        corner = prefs.getString(CORNER, null)?.let { name -> Corner.entries.firstOrNull { it.name == name } } ?: Corner.BOTTOM_LEFT,
    )

    fun save(o: OverlayInfo) {
        prefs.edit().putString(GYM, o.gym).putBoolean(DATE, o.showDate).putString(CORNER, o.corner.name).apply()
    }

    private companion object {
        const val GYM = "gym"
        const val DATE = "show_date"
        const val CORNER = "corner"
    }
}
