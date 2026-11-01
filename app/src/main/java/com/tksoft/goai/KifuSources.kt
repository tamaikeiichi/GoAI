package com.tksoft.goai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 「過去の棋譜」に取り込む SGF を、ファイル・圧縮ファイル・フォルダから取り出す。
 * - 1つの SGF
 * - ZIP、tar、およびそれらを gzip / bzip2 で圧縮したもの (.tar.gz, .tgz, .tar.bz2, .tbz2, .sgf.gz など)。
 *   形式は拡張子ではなくファイルの先頭のバイトで見分ける。中のフォルダ分けはたどり、圧縮ファイルの中の圧縮ファイルも読む
 * - フォルダ (中のフォルダもすべてたどる。そこに置かれた圧縮ファイルも読む)
 */
object KifuSources {
    /** これより大きい SGF は読まない (壊れたファイルでメモリを使い切らないように) */
    private const val MAX_SGF_BYTES = 32 * 1024 * 1024
    /** 圧縮ファイルの中の圧縮ファイルをたどる深さ */
    private const val MAX_DEPTH = 4

    private val archiveExtensions = listOf(".zip", ".tar", ".gz", ".tgz", ".bz2", ".tbz", ".tbz2")

    fun isSgfName(name: String) = name.endsWith(".sgf", ignoreCase = true)
    fun isArchiveName(name: String) = archiveExtensions.any { name.endsWith(it, ignoreCase = true) }

    /**
     * input (名前は name) の中の SGF を1つずつ onSgf に渡す。onSgf には SGF のファイル名 (フォルダ部分を除く) と中身を渡す。
     * 読めない部分があれば、それまでに見つけた SGF は渡したうえで例外を投げる
     */
    fun forEachSgf(input: InputStream, name: String, onSgf: (String, ByteArray) -> Unit) =
        forEachSgf(input, name, 0, onSgf)

    private fun forEachSgf(raw: InputStream, name: String, depth: Int, onSgf: (String, ByteArray) -> Unit) {
        val input = BufferedInputStream(raw, 64 * 1024)
        val head = peek(input, 512)
        when {
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> readZip(input, depth, onSgf)
            head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
                forEachSgf(GZIPInputStream(input, 64 * 1024), stripCompressionExtension(name), depth + 1, onSgf)
            head.size >= 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() ->
                forEachSgf(Bzip2InputStream(input), stripCompressionExtension(name), depth + 1, onSgf)
            isTarHeader(head) -> readTar(input, depth, onSgf)
            else -> onSgf(baseName(name), readLimited(input))
        }
    }

    private fun peek(input: BufferedInputStream, n: Int): ByteArray {
        input.mark(n)
        val buf = ByteArray(n)
        var len = 0
        while (len < n) {
            val r = input.read(buf, len, n - len)
            if (r < 0) break
            len += r
        }
        input.reset()
        return buf.copyOf(len)
    }

    private fun baseName(path: String) = path.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')

    private fun stripCompressionExtension(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tgz") || lower.endsWith(".tbz") -> name.dropLast(4) + ".tar"
            lower.endsWith(".tbz2") -> name.dropLast(5) + ".tar"
            lower.endsWith(".gz") -> name.dropLast(3)
            lower.endsWith(".bz2") -> name.dropLast(4)
            else -> name
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_SGF_BYTES) throw IOException("file too large")
        }
        return out.toByteArray()
    }

    /** 中の圧縮ファイルを読むとき、外側のストリームまで閉じてしまわないようにする */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    private fun handleEntry(path: String, data: InputStream, depth: Int, onSgf: (String, ByteArray) -> Unit) {
        when {
            isSgfName(path) -> onSgf(baseName(path), readLimited(data))
            isArchiveName(path) && depth < MAX_DEPTH -> forEachSgf(NonClosing(data), path, depth + 1, onSgf)
        }
    }

    private fun readZip(input: InputStream, depth: Int, onSgf: (String, ByteArray) -> Unit) {
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory) handleEntry(e.name, zip, depth, onSgf)
            zip.closeEntry()
        }
    }

    // ===== tar =====

    private fun isTarHeader(h: ByteArray): Boolean {
        if (h.size < 512) return false
        if (String(h, 257, 5, Charsets.ISO_8859_1) == "ustar") return true
        // 古い形式 (ustar の印が無い) は、ヘッダのチェックサムで見分ける
        val stored = parseOctal(h, 148, 8) ?: return false
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) ' '.code else (h[i].toInt() and 0xff)
        return sum == stored && h[0] != 0.toByte()
    }

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long? {
        var i = off
        val end = off + len
        while (i < end && (h[i] == ' '.code.toByte() || h[i] == 0.toByte())) i++
        if (i >= end) return null
        var v = 0L
        var digits = 0
        while (i < end && h[i] in '0'.code.toByte()..'7'.code.toByte()) {
            v = v * 8 + (h[i] - '0'.code.toByte()); i++; digits++
        }
        return if (digits == 0) null else v
    }

    private fun tarSize(h: ByteArray): Long {
        // 8GB 以上は先頭ビットを立てた 2 進数で書かれる (GNU tar)
        if (h[124].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        return parseOctal(h, 124, 12) ?: 0L
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var len = 0
        while (len < buf.size) {
            val r = input.read(buf, len, buf.size - len)
            if (r < 0) {
                if (len == 0) return false
                throw IOException("truncated tar")
            }
            len += r
        }
        return true
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("truncated tar")
            left -= r
        }
    }

    /** 1つの項目の中身だけを読ませる */
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        override fun close() {}
        fun drain() = skipFully(this, left)
    }

    private fun readTar(input: InputStream, depth: Int, onSgf: (String, ByteArray) -> Unit) {
        val h = ByteArray(512)
        var longName: String? = null
        var paxPath: String? = null
        while (readFully(input, h)) {
            if (h.all { it == 0.toByte() }) break // 終わりの印
            val size = tarSize(h)
            val padded = (size + 511) / 512 * 512
            val type = h[156].toInt().toChar()
            when (type) {
                // GNU tar の長い名前と、pax の拡張ヘッダ (次の項目の名前などが書かれている)
                'L' -> { longName = String(readTarData(input, size, padded), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> { paxPath = parsePaxPath(readTarData(input, size, padded)); continue }
                'g' -> { skipFully(input, padded); continue }
            }
            val ustarPrefix = if (String(h, 257, 5, Charsets.ISO_8859_1) == "ustar") cString(h, 345, 155) else ""
            val headerName = cString(h, 0, 100).let { if (ustarPrefix.isNotEmpty()) "$ustarPrefix/$it" else it }
            val name = paxPath ?: longName ?: headerName
            longName = null; paxPath = null
            if (type == '0' || type == '\u0000' || type == '7') {
                val data = Bounded(input, size)
                handleEntry(name, data, depth, onSgf)
                data.drain()
                skipFully(input, padded - size)
            } else {
                skipFully(input, padded)
            }
        }
    }

    private fun readTarData(input: InputStream, size: Long, padded: Long): ByteArray {
        if (size > MAX_SGF_BYTES) throw IOException("tar header too large")
        val data = ByteArray(size.toInt())
        if (!readFully(input, data) && size > 0) throw IOException("truncated tar")
        skipFully(input, padded - size)
        return data
    }

    /** pax の拡張ヘッダ ("長さ path=名前\n" の並び) から path を取り出す */
    private fun parsePaxPath(data: ByteArray): String? {
        var pos = 0
        var path: String? = null
        while (pos < data.size) {
            val sp = (pos until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val len = String(data, pos, sp - pos, Charsets.ISO_8859_1).toIntOrNull() ?: break
            if (len <= 0 || pos + len > data.size) break
            val record = String(data, sp + 1, pos + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            if (record.startsWith("path=")) path = record.removePrefix("path=")
            pos += len
        }
        return path
    }

    // ===== フォルダ (Storage Access Framework) =====

    /**
     * 選んだフォルダ (treeUri) の中を、下のフォルダまですべてたどって SGF を onSgf に渡す。圧縮ファイルの中も読む。
     * 1つのファイルが読めなくても続け、その数を onError で知らせる
     */
    fun forEachSgfInTree(context: Context, treeUri: Uri, onSgf: (String, ByteArray) -> Unit, onError: () -> Unit) {
        val resolver = context.contentResolver
        val stack = ArrayDeque<String>()
        stack.addLast(DocumentsContract.getTreeDocumentId(treeUri))
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val docId = stack.removeLast()
            if (!visited.add(docId)) continue
            val children = mutableListOf<Triple<String, String, Boolean>>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: ""
                        val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        children.add(Triple(id, name, isDir))
                    }
                }
            } catch (e: Exception) { onError(); continue }
            // 名前順に読む (フォルダは後で、名前順に取り出せるよう逆順に積む)
            val sorted = children.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
            for ((id, name, isDir) in sorted) {
                if (isDir || !(isSgfName(name) || isArchiveName(name))) continue
                try {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    resolver.openInputStream(uri)?.use { forEachSgf(it, name, onSgf) }
                } catch (e: Exception) { onError() }
            }
            sorted.filter { it.third }.asReversed().forEach { stack.addLast(it.first) }
        }
    }
}
