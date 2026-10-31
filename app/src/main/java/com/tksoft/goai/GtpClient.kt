package com.tksoft.goai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GTP のコマンドを1つずつ送り、応答 ("=" または "?" で始まり空行で終わる) を待つ。
 * 将棋の USI と違い、GTP は「コマンド → 応答」が必ず1対1なので、送信を Mutex で直列化するだけでよい
 */
class GtpClient(private val engine: KataGoEngine) {
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    data class Response(val ok: Boolean, val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    init {
        engine.onOutputReceived = { lines.trySend(it) }
        // エンジンが終わってしまった場合、待っているコマンドをエラーで返す
        engine.onExited = { lines.trySend("? engine exited"); lines.trySend("") }
    }

    /**
     * @param onLine 応答の2行目以降 (kata-genmove_analyze の info 行など) を受け取るたびに呼ぶ
     */
    suspend fun send(command: String, onLine: ((String) -> Unit)? = null): Response =
        // 途中でキャンセルすると応答の行がずれるので、送ったら最後まで読む
        withContext(NonCancellable) {
            mutex.withLock {
                engine.sendCommand(command)
                readResponse(onLine)
            }
        }

    @Volatile private var analyzing = false
    @Volatile private var stopSent = false

    /**
     * kata-analyze のように、次の行が来るまで info 行を出し続けるコマンド。stopAnalysis() で止めると戻る。
     * KataGo は止めるための行 ("stop") もコマンドとして実行して応答を返すので、それも読んでおく
     */
    suspend fun analyze(command: String, onLine: (String) -> Unit): Response =
        withContext(NonCancellable) {
            mutex.withLock {
                stopSent = false
                analyzing = true
                engine.sendCommand(command)
                val r = readResponse(onLine)
                analyzing = false
                if (stopSent) readResponse(null)
                r
            }
        }

    fun stopAnalysis() {
        if (analyzing && !stopSent) {
            stopSent = true
            engine.sendCommand("stop")
        }
    }

    private suspend fun readResponse(onLine: ((String) -> Unit)?): Response {
        var header: String
        while (true) {
            header = lines.receive()
            if (header.startsWith("=") || header.startsWith("?")) break
        }
        val body = mutableListOf<String>()
        header.substring(1).trim().takeIf { it.isNotEmpty() }?.let { body += it }
        while (true) {
            val line = lines.receive()
            if (line.isEmpty()) break
            body += line
            onLine?.invoke(line)
        }
        return Response(header.startsWith("="), body)
    }
}

/** kata-analyze 系の info 行の候補手1つ分 (値はすべて黒番から見たもの: reportAnalysisWinratesAs = BLACK) */
data class Candidate(
    val move: Move,
    val visits: Int,
    val winrateBlack: Double,
    val scoreLeadBlack: Double,
    val order: Int,
    val pv: List<Move>,
)

/** rootInfo (局面そのものの評価。rootInfo true を付けたときだけ来る) */
data class RootInfo(val winrateBlack: Double, val scoreLeadBlack: Double)

data class Analysis(val candidates: List<Candidate>, val ownershipBlack: List<Double>?, val root: RootInfo? = null) {
    val best: Candidate? get() = candidates.minByOrNull { it.order }
}

object AnalysisParser {
    /** "info move D5 visits 10 ... pv D5 E4 info move ... rootInfo visits 12 ... ownership 0.1 ..." の1行を読む */
    fun parse(line: String, size: Int): Analysis? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.firstOrNull() != "info") return null
        val candidates = mutableListOf<Candidate>()
        var ownership: List<Double>? = null
        var root: RootInfo? = null
        val sectionStarts = setOf("info", "rootInfo", "ownership")
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "info" -> {
                    val end = (i + 1 until tokens.size).firstOrNull { tokens[it] in sectionStarts } ?: tokens.size
                    parseCandidate(tokens.subList(i + 1, end), size)?.let { candidates += it }
                    i = end
                }
                "rootInfo" -> {
                    val end = (i + 1 until tokens.size).firstOrNull { tokens[it] in sectionStarts } ?: tokens.size
                    val map = tokens.subList(i + 1, end).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
                    val w = map["winrate"]?.toDoubleOrNull()
                    val s = map["scoreLead"]?.toDoubleOrNull()
                    if (w != null && s != null) root = RootInfo(w, s)
                    i = end
                }
                "ownership" -> {
                    ownership = tokens.subList(i + 1, (i + 1 + size * size).coerceAtMost(tokens.size))
                        .mapNotNull { it.toDoubleOrNull() }
                        .takeIf { it.size == size * size }
                    i += 1 + size * size
                }
                else -> i++
            }
        }
        return Analysis(candidates, ownership, root)
    }

    private fun parseCandidate(t: List<String>, size: Int): Candidate? {
        val map = mutableMapOf<String, String>()
        var pv = emptyList<Move>()
        var i = 0
        while (i < t.size) {
            if (t[i] == "pv") {
                pv = t.subList(i + 1, t.size).takeWhile { GtpCoord.fromGtp(it, size) != null }
                    .mapNotNull { GtpCoord.fromGtp(it, size) }
                i += 1 + pv.size
            } else if (i + 1 < t.size) {
                map[t[i]] = t[i + 1]
                i += 2
            } else i++
        }
        val move = map["move"]?.let { GtpCoord.fromGtp(it, size) } ?: return null
        return Candidate(
            move = move,
            visits = map["visits"]?.toIntOrNull() ?: 0,
            winrateBlack = map["winrate"]?.toDoubleOrNull() ?: 0.5,
            scoreLeadBlack = map["scoreLead"]?.toDoubleOrNull() ?: 0.0,
            order = map["order"]?.toIntOrNull() ?: 0,
            pv = pv,
        )
    }
}
