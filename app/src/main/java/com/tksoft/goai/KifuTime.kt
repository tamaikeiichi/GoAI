package com.tksoft.goai

import java.util.Locale
import kotlin.math.roundToLong

/** 棋譜の持ち時間 (SGF の TM と OT)。OT は "5x30 byo-yomi" のような自由な書き方なので、文字列のまま持つ */
data class TimeSettings(val mainTimeSec: Double?, val overtime: String?)

/** 読み込んだ棋譜の残り時間 (GoNode.timeLeft) から、消費時間と残り時間を求める */
object KifuTime {
    /** OT の秒読み1回の秒数 ("5x30 byo-yomi"・"3×60"・"30秒" など)。読めなければ null */
    fun byoYomiSeconds(overtime: String?): Double? {
        val ot = overtime ?: return null
        Regex("""(\d+)\s*[x×X*]\s*(\d+(?:\.\d+)?)""").find(ot)?.let { return it.groupValues[2].toDoubleOrNull() }
        Regex("""(\d+(?:\.\d+)?)\s*(?:秒|s\b|sec)""", RegexOption.IGNORE_CASE).find(ot)?.let { return it.groupValues[1].toDoubleOrNull() }
        return null
    }

    /** node より前で、color が最後に打った手の局面 (無ければ null) */
    private fun previousMoveOf(node: GoNode, color: Stone): GoNode? {
        var n = node.parent
        while (n != null && n.parent != null) {
            if (n.move?.color == color) return n
            n = n.parent
        }
        return null
    }

    /**
     * node の手の消費時間 [秒]。同じ色の前の手 (初手なら持ち時間) の残り時間との差。
     * 秒読みで時計が戻ったときは OT の秒読みの秒数から求める。求められなければ null (フィッシャーの加算など)
     */
    fun consumed(node: GoNode, settings: TimeSettings?): Double? {
        val color = node.move?.color ?: return null
        val cur = node.timeLeft ?: return null
        val prevNode = previousMoveOf(node, color)
        val prev = if (prevNode != null) prevNode.timeLeft ?: return null else settings?.mainTimeSec ?: return null
        val prevPeriods = prevNode?.periodsLeft
        val curPeriods = node.periodsLeft
        val byo = byoYomiSeconds(settings?.overtime)
        if (curPeriods != null && byo != null) {
            // 秒読みに入った・秒読みを使った
            if (prevPeriods == null || curPeriods < prevPeriods) {
                val usedPeriods = if (prevPeriods == null) 0 else prevPeriods - curPeriods - 1
                return prev + usedPeriods.coerceAtLeast(0) * byo + (byo - cur)
            }
            // 日本式の秒読みは打つたびに時計が秒読みの秒数に戻るので、1回の中で打てば「秒読みの秒数 - 残り」。
            // カナダ式 (決まった手数を決まった時間で打つ) は時計が戻らないので、差を取る
            if (settings?.overtime?.contains("canad", ignoreCase = true) != true) return (byo - cur).coerceAtLeast(0.0)
        }
        val d = prev - cur
        if (d >= 0) return d
        // 時計が増えた: 秒読みの1回の中で打った (秒読みの秒数に戻る)
        return byo?.let { (it - cur).coerceAtLeast(0.0) }
    }

    /** node の局面での color の残り時間 [秒] と秒読みの残り回数。棋譜に時間が無ければ null */
    fun remaining(node: GoNode, color: Stone, settings: TimeSettings?): Pair<Double, Int?>? {
        var n: GoNode? = node
        while (n != null && n.parent != null) {
            if (n.move?.color == color) {
                val t = n.timeLeft ?: return null
                return t to n.periodsLeft
            }
            n = n.parent
        }
        return settings?.mainTimeSec?.let { it to null }
    }

    /** 1:02:03・2:03 のように表す (秒は四捨五入) */
    fun format(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).roundToLong()
        val h = s / 3600
        val m = s / 60 % 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** SGF に書く秒数 (整数なら小数点を付けない) */
    fun sgfNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
