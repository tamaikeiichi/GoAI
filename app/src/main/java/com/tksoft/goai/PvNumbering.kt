package com.tksoft.goai

/** 番号付きの石 (読み筋の手順)。分岐の起点より前からある石には番号の代わりに A, B... を付ける */
data class NumberedStone(val label: String, val color: Stone)

/**
 * 読み筋の手順を新聞の棋譜と同じく一枚の図にする。取られた石も盤から消さずに残し (stones)、
 * 盤上にはその点に最初に打たれた手の番号を書く (onBoard)。
 * 石が取られた後に同じ点へ打たれた手は盤上に書けないので、「その手 [盤上の石]」の組 (replays) にして盤の外に書く。
 * 起点より前からある石が取られてそこへ打たれたときは、その石に A, B... を付けて [A] のように書く
 */
class PvNumbering(
    val stones: Board,
    val onBoard: Map<Pair<Int, Int>, NumberedStone>,
    val replays: List<Pair<NumberedStone, NumberedStone>>,
)

fun pvNumbering(game: GoGame, start: Int): PvNumbering {
    var stones = game.boardAt(start)
    val onBoard = LinkedHashMap<Pair<Int, Int>, NumberedStone>()
    val replays = mutableListOf<Pair<NumberedStone, NumberedStone>>()
    var letters = 0
    game.moves.forEachIndexed { i, m ->
        val mv = m.move
        if (i < start || mv !is Move.Play) return@forEachIndexed
        val p = mv.x to mv.y
        val stone = NumberedStone((i - start + 1).toString(), m.color)
        val first = onBoard[p]
        val before = stones[mv.x, mv.y]
        when {
            first != null -> replays.add(stone to first)
            before != Stone.EMPTY -> {
                val marked = NumberedStone(MARK_LETTERS[letters++ % MARK_LETTERS.length].toString(), before)
                onBoard[p] = marked
                replays.add(stone to marked)
            }
            else -> {
                onBoard[p] = stone
                stones = stones.withStone(mv.x, mv.y, m.color)
            }
        }
    }
    return PvNumbering(stones, onBoard, replays)
}

private const val MARK_LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"
