package com.tksoft.goai

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * SGF (囲碁の棋譜) を読み込んで樹形図にする。変化図は分岐になる。
 * 9路盤で、最初に置かれている石が黒だけ (置碁) のものに対応する
 */
object SgfParser {
    class Loaded(
        val root: GoNode,
        val komi: Double?,
        val rules: Rules?,
        val blackName: String?,
        val whiteName: String?,
        val result: String?,
        val date: String?,
        val timeSettings: TimeSettings?,
    )

    /**
     * ファイルの中身を文字列にする。CA[...] に文字コードが書かれていればそれを使い、
     * 無ければ UTF-8 として読めるか試して、だめなら日本の棋譜に多い Shift_JIS とみなす
     */
    fun decode(bytes: ByteArray): String {
        // CA の値は ASCII なので、とりあえず ISO-8859-1 で読んで探す
        val ca = Regex("""CA\s*\[([^\]]*)\]""").find(String(bytes, Charsets.ISO_8859_1))?.groupValues?.get(1)?.trim()
        ca?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }?.let { return String(bytes, it) }
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charset.forName("Shift_JIS")) }
    }

    private class SgfNode(val props: Map<String, List<String>>, val children: MutableList<SgfNode> = mutableListOf())

    fun parse(text: String): Loaded {
        val start = text.indexOf("(;")
        if (start < 0) throw IllegalArgumentException("not SGF")
        val p = Reader(text, start)
        val tree = p.readGameTree() ?: throw IllegalArgumentException("empty SGF")
        val props = tree.props
        val size = props["SZ"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 19
        if (size !in BOARD_SIZES) throw IllegalArgumentException("board size $size is not supported")
        if (props["AW"] != null) throw IllegalArgumentException("setup stones (AW) are not supported")
        val handicap = props["AB"].orEmpty().flatMap { expandPoints(it) }
        val root = GoNode(GoGame(size, handicap))
        // 根のノードに手が書かれていることもある
        val firstNodes = if (props["B"] != null || props["W"] != null) listOf(tree) else tree.children
        firstNodes.forEach { addMoves(root, it) }

        val result = props["RE"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (result != null) {
            var last = root
            while (true) last = last.children.firstOrNull() ?: break
            if (last !== root) last.result = GameResult(normalizeResult(result))
        }
        val rules = props["RU"]?.firstOrNull()?.lowercase()?.let {
            when {
                it.startsWith("jap") -> Rules.JAPANESE
                it.startsWith("chi") || it.startsWith("aga") || it.startsWith("new") -> Rules.CHINESE
                else -> null
            }
        }
        return Loaded(
            root = root,
            komi = props["KM"]?.firstOrNull()?.trim()?.toDoubleOrNull(),
            rules = rules,
            blackName = props["PB"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            whiteName = props["PW"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            result = result,
            date = props["DT"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            timeSettings = TimeSettings(
                mainTimeSec = props["TM"]?.firstOrNull()?.trim()?.toDoubleOrNull(),
                overtime = props["OT"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            ).takeIf { it.mainTimeSec != null || it.overtime != null },
        )
    }

    /** "B+3.5" / "W+R" / "0" 以外の書き方 ("B+Resign", "Draw" など) をそろえる */
    private fun normalizeResult(re: String): String = when {
        re.equals("Draw", true) || re == "0" -> "0"
        re.matches(Regex("(?i)[BW]\\+(R|Resign)")) -> "${re[0].uppercaseChar()}+R"
        else -> re
    }

    private fun addMoves(parent: GoNode, sgf: SgfNode) {
        val color = when {
            sgf.props["B"] != null -> Stone.BLACK
            sgf.props["W"] != null -> Stone.WHITE
            else -> null
        }
        var node = parent
        if (color != null) {
            val v = (sgf.props["B"] ?: sgf.props["W"])!!.first().trim()
            // 手番が違う手 (同じ色が続けて打つなど) はこの盤面では表せないので、その先は読まない
            if (node.game.toPlay != color) return
            val move = if (v.isEmpty() || v == "tt") Move.Pass else {
                val x = v[0] - 'a'
                val y = v.getOrNull(1)?.let { it - 'a' } ?: return
                Move.Play(x, y)
            }
            node = node.childFor(move) ?: return
            // 打った後の残り時間 (黒は BL・OB、白は WL・OW)
            val (tl, pl) = if (color == Stone.BLACK) "BL" to "OB" else "WL" to "OW"
            sgf.props[tl]?.firstOrNull()?.trim()?.toDoubleOrNull()?.let { node.timeLeft = it }
            sgf.props[pl]?.firstOrNull()?.trim()?.toIntOrNull()?.let { node.periodsLeft = it }
        }
        sgf.children.forEach { addMoves(node, it) }
    }

    /** "aa" または範囲指定 "aa:cc" */
    private fun expandPoints(v: String): List<Pair<Int, Int>> {
        val t = v.trim()
        if (t.length == 2) return listOf((t[0] - 'a') to (t[1] - 'a'))
        if (t.length == 5 && t[2] == ':') {
            val x1 = t[0] - 'a'; val y1 = t[1] - 'a'; val x2 = t[3] - 'a'; val y2 = t[4] - 'a'
            return (minOf(y1, y2)..maxOf(y1, y2)).flatMap { y -> (minOf(x1, x2)..maxOf(x1, x2)).map { x -> x to y } }
        }
        return emptyList()
    }

    private class Reader(val s: String, var i: Int) {
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        /** "(" から対応する ")" まで。連続するノードは子の列にする */
        fun readGameTree(): SgfNode? {
            skipWs()
            if (i >= s.length || s[i] != '(') return null
            i++
            var first: SgfNode? = null
            var last: SgfNode? = null
            while (true) {
                skipWs()
                if (i >= s.length) break
                when (s[i]) {
                    ';' -> {
                        i++
                        val n = SgfNode(readProps())
                        if (first == null) first = n else last!!.children.add(n)
                        last = n
                    }
                    '(' -> {
                        val sub = readGameTree() ?: break
                        if (last == null) { first = sub; last = sub } else last.children.add(sub)
                    }
                    ')' -> { i++; break }
                    else -> i++
                }
            }
            return first
        }

        private fun readProps(): Map<String, List<String>> {
            val props = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                skipWs()
                if (i >= s.length || !s[i].isLetter()) break
                val keyStart = i
                while (i < s.length && s[i].isLetter()) i++
                // 古い SGF の小文字混じりのキー (例: "AddBlack") は大文字だけを使う
                val key = s.substring(keyStart, i).filter { it.isUpperCase() }
                val values = mutableListOf<String>()
                while (true) {
                    skipWs()
                    if (i >= s.length || s[i] != '[') break
                    i++
                    val sb = StringBuilder()
                    while (i < s.length && s[i] != ']') {
                        if (s[i] == '\\' && i + 1 < s.length) i++
                        sb.append(s[i]); i++
                    }
                    i++
                    values.add(sb.toString())
                }
                props.getOrPut(key) { mutableListOf() }.addAll(values)
            }
            return props
        }
    }
}
