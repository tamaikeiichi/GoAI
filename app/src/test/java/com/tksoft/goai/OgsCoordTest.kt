package com.tksoft.goai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OgsCoordTest {
    @Test
    fun encodesMovesLikeOgs() {
        // 左上が "aa"、1文字目が列
        assertEquals("aa", OgsCoord.encode(Move.Play(0, 0)))
        assertEquals("dp", OgsCoord.encode(Move.Play(3, 15)))
        assertEquals("..", OgsCoord.encode(Move.Pass))
    }

    @Test
    fun decodesPointListsAndDropsOffBoard() {
        assertEquals(listOf(3 to 3, 15 to 15), OgsCoord.decodePoints("ddpp", 19))
        assertEquals(listOf(2 to 2), OgsCoord.decodePoints("cc..ss", 9))
        assertEquals(emptyList<Pair<Int, Int>>(), OgsCoord.decodePoints("", 9))
    }

    @Test
    fun encodesPointsInRowOrder() {
        // OGS と同じく上の行から、行の中は左から
        assertEquals("baabcb", OgsCoord.encodePoints(setOf(2 to 1, 0 to 1, 1 to 0)))
    }

    @Test
    fun flags() {
        assertEquals("🇯🇵", OgsCoord.flag("jp"))
        assertEquals("🇺🇸", OgsCoord.flag("US"))
        assertNull(OgsCoord.flag("un"))
        assertNull(OgsCoord.flag("_Pirate"))
        assertNull(OgsCoord.flag(null))
        assertEquals("tamaikeiichi", OgsCoord.usernameOf("tamaikeiichi [6k]"))
    }

    @Test
    fun ranks() {
        assertEquals("1d", OgsCoord.rankText(30.0))
        assertEquals("1k", OgsCoord.rankText(29.4))
        assertEquals("25k", OgsCoord.rankText(5.0))
        assertNull(OgsCoord.rankText(-100.0))
        assertNull(OgsCoord.rankText(Double.NaN))
    }
}
