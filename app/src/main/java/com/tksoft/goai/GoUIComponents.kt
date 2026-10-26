package com.tksoft.goai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ShogiGUI (ShogiUIComponents.kt) の読み筋カード・対局者表示・樹形図を囲碁向けにしたもの

// 読み筋のランクに応じたカラーパレット
@Composable
fun getPvColor(rank: Int): Color {
    return when (rank) {
        1 -> MaterialTheme.colorScheme.primaryContainer
        2 -> MaterialTheme.colorScheme.secondaryContainer
        3 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/**
 * 読み筋カード。座標を並べた手順は分かりにくいので、初手と、読んだ先の形勢だけを書く。
 * タップすると読み筋が盤上に番号付きで並ぶ
 */
@Composable
fun PvInfoCard(rank: Int, mover: Stone, firstMove: String, evaluation: String, selected: Boolean, onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onTap() },
        colors = CardDefaults.cardColors(
            containerColor = getPvColor(rank)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StoneIcon(mover, Modifier.size(18.dp))
            Text(
                text = firstMove,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                modifier = Modifier.widthIn(min = 56.dp)
            )
            Text(
                text = evaluation,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else null
            )
        }
    }
}

/** 対局者の表示 (ShogiGUI の PlayerInfoContent)。持ち駒の代わりにアゲハマを出す */
@Composable
fun PlayerStatusSection(
    name: String, stone: Stone, captures: Int, isActive: Boolean, gameResult: String = "",
    onNameClick: () -> Unit = {},
    alignStart: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        // 縦長の画面では上側の対局者も右寄せにする（左上にメニューボタンを置くため）。
        // 横長の画面ではメニューボタンが別の列にあるので、どちらも左寄せにする
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.End
    ) {
        // 名前をタップすると編集できる (ShogiGUI と同じ)
        Row(
            modifier = Modifier.clickable { onNameClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StoneIcon(stone, Modifier.size(18.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.Bold else null,
                maxLines = 1
            )
        }
        Text(
            text = stringResource(R.string.captures_one, captures),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (gameResult.isNotEmpty()) {
            Text(
                text = gameResult,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

// 棋譜ツリーのレイアウト結果。nodes[i] を (col[i]=手数, row[i]=行) に置き、parentIdx[i] と線で結ぶ
private class GoTreeLayout(
    val nodes: List<GoNode>,
    val col: IntArray,
    val row: IntArray,
    val parentIdx: IntArray,
    val indexOf: Map<GoNode, Int>,
    val maxCol: Int,
    val rowCount: Int
)

// 本譜（continuationChild を辿る列）を 0 行目に左から右へ一直線に並べ、
// 分岐はそれぞれ新しい行を割り当てて下へ並べる（樹形図）
private fun layoutKifuTree(root: GoNode): GoTreeLayout {
    val nodes = ArrayList<GoNode>()
    val cols = ArrayList<Int>(); val rows = ArrayList<Int>(); val parents = ArrayList<Int>()
    val indexOf = HashMap<GoNode, Int>()
    var nextRow = 0
    fun layRow(start: GoNode) {
        val row = nextRow++
        val pending = ArrayList<GoNode>()
        var n: GoNode? = start
        while (n != null) {
            indexOf[n] = nodes.size
            nodes.add(n); cols.add(n.moveCount - root.moveCount); rows.add(row)
            parents.add(n.parent?.let { indexOf[it] } ?: -1)
            // 同じ行に続ける子以外（手作業の手・別候補の読み筋）は新しい行へ
            val next = n.continuationChild()
            n.children.forEach { if (it !== next) pending.add(it) }
            n = next
        }
        // 分岐点が右（後）にある枝ほど親の行の近くに置く。枝とその子孫の行は連続させる。
        // こうすると、親から遠い行へ下りる斜め線が通る列には、間の行の線がまだ始まっていないので交差しない
        pending.asReversed().forEach { layRow(it) }
    }
    layRow(root)
    return GoTreeLayout(
        nodes, cols.toIntArray(), rows.toIntArray(), parents.toIntArray(), indexOf,
        cols.maxOrNull() ?: 0, nextRow
    )
}

// 2本指以上のピンチだけを検出する（指の並びの向きによらず、指の間隔の変化を倍率とする）。
// Initial パスで子より先に受け取り、2本指の間はイベントを消費して子のドラッグ・タップを止める。
// 1本指の操作には触れないので、子のタップやスクロールはそのまま動く
private suspend fun PointerInputScope.detectPinchGestures(onZoom: (zoom: Float) -> Unit) {
    // 指の間隔（重心からの平均距離）がこれ未満のときは倍率を変えない（誤差で暴れないように）
    val minSpan = 12.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pts = event.changes.filter { it.pressed && it.previousPressed }
            if (pts.size >= 2) {
                val n = pts.size.toFloat()
                val cur = Offset(pts.sumOf { it.position.x.toDouble() }.toFloat() / n, pts.sumOf { it.position.y.toDouble() }.toFloat() / n)
                val prev = Offset(pts.sumOf { it.previousPosition.x.toDouble() }.toFloat() / n, pts.sumOf { it.previousPosition.y.toDouble() }.toFloat() / n)
                val span = pts.map { (it.position - cur).getDistance() }.average().toFloat()
                val pSpan = pts.map { (it.previousPosition - prev).getDistance() }.average().toFloat()
                if (span > minSpan && pSpan > minSpan) onZoom(span / pSpan)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
fun KifuTreeSection(
    currentNode: GoNode,
    currentPath: List<GoNode>,
    onNodeChange: (GoNode) -> Unit
) {
    val currentIndex = currentPath.indexOf(currentNode).coerceAtLeast(0)
    val maxIndex = (currentPath.size - 1).coerceAtLeast(0)
    // 樹形図の縦方向の拡大縮小（KifuTreeView が登録する）
    val zoomHandler = remember { mutableStateOf<((Float) -> Unit)?>(null) }

    Column(
        // ピンチは樹形図だけでなくセクション全体（手数表示・矢印ボタン含む）で受ける。
        // 樹形図は高さが小さく、斜めや縦のピンチだと2本目の指が樹形図の外に出てしまうため
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .pointerInput(Unit) { detectPinchGestures { zoom -> zoomHandler.value?.invoke(zoom) } },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.move_counter, currentNode.moveCount, maxIndex),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .repeatingClickable(enabled = currentIndex > 0) { if (currentIndex > 0) onNodeChange(currentPath[currentIndex - 1]) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_previous),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)) }

            KifuTreeView(
                currentNode = currentNode,
                currentPath = currentPath,
                onNodeChange = onNodeChange,
                zoomHandler = zoomHandler,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .repeatingClickable(enabled = currentIndex < maxIndex) { if (currentIndex < maxIndex) onNodeChange(currentPath[currentIndex + 1]) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.nav_next), tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
        }
    }
}

// 手順をドットと線の樹形図で表示し、現在の手順のドットに形勢棒グラフを重ねる。
// ピンチ（zoomHandler 経由）で縦方向を縮小・ドラッグで上下スクロール・タップでその手へ移動。
@Composable
private fun KifuTreeView(
    currentNode: GoNode,
    currentPath: List<GoNode>,
    onNodeChange: (GoNode) -> Unit,
    zoomHandler: MutableState<((Float) -> Unit)?>,
    modifier: Modifier = Modifier
) {
    var root = currentNode
    while (root.parent != null) root = root.parent!!
    // children (SnapshotStateList) をコンポジション中に読むので、ツリーが変われば再レイアウトされる
    val layout = layoutKifuTree(root)
    val pathSet = remember(currentPath) { currentPath.toHashSet() }

    val density = LocalDensity.current
    // 行間は棒グラフ（上下）がちょうど収まる高さ
    val baseRowPx = with(density) { 48.dp.toPx() }
    val padX = with(density) { 12.dp.toPx() }
    val minPadYPx = with(density) { 12.dp.toPx() }
    val minScaleY = 0.25f
    val maxScaleY = 1f // 縦はデフォルトより拡大しない（縮小して全体を見る用途のみ）

    val visibleRows = layout.rowCount.coerceIn(1, 2)
    val viewHeight = 48.dp * visibleRows

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var initialized by remember(root) { mutableStateOf(false) }
    var offsetY by remember(root) { mutableFloatStateOf(0f) }
    var scaleY by remember(root) { mutableFloatStateOf(1f) } // 縦方向の倍率（行間）
    fun rowPx() = baseRowPx * scaleY
    // 棒グラフの半分の高さ (±2000 で振り切れ)。隣の行の棒と重ならないよう行間の半分弱にする
    fun barHalfPx() = rowPx() * 0.46f
    // 上下の余白は棒グラフが切れない高さ（ただし表示高さの半分まで）
    fun padY() = barHalfPx().coerceAtLeast(minPadYPx).coerceAtMost(viewSize.height / 2f)

    val currentLayout by rememberUpdatedState(layout)
    val currentOnNodeChange by rememberUpdatedState(onNodeChange)
    // 横方向は拡大縮小せず、手数に合わせて常に幅いっぱいに並べる
    val offsetX = padX
    fun colPx() = (viewSize.width - 2 * padX) / currentLayout.maxCol.coerceAtLeast(1)

    fun clampOffsets() {
        val l = currentLayout
        val h = viewSize.height.toFloat()
        val contentH = (l.rowCount - 1) * rowPx()
        val padY = padY()
        offsetY = if (contentH <= h - 2 * padY) padY else offsetY.coerceIn(h - padY - contentH, padY)
    }

    // 初期表示: 本譜の行を上端に
    LaunchedEffect(root, viewSize) {
        if (viewSize.width == 0 || initialized) return@LaunchedEffect
        offsetY = padY()
        initialized = true
        clampOffsets()
    }

    // 現在の手の行が表示範囲外に出たらそこへスクロール。高さ変化 (分岐の追加) でも再クランプする
    LaunchedEffect(currentNode, initialized, viewSize, layout.rowCount) {
        if (!initialized || viewSize.width == 0) return@LaunchedEffect
        val l = currentLayout
        val idx = l.indexOf[currentNode] ?: return@LaunchedEffect
        val h = viewSize.height.toFloat()
        val y = offsetY + l.row[idx] * rowPx()
        if (y < padY() || y > h - padY()) offsetY = h / 2f - l.row[idx] * rowPx()
        clampOffsets()
    }

    // ピンチの向きによらず縦方向（行間）だけ拡大縮小。現在の手の行が動かないようにオフセットを補正
    val currentNodeState by rememberUpdatedState(currentNode)
    SideEffect {
        zoomHandler.value = handler@{ zoom ->
            if (!initialized) return@handler
            val oldY = scaleY
            val newY = (oldY * zoom).coerceIn(minScaleY, maxScaleY)
            val row = currentLayout.indexOf[currentNodeState]?.let { currentLayout.row[it] } ?: 0
            val anchorY = offsetY + row * rowPx()
            offsetY = anchorY - (anchorY - offsetY) * (newY / oldY)
            scaleY = newY
            clampOffsets()
        }
    }

    // M3 Slider のデフォルト配色 (SliderDefaults.colors)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer
    val activeTickColor = MaterialTheme.colorScheme.onPrimary
    val inactiveTickColor = MaterialTheme.colorScheme.onSecondaryContainer
    val thumbColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    // 現在の手順上で、現在の手までの手 (= Slider のアクティブ側)
    val pathIndex = remember(currentPath) { HashMap<GoNode, Int>().also { m -> currentPath.forEachIndexed { i, n -> m[n] = i } } }
    val currentPathIdx = pathIndex[currentNode] ?: -1

    // M3 Expressive: トーナルなコンテナ + やや控えめな角丸
    Canvas(
        modifier = modifier
            .height(viewHeight)
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .onSizeChanged { viewSize = it }
            .pointerInput(root) {
                // 1本指のドラッグで上下スクロール（2本指はセクション側のピンチが消費するので来ない）
                detectDragGestures { change, drag ->
                    if (!initialized) return@detectDragGestures
                    offsetY += drag.y
                    clampOffsets()
                    change.consume()
                }
            }
            .pointerInput(root) {
                detectTapGestures { pos ->
                    if (!initialized) return@detectTapGestures
                    val l = currentLayout
                    val colPx = colPx()
                    val rowPx = rowPx()
                    val threshold = 28.dp.toPx()
                    var best = -1; var bestD = threshold * threshold
                    for (i in l.nodes.indices) {
                        val dx = offsetX + l.col[i] * colPx - pos.x
                        val dy = offsetY + l.row[i] * rowPx - pos.y
                        val d = dx * dx + dy * dy
                        if (d < bestD) { bestD = d; best = i }
                    }
                    if (best >= 0) currentOnNodeChange(l.nodes[best])
                }
            }
    ) {
        if (!initialized) return@Canvas
        val colPx = colPx()
        val rowPx = rowPx()
        // Slider の寸法 (SliderTokens) を基に、トラックは細め (6dp)。つまみ 4x24dp・つまみとトラックの隙間 6dp・目盛り径 3dp。
        // 縦に縮小したときは行間に収まるよう縮める
        val trackH = minOf(6.dp.toPx(), rowPx * 0.25f)
        val thumbW = 4.dp.toPx()
        val thumbH = minOf(20.dp.toPx(), rowPx * 0.5f)
        val thumbGap = 6.dp.toPx()
        val tickR = minOf(1.5f.dp.toPx(), trackH / 4f)
        val left = -trackH * 2; val right = size.width + trackH * 2
        fun pos(i: Int) = Offset(offsetX + layout.col[i] * colPx, offsetY + layout.row[i] * rowPx)
        fun isActive(node: GoNode) = (pathIndex[node] ?: Int.MAX_VALUE) <= currentPathIdx
        fun trackColor(node: GoNode) = if (isActive(node)) activeTrackColor else inactiveTrackColor
        val cursorIdx = layout.indexOf[currentNode]
        val cut = thumbW / 2 + thumbGap // つまみの左右はトラックを切り欠く

        // トラック: 未到達側を先に、到達側を上に描く。
        // 線は端を切りっぱなしにし、各ドットの位置に円を置いて角丸・継ぎ目にする（つまみの位置は除く）
        for (pass in 0..1) {
            val wantActive = pass == 1
            for (i in layout.nodes.indices) {
                val p = layout.parentIdx[i]
                if (p < 0) continue
                val node = layout.nodes[i]
                if (isActive(node) != wantActive) continue
                var a = pos(p); var b = pos(i)
                if (b.x < left || a.x > right) continue
                val len = (b - a).getDistance()
                if (len <= 0f) continue
                val dir = (b - a) / len
                var l = len
                if (p == cursorIdx) { a += dir * cut; l -= cut }
                if (i == cursorIdx) { b -= dir * cut; l -= cut }
                if (l <= 0f) continue
                // 分岐して別の行へ下りる斜めの線はごく細く、現在の手の左右によらず常にアクティブ側の濃い色
                val diagonal = layout.row[p] != layout.row[i]
                if (diagonal) drawLine(activeTrackColor, a, b, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                else drawLine(trackColor(node), a, b, strokeWidth = trackH, cap = StrokeCap.Butt)
            }
            for (i in layout.nodes.indices) {
                if (i == cursorIdx) continue
                val node = layout.nodes[i]
                // 根は子（最初の手）の色に合わせる
                val colorNode = if (layout.parentIdx[i] < 0) node.continuationChild() ?: node.children.firstOrNull() ?: node else node
                if (isActive(colorNode) != wantActive) continue
                val c = pos(i)
                if (c.x < left || c.x > right) continue
                // トラック端の丸みは、つまみに接していない側だけ
                val nearThumb = cursorIdx?.let { (pos(it) - c).getDistance() < cut + trackH / 2 } ?: false
                if (!nearThumb) drawCircle(trackColor(colorNode), radius = trackH / 2, center = c)
            }
        }

        // 目盛り（各手）。密集して見分けられないときは省く
        if (colPx >= tickR * 4) {
            for (i in layout.nodes.indices) {
                if (i == cursorIdx) continue
                val c = pos(i)
                if (c.x < left || c.x > right) continue
                val colorNode = if (layout.parentIdx[i] < 0) layout.nodes[i].continuationChild() ?: layout.nodes[i] else layout.nodes[i]
                if (cursorIdx != null && (pos(cursorIdx) - c).getDistance() < cut) continue
                drawCircle(if (isActive(colorNode)) activeTickColor else inactiveTickColor, radius = tickR, center = c)
            }
        }

        // 形勢棒グラフ（解析済みの全ての手）: 各手の位置を中心に上下へ伸ばす。
        // 囲碁は黒の勝率で描く (上が黒有利・下が白有利、50% が中心、0% / 100% で振り切れ)。
        // 囲碁の勝率は将棋の評価値ほど振れず 50% 近くにとどまることが多いので、50% からの差の平方根を棒の長さにして、
        // 小さな差も見えるようにする (60% で半分弱、75% で 7 割)
        val barWidth = colPx.coerceAtLeast(2f)
        for (i in layout.nodes.indices) {
            val winrate = layout.nodes[i].winrateBlack ?: continue
            val c = pos(i)
            if (c.x < left || c.x > right) continue
            val diff = ((winrate - 0.5) * 2).toFloat().coerceIn(-1f, 1f)
            val normalized = kotlin.math.sign(diff) * kotlin.math.sqrt(kotlin.math.abs(diff))
            val barColor = if (normalized >= 0) blackBarColor else whiteBarColor
            // 中央 (50%) はグレーで、上 (黒有利) へ行くほど黒、下 (白有利) へ行くほど白になるグラデーションにする。
            // 棒の長さによらず同じ位置は同じ色になるよう、グラデーションは中央から端までの全体にとる
            val edgeY = if (normalized >= 0) c.y - barHalfPx() else c.y + barHalfPx()
            val brush = Brush.verticalGradient(listOf(barMidColor, barColor), startY = c.y, endY = edgeY)
            drawLine(brush, c, Offset(c.x, c.y - normalized * barHalfPx()), strokeWidth = barWidth)
        }

        // 目数の折れ線グラフ (棒グラフに重ねる): 黒のリードを上、白のリードを下にとり、20 目で振り切れ。
        // 分岐も含め、親の手と線で結ぶ
        fun leadY(i: Int): Float? = layout.nodes[i].scoreLeadBlack?.let {
            pos(i).y - (it / MAX_SCORE_LEAD_ON_GRAPH).toFloat().coerceIn(-1f, 1f) * barHalfPx()
        }
        val leadStroke = 1.5.dp.toPx()
        for (i in layout.nodes.indices) {
            val y = leadY(i) ?: continue
            val c = pos(i)
            val p = layout.parentIdx[i]
            val py = if (p >= 0) leadY(p) else null
            if (p >= 0 && py != null) {
                val pc = pos(p)
                if (maxOf(c.x, pc.x) < left || minOf(c.x, pc.x) > right) continue
                drawLine(scoreLineColor, Offset(pc.x, py), Offset(c.x, y), strokeWidth = leadStroke)
            } else if (c.x in left..right) {
                // 前後の手がまだ解析されていないときも見えるよう点を打つ
                drawCircle(scoreLineColor, radius = leadStroke, center = Offset(c.x, y))
            }
        }

        // 現在の手: Slider のつまみ（縦長の角丸バー）
        cursorIdx?.let { i ->
            val c = pos(i)
            drawRoundRect(
                thumbColor,
                topLeft = Offset(c.x - thumbW / 2, c.y - thumbH / 2),
                size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2)
            )
        }
    }
}

@Composable
fun Modifier.repeatingClickable(enabled: Boolean = true, initialDelay: Long = 500L, delay: Long = 100L, onClick: () -> Unit): Modifier {
    val currentOnClick by rememberUpdatedState(onClick); val scope = rememberCoroutineScope()
    return if (!enabled) this else this.pointerInput(enabled) {
        // 押している間に端へ着くと enabled=false でこの pointerInput が外れ、tryAwaitRelease() がキャンセル例外で抜ける。
        // そのときも連打を止めるよう finally で止める（止めないと押し終えた後も連打が続き、逆方向へ戻れなくなる）
        // 1回の押下は、連打が始まる前に離したときだけ1回とする。onTap を使うと長押しで連打した後の
        // 指離しでも呼ばれて1回余分に進む（onTap は onPress の後始末より先に動くので、連打も1回多く出る）
        detectTapGestures(onPress = {
            var repeated = false
            val job = scope.launch { delay(initialDelay); while (true) { repeated = true; currentOnClick(); delay(delay) } }
            val released = try { tryAwaitRelease() } finally { job.cancel() }
            if (released && !repeated) currentOnClick()
        })
    }
}
