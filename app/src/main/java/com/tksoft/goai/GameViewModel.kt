package com.tksoft.goai

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 対応している盤の大きさ */
val BOARD_SIZES = listOf(9, 13, 19)

enum class Rules(val gtp: String, val defaultKomi: Double) {
    JAPANESE("japanese", 6.5),
    CHINESE("chinese", 7.0),
}

/** 解析は候補手の探索数の合計がこれを超えたら自動で止める (電池の消耗を抑える) */
private const val ANALYSIS_MAX_VISITS = 3000
/** 読み筋カードに出す候補手の数 */
private const val ANALYSIS_MAX_MOVES = 3
/** 「この局面から全て解析」で、思考時間を過ぎても最初の結果が出ないときに待つ上限 [ms] */
private const val FIRST_RESULT_TIMEOUT_MS = 10_000L

data class GameSettings(
    val boardSize: Int = 9,
    val humanColor: Stone = Stone.BLACK,
    val rules: Rules = Rules.JAPANESE,
    val komi: Double = 6.5,
    val handicap: Int = 0,
    /** 石の置き方: false = タップ, true = 指のやや上に出る半透明の石をドラッグして離す */
    val dragToPlace: Boolean = false,
    /**
     * 思考時間 [ms]: AI が1手に使う時間と、「この局面から全て解析」で1手あたりに使う時間。
     * このアプリは解析が主なので AI の強さは選ばせず、常に最善手を打たせて、強さは思考時間だけで変える
     */
    val analysisTimeMs: Long = 1000L,
)

/** 置石の最大数 (9路盤は5子まで) */
fun maxHandicap(boardSize: Int) = if (boardSize == 9) 5 else 9

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
    // 盤の大きさによって使うネットが違うので、ネットを変えるときはエンジンを作り直す
    private var engine = KataGoEngine()
    private var gtp = GtpClient(engine)
    private var loadedModel: KataGoModel? = null
    private val engineMutex = Mutex()
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var settings by mutableStateOf(loadSettings())
        private set
    var currentNode by mutableStateOf(GoNode(GoGame(settings.boardSize)))
        private set
    var engineStatus by mutableStateOf<EngineStatus>(EngineStatus.Loading)
        private set
    /** AI が手を考えている・整地している */
    var thinking by mutableStateOf(false)
        private set
    /** 解析 (kata-analyze) 中 */
    var analyzing by mutableStateOf(false)
        private set
    /** 「この局面から全て解析」中 (ShogiGUI の自動解析: 1手ずつ決まった時間だけ解析して次の手へ進む) */
    var autoAnalyzing by mutableStateOf(false)
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

    /**
     * 対局中に人が持っている石。null は検討中 (ShogiGUI と同じく、両方の石を自由に置け、AI は打たない)。
     * メニューの「黒番/白番を持って対局」で今の局面から対局を始め、「対局中止」・終局・投了で null に戻る
     */
    var humanColor by mutableStateOf<Stone?>(null)
        private set

    // 表示中の棋譜の対局者名・対局日と、保存先の「過去の棋譜」の id (まだ1手も打っていない棋譜なら null)
    var blackName by mutableStateOf(prefs.getString("black_name", null) ?: str(R.string.black))
        private set
    var whiteName by mutableStateOf(prefs.getString("white_name", null) ?: str(R.string.white))
        private set
    private var gameDate: String? = prefs.getString("game_date", null)
    private var historyId: String? = prefs.getString("history_id", null)

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    init {
        // 前回表示していた棋譜を復元する
        prefs.getString("current_tree", null)?.let { saved ->
            runCatching { KifuJson.fromJson(JSONObject(saved)) }.getOrNull()?.let { r ->
                var n = r.root
                while (true) n = n.children.firstOrNull { !it.isPvBranch } ?: break
                currentNode = n
            }
        }
        viewModelScope.launch { configureEngine() }
    }

    /** 盤の大きさに合うネットでエンジンを動かす。違うネットで動いていれば起動し直す */
    private suspend fun ensureEngine(size: Int): Boolean {
        val model = KataGoModel.forBoardSize(size)
        if (loadedModel == model && engineStatus == EngineStatus.Ready) return true
        engineStatus = EngineStatus.Loading
        return try {
            if (loadedModel != null) {
                gtp.stopAnalysis()
                engine.stop()
                engine = KataGoEngine()
                gtp = GtpClient(engine)
                loadedModel = null
            }
            val ctx = getApplication<Application>()
            val (modelFile, config) = withContext(Dispatchers.IO) {
                KataGoFiles.prepareModel(ctx, model) to KataGoFiles.writeConfig(ctx)
            }
            engine.start(ctx.filesDir.absolutePath, modelFile.absolutePath, config.absolutePath)
            val version = gtp.send("version")
            if (!version.ok) throw IllegalStateException(version.text)
            loadedModel = model
            engineGame = null
            engineName = "KataGo ${version.text} (${model.displayName})"
            engineStatus = EngineStatus.Ready
            engineReadyToast = engineName
            true
        } catch (e: Exception) {
            Log.e("GoAI", "engine start failed", e)
            engineStatus = EngineStatus.Error(e.message ?: e.toString())
            false
        }
    }

    override fun onCleared() {
        gtp.stopAnalysis()
        engine.stop()
    }

    val result: GameResult? get() = currentNode.result

    val isPlayingGame: Boolean get() = humanColor != null

    // 検討中は終局した局面からも続きを打てる
    val canPlay: Boolean
        get() = engineStatus == EngineStatus.Ready && !thinking &&
            (humanColor == null || (result == null && game.toPlay == humanColor))

    /** 表示中の棋譜の盤の大きさに合わせてエンジンを用意し、ルール・コミ・思考時間を送る */
    private suspend fun configureEngine() = engineMutex.withLock {
        val s = settings
        val size = game.size
        if (!ensureEngine(size)) return@withLock
        engineGame = null
        gtp.send("boardsize $size")
        gtp.send("kata-set-rules ${s.rules.gtp}")
        gtp.send("komi ${s.komi}")
        // AI の手は思考時間だけで止める (探索数では止めない)。いつも最善手を選ぶ
        gtp.send("kata-set-param maxVisits 1000000")
        gtp.send("kata-set-param maxTime ${s.analysisTimeMs / 1000.0}")
        gtp.send("kata-set-param chosenMoveTemperature 0")
        gtp.send("kata-set-param chosenMoveTemperatureEarly 0")
        gtp.send("clear_cache")
    }

    /** 設定 (盤の大きさ・思考時間・ルール・コミ・置石) を保存してエンジンに送る。盤の大きさを変えたらリセットする */
    fun applySettings(newSettings: GameSettings) {
        val sizeChanged = newSettings.boardSize != game.size
        settings = newSettings.copy(humanColor = settings.humanColor)
        saveSettings(settings)
        if (sizeChanged) {
            reset()
            return
        }
        if (engineStatus != EngineStatus.Ready) return
        viewModelScope.launch {
            stopAnalysisAndWait()
            configureEngine()
        }
    }

    /** 新しい棋譜にする (ShogiGUI のリセット)。置石は設定のもの */
    fun reset() {
        if (thinking) return
        saveKifu()
        stopAnalysis()
        humanColor = null
        clearAnalysis()
        engineGame = null
        currentNode = GoNode(GoGame(settings.boardSize, GoGame.handicapPoints(settings.boardSize, settings.handicap)))
        blackName = str(R.string.black)
        whiteName = str(R.string.white)
        // 次に打った手からは新しい棋譜として履歴に保存する
        historyId = null
        gameDate = null
        prefs.edit().remove("current_tree").remove("history_id").remove("game_date")
            .remove("black_name").remove("white_name").apply()
        viewModelScope.launch {
            analysisJob?.join()
            configureEngine()
        }
    }

    /** 本譜 (最初の非 PV 子の列) だけを残し、それ以外の子 (分岐・読み筋) を削除する */
    fun deleteBranches() {
        if (thinking) return
        stopAnalysis()
        unpin()
        var n: GoNode? = currentNode.root
        while (n != null) {
            val main = n.children.firstOrNull { !it.isPvBranch }
            n.children.retainAll { it === main }
            n = main
        }
        // 削除した分岐の上にいたら、本譜へ戻る
        if (currentNode !in currentNode.root.linePath()) {
            var m = currentNode.root
            for (next in currentNode.pathFromRoot().drop(1)) {
                if (m.children.firstOrNull() !== next) break
                m = next
            }
            currentNode = m
        }
        saveKifu()
    }

    /** 今の局面から、color を持って AI と対局する (ShogiGUI の「先手番を持って対局」) */
    fun playAs(color: Stone) {
        if (thinking || engineStatus != EngineStatus.Ready) return
        stopAnalysis()
        unpin()
        humanColor = color
        settings = settings.copy(humanColor = color).also { saveSettings(it) }
        val ai = str(R.string.ai_name)
        if (color == Stone.BLACK) { blackName = str(R.string.you); whiteName = ai }
        else { blackName = ai; whiteName = str(R.string.you) }
        if (gameDate == null) gameDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        viewModelScope.launch {
            analysisJob?.join()
            maybeEngineMove()
        }
    }

    fun stopGame() {
        humanColor = null
    }

    /** 対局中で AI の手番なら AI に打たせる (局面を移動したときも、ShogiGUI と同じく AI が続きを打つ) */
    private suspend fun maybeEngineMove() {
        val hc = humanColor ?: return
        if (engineStatus != EngineStatus.Ready || thinking || result != null || game.toPlay == hc) return
        stopAnalysisAndWait()
        engineMove()
    }

    /** 樹形図・矢印ボタンで局面を移動する */
    fun goTo(node: GoNode) {
        if (thinking || node === currentNode) return
        if (autoAnalyzing) stopAutoAnalysis()
        // 読み筋の分岐の外へ出たら、固定していた読み筋を外す
        val pinned = pinnedNode
        if (pinned != null && node !== pinned && pinned !in node.pathFromRoot()) unpin()
        currentNode = node
        // 読み筋の分岐の中を動いている間は、起点の局面の解析を続ける (カードが最新の結果に変わり続ける)
        if (analyzing && pinnedNode == null) restartAnalysis()
        viewModelScope.launch { maybeEngineMove() }
    }

    /** 本譜 (最初の非 PV 子を辿る列) 上の、分岐の起点へ戻る。分岐の手順や形勢は消さずに残す */
    fun goToMainLine() {
        var mainNode = currentNode.root
        for (next in currentNode.pathFromRoot().drop(1)) {
            if (mainNode.children.firstOrNull { !it.isPvBranch } !== next) break
            mainNode = next
        }
        unpin()
        goTo(mainNode)
    }

    val isOnMainLine: Boolean
        get() = currentNode.pathFromRoot().zipWithNext().all { (p, c) -> p.children.firstOrNull { !it.isPvBranch } === c }

    fun onBoardTap(x: Int, y: Int) = playMove(Move.Play(x, y))

    fun pass() = playMove(Move.Pass)

    private fun playMove(move: Move) {
        if (!canPlay) return
        if (autoAnalyzing) stopAutoAnalysis()
        val next = currentNode.childFor(move)
        if (next == null) {
            message = getApplication<Application>().getString(R.string.illegal_move)
            return
        }
        unpin()
        currentNode = next
        saveKifu()
        viewModelScope.launch {
            when {
                game.isEndedByPasses -> {
                    stopAnalysisAndWait()
                    finishByScoring()
                }
                isPlayingGame -> maybeEngineMove()
                analyzing -> restartAnalysis()
            }
        }
    }

    fun resign() {
        val hc = humanColor ?: return
        if (!canPlay) return
        currentNode.result = GameResult("${hc.opponent.gtp}+R")
        humanColor = null
        saveKifu()
    }

    /** ShogiGUI の解析ボタン: 対局中は対局中止、解析中は停止、それ以外は解析を始める */
    fun onAnalyzeButton() {
        when {
            autoAnalyzing -> stopAutoAnalysis()
            isPlayingGame -> stopGame()
            analyzing -> stopAnalysis()
            engineStatus == EngineStatus.Ready && !thinking -> {
                unpin()
                startAnalysis()
            }
        }
    }

    /** 「この局面から全て解析」ボタン: 解析中なら止める */
    fun onAutoAnalyzeButton() {
        if (autoAnalyzing) stopAutoAnalysis() else startAutoAnalysis()
    }

    /**
     * 今の局面から本譜の最後まで、1手ずつ settings.analysisTimeMs だけ解析して形勢を付け、次の手へ進む
     * (ShogiGUI の「現局面から最後まで解析」)
     */
    private fun startAutoAnalysis() {
        if (engineStatus != EngineStatus.Ready || thinking || isPlayingGame) return
        stopAnalysis()
        unpin()
        autoAnalyzing = true
        val prev = analysisJob
        analysisJob = viewModelScope.launch {
            prev?.join()
            var node = currentNode
            try {
                while (autoAnalyzing) {
                    currentNode = node
                    syncEngine(node.game)
                    analysisNode = node
                    analysis = null
                    analyzing = true
                    val evaluated = CompletableDeferred<Unit>()
                    val search = launch {
                        gtp.analyze("kata-analyze ${node.game.toPlay.gtp} interval 50 maxmoves $ANALYSIS_MAX_MOVES") { line ->
                            val a = AnalysisParser.parse(line, node.game.size) ?: return@analyze
                            analysis = a
                            a.best?.let { node.setEvaluation(it); evaluated.complete(Unit) }
                        }
                    }
                    delay(settings.analysisTimeMs)
                    // 思考時間が短いと、最初の結果が出る前に止めてしまい形勢が付かないので、最初の結果までは待つ
                    withTimeoutOrNull(FIRST_RESULT_TIMEOUT_MS) { evaluated.await() }
                    gtp.stopAnalysis()
                    search.join()
                    node = node.continuationChild() ?: break
                }
            } finally {
                autoAnalyzing = false
                analyzing = false
                saveKifu()
            }
        }
    }

    fun stopAutoAnalysis() {
        autoAnalyzing = false
        gtp.stopAnalysis()
    }

    /** クリップボードなどの SGF を読み込んで新しい棋譜にする */
    fun loadSgf(text: String) {
        if (thinking) return
        val loaded = try {
            SgfParser.parse(text)
        } catch (e: Exception) {
            message = str(R.string.sgf_load_failed, e.message ?: "")
            return
        }
        stopAnalysis()
        humanColor = null
        clearAnalysis()
        engineGame = null
        settings = settings.copy(
            boardSize = loaded.root.game.size,
            komi = loaded.komi ?: settings.komi,
            rules = loaded.rules ?: settings.rules,
            handicap = loaded.root.game.handicapStones.size,
        ).also { saveSettings(it) }
        var last = loaded.root
        while (true) last = last.children.firstOrNull() ?: break
        currentNode = last
        blackName = loaded.blackName ?: str(R.string.black)
        whiteName = loaded.whiteName ?: str(R.string.white)
        gameDate = loaded.date
        historyId = null
        saveKifu()
        viewModelScope.launch {
            analysisJob?.join()
            configureEngine()
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
                val a = AnalysisParser.parse(line, node.game.size) ?: return@analyze
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
        // 解析で付いた形勢 (形勢グラフ) は、解析を止めたときにまとめて保存する
        if (analyzing) saveKifu()
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
            val a = analysis
            val n = analysisNode
            val pn = pinnedNode
            if (pn != null) {
                // 読み筋を盤上に出している間も、起点の局面の解析が続いていれば最新の結果をカードに出す
                if (a != null && n === pn) return pn to a
                return pinnedAnalysis?.let { pn to it }
            }
            if (a == null || n == null) return null
            return if (n === currentNode) n to a else null
        }

    /**
     * 読み筋カードのタップ: その時点の読み筋を色付きの分岐として樹形図に加え、読み筋の最後の局面へ移動する。
     * 盤上には分岐の起点からの手順を番号で表示する (pvStartMove)。
     * 解析は起点の局面のまま続け、カードは最新の結果に変わっていく。盤上の読み筋はカードをもう一度タップしたときに更新する
     */
    fun playPv(rank: Int) {
        if (thinking) return
        val (branchPoint, a) = shownAnalysis ?: return
        val cand = a.candidates.sortedBy { it.order }.getOrNull(rank - 1) ?: return
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
        if (!analyzing) saveKifu()
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
                else AnalysisParser.parse(line, node.game.size)?.let { last = it }
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
                humanColor = null
                saveKifu()
                return
            }
            // 考えている間に「対局中止」されたら、その手は打たない
            if (humanColor == null) return
            val move = GtpCoord.fromGtp(moveText, node.game.size)
            val next = move?.let { node.childFor(it) }
            if (next == null) {
                message = "engine played an unexpected move: $moveText"
                return
            }
            // AI の手の後の局面の形勢も、AI の読み (最善手の値) で埋めておく
            best?.let { next.setEvaluation(it) }
            engineGame = next.game
            currentNode = next
            saveKifu()
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
                AnalysisParser.parse(line, node.game.size)?.ownershipBlack?.let { ownership = it }
            }
            val board = node.game.board
            val size = node.game.size
            val deadStones = ownership?.let { o ->
                (0 until size).flatMap { y -> (0 until size).map { x -> x to y } }.filter { (x, y) ->
                    val v = o[y * size + x]
                    when (board[x, y]) {
                        Stone.BLACK -> v < -0.5
                        Stone.WHITE -> v > 0.5
                        Stone.EMPTY -> false
                    }
                }.toSet()
            } ?: emptySet()
            node.result = GameResult(score.text.trim().ifEmpty { "?" }, deadStones, ownership)
            humanColor = null
            saveKifu()
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
                    GtpCoord.toGtp(Move.Play(it.first, it.second), target.size)
                })
            }
            moves
        }
        for (m in toSend) {
            val r = gtp.send("play ${m.color.gtp} ${GtpCoord.toGtp(m.move, target.size)}")
            if (!r.ok) Log.w("GoAI", "engine rejected move: ${r.text}")
        }
        engineGame = target
    }

    private fun record() = KifuRecord(currentNode.root, settings.komi, settings.rules, blackName, whiteName, gameDate)

    fun toSgf(): String = Sgf.write(record())

    /**
     * 棋譜を保存する。着手・終局・解析の停止などのたびと、画面が裏に回るときに呼ぶ。
     * 表示中の棋譜 (次回起動時に復元) と「過去の棋譜」の両方に同じ内容を保存する
     */
    fun saveKifu() {
        val root = currentNode.root
        val json = KifuJson.toJson(record()).toString()
        prefs.edit().putString("current_tree", json).putString("black_name", blackName)
            .putString("white_name", whiteName).putString("game_date", gameDate).apply()
        if (historyId != null || root.children.isNotEmpty()) {
            val id = historyId ?: KifuHistoryManager.newId().also {
                historyId = it
                prefs.edit().putString("history_id", it).apply()
            }
            KifuHistoryManager.saveOrUpdateAsync(
                getApplication(), id, json, blackName, whiteName,
                KifuHistoryManager.mainLineResult(root), gameDate, KifuHistoryManager.countMainLineMoves(root),
            )
        }
    }

    /** 「過去の棋譜」を開く。この棋譜を直したら、同じ履歴を上書きする */
    fun openHistory(id: String, r: KifuRecord) {
        if (thinking) return
        saveKifu()
        stopAnalysis()
        humanColor = null
        clearAnalysis()
        engineGame = null
        settings = settings.copy(
            boardSize = r.root.game.size, komi = r.komi, rules = r.rules, handicap = r.root.game.handicapStones.size,
        )
            .also { saveSettings(it) }
        var n = r.root
        while (true) n = n.children.firstOrNull { !it.isPvBranch } ?: break
        currentNode = n
        blackName = r.blackName.ifEmpty { str(R.string.black) }
        whiteName = r.whiteName.ifEmpty { str(R.string.white) }
        gameDate = r.gameDate
        historyId = id
        prefs.edit().putString("history_id", id).apply()
        saveKifu()
        viewModelScope.launch {
            analysisJob?.join()
            configureEngine()
        }
    }

    private fun loadSettings(): GameSettings {
        val d = GameSettings()
        return GameSettings(
            boardSize = prefs.getInt("boardSize", d.boardSize).takeIf { it in BOARD_SIZES } ?: d.boardSize,
            humanColor = if (prefs.getString("humanColor", "B") == "W") Stone.WHITE else Stone.BLACK,
            rules = runCatching { Rules.valueOf(prefs.getString("rules", d.rules.name)!!) }.getOrDefault(d.rules),
            komi = prefs.getFloat("komi", d.komi.toFloat()).toDouble(),
            handicap = prefs.getInt("handicap", d.handicap),
            dragToPlace = prefs.getBoolean("dragToPlace", d.dragToPlace),
            analysisTimeMs = prefs.getLong("analysisTimeMs", d.analysisTimeMs),
        )
    }

    private fun saveSettings(s: GameSettings) {
        prefs.edit()
            .putInt("boardSize", s.boardSize)
            .putString("humanColor", s.humanColor.gtp)
            .putString("rules", s.rules.name)
            .putFloat("komi", s.komi.toFloat())
            .putInt("handicap", s.handicap)
            .putBoolean("dragToPlace", s.dragToPlace)
            .putLong("analysisTimeMs", s.analysisTimeMs)
            .apply()
    }
}
