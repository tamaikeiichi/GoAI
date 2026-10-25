package com.tksoft.goai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
private const val COLUMN_LABELS = "ABCDEFGHJKLMNOPQRST"

/** 盤上に示す候補手 (解析中) */
data class BoardCandidate(val move: Move.Play, val color: Color, val label: String)

/**
 * 碁盤。将棋盤と違い、石はマスの中ではなく線の交点に置く。
 * 盤の外側に1マス分の余白を取り、そこに座標 (A〜J, 9〜1) を書く
 *
 * @param pvStartMove 読み筋の分岐を表示中なら、番号を付け始める手数 (その手から 1, 2, 3...)
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
) {
    val size = game.size
    // 交点の数 + 両側の余白 (座標用に 0.5 マスずつ広げる)
    val cells = size + 1f
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(size) {
                detectTapGestures { pos ->
                    val cell = this.size.width / cells
                    val x = (pos.x / cell - 1f).roundToInt()
                    val y = (pos.y / cell - 1f).roundToInt()
                    if (x in 0 until size && y in 0 until size) onTap(x, y)
                }
            }
    ) {
        val cell = this.size.width / cells
        fun center(x: Int, y: Int) = Offset(cell * (x + 1f), cell * (y + 1f))

        drawRect(boardColor)
        drawGrid(size, cell)
        drawCoordinates(size, cell)

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
            // 読み筋の手順: 分岐の起点からの手に番号を付ける (同じ点に何度も打たれたら最後の手)
            val numbers = HashMap<Pair<Int, Int>, Int>()
            game.moves.forEachIndexed { i, m ->
                val mv = m.move
                if (i >= pvStartMove && mv is Move.Play) numbers[mv.x to mv.y] = i - pvStartMove + 1
            }
            numbers.forEach { (p, num) ->
                val stone = board[p.first, p.second]
                if (stone == Stone.EMPTY) return@forEach
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

        // 解析中の候補手: 読み筋カードと同じ色の丸と順位
        candidates.forEach { cand ->
            val c = center(cand.move.x, cand.move.y)
            drawCircle(cand.color, radius = cell * 0.42f, center = c)
            drawCircle(lineColor.copy(alpha = 0.5f), radius = cell * 0.42f, center = c, style = Stroke(cell * 0.02f))
            drawLabel(cand.label, c, cell * 0.45f, Color(0xFF1B1B1B))
        }
    }
}

private fun DrawScope.drawGrid(size: Int, cell: Float) {
    val start = cell
    val end = cell * size
    val stroke = (cell * 0.03f).coerceAtLeast(1f)
    for (i in 0 until size) {
        val p = cell * (i + 1f)
        val w = if (i == 0 || i == size - 1) stroke * 2 else stroke
        drawLine(lineColor, Offset(start, p), Offset(end, p), strokeWidth = w)
        drawLine(lineColor, Offset(p, start), Offset(p, end), strokeWidth = w)
    }
    // 星 (9路盤は4隅の三々の内側と天元)
    val stars = when (size) {
        9 -> listOf(2 to 2, 6 to 2, 2 to 6, 6 to 6, 4 to 4)
        13 -> listOf(3 to 3, 9 to 3, 3 to 9, 9 to 9, 6 to 6)
        19 -> listOf(3, 9, 15).flatMap { a -> listOf(3, 9, 15).map { b -> a to b } }
        else -> emptyList()
    }
    stars.forEach { (x, y) ->
        drawCircle(lineColor, radius = cell * 0.08f, center = Offset(cell * (x + 1f), cell * (y + 1f)))
    }
}

/** 盤の外側の座標。GTP や読み筋カードと同じ表記 (列は I を飛ばした A〜, 行は下から 1〜) */
private fun DrawScope.drawCoordinates(size: Int, cell: Float) {
    val color = lineColor.copy(alpha = 0.7f)
    val ts = cell * 0.3f
    for (i in 0 until size) {
        val p = cell * (i + 1f)
        val col = COLUMN_LABELS[i].toString()
        val row = (size - i).toString()
        drawLabel(col, Offset(p, cell * 0.4f), ts, color, bold = false)
        drawLabel(col, Offset(p, cell * (size + 0.6f)), ts, color, bold = false)
        drawLabel(row, Offset(cell * 0.4f, p), ts, color, bold = false)
        drawLabel(row, Offset(cell * (size + 0.6f), p), ts, color, bold = false)
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
