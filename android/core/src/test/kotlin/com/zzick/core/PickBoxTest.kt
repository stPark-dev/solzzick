package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.pickBox
import com.zzick.core.tracking.showsClimber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickBoxTest {
    private val boxes = listOf(Box(0.1, 0.5, 0.3, 0.9), Box(0.6, 0.2, 0.8, 0.6))

    @Test fun insideBoxWins() = assertEquals(1, pickBox(boxes, 0.7, 0.4))
    @Test fun outsideTakesNearestEdge() = assertEquals(0, pickBox(boxes, 0.35, 0.95))
    @Test fun emptyIsNull() = assertNull(pickBox(emptyList(), 0.5, 0.5))
    @Test fun centerOfChosenBox() = assertEquals(0.7 to 0.4, boxes[1].center)

    /**
     * 점검에서 찾음(20260821_155813, 20260607_151150): 고르기 화면이 '사람이 처음 보이는' 프레임이라, 등반자가 들어오기 전
     * 멀리 있는 작은 사람(높이 0.05~0.13)·홀드 덩어리만 보였다. 등반자 크기(높이 0.15 이상) 박스가 있는 프레임을 고른다.
     */
    @Test fun onlySmallFarPeopleDoNotShowTheClimber() =
        assertFalse(showsClimber(listOf(Box(0.6, 0.7, 0.65, 0.83), Box(0.9, 0.82, 0.93, 0.87))))
    @Test fun climberSizedBoxShowsTheClimber() =
        assertTrue(showsClimber(listOf(Box(0.6, 0.7, 0.65, 0.83), Box(0.3, 0.3, 0.5, 0.6))))
}
