package com.tksoft.goai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class KifuSourcesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("kifu/$name")!!

    private fun collect(name: String): Map<String, ByteArray> {
        val found = linkedMapOf<String, ByteArray>()
        resource(name).use { KifuSources.forEachSgf(it, name) { n, b -> found[n] = b } }
        return found
    }

    /** a/b/1012585.sgf (CGOS の 19路盤) と c/game2.sgf (9路盤) と readme.txt (SGF ではない) を固めたもの */
    private fun checkSample(name: String) {
        val found = collect(name)
        assertEquals(setOf("1012585.sgf", "game2.sgf"), found.keys)
        val cgos = SgfParser.parse(SgfParser.decode(found.getValue("1012585.sgf")))
        assertEquals(19, cgos.root.game.size)
        assertTrue(KifuHistoryManager.countMainLineMoves(cgos.root) > 100)
        assertEquals("kata1_b10s3203v800", cgos.blackName)
        val small = SgfParser.parse(SgfParser.decode(found.getValue("game2.sgf")))
        assertEquals(9, small.root.game.size)
        assertEquals(2, KifuHistoryManager.countMainLineMoves(small.root))
    }

    @Test fun readsTarBz2WithFolders() = checkSample("sample.tar.bz2")
    @Test fun readsTarGz() = checkSample("sample.tar.gz")
    @Test fun readsZip() = checkSample("sample.zip")

    @Test
    fun readsSingleCompressedSgf() {
        val found = collect("game2.sgf.bz2")
        assertEquals(setOf("game2.sgf"), found.keys)
    }

    @Test
    fun bzip2MultiBlockAndConcatenatedStreams() {
        // 274005 バイトのデータ (長い同じ文字の並びと乱数) を、-1 (複数ブロック) と -9 で圧縮して連結したもの
        val out = resource("mixed_concat.bin.bz2").use { Bzip2InputStream(it.buffered()).readBytes() }
        assertEquals(274005 * 2, out.size)
        val md5 = MessageDigest.getInstance("MD5")
        val expected = "5ba222bca9a5315c8d1d8b7922a654ed"
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals(expected, hex(md5.digest(out.copyOfRange(0, 274005))))
        assertEquals(expected, hex(md5.digest(out.copyOfRange(274005, out.size))))
    }

    /**
     * 手元の大きな圧縮ファイルと、それを展開したフォルダを比べる (無ければ飛ばす)。
     * 環境変数 GOAI_TEST_ARCHIVE (例: 19x19_2026_09.tar.bz2) と GOAI_TEST_FOLDER (それを展開したフォルダ) で指定する
     */
    @Test
    fun largeArchiveMatchesFolder() {
        val archive = System.getenv("GOAI_TEST_ARCHIVE")?.let(::File)
        val folder = System.getenv("GOAI_TEST_FOLDER")?.let(::File)
        assumeTrue(archive != null && archive.exists() && folder != null && folder.isDirectory)
        val expected = folder!!.walkTopDown().filter { it.isFile && it.name.endsWith(".sgf") }.associateBy { it.name }
        var count = 0
        var parsed = 0
        val start = System.currentTimeMillis()
        archive!!.inputStream().use { input ->
            KifuSources.forEachSgf(input, archive.name) { name, bytes ->
                count++
                assertArrayEquals(name, expected.getValue(name).readBytes(), bytes)
                val r = SgfParser.parse(SgfParser.decode(bytes))
                if (r.root.children.isNotEmpty()) parsed++
            }
        }
        println("read $count SGF ($parsed with moves) in ${System.currentTimeMillis() - start} ms")
        assertEquals(expected.size, count)
    }
}
