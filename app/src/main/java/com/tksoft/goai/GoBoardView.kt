package com.tksoft.goai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/** 通常の碁盤の色 (読み筋の分岐上では ShogiGUI と同じく候補順位の色にする) */
val woodBoardColor = Color(0xFFDCB35C)
private val lineColor = Color(0xFF3A2A10)
/** 座標の文字色 (OGS の木目の盤と同じ) */
private val labelColor = Color(0xFF444444)
/**
 * 盤の端から最初の線までのマス数。石を少しでも大きくするため、座標欄は OGS (1 マス + 半マス) より狭くし、
 * 端の石 (半径 0.48 マス) のすぐ外に座標の文字が収まる幅にする
 */
private const val MARGIN = 1.1f
/** 盤の端から座標の文字の中心までのマス数 */
private const val LABEL_POS = 0.32f
private const val COLUMN_LABELS = "ABCDEFGHJKLMNOPQRST"
/** 碁盤の目の縦横比。実物の碁盤 (目の幅 約 22mm, 高さ 約 23.7mm) と同じく少し縦長にする */
const val BOARD_CELL_ASPECT = 1.07f

/** 番号付きの石 (読み筋の手順) */
data class NumberedStone(val number: Int, val color: Stone)

/**
 * 読み筋の手順の番号。盤上には、その点に最初に打たれた手の番号を書く。
 * 石が取られた後に同じ点へ打たれた手は盤上に書けないので、新聞の棋譜と同じく
 * 「その手 [盤上の石]」の組 (replays) にして盤の外に書く
 */
class PvNumbering(
    val onBoard: Map<Pair<Int, Int>, NumberedStone>,
    val replays: List<Pair<NumberedStone, NumberedStone>>,
)

fun pvNumbering(game: GoGame, start: Int): PvNumbering {
    val onBoard = LinkedHashMap<Pair<Int, Int>, NumberedStone>()
    val replays = mutableListOf<Pair<NumberedStone, NumberedStone>>()
    game.moves.forEachIndexed { i, m ->
        val mv = m.move
        if (i < start || mv !is Move.Play) return@forEachIndexed
        val stone = NumberedStone(i - start + 1, m.color)
        val first = onBoard[mv.x to mv.y]
        if (first == null) onBoard[mv.x to mv.y] = stone else replays.add(stone to first)
    }
    return PvNumbering(onBoard, replays)
}

/** 黒番・白番などを示す石 (盤上の石と同じ描き方)。文字の ● ○ の代わりに使う */
@Composable
fun StoneIcon(stone: Stone, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawStone(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension / 0.96f, stone, alpha = 1f)
    }
}

/** 盤の外に書く番号付きの石 (盤上の石と同じ描き方) */
@Composable
fun NumberedStoneIcon(stone: NumberedStone, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val d = this.size.minDimension
        drawStone(Offset(this.size.width / 2, this.size.height / 2), d / 0.96f, stone.color, alpha = 1f)
        drawLabel(
            stone.number.toString(), Offset(this.size.width / 2, this.size.height / 2),
            d * if (stone.number >= 100) 0.42f else if (stone.number >= 10) 0.5f else 0.6f,
            if (stone.color == Stone.BLACK) Color.White else Color.Black,
        )
    }
}

/** 盤上に示す候補手 (解析中) */
data class BoardCandidate(val move: Move.Play, val color: Color, val label: String)

/**
 * 碁盤。将棋盤と違い、石はマスの中ではなく線の交点に置く。
 * 座標は OGS (online-go.com) と同じく盤の外側の欄 (MARGIN) に書く。上下に数字 (左から 1〜)、
 * 左右にアルファベット (上から A〜、I は使わない) を太字で書く。左上が 1A
 * 目は実物の碁盤と同じく少し縦長 (BOARD_CELL_ASPECT) で、石は正円
 *
 * @param pvStartMove 読み筋の分岐を表示中なら、番号を付け始める手数 (その手から 1, 2, 3...)
 * @param placement 石の置き方。13路・19路で交点を正確にタップしにくいときのために、
 *   DOUBLE_TAP は1回目のタップで半透明の石を出して同じ点の2回目のタップで打ち、
 *   DRAG は触れた位置のやや上 (指に隠れない所) に半透明の石を出し、ドラッグして離した交点に打つ
 * @param ghostColor 半透明の石の色。打てないとき (AI の手番など) は null
 */
@Composable
fun GoBoardView(
    game: GoGame,
    onTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    boardColor: Color = woodBoardColor,
    candidates: List<BoardCandidate> = emptyList(),
    result: GameResult? = null,
    pvStartMove: Int? = null,
    placement: Placement = Placement.TAP,
    ghostColor: Stone? = null,
) {
    val size = game.size
    // 交点の数 + 両側の余白 (座標欄)
    val cells = size - 1 + 2 * MARGIN
    // ドラッグで打つときの半透明の石の位置
    var ghost by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // ダブルタップで打つときの、1回目にタップした点 (半透明の石を出す)。局面が変わったら消す
    var pending by remember(game) { mutableStateOf<Pair<Int, Int>?>(null) }
    // 半透明の石を指からどれだけ上に出すか。触れた点は指の腹の中心なので、指先 (触れた点の 5mm ほど上まで) に
    // 隠れないよう 1cm ほど上に出す
    val liftPx = with(LocalDensity.current) { 64.dp.toPx() }
    Canvas(
        modifier = modifier
            .aspectRatio(1f / BOARD_CELL_ASPECT)
            .pointerInput(size, placement, game) {
                fun point(pos: Offset, lift: Float): Pair<Int, Int>? {
                    val x = (pos.x / (this.size.width / cells) - MARGIN).roundToInt()
                    val y = ((pos.y - lift) / (this.size.height / cells) - MARGIN).roundToInt()
                    return if (x in 0 until size && y in 0 until size) x to y else null
                }
                if (placement == Placement.TAP) {
                    detectTapGestures { pos -> point(pos, 0f)?.let { (x, y) -> onTap(x, y) } }
                } else if (placement == Placement.DOUBLE_TAP) {
                    // 別の点をタップしたら、半透明の石をそこへ移す
                    detectTapGestures { pos ->
                        val p = point(pos, 0f) ?: return@detectTapGestures
                        if (p == pending) {
                            pending = null
                            onTap(p.first, p.second)
                        } else pending = p
                    }
                } else {
                    // 指を置いている間は盤のスクロールより石の位置合わせを優先するので、イベントを消費する
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        ghost = point(down.position, liftPx)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    ghost?.let { (x, y) -> onTap(x, y) }
                                    break
                                }
                                ghost = point(change.position, liftPx)
                                change.consume()
                            }
                        } finally {
                            ghost = null
                        }
                    }
                }
            }
    ) {
        // cell は目の幅。石・印の大きさは幅を基準にする (縦は cellH)
        val cell = this.size.width / cells
        val cellH = this.size.height / cells
        fun center(x: Int, y: Int) = Offset(cell * (x + MARGIN), cellH * (y + MARGIN))

        drawRect(boardColor)
        drawGrid(size, cell, cellH)
        drawCoordinates(size, cell, cellH)

        val board = game.board
        val dead = result?.deadStones ?: emptySet()
        val ownership = result?.ownershipBlack

        for (y in 0 until size) for (x in 0 until size) {
            val s = board[x, y]
            if (s != Stone.EMPTY) drawStone(center(x, y), cell, s, alpha = if ((x to y) in dead) 0.45f else 1f)
        }

        // 終局後: 地 (KataGo の ownership) を小さな四角で示す。死に石の上にも出す
        if (ownership != null) {
            for (y in 0 until size) for (x in 0 until size) {
                val o = ownership[y * size + x]
                val owner = when {
                    o > 0.5 -> Stone.BLACK
                    o < -0.5 -> Stone.WHITE
                    else -> Stone.EMPTY
                }
                if (owner == Stone.EMPTY) continue
                if (board[x, y] == Stone.EMPTY || (x to y) in dead) {
                    val half = cell * 0.17f
                    val c = center(x, y)
                    drawRect(
                        if (owner == Stone.BLACK) Color.Black else Color.White,
                        topLeft = Offset(c.x - half, c.y - half),
                        size = Size(half * 2, half * 2),
                    )
                }
            }
        }

        if (pvStartMove != null) {
            // 読み筋の手順: 分岐の起点からの手に番号を付ける (同じ点に打ち直した手は盤の外に書く: pvNumbering)
            pvNumbering(game, pvStartMove).onBoard.forEach { (p, numbered) ->
                val stone = board[p.first, p.second]
                if (stone == Stone.EMPTY) return@forEach
                val num = numbered.number
                drawLabel(
                    num.toString(), center(p.first, p.second), cell * if (num >= 10) 0.42f else 0.5f,
                    if (stone == Stone.BLACK) Color.White else Color.Black,
                )
            }
        } else {
            // 最後の手の印
            (game.lastMove?.move as? Move.Play)?.let { m ->
                val stone = board[m.x, m.y]
                if (stone != Stone.EMPTY) {
                    drawCircle(
                        color = if (stone == Stone.BLACK) Color.White else Color.Black,
                        radius = cell * 0.18f,
                        center = center(m.x, m.y),
                        style = Stroke(width = cell * 0.06f),
                    )
                }
            }
        }

        // 解析中の候補手: 読み筋カードと同じ色の丸と順位。
        // どちらの手番の候補か分かるよう、丸の縁を打つ側の色 (黒番なら黒、白番なら白) のやや太い線にする
        val candidateRim = if (game.toPlay == Stone.BLACK) Color(0xFF111111) else Color.White
        val rimWidth = cell * 0.07f
        candidates.forEach { cand ->
            val c = center(cand.move.x, cand.move.y)
            drawCircle(cand.color, radius = cell * 0.42f, center = c)
            drawCircle(candidateRim, radius = cell * 0.42f - rimWidth / 2, center = c, style = Stroke(rimWidth))
            drawLabel(cand.label, c, cell * 0.45f, Color(0xFF1B1B1B))
        }

        // ドラッグ中の半透明の石と、狙っている交点が分かるよう縦横の線を強調する。
        // 解析中の候補手の丸に隠れないよう、いちばん最後に描く
        val g = ghost ?: pending
        if (g != null && ghostColor != null && board[g.first, g.second] == Stone.EMPTY) {
            val c = center(g.first, g.second)
            val guide = lineColor.copy(alpha = 0.6f)
            drawLine(guide, Offset(cell * MARGIN, c.y), Offset(cell * (size - 1 + MARGIN), c.y), strokeWidth = cell * 0.08f)
            drawLine(guide, Offset(c.x, cellH * MARGIN), Offset(c.x, cellH * (size - 1 + MARGIN)), strokeWidth = cell * 0.08f)
            drawStone(c, cell, ghostColor, alpha = 0.6f)
        }
    }
}

private fun DrawScope.drawGrid(size: Int, cell: Float, cellH: Float) {
    val stroke = (cell * 0.03f).coerceAtLeast(1f)
    val last = size - 1 + MARGIN
    for (i in 0 until size) {
        val px = cell * (i + MARGIN)
        val py = cellH * (i + MARGIN)
        val w = if (i == 0 || i == size - 1) stroke * 2 else stroke
        drawLine(lineColor, Offset(cell * MARGIN, py), Offset(cell * last, py), strokeWidth = w)
        drawLine(lineColor, Offset(px, cellH * MARGIN), Offset(px, cellH * last), strokeWidth = w)
    }
    // 星 (9路盤は4隅の三々の内側と天元)
    val stars = when (size) {
        9 -> listOf(2 to 2, 6 to 2, 2 to 6, 6 to 6, 4 to 4)
        13 -> listOf(3 to 3, 9 to 3, 3 to 9, 9 to 9, 6 to 6)
        19 -> listOf(3, 9, 15).flatMap { a -> listOf(3, 9, 15).map { b -> a to b } }
        else -> emptyList()
    }
    stars.forEach { (x, y) ->
        drawCircle(lineColor, radius = cell * 0.08f, center = Offset(cell * (x + MARGIN), cellH * (y + MARGIN)))
    }
}

/** 盤の外側の座標。GTP や読み筋カードと同じ表記 (列は I を飛ばした A〜, 行は下から 1〜) */
private fun DrawScope.drawCoordinates(size: Int, cell: Float, cellH: Float) {
    val ts = cell * 0.36f
    // 座標欄 (盤の端と、端の石の間) に書く
    val far = size - 1 + 2 * MARGIN - LABEL_POS
    for (i in 0 until size) {
        val px = cell * (i + MARGIN)
        val py = cellH * (i + MARGIN)
        // 左上を 1A にする (新聞の棋譜と同じく上辺に数字、左辺に記号)。GTP の座標 (列が記号、行は下から) とは違う
        val col = (i + 1).toString()
        val row = COLUMN_LABELS[i].toString()
        drawLabel(col, Offset(px, cellH * LABEL_POS), ts, labelColor)
        drawLabel(col, Offset(px, cellH * far), ts, labelColor)
        drawLabel(row, Offset(cell * LABEL_POS, py), ts, labelColor)
        drawLabel(row, Offset(cell * far, py), ts, labelColor)
    }
}

private fun DrawScope.drawStone(c: Offset, cell: Float, stone: Stone, alpha: Float) {
    val r = cell * 0.48f
    // 影
    drawCircle(Color.Black.copy(alpha = 0.25f * alpha), radius = r, center = c + Offset(cell * 0.03f, cell * 0.04f))
    val brush = if (stone == Stone.BLACK) {
        Brush.radialGradient(
            listOf(Color(0xFF5A5A5A), Color(0xFF111111)),
            center = c + Offset(-r * 0.35f, -r * 0.35f), radius = r * 1.3f,
        )
    } else {
        Brush.radialGradient(
            listOf(Color.White, Color(0xFFD0D0D0)),
            center = c + Offset(-r * 0.35f, -r * 0.35f), radius = r * 1.4f,
        )
    }
    drawCircle(brush, radius = r, center = c, alpha = alpha)
    if (stone == Stone.WHITE) {
        drawCircle(Color(0xFF888888), radius = r, center = c, style = Stroke(width = cell * 0.015f), alpha = alpha)
    }
}

private fun DrawScope.drawLabel(text: String, c: Offset, textSize: Float, color: Color, bold: Boolean = true) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.textSize = textSize
        this.color = color.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = bold
    }
    val y = c.y - (paint.descent() + paint.ascent()) / 2
    drawContext.canvas.nativeCanvas.drawText(text, c.x, y, paint)
}
