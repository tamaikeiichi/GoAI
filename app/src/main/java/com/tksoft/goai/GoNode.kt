package com.tksoft.goai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 棋譜の1局面 (ShogiGUI の KifuNode に相当)。GoGame は不変なので、局面そのものを持たせる。
 * 待ったや読み筋カードのタップで別の手を打つと、子が増えて樹形図の分岐になる
 */
class GoNode(
    val game: GoGame,
    val parent: GoNode? = null,
    val isPvBranch: Boolean = false,
    val pvColorIndex: Int = 0, // 0:なし, 1:第1候補, 2:第2候補...
) {
    val children = mutableStateListOf<GoNode>()
    val moveCount: Int get() = game.moves.size
    /** この局面に着いた手 (根は null) */
    val move: MoveRecord? get() = if (parent == null) null else game.lastMove

    // 解析で得た形勢 (黒番から見た値。null = 未解析)
    var winrateBlack by mutableStateOf<Double?>(null)
    var scoreLeadBlack by mutableStateOf<Double?>(null)
    /** この局面の最後の解析結果。戻ってきたときに読み筋カードと盤上の候補手を出す (再解析で上書き。保存はしない) */
    var analysis by mutableStateOf<Analysis?>(null)
    /** この局面で対局が終わった (投了・終局) ときの結果 */
    var result by mutableStateOf<GameResult?>(null)

    // 樹形図で同じ行に続ける子。PV分岐は同じ候補順位のPVだけ、それ以外は最初の非PV子を続ける。
    // 待ったして打ち直した手や別の候補の読み筋は、新しい行（枝分かれ）になる
    fun continuationChild(): GoNode? =
        if (isPvBranch) children.firstOrNull { it.isPvBranch && it.pvColorIndex == pvColorIndex }
        else children.firstOrNull { !it.isPvBranch }

    /** move を打った子。同じ手の子があればそれを使う (PV 分岐かどうか・色も一致するものだけ) */
    fun childFor(move: Move, isPv: Boolean = false, pvColor: Int = 0): GoNode? {
        children.firstOrNull { it.move?.move == move && it.isPvBranch == isPv && (!isPv || it.pvColorIndex == pvColor) }
            ?.let { return it }
        val next = game.play(move) ?: return null
        return GoNode(next, this, isPv, pvColor).also { children.add(it) }
    }

    fun setEvaluation(c: Candidate) {
        winrateBlack = c.winrateBlack
        scoreLeadBlack = c.scoreLeadBlack
    }

    val root: GoNode get() = parent?.root ?: this

    /** 根からこの局面まで */
    fun pathFromRoot(): List<GoNode> = generateSequence(this) { it.parent }.toList().asReversed()

    /** 根からこの局面を通り、continuationChild を辿った終わりまで (樹形図・矢印ボタンで動く範囲) */
    fun linePath(): List<GoNode> {
        val path = pathFromRoot().toMutableList()
        var n = continuationChild()
        while (n != null) {
            path.add(n)
            n = n.continuationChild()
        }
        return path
    }
}
