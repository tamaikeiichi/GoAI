package com.tksoft.goai

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val BOARD_SIZE = 9

enum class Rules(val gtp: String, val defaultKomi: Double) {
    JAPANESE("japanese", 6.5),
    CHINESE("chinese", 7.0),
}

/**
 * AI の強さ。KataGo は 1 visit (読みなし) でもかなり強いので、弱いレベルは
 * 探索数を減らすだけでなく chosenMoveTemperature を上げて、ときどき最善でない手を選ばせる。
 * CPU (Eigen) で b18c384 のネットを動かすと 1 秒あたり数十 visit 程度なので、最大でも 256 visit にしている
 */
enum class Level(val maxVisits: Int, val temperature: Double) {
    LV1(8, 1.5),
    LV2(16, 0.8),
    LV3(32, 0.4),
    LV4(64, 0.1),
    LV5(128, 0.0),
    LV6(256, 0.0),
}

/** 解析は候補手の探索数の合計がこれを超えたら自動で止める (電池の消耗を抑える) */
private const val ANALYSIS_MAX_VISITS = 3000
/** 読み筋カードに出す候補手の数 */
private const val ANALYSIS_MAX_MOVES = 3

data class GameSettings(
    val humanColor: Stone = Stone.BLACK,
    val rules: Rules = Rules.JAPANESE,
    val komi: Double = Rules.JAPANESE.defaultKomi,
    val handicap: Int = 0,
    val level: Level = Level.LV3,
)

sealed class EngineStatus {
    data object Loading : EngineStatus()
    data object Ready : EngineStatus()
    data class Error(val message: String) : EngineStatus()
}

data class GameResult(
    /** "B+3.5" 形式 (GTP / SGF の RE と同じ) */
    val code: String,
    /** 投了以外で、死に石と地を表示するときに使う */
    val deadStones: Set<Pair<Int, Int>> = emptySet(),
    val ownershipBlack: List<Double>? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = KataGoEngine()
    private val gtp = GtpClient(engine)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var settings by mutableStateOf(loadSettings())
        private set
    var currentNode by mutableStateOf(GoNode(GoGame(BOARD_SIZE)))
        private set
    var engineStatus by mutableStateOf<EngineStatus>(EngineStatus.Loading)
        private set
    /** AI が手を考えている・整地している */
    var thinking by mutableStateOf(false)
        private set
    /** 解析 (kata-analyze) 中 */
    var analyzing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var engineName by mutableStateOf("")
        private set
    /** エンジン準備完了の通知 (表示し終えたら UI 側で null にする) */
    var engineReadyToast by mutableStateOf<String?>(null)

    /** 解析結果とその局面。読み筋カードと盤上の候補手に使う */
    var analysis by mutableStateOf<Analysis?>(null)
        private set
    var analysisNode by mutableStateOf<GoNode?>(null)
        private set
    /** 読み筋カードをタップして分岐を作ったときに固定した解析結果と、その分岐の起点 */
    private var pinnedAnalysis by mutableStateOf<Analysis?>(null)
    private var pinnedNode by mutableStateOf<GoNode?>(null)
    var selectedPvRank by mutableStateOf<Int?>(null)
        private set

    val game: GoGame get() = currentNode.game
    val currentPath: List<GoNode> get() = currentNode.linePath()

    /** エンジン側に送ってある局面 */
    private var engineGame: GoGame? = null
    private var analysisJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val (model, config) = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    KataGoFiles.prepareModel(ctx) to KataGoFiles.writeConfig(ctx)
                }
                engine.start(getApplication<Application>().filesDir.absolutePath, model.absolutePath, config.absolutePath)
                val version = gtp.send("version")
                if (!version.ok) throw IllegalStateException(version.text)
                engineName = "KataGo ${version.text}"
                engineStatus = EngineStatus.Ready
                engineReadyToast = engineName
                startNewGame(settings)
            } catch (e: Exception) {
                Log.e("GoAI", "engine start failed", e)
                engineStatus = EngineStatus.Error(e.message ?: e.toString())
            }
        }
    }

    override fun onCleared() {
        gtp.stopAnalysis()
        engine.stop()
    }

    /** 終局・投了した局面より後は検討 (自由に両方の石を打てる、AI は応じない) */
    val isReviewMode: Boolean
        get() = currentNode.pathFromRoot().any { it.result != null }

    val result: GameResult? get() = currentNode.result

    val canPlay: Boolean
        get() = engineStatus == EngineStatus.Ready && !thinking && result == null &&
            (isReviewMode || game.toPlay == settings.humanColor)

    fun startNewGame(newSettings: GameSettings) {
        if (thinking) return
        stopAnalysis()
        settings = newSettings
        saveSettings(newSettings)
        currentNode = GoNode(GoGame(BOARD_SIZE, GoGame.handicapPoints(BOARD_SIZE, newSettings.handicap)))
        clearAnalysis()
        engineGame = null
        viewModelScope.launch {
            analysisJob?.join()
            gtp.send("boardsize $BOARD_SIZE")
            gtp.send("kata-set-rules ${newSettings.rules.gtp}")
            gtp.send("komi ${newSettings.komi}")
            gtp.send("kata-set-param maxVisits ${newSettings.level.maxVisits}")
            gtp.send("kata-set-param chosenMoveTemperature ${newSettings.level.temperature}")
            gtp.send("kata-set-param chosenMoveTemperatureEarly ${newSettings.level.temperature.coerceAtLeast(0.5)}")
            gtp.send("clear_cache")
            if (game.toPlay != newSettings.humanColor) engineMove()
        }
    }

    /** 樹形図・矢印ボタンで局面を移動する */
    fun goTo(node: GoNode) {
        if (thinking || node === currentNode) return
        // 読み筋の分岐の外へ出たら、固定していた読み筋を外す
        val pinned = pinnedNode
        if (pinned != null && node !== pinned && pinned !in node.pathFromRoot()) unpin()
        currentNode = node
        if (analyzing) restartAnalysis()
    }

    fun onBoardTap(x: Int, y: Int) = playMove(Move.Play(x, y))

    fun pass() = playMove(Move.Pass)

    private fun playMove(move: Move) {
        if (!canPlay) return
        val next = currentNode.childFor(move)
        if (next == null) {
            message = getApplication<Application>().getString(R.string.illegal_move)
            return
        }
        unpin()
        currentNode = next
        val review = isReviewMode
        viewModelScope.launch {
            when {
                game.isEndedByPasses && !review -> {
                    stopAnalysisAndWait()
                    finishByScoring()
                }
                !review && game.toPlay != settings.humanColor -> {
                    stopAnalysisAndWait()
                    engineMove()
                }
                analyzing -> restartAnalysis()
            }
        }
    }

    /** 自分の手番の局面まで戻る (戻った局面から別の手を打つと分岐になる) */
    fun undo() {
        if (thinking) return
        var n = currentNode.parent ?: return
        if (!isReviewMode) {
            while (n.game.toPlay != settings.humanColor) n = n.parent ?: return
        }
        goTo(n)
    }

    fun resign() {
        if (!canPlay || isReviewMode) return
        stopAnalysis()
        currentNode.result = GameResult("${settings.humanColor.opponent.gtp}+R")
    }

    fun toggleAnalysis() {
        if (analyzing) stopAnalysis() else if (engineStatus == EngineStatus.Ready && !thinking) {
            unpin()
            startAnalysis()
        }
    }

    private fun startAnalysis() {
        analyzing = true
        val node = currentNode
        val prev = analysisJob
        analysisJob = viewModelScope.launch {
            prev?.join()
            syncEngine(node.game)
            if (!analyzing || currentNode !== node) return@launch
            analysisNode = node
            analysis = null
            gtp.analyze("kata-analyze ${node.game.toPlay.gtp} interval 50 maxmoves $ANALYSIS_MAX_MOVES") { line ->
                val a = AnalysisParser.parse(line, BOARD_SIZE) ?: return@analyze
                analysis = a
                a.best?.let { node.setEvaluation(it) }
                if (a.candidates.sumOf { it.visits } >= ANALYSIS_MAX_VISITS) {
                    analyzing = false
                    gtp.stopAnalysis()
                }
            }
        }
    }

    private fun restartAnalysis() {
        gtp.stopAnalysis()
        startAnalysis()
    }

    fun stopAnalysis() {
        analyzing = false
        gtp.stopAnalysis()
    }

    private suspend fun stopAnalysisAndWait() {
        stopAnalysis()
        analysisJob?.join()
    }

    private fun clearAnalysis() {
        analysis = null
        analysisNode = null
        unpin()
    }

    private fun unpin() {
        pinnedAnalysis = null
        pinnedNode = null
        selectedPvRank = null
    }

    /** 読み筋カードに出す解析結果と、その局面 */
    val shownAnalysis: Pair<GoNode, Analysis>?
        get() {
            val pa = pinnedAnalysis
            val pn = pinnedNode
            if (pa != null && pn != null) return pn to pa
            val a = analysis ?: return null
            val n = analysisNode ?: return null
            return if (n === currentNode) n to a else null
        }

    /**
     * 読み筋カードのタップ: その読み筋を色付きの分岐として樹形図に加え、読み筋の最後の局面へ移動する。
     * 盤上には分岐の起点からの手順を番号で表示する (pvStartMove)
     */
    fun playPv(rank: Int) {
        if (thinking) return
        val (branchPoint, a) = shownAnalysis ?: return
        val cand = a.candidates.sortedBy { it.order }.getOrNull(rank - 1) ?: return
        stopAnalysis()
        pinnedAnalysis = a
        pinnedNode = branchPoint
        selectedPvRank = rank
        var p = branchPoint
        val nodes = mutableListOf<GoNode>()
        for (m in cand.pv) {
            p = p.childFor(m, isPv = true, pvColor = rank) ?: break
            nodes.add(p)
        }
        currentNode = nodes.lastOrNull() ?: branchPoint
    }

    /** 読み筋の分岐上にいるとき、盤上で手順の番号を付け始める手 (分岐の起点の手数) */
    val pvStartMove: Int?
        get() = pinnedNode?.takeIf { currentNode.isPvBranch }?.moveCount

    private suspend fun engineMove() {
        val node = currentNode
        if (node.result != null) return
        thinking = true
        try {
            syncEngine(node.game)
            val color = node.game.toPlay
            var played: String? = null
            var last: Analysis? = null
            val resp = gtp.send("kata-genmove_analyze ${color.gtp} interval 50") { line ->
                if (line.startsWith("play ")) played = line.removePrefix("play ").trim()
                else AnalysisParser.parse(line, BOARD_SIZE)?.let { last = it }
            }
            val best = last?.best
            best?.let { node.setEvaluation(it) }
            val moveText = played
            if (!resp.ok || moveText == null) {
                message = resp.text.ifEmpty { "engine error" }
                return
            }
            if (moveText.equals("resign", ignoreCase = true)) {
                node.result = GameResult("${color.opponent.gtp}+R")
                return
            }
            val move = GtpCoord.fromGtp(moveText, BOARD_SIZE)
            val next = move?.let { node.childFor(it) }
            if (next == null) {
                message = "engine played an unexpected move: $moveText"
                return
            }
            // AI の手の後の局面の形勢も、AI の読み (最善手の値) で埋めておく
            best?.let { next.setEvaluation(it) }
            engineGame = next.game
            currentNode = next
            if (next.game.isEndedByPasses) finishByScoring()
        } finally {
            thinking = false
        }
    }

    /** 2連続パスで終局: KataGo に死活を判定させて結果を出す */
    private suspend fun finishByScoring() {
        val node = currentNode
        thinking = true
        try {
            syncEngine(node.game)
            val score = gtp.send("final_score")
            // 死に石は final_status_list dead でも取れるが、そちらも内部で探索するので
            // 地の表示用の ownership から「相手の地の中にある石」として求める
            var ownership: List<Double>? = null
            gtp.send("kata-search_analyze ${node.game.toPlay.gtp} interval 1000 ownership true") { line ->
                AnalysisParser.parse(line, BOARD_SIZE)?.ownershipBlack?.let { ownership = it }
            }
            val board = node.game.board
            val deadStones = ownership?.let { o ->
                (0 until BOARD_SIZE).flatMap { y -> (0 until BOARD_SIZE).map { x -> x to y } }.filter { (x, y) ->
                    val v = o[y * BOARD_SIZE + x]
                    when (board[x, y]) {
                        Stone.BLACK -> v < -0.5
                        Stone.WHITE -> v > 0.5
                        Stone.EMPTY -> false
                    }
                }.toSet()
            } ?: emptySet()
            node.result = GameResult(score.text.trim().ifEmpty { "?" }, deadStones, ownership)
        } finally {
            thinking = false
        }
    }

    /** エンジンの局面を target に合わせる。送ってある局面の続きなら残りの手だけ送る */
    private suspend fun syncEngine(target: GoGame) {
        val sent = engineGame
        val moves = target.moves
        val prefixOk = sent != null && sent.handicapStones == target.handicapStones &&
            sent.moves.size <= moves.size && moves.subList(0, sent.moves.size) == sent.moves
        val toSend = if (prefixOk) {
            moves.drop(sent!!.moves.size)
        } else {
            gtp.send("clear_board")
            if (target.handicapStones.isNotEmpty()) {
                gtp.send("set_free_handicap " + target.handicapStones.joinToString(" ") {
                    GtpCoord.toGtp(Move.Play(it.first, it.second), BOARD_SIZE)
                })
            }
            moves
        }
        for (m in toSend) {
            val r = gtp.send("play ${m.color.gtp} ${GtpCoord.toGtp(m.move, BOARD_SIZE)}")
            if (!r.ok) Log.w("GoAI", "engine rejected move: ${r.text}")
        }
        engineGame = target
    }

    fun toSgf(): String = Sgf.write(currentNode.root, settings)

    private fun loadSettings(): GameSettings {
        val d = GameSettings()
        return GameSettings(
            humanColor = if (prefs.getString("humanColor", "B") == "W") Stone.WHITE else Stone.BLACK,
            rules = runCatching { Rules.valueOf(prefs.getString("rules", d.rules.name)!!) }.getOrDefault(d.rules),
            komi = prefs.getFloat("komi", d.komi.toFloat()).toDouble(),
            handicap = prefs.getInt("handicap", d.handicap),
            level = runCatching { Level.valueOf(prefs.getString("level", d.level.name)!!) }.getOrDefault(d.level),
        )
    }

    private fun saveSettings(s: GameSettings) {
        prefs.edit()
            .putString("humanColor", s.humanColor.gtp)
            .putString("rules", s.rules.name)
            .putFloat("komi", s.komi.toFloat())
            .putInt("handicap", s.handicap)
            .putString("level", s.level.name)
            .apply()
    }
}
