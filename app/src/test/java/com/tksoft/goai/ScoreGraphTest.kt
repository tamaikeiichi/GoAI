package com.tksoft.goai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreGraphTest {
    @Test
    fun logScalePerBoardSize() {
        assertEquals(0f, scoreLeadToGraph(0.0, 9), 1e-6f)
        assertEquals(1f, scoreLeadToGraph(10.0, 9), 1e-6f)
        assertEquals(-1f, scoreLeadToGraph(-50.0, 19), 1e-6f)
        // 9路の5目は19路の5目より大きく動く
        assertTrue(scoreLeadToGraph(5.0, 9) > scoreLeadToGraph(5.0, 19))
        // 対数なので、小さな差でも直線より大きく動く
        assertTrue(scoreLeadToGraph(3.0, 19) > 3.0f / 30f)
        assertEquals(-scoreLeadToGraph(4.0, 13), scoreLeadToGraph(-4.0, 13), 1e-6f)
    }
}
