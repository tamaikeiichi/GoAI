package com.tksoft.goai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SgfTest {
    @Test
    fun parsesMainLineAndVariations() {
        val sgf = "(;GM[1]FF[4]SZ[19]KM[6.5]RU[Japanese]PB[Alice]PW[Bob]RE[W+R];B[pd];W[dp]" +
            "(;B[pp];W[dd])(;B[dd]))"
        val r = SgfParser.parse(sgf)
        assertEquals(19, r.root.game.size)
        assertEquals(6.5, r.komi!!, 1e-9)
        assertEquals(Rules.JAPANESE, r.rules)
        assertEquals("Alice", r.blackName)
        val second = r.root.children[0].children[0]
        assertEquals(2, second.children.size)
        // 本譜の最後に結果が付く
        assertEquals("W+R", second.children[0].children[0].result?.code)
    }

    @Test
    fun handicapAndPass() {
        val r = SgfParser.parse("(;SZ[9]HA[2]AB[cg][gc];W[ee];B[];W[tt])")
        assertEquals(2, r.root.game.handicapStones.size)
        var n = r.root
        repeat(3) { n = n.children[0] }
        assertEquals(Move.Pass, n.move?.move)
    }

    @Test
    fun roundTrip() {
        val root = GoNode(GoGame(13))
        val a = root.childFor(Move.Play(3, 3))!!
        a.childFor(Move.Play(9, 9))!!
        a.childFor(Move.Play(9, 3))!!
        val text = Sgf.write(KifuRecord(root, 6.5, Rules.CHINESE, "X", "Y", "2026-10-25"))
        val back = SgfParser.parse(text)
        assertEquals(13, back.root.game.size)
        assertEquals(Rules.CHINESE, back.rules)
        assertEquals(2, back.root.children[0].children.size)
        assertNotNull(back.root.children[0].children[1].move)
    }

    @Test
    fun decodesCharsets() {
        val sgf = "(;SZ[9]PB[黒田]PW[白石];B[ee])"
        assertEquals(sgf, SgfParser.decode(sgf.toByteArray(Charsets.UTF_8)))
        // CA が無く UTF-8 として読めなければ Shift_JIS
        assertEquals(sgf, SgfParser.decode(sgf.toByteArray(charset("Shift_JIS"))))
        // CA があればそれに従う
        val euc = "(;CA[EUC-JP]SZ[9]PB[黒田];B[ee])"
        assertEquals(euc, SgfParser.decode(euc.toByteArray(charset("EUC-JP"))))
    }
}
