package com.tksoft.goai

/** 棋譜を SGF (囲碁の標準的な棋譜形式) にする。分岐 (読み筋の分岐は除く) は SGF の変化図として書く */
object Sgf {
    private fun coord(x: Int, y: Int) = "${'a' + x}${'a' + y}"

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("]", "\\]")

    fun write(r: KifuRecord): String {
        val root = r.root
        val game = root.game
        val rules = if (r.rules == Rules.JAPANESE) "Japanese" else "Chinese"
        val sb = StringBuilder()
        sb.append("(;GM[1]FF[4]CA[UTF-8]AP[GoAI]SZ[${game.size}]")
        sb.append("KM[${r.komi}]RU[$rules]")
        r.gameDate?.let { sb.append("DT[${escape(it)}]") }
        if (r.blackName.isNotEmpty()) sb.append("PB[${escape(r.blackName)}]")
        if (r.whiteName.isNotEmpty()) sb.append("PW[${escape(r.whiteName)}]")
        if (game.handicapStones.isNotEmpty()) {
            sb.append("HA[${game.handicapStones.size}]AB")
            game.handicapStones.forEach { (x, y) -> sb.append("[${coord(x, y)}]") }
        }
        KifuHistoryManager.mainLineResult(root).takeIf { it.isNotEmpty() }?.let { sb.append("RE[$it]") }
        r.timeSettings?.mainTimeSec?.let { sb.append("TM[${KifuTime.sgfNumber(it)}]") }
        r.timeSettings?.overtime?.let { sb.append("OT[${escape(it)}]") }
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
        // 読み込んだ棋譜の残り時間 (黒は BL・OB、白は WL・OW)
        val (tl, pl) = if (m.color == Stone.BLACK) "BL" to "OB" else "WL" to "OW"
        node.timeLeft?.let { sb.append("$tl[${KifuTime.sgfNumber(it)}]") }
        node.periodsLeft?.let { sb.append("$pl[$it]") }
        writeChildren(node, sb)
    }
}
