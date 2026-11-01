package com.tksoft.goai

import java.io.IOException
import java.io.InputStream

/**
 * bzip2 (.bz2) を展開しながら読む。Java 標準には無く、外部ライブラリを足さないよう自前で持つ。
 * 複数のストリームを連結したもの (pbzip2 など) にも対応する。ブロックごとに CRC を確かめる
 */
class Bzip2InputStream(private val input: InputStream) : InputStream() {
    private var bitBuf = 0L
    private var bitCount = 0

    private var blockSize100k = 0
    private var streamCrc = 0
    private var computedStreamCrc = 0
    private var eof = false

    // 現在のブロック (逆 BWT の表と、そこからの出力の状態)
    private var tt = IntArray(0)
    private var ll = ByteArray(0)
    private var tPos = 0
    private var remaining = 0
    private var blockCrc = 0
    private var computedBlockCrc = -1
    private var lastByte = -1
    private var runCount = 0
    private var repeatLeft = 0
    private var inBlock = false

    init {
        if (!readStreamHeader()) throw IOException("not a bzip2 stream")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var produced = 0
        while (produced < len) {
            if (repeatLeft > 0) {
                b[off + produced++] = lastByte.toByte()
                updateCrc(lastByte)
                repeatLeft--
                continue
            }
            if (remaining == 0) {
                if (inBlock) finishBlock()
                if (produced > 0) break
                if (eof || !nextBlock()) return -1
                continue
            }
            val entry = tt[tPos]
            val ch = entry and 0xff
            tPos = entry ushr 8
            remaining--
            // ブロック内の 4 つ並んだ同じ値の次の 1 バイトは、さらに繰り返す回数
            if (runCount == 4) {
                repeatLeft = ch
                runCount = 0
                continue
            }
            if (ch == lastByte) runCount++ else { runCount = 1; lastByte = ch }
            b[off + produced++] = ch.toByte()
            updateCrc(ch)
        }
        return produced
    }

    override fun close() = input.close()

    private fun updateCrc(b: Int) {
        computedBlockCrc = (computedBlockCrc shl 8) xor CRC_TABLE[((computedBlockCrc ushr 24) xor b) and 0xff]
    }

    private fun finishBlock() {
        inBlock = false
        val crc = computedBlockCrc.inv()
        if (crc != blockCrc) throw IOException("bzip2 block CRC mismatch")
        computedStreamCrc = ((computedStreamCrc shl 1) or (computedStreamCrc ushr 31)) xor crc
    }

    // ===== ビット単位の読み込み =====

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val c = input.read()
            if (c < 0) throw IOException("unexpected end of bzip2 stream")
            bitBuf = (bitBuf shl 8) or c.toLong()
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuf ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    private fun bit(): Int = bits(1)

    private fun readStreamHeader(): Boolean {
        bitBuf = 0; bitCount = 0
        val b = input.read()
        if (b < 0) return false
        if (b != 'B'.code || input.read() != 'Z'.code || input.read() != 'h'.code) return false
        val level = input.read()
        if (level < '1'.code || level > '9'.code) return false
        blockSize100k = level - '0'.code
        computedStreamCrc = 0
        return true
    }

    /** 次のブロックを読み込む。ストリームの終わりなら、続く連結ストリームを探す */
    private fun nextBlock(): Boolean {
        while (true) {
            val magicHi = bits(24)
            val magicLo = bits(24)
            if (magicHi == 0x314159 && magicLo == 0x265359) {
                readBlock()
                return true
            }
            if (magicHi == 0x177245 && magicLo == 0x385090) {
                streamCrc = bits(16) shl 16 or bits(16)
                if (streamCrc != computedStreamCrc) throw IOException("bzip2 stream CRC mismatch")
                // 連結された次のストリーム (無ければ終わり)
                if (!readStreamHeader()) { eof = true; return false }
                continue
            }
            throw IOException("bad bzip2 block header")
        }
    }

    private fun readBlock() {
        blockCrc = bits(16) shl 16 or bits(16)
        if (bit() != 0) throw IOException("randomized bzip2 blocks are not supported")
        val origPtr = bits(24)

        // 使われているバイト値
        val seqToUnseq = IntArray(256)
        var numInUse = 0
        val inUse16 = bits(16)
        for (i in 0 until 16) {
            if (inUse16 and (0x8000 ushr i) != 0) {
                val w = bits(16)
                for (j in 0 until 16) if (w and (0x8000 ushr j) != 0) seqToUnseq[numInUse++] = i * 16 + j
            }
        }
        if (numInUse == 0) throw IOException("bad bzip2 block")
        val alphaSize = numInUse + 2

        // ハフマン表の数と、50 記号ごとにどの表を使うか (MTF 符号化されている)
        val nGroups = bits(3)
        if (nGroups < 2 || nGroups > 6) throw IOException("bad bzip2 block")
        val nSelectors = bits(15)
        if (nSelectors < 1) throw IOException("bad bzip2 block")
        val selectorMtf = IntArray(nGroups) { it }
        val selectors = IntArray(minOf(nSelectors, MAX_SELECTORS))
        for (i in 0 until nSelectors) {
            var j = 0
            while (bit() == 1) { j++; if (j >= nGroups) throw IOException("bad bzip2 block") }
            val v = selectorMtf[j]
            for (k in j downTo 1) selectorMtf[k] = selectorMtf[k - 1]
            selectorMtf[0] = v
            if (i < selectors.size) selectors[i] = v
        }

        // 各ハフマン表の符号長 (差分で書かれている)
        val limit = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val base = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val perm = Array(nGroups) { IntArray(alphaSize) }
        val minLens = IntArray(nGroups)
        val length = IntArray(alphaSize)
        for (t in 0 until nGroups) {
            var curr = bits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (curr < 1 || curr > MAX_CODE_LEN) throw IOException("bad bzip2 code length")
                    if (bit() == 0) break
                    if (bit() == 0) curr++ else curr--
                }
                length[i] = curr
            }
            var minLen = MAX_CODE_LEN; var maxLen = 0
            for (i in 0 until alphaSize) { minLen = minOf(minLen, length[i]); maxLen = maxOf(maxLen, length[i]) }
            createDecodeTables(limit[t], base[t], perm[t], length, minLen, maxLen, alphaSize)
            minLens[t] = minLen
        }

        // 記号を読み、ランレングス (RUNA/RUNB) と MTF を戻して BWT 後の列 (ll) を作る
        val maxBlock = blockSize100k * 100000
        if (ll.size < maxBlock) { ll = ByteArray(maxBlock); tt = IntArray(maxBlock) }
        val unzftab = IntArray(256)
        val yy = IntArray(256) { it }
        val eob = numInUse + 1
        var nblock = 0
        var groupNo = -1
        var groupPos = 0
        var run = 0
        var runPos = 1
        var g = 0
        while (true) {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= selectors.size) throw IOException("bad bzip2 block")
                groupPos = 50
                g = selectors[groupNo]
            }
            groupPos--
            // ハフマン符号を 1 つ読む
            val lim = limit[g]
            var zn = minLens[g]
            var zvec = bits(zn)
            while (zvec > lim[zn]) {
                zn++
                if (zn > MAX_CODE_LEN) throw IOException("bad bzip2 huffman code")
                zvec = (zvec shl 1) or bit()
            }
            val idx = zvec - base[g][zn]
            if (idx < 0 || idx >= alphaSize) throw IOException("bad bzip2 huffman code")
            val sym = perm[g][idx]

            if (sym <= 1) { // RUNA = 0, RUNB = 1
                run += (sym + 1) * runPos
                runPos = runPos shl 1
                if (run > maxBlock) throw IOException("bad bzip2 run")
                continue
            }
            if (run > 0) {
                if (nblock + run > maxBlock) throw IOException("bzip2 block too large")
                val v = seqToUnseq[yy[0]]
                unzftab[v] += run
                java.util.Arrays.fill(ll, nblock, nblock + run, v.toByte())
                nblock += run
                run = 0; runPos = 1
            }
            if (sym == eob) break
            if (nblock >= maxBlock) throw IOException("bzip2 block too large")
            val mi = sym - 1
            val u = yy[mi]
            System.arraycopy(yy, 0, yy, 1, mi)
            yy[0] = u
            val v = seqToUnseq[u]
            unzftab[v]++
            ll[nblock++] = v.toByte()
        }
        if (origPtr < 0 || origPtr >= nblock) throw IOException("bad bzip2 origPtr")

        // 逆 BWT: tt の下位 8 ビットにバイト値、上位にたどる先を入れる
        val cftab = IntArray(256)
        var sum = 0
        for (i in 0 until 256) { cftab[i] = sum; sum += unzftab[i] }
        for (i in 0 until nblock) {
            val c = ll[i].toInt() and 0xff
            tt[cftab[c]++] = i shl 8
        }
        for (i in 0 until nblock) tt[i] = tt[i] or (ll[i].toInt() and 0xff)
        tPos = tt[origPtr] ushr 8
        remaining = nblock
        lastByte = -1
        runCount = 0
        repeatLeft = 0
        computedBlockCrc = -1
        inBlock = true
    }

    private fun createDecodeTables(
        limit: IntArray, base: IntArray, perm: IntArray, length: IntArray, minLen: Int, maxLen: Int, alphaSize: Int,
    ) {
        var pp = 0
        for (i in minLen..maxLen) for (j in 0 until alphaSize) if (length[j] == i) perm[pp++] = j
        java.util.Arrays.fill(base, 0)
        for (i in 0 until alphaSize) base[length[i] + 1]++
        for (i in 1 until base.size) base[i] += base[i - 1]
        java.util.Arrays.fill(limit, -1)
        var vec = 0
        for (i in minLen..maxLen) {
            vec += base[i + 1] - base[i]
            limit[i] = vec - 1
            vec = vec shl 1
        }
        for (i in minLen + 1..maxLen) base[i] = ((limit[i - 1] + 1) shl 1) - base[i]
        // maxLen より長い符号は無いので、そこで必ず止まるようにする
        for (i in maxLen + 1 until limit.size) limit[i] = Int.MAX_VALUE
    }

    private companion object {
        const val MAX_CODE_LEN = 20
        const val MAX_SELECTORS = 18002
        val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
            c
        }
    }
}
