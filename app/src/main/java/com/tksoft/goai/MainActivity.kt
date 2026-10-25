package com.tksoft.goai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tksoft.goai.ui.theme.GoAITheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // エッジ ツー エッジ表示。androidx の enableEdgeToEdge() は旧バージョン向けに非推奨 API
        // (Window.setStatusBarColor 等) を呼び Play Console で警告されるため、バーの色・カットアウトは
        // テーマ (res/values*/themes.xml) で指定し、ここではレイアウトとアイコンの明暗だけを設定する
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
        setContent {
            GoAITheme {
                var showAbout by rememberSaveable { mutableStateOf(false) }
                if (showAbout) {
                    BackHandler { showAbout = false }
                    AboutScreen(onBack = { showAbout = false })
                } else {
                    GameScreen(onAbout = { showAbout = true })
                }
            }
        }
    }
}

@Composable
fun GameScreen(onAbout: () -> Unit, vm: GameViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showNewGame by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    // 準備完了ポップアップを bottomBar のすぐ上に出すため、bottomBar の高さを測る
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { bottomBarHeightPx = it.height }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        KifuTreeSection(vm.currentNode, vm.currentPath) { vm.goTo(it) }
                        Spacer(modifier = Modifier.height(8.dp))
                        BottomButtons(vm)
                    }
                }
            }
        ) { innerPadding ->
            val isWide = LocalConfiguration.current.screenWidthDp >= 840
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding() + 16.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(0.5f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BoardArea(vm, Modifier.sizeIn(maxWidth = 500.dp, maxHeight = 500.dp)) { showMenu = true }
                        Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
                    }
                    Column(modifier = Modifier.weight(0.5f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        PvCards(vm)
                        Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BoardArea(vm, Modifier.padding(16.dp)) { showMenu = true }
                    Column(modifier = Modifier.padding(8.dp)) { PvCards(vm) }
                    // bottomBar に隠れる分をスクロールで見られるようにする
                    Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
                }
            }
        }

        // エンジン準備完了の通知。クリック系の Modifier を付けないのでタッチは下の画面にそのまま届く
        LaunchedEffect(vm.engineReadyToast) {
            if (vm.engineReadyToast != null) {
                delay(2500)
                vm.engineReadyToast = null
            }
        }
        AnimatedVisibility(
            visible = vm.engineReadyToast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { bottomBarHeightPx.toDp() } + 8.dp)
        ) {
            var shownName by remember { mutableStateOf("") }
            vm.engineReadyToast?.let { shownName = it }
            EngineReadyToast(shownName)
        }

        // Gemini アプリのように、左から画面全体にせり出すメニュー。右上の × か左へのスワイプで閉じる
        AnimatedVisibility(
            visible = showMenu,
            enter = slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { -it },
            exit = slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { -it },
            modifier = Modifier.fillMaxSize()
        ) {
            BackHandler { showMenu = false }
            FullScreenMenu(onClose = { showMenu = false }) {
                val ready = vm.engineStatus == EngineStatus.Ready && !vm.thinking
                MenuItem(Icons.Outlined.RestartAlt, text = { Text(stringResource(R.string.menu_new_game)) }) {
                    showMenu = false
                    if (ready) showNewGame = true
                }
                MenuItem({ MarkIcon("●") }, text = { Text(stringResource(R.string.menu_play_black)) }) {
                    showMenu = false
                    if (ready) vm.startNewGame(vm.settings.copy(humanColor = Stone.BLACK))
                }
                MenuItem({ MarkIcon("○") }, text = { Text(stringResource(R.string.menu_play_white)) }) {
                    showMenu = false
                    if (ready) vm.startNewGame(vm.settings.copy(humanColor = Stone.WHITE))
                }
                MenuItem(Icons.Outlined.Share, text = { Text(stringResource(R.string.menu_share_sgf)) }) {
                    showMenu = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-go-sgf"
                        putExtra(Intent.EXTRA_TEXT, vm.toSgf())
                        putExtra(Intent.EXTRA_SUBJECT, "GoAI.sgf")
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
                MenuItem(Icons.Outlined.ContentCopy, text = { Text(stringResource(R.string.menu_copy_sgf)) }) {
                    showMenu = false
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SGF", vm.toSgf()))
                    vm.message = context.getString(R.string.copied)
                }
                MenuItem(Icons.Outlined.Info, text = { Text(stringResource(R.string.about)) }) {
                    showMenu = false
                    onAbout()
                }
            }
        }
    }

    if (showNewGame) {
        NewGameDialog(
            initial = vm.settings,
            onDismiss = { showNewGame = false },
            onStart = {
                showNewGame = false
                vm.startNewGame(it)
            },
        )
    }
}

/** 上の対局者 (AI)・盤・下の対局者 (あなた) */
@Composable
private fun BoardArea(vm: GameViewModel, boardModifier: Modifier, onMenu: () -> Unit) {
    val game = vm.game
    val human = vm.settings.humanColor
    val ai = human.opponent
    val result = vm.result
    val resultText = result?.let { resultText(it.code) } ?: ""
    fun captures(s: Stone) = if (s == Stone.BLACK) game.capturedByBlack else game.capturedByWhite
    val turn = if (result == null) game.toPlay else null

    Box(modifier = Modifier.fillMaxWidth()) {
        PlayerStatusSection(
            name = stringResource(R.string.ai_name, vm.settings.level.ordinal + 1),
            stone = ai,
            captures = captures(ai),
            isActive = turn == ai,
            gameResult = when {
                vm.thinking && game.isEndedByPasses -> stringResource(R.string.counting)
                vm.thinking -> stringResource(R.string.ai_thinking)
                else -> ""
            },
        )
        MenuButton(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp), onClick = onMenu)
    }

    val shown = vm.shownAnalysis
    // 解析中の局面では、読み筋カードの候補手を盤上にも示す
    val candidates = if (shown != null && shown.first === vm.currentNode && vm.selectedPvRank == null) {
        shown.second.candidates.sortedBy { it.order }.take(3).mapIndexedNotNull { i, c ->
            (c.move as? Move.Play)?.let { BoardCandidate(it, getPvColor(i + 1), "${i + 1}") }
        }
    } else emptyList()
    val node = vm.currentNode
    Box(contentAlignment = Alignment.Center) {
        GoBoardView(
            game = game,
            onTap = vm::onBoardTap,
            modifier = boardModifier.fillMaxWidth(),
            boardColor = if (node.isPvBranch) getPvColor(node.pvColorIndex) else woodBoardColor,
            candidates = candidates,
            result = result,
            pvStartMove = vm.pvStartMove,
        )
        when (val s = vm.engineStatus) {
            is EngineStatus.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.engine_loading), style = MaterialTheme.typography.bodyLarge)
            }
            is EngineStatus.Error -> Text(
                stringResource(R.string.engine_error, s.message),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            else -> {}
        }
    }

    PlayerStatusSection(
        name = stringResource(R.string.you),
        stone = human,
        captures = captures(human),
        isActive = turn == human,
        gameResult = resultText,
    )
}

/** 読み筋カード: 初手と、読んだ先の形勢 (あなたから見た勝率・目数) */
@Composable
private fun PvCards(vm: GameViewModel) {
    val (_, analysis) = vm.shownAnalysis ?: return
    val human = vm.settings.humanColor
    analysis.candidates.sortedBy { it.order }.take(3).forEachIndexed { i, c ->
        val rank = i + 1
        val first = c.pv.firstOrNull() ?: c.move
        val mover = if (vm.shownAnalysis?.first?.game?.toPlay == Stone.WHITE) "○" else "●"
        val winrate = if (human == Stone.BLACK) c.winrateBlack else 1 - c.winrateBlack
        val lead = if (human == Stone.BLACK) c.scoreLeadBlack else -c.scoreLeadBlack
        PvInfoCard(
            rank = rank,
            firstMove = "$mover ${GtpCoord.toGtp(first, BOARD_SIZE).replace("pass", stringResource(R.string.pass))}",
            evaluation = stringResource(
                R.string.evaluation_format,
                String.format(Locale.US, "%.0f", winrate * 100),
                String.format(Locale.US, "%+.1f", lead),
            ),
            selected = vm.selectedPvRank == rank,
        ) { vm.playPv(rank) }
    }
}

@Composable
private fun BottomButtons(vm: GameViewModel) {
    val ready = vm.engineStatus == EngineStatus.Ready
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarButton(
            icon = { Icon(painterResource(R.drawable.undo_24px), null) },
            label = stringResource(R.string.undo),
            enabled = !vm.thinking && vm.currentNode.parent != null,
            onClick = vm::undo,
        )
        BarButton(
            icon = { Icon(Icons.Outlined.SkipNext, null) },
            label = stringResource(R.string.pass),
            enabled = vm.canPlay,
            onClick = vm::pass,
        )
        BarButton(
            icon = {
                Icon(
                    painterResource(if (vm.analyzing) R.drawable.stop_circle_24px else R.drawable.network_intelligence_24px),
                    null,
                )
            },
            label = when {
                vm.analyzing -> stringResource(R.string.action_stop)
                !ready -> stringResource(R.string.action_preparing)
                else -> stringResource(R.string.action_analyze)
            },
            enabled = vm.analyzing || (ready && !vm.thinking),
            highlighted = vm.analyzing,
            onClick = vm::toggleAnalysis,
        )
        BarButton(
            icon = { Icon(Icons.Outlined.Flag, null) },
            label = stringResource(R.string.resign),
            enabled = vm.canPlay && !vm.isReviewMode,
            onClick = vm::resign,
        )
    }
}

@Composable
private fun RowScope.BarButton(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(0.3f).height(72.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.basicMarquee())
        }
    }
}

@Composable
private fun colorName(s: Stone) = stringResource(if (s == Stone.BLACK) R.string.black else R.string.white)

@Composable
private fun resultText(code: String): String {
    // "B+3.5", "W+R", "0" (持碁)
    if (code == "0") return stringResource(R.string.result_draw)
    val winner = if (code.startsWith("B")) colorName(Stone.BLACK) else colorName(Stone.WHITE)
    val detail = code.substringAfter("+", "")
    return when {
        detail.equals("R", ignoreCase = true) -> stringResource(R.string.result_resign, winner)
        detail.isNotEmpty() -> stringResource(R.string.result_points, winner, detail)
        else -> code
    }
}

@Composable
private fun NewGameDialog(initial: GameSettings, onDismiss: () -> Unit, onStart: (GameSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_game)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.your_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Stone.BLACK, Stone.WHITE).forEach { c ->
                        FilterChip(s.humanColor == c, { s = s.copy(humanColor = c) }, { Text(colorName(c)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.level), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Level.entries.forEachIndexed { i, lv ->
                        FilterChip(s.level == lv, { s = s.copy(level = lv) }, { Text("${i + 1}") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.rules), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rules.entries.forEach { r ->
                        val label = if (r == Rules.JAPANESE) R.string.rules_japanese else R.string.rules_chinese
                        FilterChip(s.rules == r, {
                            s = s.copy(rules = r, komi = if (s.handicap > 0) 0.5 else r.defaultKomi)
                        }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.handicap), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 3, 4, 5).forEach { h ->
                        FilterChip(s.handicap == h, {
                            s = s.copy(handicap = h, komi = if (h > 0) 0.5 else s.rules.defaultKomi)
                        }, { Text(if (h == 0) stringResource(R.string.handicap_none) else "$h") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.komi), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5, 5.5, 6.5, 7.0, 7.5).forEach { k ->
                        FilterChip(s.komi == k, { s = s.copy(komi = k) }, { Text("$k") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(s) }) { Text(stringResource(R.string.start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** エンジン準備完了のポップアップ本体 (ShogiGUI の EngineReadyToast)。Surface は背後へのタッチを遮るため、Box + background で描画する */
@Composable
fun EngineReadyToast(engineName: String) {
    Box(
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.4f)),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(R.string.engine_ready_toast, engineName),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

// Gemini アプリ風のメニューアイコン（短い2本線）
private val TwoLineMenuIcon: ImageVector by lazy {
    ImageVector.Builder("TwoLineMenu", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString("M4,8.8 L20,8.8 M4,15.2 L20,15.2").toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}

// 左上のハンバーガーメニューボタン (Gemini アプリ風: 白い丸に細い縁取りと淡い影)
@Composable
private fun MenuButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(TwoLineMenuIcon, stringResource(R.string.menu_content_description))
        }
    }
}

// 画面全体を覆うメニュー (Gemini アプリ風)。左上にアプリ名、右上に ×。
// 左へスワイプすると指に付いて動き、十分に動かすか素早く払うと閉じる
@Composable
private fun FullScreenMenu(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    Surface(
        // うっすら半透明にして、後ろの盤面が透けて見えるようにする
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> dragX = (dragX + d).coerceAtMost(0f) },
                onDragStopped = { velocity ->
                    if (dragX < -widthPx * 0.25f || velocity < -1500f) onClose()
                    else androidx.compose.animation.core.animate(dragX, 0f) { v, _ -> dragX = v }
                }
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), content = content)
        }
    }
}

// メニューの1項目
@Composable
private fun MenuItem(icon: ImageVector, text: @Composable () -> Unit, onClick: () -> Unit) =
    MenuItem({ Icon(icon, contentDescription = null) }, text, onClick)

@Composable
private fun MenuItem(icon: @Composable () -> Unit, text: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            icon()
            ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp)) { text() }
        }
    }
}

// 黒・白の印 (● ○) をアイコンの代わりに表示する
@Composable
private fun MarkIcon(mark: String) {
    // ほかの項目のアイコンと同じ 24dp の枠に収めて、項目名の位置をそろえる
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Text(mark, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        runCatching { context.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(notices, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.size(24.dp))
        }
    }
}
