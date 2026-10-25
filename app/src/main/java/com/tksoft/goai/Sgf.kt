package com.tksoft.goai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 棋譜を SGF (囲碁の標準的な棋譜形式) にする。分岐 (読み筋の分岐は除く) は SGF の変化図として書く */
object Sgf {
    private fun coord(x: Int, y: Int) = "${'a' + x}${'a' + y}"

    fun write(root: GoNode, settings: GameSettings): String {
        val game = root.game
        val ai = "KataGo"
        val (pb, pw) = if (settings.humanColor == Stone.BLACK) "You" to ai else ai to "You"
        val rules = if (settings.rules == Rules.JAPANESE) "Japanese" else "Chinese"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("(;GM[1]FF[4]CA[UTF-8]AP[GoAI]SZ[${game.size}]")
        sb.append("KM[${settings.komi}]RU[$rules]DT[$date]PB[$pb]PW[$pw]")
        if (game.handicapStones.isNotEmpty()) {
            sb.append("HA[${game.handicapStones.size}]AB")
            game.handicapStones.forEach { (x, y) -> sb.append("[${coord(x, y)}]") }
        }
        // 本譜 (最初の子を辿った先) の結果を RE にする
        var n: GoNode? = root
        var mainResult: String? = null
        while (n != null) {
            n.result?.let { mainResult = it.code }
            n = n.children.firstOrNull { !it.isPvBranch }
        }
        mainResult?.let { sb.append("RE[$it]") }
        writeChildren(root, sb)
        sb.append(")\n")
        return sb.toString()
    }

    private fun writeChildren(node: GoNode, sb: StringBuilder) {
        val kids = node.children.filter { !it.isPvBranch }
        when {
            kids.isEmpty() -> return
            kids.size == 1 -> writeNode(kids[0], sb)
            else -> kids.forEach { k ->
                sb.append("\n(")
                writeNode(k, sb)
                sb.append(")")
            }
        }
    }

    private fun writeNode(node: GoNode, sb: StringBuilder) {
        val m = node.move ?: return
        val pos = when (val mv = m.move) {
            is Move.Pass -> ""
            is Move.Play -> coord(mv.x, mv.y)
        }
        sb.append(";${m.color.gtp}[$pos]")
        writeChildren(node, sb)
    }
}
