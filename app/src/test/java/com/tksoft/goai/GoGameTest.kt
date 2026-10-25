package com.tksoft.goai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGameTest {
    private fun GoGame.p(x: Int, y: Int) = play(Move.Play(x, y))!!
    private fun GoGame.pass() = play(Move.Pass)!!

    @Test
    fun captureInCorner() {
        // 黒 A9 を白 B9, A8 で取る
        val g = GoGame(9).p(0, 0).p(1, 0).pass().p(0, 1)
        assertEquals(Stone.EMPTY, g.board[0, 0])
        assertEquals(1, g.capturedByWhite)
    }

    @Test
    fun suicideIsIllegal() {
        // 白 B9, A8 に囲まれた A9 に黒は打てない
        val g = GoGame(9).pass().p(1, 0).pass().p(0, 1)
        assertEquals(Stone.BLACK, g.toPlay)
        assertNull(g.play(Move.Play(0, 0)))
    }

    @Test
    fun simpleKoCannotBeRetakenImmediately() {
        // コウの形: 黒 (1,1)(0,2)(1,3) と 白 (2,1)(3,2)(2,3) の間に黒 (2,2) を置き、白 (1,2) で取る
        var g = GoGame(9)
        g = g.p(1, 1).p(2, 1)   // B C
        g = g.p(0, 2).p(3, 2)
        g = g.p(1, 3).p(2, 3)
        g = g.p(2, 2)
        // 白 (1,2) で黒 (2,2) を取る (コウ)
        val taken = g.p(1, 2)
        assertEquals(Stone.EMPTY, taken.board[2, 2])
        // 黒はすぐには取り返せない
        assertNull(taken.play(Move.Play(2, 2)))
        // 1手よそに打てば取り返せる
        val later = taken.p(8, 0).p(7, 8)
        assertNotNull(later.play(Move.Play(2, 2)))
    }

    @Test
    fun twoPassesEndGame() {
        val g = GoGame(9).p(4, 4).pass()
        assertFalse(g.isEndedByPasses)
        assertTrue(g.pass().isEndedByPasses)
    }

    @Test
    fun undoRestoresPosition() {
        val g = GoGame(9).p(4, 4).p(3, 3)
        val u = g.undo(1)
        assertEquals(1, u.moves.size)
        assertEquals(Stone.EMPTY, u.board[3, 3])
        assertEquals(Stone.WHITE, u.toPlay)
    }

    @Test
    fun handicapWhiteMovesFirst() {
        val g = GoGame(9, GoGame.handicapPoints(9, 2))
        assertEquals(Stone.WHITE, g.toPlay)
        assertEquals(2, g.board.count(Stone.BLACK))
    }

    @Test
    fun gtpCoordinatesSkipI() {
        assertEquals("J9", GtpCoord.toGtp(Move.Play(8, 0), 9))
        assertEquals("A1", GtpCoord.toGtp(Move.Play(0, 8), 9))
        assertEquals(Move.Play(8, 0), GtpCoord.fromGtp("j9", 9))
        assertEquals(Move.Pass, GtpCoord.fromGtp("pass", 9))
        assertNull(GtpCoord.fromGtp("Z1", 9))
    }

    @Test
    fun parsesKataAnalyzeLine() {
        val own = List(81) { if (it == 0) "0.9" else "-0.1" }.joinToString(" ")
        val line = "info move E5 visits 10 winrate 0.55 scoreLead 1.5 order 0 pv E5 D4 " +
            "info move D4 visits 3 winrate 0.40 scoreLead -0.5 order 1 pv D4 ownership $own"
        val a = AnalysisParser.parse(line, 9)!!
        assertEquals(2, a.candidates.size)
        assertEquals(Move.Play(4, 4), a.best!!.move)
        assertEquals(0.55, a.best!!.winrateBlack, 1e-9)
        assertEquals(listOf(Move.Play(4, 4), Move.Play(3, 5)), a.best!!.pv)
        assertEquals(0.9, a.ownershipBlack!![0], 1e-9)
    }
}
