package com.tksoft.goai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KifuTimeTest {
    private fun line(root: GoNode): List<GoNode> = generateSequence(root.children.firstOrNull()) { it.children.firstOrNull() }.toList()

    @Test
    fun consumedFromTimeLeft() {
        val r = SgfParser.parse("(;SZ[19]TM[3600]OT[5x30 byo-yomi];B[pd]BL[3590];W[dp]WL[3550.5];B[pp]BL[3500])")
        val ts = r.timeSettings
        assertEquals(3600.0, ts?.mainTimeSec!!, 1e-9)
        val (b1, w1, b2) = line(r.root)
        // 初手は持ち時間との差
        assertEquals(10.0, KifuTime.consumed(b1, ts)!!, 1e-9)
        assertEquals(49.5, KifuTime.consumed(w1, ts)!!, 1e-9)
        assertEquals(90.0, KifuTime.consumed(b2, ts)!!, 1e-9)
        // 白の残り時間は、黒の手の後も白の最後の手のもの
        assertEquals(3550.5, KifuTime.remaining(b2, Stone.WHITE, ts)!!.first, 1e-9)
        assertEquals(3600.0, KifuTime.remaining(r.root, Stone.BLACK, ts)!!.first, 1e-9)
    }

    @Test
    fun byoYomi() {
        val r = SgfParser.parse("(;SZ[19]TM[60]OT[3x30 byo-yomi];B[pd]BL[20];W[dp];B[pp]BL[25]OB[3];W[dd];B[cc]BL[22]OB[3];W[qq];B[dc]BL[10]OB[1])")
        val ts = r.timeSettings
        val n = line(r.root)
        // 持ち時間 20 秒残しから秒読みへ: 20 + (30 - 25)
        assertEquals(25.0, KifuTime.consumed(n[2], ts)!!, 1e-9)
        // 秒読みの1回の中で打った: 30 - 22
        assertEquals(8.0, KifuTime.consumed(n[4], ts)!!, 1e-9)
        // 秒読みを1回使った (3 → 1): 22 + 30 + (30 - 10)
        assertEquals(72.0, KifuTime.consumed(n[6], ts)!!, 1e-9)
        // 時間の無い手
        assertNull(KifuTime.consumed(n[1], ts))
        assertEquals(1, KifuTime.remaining(n[6], Stone.BLACK, ts)!!.second)
    }

    @Test
    fun noTimeInfo() {
        val r = SgfParser.parse("(;SZ[9];B[ee];W[cc])")
        assertNull(r.timeSettings)
        assertNull(KifuTime.consumed(line(r.root)[0], null))
        assertNull(KifuTime.remaining(r.root, Stone.BLACK, null))
    }

    @Test
    fun roundTripSgf() {
        val src = "(;SZ[19]TM[3600]OT[5x30 byo-yomi];B[pd]BL[3590];W[dp]WL[3550.5]OW[5])"
        val r = SgfParser.parse(src)
        val text = Sgf.write(KifuRecord(r.root, 6.5, Rules.JAPANESE, "", "", null, r.timeSettings))
        val back = SgfParser.parse(text)
        assertEquals(r.timeSettings, back.timeSettings)
        val (b, w) = line(back.root)
        assertEquals(3590.0, b.timeLeft!!, 1e-9)
        assertEquals(3550.5, w.timeLeft!!, 1e-9)
        assertEquals(5, w.periodsLeft)
    }

    @Test
    fun canadianOvertime() {
        val r = SgfParser.parse("(;SZ[19]TM[0]OT[25 moves / 600 canadian];B[pd]BL[590]OB[25];W[dp];B[pp]BL[570]OB[24])")
        // 決まった手数の間は時計が戻らないので差を取る (byoYomiSeconds は読めないので、残り回数は使わない)
        assertEquals(20.0, KifuTime.consumed(line(r.root)[2], r.timeSettings)!!, 1e-9)
    }

    @Test
    fun format() {
        assertEquals("0:09", KifuTime.format(9.4))
        assertEquals("1:05", KifuTime.format(65.0))
        assertEquals("1:00:00", KifuTime.format(3600.0))
    }
}
