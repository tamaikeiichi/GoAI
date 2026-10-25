package com.tksoft.goai

/** 石の色。EMPTY は空点 */
enum class Stone {
    EMPTY, BLACK, WHITE;

    val opponent: Stone
        get() = when (this) {
            BLACK -> WHITE
            WHITE -> BLACK
            EMPTY -> EMPTY
        }

    /** GTP の色指定 ("B" / "W") */
    val gtp: String get() = if (this == BLACK) "B" else "W"
}

/** 着手。pass は盤上の位置を持たない */
sealed class Move {
    data class Play(val x: Int, val y: Int) : Move()
    data object Pass : Move()
}

data class MoveRecord(val color: Stone, val move: Move)

/**
 * 盤面 (不変)。座標は x = 左から 0.., y = 上から 0..
 * 将棋と違って駒の動きは無く、着手は「空点に石を置き、呼吸点の無くなった相手の石を取る」だけ
 */
class Board private constructor(val size: Int, private val cells: Array<Stone>) {
    constructor(size: Int) : this(size, Array(size * size) { Stone.EMPTY })

    operator fun get(x: Int, y: Int): Stone = cells[y * size + x]

    fun isOnBoard(x: Int, y: Int) = x in 0 until size && y in 0 until size

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> =
        listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).filter { (nx, ny) -> isOnBoard(nx, ny) }

    /** (x, y) の石を含む連と、その呼吸点の数 */
    private fun groupAndLiberties(x: Int, y: Int, cells: Array<Stone>): Pair<Set<Int>, Int> {
        val color = cells[y * size + x]
        val group = mutableSetOf(y * size + x)
        val liberties = mutableSetOf<Int>()
        val stack = ArrayDeque(listOf(x to y))
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            for ((nx, ny) in neighbors(cx, cy)) {
                val i = ny * size + nx
                when (cells[i]) {
                    Stone.EMPTY -> liberties.add(i)
                    color -> if (group.add(i)) stack.addLast(nx to ny)
                    else -> {}
                }
            }
        }
        return group to liberties.size
    }

    /**
     * (x, y) に color の石を置いた結果。置けない (石がある・自殺手) 場合は null。
     * コウの判定は局面の履歴が要るので GoGame で行う
     */
    fun play(x: Int, y: Int, color: Stone): PlayResult? {
        if (!isOnBoard(x, y) || this[x, y] != Stone.EMPTY) return null
        val next = cells.copyOf()
        next[y * size + x] = color
        var captured = 0
        for ((nx, ny) in neighbors(x, y)) {
            if (next[ny * size + nx] != color.opponent) continue
            val (group, libs) = groupAndLiberties(nx, ny, next)
            if (libs == 0) {
                group.forEach { next[it] = Stone.EMPTY }
                captured += group.size
            }
        }
        // 自殺手は禁止 (日本ルール・中国ルールとも)
        if (groupAndLiberties(x, y, next).second == 0) return null
        return PlayResult(Board(size, next), captured)
    }

    fun withoutStones(points: Collection<Pair<Int, Int>>): Board {
        val next = cells.copyOf()
        points.forEach { (x, y) -> next[y * size + x] = Stone.EMPTY }
        return Board(size, next)
    }

    fun count(color: Stone) = cells.count { it == color }

    override fun equals(other: Any?) = other is Board && other.size == size && other.cells.contentEquals(cells)
    override fun hashCode() = cells.contentHashCode()

    data class PlayResult(val board: Board, val captured: Int)
}

/** 盤上の点と GTP の座標 ("D5" など。I は使わず、行番号は下から数える) の変換 */
object GtpCoord {
    private const val LETTERS = "ABCDEFGHJKLMNOPQRST"

    fun toGtp(move: Move, size: Int): String = when (move) {
        is Move.Pass -> "pass"
        is Move.Play -> "${LETTERS[move.x]}${size - move.y}"
    }

    /** "pass" / "resign" 以外の不正な文字列は null */
    fun fromGtp(s: String, size: Int): Move? {
        val t = s.trim().uppercase()
        if (t == "PASS") return Move.Pass
        if (t.length < 2) return null
        val x = LETTERS.indexOf(t[0])
        val row = t.substring(1).toIntOrNull() ?: return null
        val y = size - row
        if (x !in 0 until size || y !in 0 until size) return null
        return Move.Play(x, y)
    }
}

/**
 * 対局の状態 (不変)。手の履歴から盤面を作り直せるようにしておき、待ったは履歴を切り詰めるだけにする
 */
class GoGame private constructor(
    val size: Int,
    val handicapStones: List<Pair<Int, Int>>,
    val moves: List<MoveRecord>,
    /** moves の各手の後の盤面。positions[0] は初期局面 */
    private val positions: List<Board>,
    /** 取った石の数 (アゲハマ)。黒が取った白石, 白が取った黒石 */
    val capturedByBlack: Int,
    val capturedByWhite: Int,
) {
    constructor(size: Int, handicapStones: List<Pair<Int, Int>> = emptyList()) : this(
        size, handicapStones, emptyList(),
        listOf(handicapStones.fold(Board(size)) { b, (x, y) -> b.play(x, y, Stone.BLACK)!!.board }),
        0, 0,
    )

    val board: Board get() = positions.last()

    /** 置き碁は白から打つ */
    val toPlay: Stone
        get() {
            val first = if (handicapStones.isEmpty()) Stone.BLACK else Stone.WHITE
            return if (moves.size % 2 == 0) first else first.opponent
        }

    val lastMove: MoveRecord? get() = moves.lastOrNull()

    /** 直前の2手が連続パスなら終局 */
    val isEndedByPasses: Boolean
        get() = moves.size >= 2 && moves.takeLast(2).all { it.move is Move.Pass }

    /**
     * 着手の結果。打てない場合は null。
     * コウは「直前の局面に戻る手」を禁止する (単純コウ)。三コウなどの同形反復はエンジン側の判定に任せる
     */
    fun play(move: Move): GoGame? {
        val color = toPlay
        return when (move) {
            is Move.Pass -> GoGame(
                size, handicapStones, moves + MoveRecord(color, move), positions + board,
                capturedByBlack, capturedByWhite,
            )
            is Move.Play -> {
                val result = board.play(move.x, move.y, color) ?: return null
                if (positions.size >= 2 && result.board == positions[positions.size - 2]) return null
                GoGame(
                    size, handicapStones, moves + MoveRecord(color, move), positions + result.board,
                    capturedByBlack + if (color == Stone.BLACK) result.captured else 0,
                    capturedByWhite + if (color == Stone.WHITE) result.captured else 0,
                )
            }
        }
    }

    fun isLegal(move: Move) = play(move) != null

    /** 最後の n 手を取り消す */
    fun undo(n: Int = 1): GoGame {
        var g = GoGame(size, handicapStones)
        moves.dropLast(n.coerceAtMost(moves.size)).forEach { g = g.play(it.move)!! }
        return g
    }

    companion object {
        /** 9路盤の置石の位置 (日本の慣例: 星 → 天元) */
        fun handicapPoints(size: Int, count: Int): List<Pair<Int, Int>> {
            if (count < 2) return emptyList()
            val e = if (size >= 13) 3 else 2
            val far = size - 1 - e
            val mid = size / 2
            val corners = listOf(far to e, e to far, far to far, e to e)
            val sides = listOf(e to mid, far to mid, mid to e, mid to far)
            val center = mid to mid
            return when (count) {
                2, 3, 4 -> corners.take(count)
                5 -> corners + center
                6 -> corners + sides.take(2)
                7 -> corners + sides.take(2) + center
                8 -> corners + sides
                else -> corners + sides + center
            }
        }
    }
}
