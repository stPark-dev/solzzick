package com.zzick.core

import com.zzick.core.tracking.Box
import com.zzick.core.tracking.pickBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickBoxTest {
    private val boxes = listOf(Box(0.1, 0.5, 0.3, 0.9), Box(0.6, 0.2, 0.8, 0.6))

    @Test fun insideBoxWins() = assertEquals(1, pickBox(boxes, 0.7, 0.4))
    @Test fun outsideTakesNearestEdge() = assertEquals(0, pickBox(boxes, 0.35, 0.95))
    @Test fun emptyIsNull() = assertNull(pickBox(emptyList(), 0.5, 0.5))
    @Test fun centerOfChosenBox() = assertEquals(0.7 to 0.4, boxes[1].center)
}
