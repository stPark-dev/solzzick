package com.zzick.core

import com.zzick.core.batch.BatchQueue
import com.zzick.core.overlay.Grade
import com.zzick.core.overlay.OverlayInfo
import com.zzick.core.vcam.EditSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** 작업 목록에 보일 난이도 색·암장 이름(사용자 요청): 그 작업 영상들의 표시 설정(개별 설정이 있으면 그것)에서 모은다. */
class JobBadgesTest {
    private fun s(grade: Grade?, gym: String) = EditSettings(overlay = OverlayInfo(gym = gym, grade = grade))

    @Test
    fun collectsDistinctGradesAndGymsInItemOrder() {
        var q = BatchQueue<String>().add(listOf("a", "b", "c", "d"), common = s(Grade.PINK, " 클라임 A "))
        val (a, b, c, _) = q.items.map { it.id }
        q = q.withItemSettings(a, s(Grade.PURPLE, "클라임 A"))
        q = q.withItemSettings(b, s(null, ""))
        q = q.withItemSettings(c, s(Grade.PINK, "클라임 B"))
        val badges = q.badgesOf(q.jobs[0].id)
        assertEquals(listOf(Grade.PURPLE, Grade.PINK), badges.grades)
        assertEquals(listOf("클라임 A", "클라임 B"), badges.gyms)
    }

    @Test
    fun emptyWhenNothingSetAndOtherJobsDoNotLeak() {
        val q = BatchQueue<String>().add(listOf("a"), common = EditSettings()).add(listOf("b"), common = s(Grade.RED, "X"))
        val first = q.badgesOf(q.jobs[0].id)
        assertEquals(emptyList<Grade>(), first.grades)
        assertEquals(emptyList<String>(), first.gyms)
        assertEquals(listOf(Grade.RED), q.badgesOf(q.jobs[1].id).grades)
    }
}
