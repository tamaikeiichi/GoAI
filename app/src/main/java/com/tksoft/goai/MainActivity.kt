package com.tksoft.goai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
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
    private val vm: GameViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onStop() {
        // ホームに戻ったときなどは解析を止める (画面に戻ったら onStart で再開する)
        vm.onAppBackground()
        vm.saveKifu()
        super.onStop()
    }

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
        // 「共有」や SGF ファイルを開く操作で起動されたとき (画面の回転などで作り直したときは読み直さない)
        if (savedInstanceState == null) receiveKifu(intent)
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

    // launchMode が singleTask なので、起動中に「共有」されたときはここに来る
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveKifu(intent)
    }

    /** 他のアプリの「共有」(SGF のテキストかファイル) や、SGF ファイルを開く操作で受け取った棋譜を読み込む */
    private fun receiveKifu(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> return
        }
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        lifecycleScope.launch {
            val text = uri?.let { u ->
                withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(u)?.use { SgfParser.decode(it.readBytes()) } }.getOrNull()
                }
            } ?: sharedText
            // ブラウザでページを「共有」すると URL だけが来る (ダウンロードはしない)
            if (text == null || !text.contains("(;")) {
                vm.message = getString(R.string.shared_not_sgf)
                return@launch
            }
            vm.loadSgf(text)
        }
    }
}

@Composable
fun GameScreen(onAbout: () -> Unit, vm: GameViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf<Stone?>(null) }
    // 横長レイアウトで盤を最大化しているか (bottomBar と読み筋カードを隠し、盤の上に1行だけ置く)
    var maximized by rememberSaveable { mutableStateOf(false) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 840
    val showMaximized = maximized && isWide
    BackHandler(enabled = showMaximized) { maximized = false }
    val coroutineScope = rememberCoroutineScope()
    var kifuIoMessage by remember { mutableStateOf<String?>(null) }
    val exportKifuLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val count = withContext(Dispatchers.IO) { KifuHistoryManager.exportAllToUri(context, uri) }
                kifuIoMessage = context.getString(R.string.kifu_export_success, count)
            }
        }
    }
    val importKifuLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) { KifuHistoryManager.importFromUri(context, uri) }
                kifuIoMessage = buildString {
                    append(context.getString(R.string.kifu_import_success, result.imported))
                    if (result.skipped > 0) append(context.getString(R.string.kifu_import_skipped_suffix, result.skipped))
                }
            }
        }
    }

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
                if (!showMaximized) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        KifuTreeSection(
                            vm.currentNode, vm.currentPath,
                            moveTime = KifuTime.consumed(vm.currentNode, vm.timeSettings)?.let { KifuTime.format(it) },
                        ) { vm.goTo(it) }
                        Spacer(modifier = Modifier.height(8.dp))
                        BottomButtons(vm)
                    }
                }
            }
        ) { innerPadding ->
            if (showMaximized) {
                // 最大化: 1行目にメニュー・両対局者 (名前とアゲハマ)・元に戻すボタン、その下に盤を残りの高さいっぱいに置く
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(8.dp)) {
                    val headerHeight = 48.dp
                    val gap = 8.dp
                    val boardWidth = minOf(maxWidth, (maxHeight - headerHeight - gap) / BOARD_CELL_ASPECT).coerceAtLeast(0.dp)
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(headerHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MenuButton { showMenu = true }
                            PlayerChip(vm, Stone.BLACK) { editingName = it }
                            PlayerChip(vm, Stone.WHITE) { editingName = it }
                            Spacer(modifier = Modifier.weight(1f))
                            // bottomBar の形勢グラフの左右にある矢印と同じ、前の手・次の手 (長押しで連続)
                            val path = vm.currentPath
                            val index = path.indexOf(vm.currentNode).coerceAtLeast(0)
                            NavButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_previous), enabled = index > 0) {
                                vm.currentPath.let { p -> p.indexOf(vm.currentNode).takeIf { it > 0 }?.let { vm.goTo(p[it - 1]) } }
                            }
                            NavButton(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.nav_next), enabled = index < path.size - 1) {
                                vm.currentPath.let { p -> p.indexOf(vm.currentNode).takeIf { it in 0 until p.size - 1 }?.let { vm.goTo(p[it + 1]) } }
                            }
                            IconButton(onClick = { maximized = false }) {
                                Icon(Icons.Filled.FullscreenExit, stringResource(R.string.restore_board))
                            }
                        }
                        Board(vm, Modifier.width(boardWidth))
                    }
                }
            } else if (isWide) {
                // 盤をなるべく大きくするため、左からメニューボタン・盤・(対局者名・読み筋カード・対局者名) の3列にする
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                        .padding(innerPadding)
                        // 盤をなるべく大きくするが、bottomBar とは少し離す
                        .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    val gap = 8.dp
                    val menuWidth = 48.dp
                    // 右の列の幅は最低これだけ取り、残りを盤にする。盤は縦長なので高さからも幅を決める
                    val minSideWidth = 180.dp
                    val boardWidth = minOf(maxWidth - menuWidth - minSideWidth - gap * 2, maxHeight / BOARD_CELL_ASPECT)
                        .coerceAtLeast(0.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        MenuButton(Modifier.padding(top = 4.dp)) { showMenu = true }
                        Board(vm, Modifier.width(boardWidth))
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(modifier = Modifier.weight(1f)) {
                                    PlayerSection(vm, top = true, alignStart = true) { editingName = it }
                                }
                                // 右上の最大化ボタン
                                IconButton(onClick = { maximized = true }) {
                                    Icon(Icons.Filled.Fullscreen, stringResource(R.string.maximize_board))
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            ) {
                                PvCards(vm, compact = true)
                                PvReplayNote(vm)
                            }
                            PlayerSection(vm, top = false, alignStart = true) { editingName = it }
                        }
                    }
                }
            } else BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                // 盤は幅いっぱいにするが、背の低い画面 (横にしたスマホ・分割画面など) では盤が見切れないよう、
                // 上下の対局者表示と bottomBar を除いた高さに収まる大きさまでにする
                val playersHeight = 150.dp
                val boardMaxWidth = ((maxHeight - innerPadding.calculateBottomPadding() - playersHeight) / BOARD_CELL_ASPECT)
                    .coerceAtLeast(200.dp)
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 盤を少しでも大きくするため、画面の端との余白は狭くする
                    BoardArea(
                        vm, Modifier.widthIn(max = boardMaxWidth).padding(horizontal = 6.dp, vertical = 8.dp),
                        onMenu = { showMenu = true }, onEditName = { editingName = it },
                    )
                    Column(modifier = Modifier.padding(8.dp)) { PvCards(vm) }
                    // bottomBar に隠れる分をスクロールで見られるようにする
                    Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding()))
                }
            }
        }

        editingName?.let { PlayerNameDialog(vm, it) { editingName = null } }

        ThinkingOverlay(vm)


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
                MenuItem(Icons.Outlined.Settings, text = { Text(stringResource(R.string.menu_settings)) }) {
                    showMenu = false
                    showSettings = true
                }
                // 解析・全て解析・本譜は bottomBar にもあるが、対局中は bottomBar から消えるので、メニューからいつでも使えるようにする
                MenuItem(
                    { Icon(painterResource(if (vm.analyzing && !vm.autoAnalyzing) R.drawable.stop_circle_24px else R.drawable.network_intelligence_24px), null) },
                    text = { Text(stringResource(if (vm.analyzing && !vm.autoAnalyzing) R.string.menu_stop_analysis else R.string.action_analyze)) },
                ) {
                    showMenu = false
                    vm.onAnalyzeButton()
                }
                MenuItem(
                    { Icon(if (vm.autoAnalyzing) painterResource(R.drawable.stop_circle_24px) else rememberVectorPainter(Icons.Outlined.FastForward), null) },
                    text = { Text(stringResource(if (vm.autoAnalyzing) R.string.menu_stop_analyze_all else R.string.action_analyze_all)) },
                ) {
                    showMenu = false
                    vm.onAutoAnalyzeButton()
                }
                MenuItem({ Icon(painterResource(R.drawable.undo_24px), null) }, text = { Text(stringResource(R.string.menu_main_line)) }) {
                    showMenu = false
                    vm.goToMainLine()
                }
                MenuItem(Icons.Outlined.RestartAlt, text = { Text(stringResource(R.string.menu_reset)) }) {
                    showMenu = false
                    vm.reset()
                }
                MenuItem(Icons.Outlined.ContentCut, text = { Text(stringResource(R.string.menu_delete_branches)) }) {
                    showMenu = false
                    vm.deleteBranches()
                }
                MenuItem(Icons.Outlined.ContentCopy, text = { Text(stringResource(R.string.menu_copy_sgf)) }) {
                    showMenu = false
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SGF", vm.toSgf()))
                    vm.message = context.getString(R.string.copied)
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
                MenuItem({ MarkIcon(Stone.BLACK) }, text = { Text(stringResource(R.string.menu_play_black)) }) {
                    showMenu = false
                    if (ready) vm.playAs(Stone.BLACK)
                }
                MenuItem({ MarkIcon(Stone.WHITE) }, text = { Text(stringResource(R.string.menu_play_white)) }) {
                    showMenu = false
                    if (ready) vm.playAs(Stone.WHITE)
                }
                // 囲碁ではパスが着手の1つなので、盤上に置く手と同じく打てるときだけ
                MenuItem(Icons.Outlined.SkipNext, text = { Text(stringResource(R.string.pass)) }) {
                    showMenu = false
                    vm.pass()
                }
                MenuItem(Icons.Outlined.Calculate, text = { Text(stringResource(R.string.menu_judge)) }) {
                    showMenu = false
                    vm.judge()
                }
                if (vm.isPlayingGame) {
                    MenuItem(Icons.Outlined.Flag, text = { Text(stringResource(R.string.resign)) }) {
                        showMenu = false
                        vm.resign()
                    }
                }
                MenuItem(Icons.Outlined.History, text = { Text(stringResource(R.string.menu_history)) }) {
                    showMenu = false
                    showHistory = true
                }
                MenuItem(Icons.Outlined.Upload, text = { Text(stringResource(R.string.menu_export_all_history)) }) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
                    exportKifuLauncher.launch("go_kifu_$ts.zip")
                    showMenu = false
                }
                MenuItem(Icons.Outlined.Download, text = { Text(stringResource(R.string.menu_import_history_zip)) }) {
                    importKifuLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    showMenu = false
                }
                MenuItem(Icons.Outlined.Info, text = { Text(stringResource(R.string.about)) }) {
                    showMenu = false
                    onAbout()
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = vm.settings,
            onDismiss = { showSettings = false },
            onSave = {
                showSettings = false
                vm.applySettings(it)
            },
        )
    }

    if (showHistory) {
        HistoryDialog(
            onDismiss = { showHistory = false },
            onOpen = { id, record ->
                showHistory = false
                vm.openHistory(id, record)
            },
        )
    }

    vm.judgementPopup?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissJudgementPopup,
            text = { Text(resultText(r.code), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            confirmButton = { TextButton(onClick = vm::dismissJudgementPopup) { Text("OK") } },
        )
    }

    kifuIoMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { kifuIoMessage = null },
            title = { Text(stringResource(R.string.menu_history)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { kifuIoMessage = null }) { Text("OK") } },
        )
    }
}

/** 過去の棋譜 (ShogiGUI と同じ。検索・開く・ゴミ箱へ入れる/戻す) */
@Composable
private fun HistoryDialog(onDismiss: () -> Unit, onOpen: (String, KifuRecord) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val historyEntries = remember { mutableStateListOf<KifuHistoryEntry>() }
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { KifuHistoryManager.loadIndex(context) }
        historyEntries.clear()
        historyEntries.addAll(loaded)
    }
    val filteredEntries by remember {
        derivedStateOf {
            val q = searchQuery.trim()
            if (q.isBlank()) historyEntries.toList()
            else historyEntries.filter { e ->
                e.blackName.contains(q, ignoreCase = true) ||
                    e.whiteName.contains(q, ignoreCase = true) ||
                    e.displayDate.contains(q)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                )
                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (historyEntries.isEmpty()) stringResource(R.string.history_empty_no_games)
                            else stringResource(R.string.history_empty_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            val isDeleted = entry.deletedAt != null
                            Surface(
                                // ゴミ箱に入れた棋譜は開けない（戻してから開く）
                                enabled = !isDeleted,
                                onClick = {
                                    coroutineScope.launch {
                                        val record = withContext(Dispatchers.IO) {
                                            KifuHistoryManager.bumpToTop(context, entry.id)
                                            KifuHistoryManager.loadKifu(context, entry.id)
                                        }
                                        if (record != null) onOpen(entry.id, record)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .alpha(if (isDeleted) 0.38f else 1f)
                                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
                                                StoneIcon(Stone.BLACK, Modifier.size(14.dp))
                                                Text(
                                                    " ${entry.blackName}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Row(Modifier.weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
                                                StoneIcon(Stone.WHITE, Modifier.size(14.dp))
                                                Text(
                                                    " ${entry.whiteName}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            Text(
                                                entry.displayDate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (entry.moveCount > 0) {
                                                Text(
                                                    stringResource(R.string.history_move_count, entry.moveCount),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (entry.gameResult.isNotEmpty()) {
                                                Text(
                                                    resultText(entry.gameResult),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    // 削除はゴミ箱に入れるだけで、24時間以内なら戻せる
                                    IconButton(onClick = {
                                        val idx = historyEntries.indexOfFirst { it.id == entry.id }
                                        if (idx >= 0) historyEntries[idx] = entry.copy(
                                            deletedAt = if (isDeleted) null else System.currentTimeMillis()
                                        )
                                        if (!isDeleted) {
                                            android.widget.Toast.makeText(
                                                context, R.string.history_deleted_toast, android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            KifuHistoryManager.setDeleted(context, entry.id, !isDeleted)
                                        }
                                    }) {
                                        if (isDeleted) Icon(painterResource(R.drawable.undo_24px), stringResource(R.string.history_restore))
                                        else Icon(painterResource(R.drawable.delete_24px), stringResource(R.string.history_delete))
                                    }
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

/** 上の対局者 (AI)・盤・下の対局者 (あなた) */
@Composable
private fun BoardArea(vm: GameViewModel, boardModifier: Modifier, onMenu: () -> Unit, onEditName: (Stone) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        PlayerSection(vm, top = true, onEditName = onEditName)
        MenuButton(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp), onClick = onMenu)
    }
    Board(vm, boardModifier.fillMaxWidth())
    PvReplayNote(vm)
    PlayerSection(vm, top = false, onEditName = onEditName)
}

/**
 * 対局者の表示。下 (top = false) があなた、上が相手 (AI)。
 * 上には AI の思考中の表示、下には対局の結果を添える
 */
@Composable
private fun PlayerSection(vm: GameViewModel, top: Boolean, alignStart: Boolean = false, onEditName: (Stone) -> Unit) {
    val game = vm.game
    val bottom = vm.humanColor ?: Stone.BLACK
    val stone = if (top) bottom.opponent else bottom
    val result = vm.result
    val turn = if (result == null) game.toPlay else null
    PlayerStatusSection(
        name = if (stone == Stone.BLACK) vm.blackName else vm.whiteName,
        stone = stone,
        captures = if (stone == Stone.BLACK) game.capturedByBlack else game.capturedByWhite,
        isActive = turn == stone,
        gameResult = if (!top) result?.let { resultText(it.code) } ?: "" else "",
        timeLeft = timeLeftText(vm, stone),
        onNameClick = { onEditName(stone) },
        alignStart = alignStart,
    )
}

/**
 * AI が考えている・地を数えているあいだ、画面の上に重ねるポップアップ (キャンセルできる)。
 * 別のウィンドウ (Dialog) にすると、KataGo が CPU を使い切っている間にウィンドウの切り替えが遅れて
 * 「応答していません」になることがあるので、同じ画面の上に重ねて描く。すぐ終わる読みではちらつかないよう、少し待ってから出す
 */
@Composable
private fun ThinkingOverlay(vm: GameViewModel) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(vm.thinking) {
        show = false
        if (vm.thinking) {
            delay(THINKING_POPUP_DELAY_MS)
            show = true
        }
    }
    if (!show || !vm.thinking) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            // 下の盤やボタンには触れさせない
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(32.dp).widthIn(min = 280.dp)
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                        stringResource(if (vm.scoring) R.string.counting else R.string.ai_thinking),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(
                    onClick = { show = false; vm.cancelThinking() },
                    modifier = Modifier.align(Alignment.End).padding(top = 16.dp)
                ) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}

/** 最大化したときの1行目の、前の手・次の手のボタン (形勢グラフの左右の矢印と同じ見た目) */
@Composable
private fun NavButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .repeatingClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
    }
}

/** 読み込んだ棋譜に時間が書かれていれば、今の局面での color の残り時間 ("残り 1:23:45"・秒読みの回数) */
@Composable
private fun timeLeftText(vm: GameViewModel, color: Stone): String? {
    val (sec, periods) = KifuTime.remaining(vm.currentNode, color, vm.timeSettings) ?: return null
    val t = KifuTime.format(sec)
    return if (periods != null) stringResource(R.string.time_left_periods, t, periods) else stringResource(R.string.time_left, t)
}

/** 最大化したときの1行目に並べる対局者 (石・名前・アゲハマ・残り時間)。名前をタップすると編集できる */
@Composable
private fun PlayerChip(vm: GameViewModel, stone: Stone, onEditName: (Stone) -> Unit) {
    val game = vm.game
    val isActive = vm.result == null && game.toPlay == stone
    Row(
        modifier = Modifier.clickable { onEditName(stone) }.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StoneIcon(stone, Modifier.size(18.dp))
        Text(
            text = if (stone == Stone.BLACK) vm.blackName else vm.whiteName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isActive) FontWeight.Bold else null,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.captures_one, if (stone == Stone.BLACK) game.capturedByBlack else game.capturedByWhite),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        timeLeftText(vm, stone)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 盤と、その上に重ねるエンジンの起動失敗の表示 */
@Composable
private fun Board(vm: GameViewModel, modifier: Modifier) {
    val game = vm.game
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
            modifier = modifier,
            boardColor = if (node.isPvBranch) getPvColor(node.pvColorIndex) else woodBoardColor,
            candidates = candidates,
            result = vm.shownResult,
            pvStartMove = vm.pvStartMove,
            placement = vm.settings.placement,
            ghostColor = if (vm.canPlay) game.toPlay else null,
        )
        // 読み込み中は bottomBar のボタン (「準備中」) で分かるので、盤には起動に失敗したときだけ出す
        when (val s = vm.engineStatus) {
            is EngineStatus.Error -> Text(
                stringResource(R.string.engine_error, s.message),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            else -> {}
        }
    }
}

/** 読み筋で、石が取られた後に同じ点へ打った手 (盤上に番号を書けない手) を盤の外に書く */
@Composable
private fun PvReplayNote(vm: GameViewModel) {
    val start = vm.pvStartMove ?: return
    val replays = pvNumbering(vm.game, start).replays
    if (replays.isEmpty()) return
    // 新聞の棋譜と同じく「打ち直した手 [その点の盤上の石]」を並べる
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        replays.forEach { (later, at) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberedStoneIcon(later, Modifier.size(28.dp))
                Text(" [", style = MaterialTheme.typography.titleMedium)
                NumberedStoneIcon(at, Modifier.size(28.dp))
                Text("]", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 対局者名の編集 (ShogiGUI と同じく、名前をタップすると開く) */
@Composable
private fun PlayerNameDialog(vm: GameViewModel, stone: Stone, onDismiss: () -> Unit) {
    var text by remember(stone) { mutableStateOf(if (stone == Stone.BLACK) vm.blackName else vm.whiteName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (stone == Stone.BLACK) R.string.player_name_title_black else R.string.player_name_title_white))
        },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { vm.renamePlayer(stone, text); onDismiss() }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** 読み筋カード: 初手と、読んだ先の形勢 (あなたから見た勝率・目数) */
@Composable
private fun PvCards(vm: GameViewModel, compact: Boolean = false) {
    val (_, analysis) = vm.shownAnalysis ?: return
    val viewer = vm.humanColor ?: vm.shownAnalysis?.first?.game?.toPlay ?: Stone.BLACK
    val label = if (vm.humanColor != null) R.string.evaluation_format else
        if (viewer == Stone.BLACK) R.string.evaluation_format_black else R.string.evaluation_format_white
    analysis.candidates.sortedBy { it.order }.take(3).forEachIndexed { i, c ->
        val rank = i + 1
        val first = c.pv.firstOrNull() ?: c.move
        val mover = vm.shownAnalysis?.first?.game?.toPlay ?: Stone.BLACK
        val winrate = if (viewer == Stone.BLACK) c.winrateBlack else 1 - c.winrateBlack
        val lead = if (viewer == Stone.BLACK) c.scoreLeadBlack else -c.scoreLeadBlack
        PvInfoCard(
            rank = rank,
            mover = mover,
            firstMove = GtpCoord.display(first, stringResource(R.string.pass)),
            evaluation = stringResource(
                label,
                String.format(Locale.US, "%.0f", winrate * 100),
                String.format(Locale.US, "%+.1f", lead),
            ),
            selected = vm.selectedPvRank == rank,
            compact = compact,
        ) { vm.playPv(rank) }
    }
}

@Composable
private fun BottomButtons(vm: GameViewModel) {
    val ready = vm.engineStatus == EngineStatus.Ready
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarButton(
            icon = { Icon(Icons.Outlined.ContentPaste, stringResource(R.string.action_load_from_clipboard_desc)) },
            label = stringResource(R.string.action_load_from_clipboard_label),
            enabled = !vm.thinking,
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) vm.message = context.getString(R.string.clipboard_empty)
                else vm.loadSgf(text)
            },
        )
        if (vm.isPlayingGame) {
            // AI と対局中は、解析・全て解析・本譜の代わりにパス・終局 (終局判定)・CPU 対局の中止を置く。解析などはメニューから使える
            BarButton(
                icon = { Icon(Icons.Outlined.SkipNext, stringResource(R.string.pass)) },
                label = stringResource(R.string.pass),
                enabled = vm.canPlay,
                onClick = vm::pass,
            )
            BarButton(
                icon = { Icon(Icons.Outlined.Calculate, stringResource(R.string.action_end_game)) },
                label = stringResource(R.string.action_end_game),
                enabled = ready && !vm.thinking,
                onClick = vm::judge,
            )
            // 対局中止はエンジンが考えている間でもできるようにする
            BarButton(
                icon = { Icon(painterResource(R.drawable.stop_circle_24px), stringResource(R.string.action_stop_cpu_game)) },
                label = stringResource(R.string.action_stop_cpu_game),
                enabled = true,
                onClick = vm::stopGame,
            )
            return@Row
        }
        val active = vm.analyzing && !vm.autoAnalyzing
        BarButton(
            icon = {
                Icon(
                    painterResource(if (active) R.drawable.stop_circle_24px else R.drawable.network_intelligence_24px),
                    stringResource(R.string.action_analyze),
                )
            },
            label = when {
                vm.analyzing -> stringResource(R.string.action_stop)
                !ready -> stringResource(R.string.action_preparing)
                else -> stringResource(R.string.action_analyze)
            },
            enabled = vm.analyzing || (ready && !vm.thinking),
            highlighted = active,
            onClick = vm::onAnalyzeButton,
        )
        BarButton(
            icon = {
                if (vm.autoAnalyzing) Icon(painterResource(R.drawable.stop_circle_24px), stringResource(R.string.action_stop))
                else Icon(Icons.Outlined.FastForward, stringResource(R.string.action_analyze_all))
            },
            label = if (vm.autoAnalyzing) stringResource(R.string.action_stop) else stringResource(R.string.action_analyze_all),
            enabled = vm.autoAnalyzing || (ready && !vm.thinking),
            highlighted = vm.autoAnalyzing,
            onClick = vm::onAutoAnalyzeButton,
        )
        BarButton(
            icon = { Icon(painterResource(R.drawable.undo_24px), stringResource(R.string.action_main_line)) },
            label = stringResource(R.string.action_main_line),
            enabled = !vm.isOnMainLine && !vm.thinking,
            onClick = vm::goToMainLine,
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
private fun SettingsDialog(initial: GameSettings, onDismiss: () -> Unit, onSave: (GameSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.board_size), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BOARD_SIZES.forEach { n ->
                        FilterChip(s.boardSize == n, {
                            // こみはその盤の既定値にする。5路盤は置石を置けないので、置石も外す
                            val h = s.handicap.coerceAtMost(maxHandicap(n))
                            s = s.copy(boardSize = n, handicap = h, komi = if (h > 0) 0.5 else s.rules.defaultKomi(n))
                        }, { Text(stringResource(R.string.board_size_format, n)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ネットワーク: 強い (遅い) か、速い (弱い) か。変えるとエンジンを読み込み直す
                Text(stringResource(R.string.net), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetChoice.entries.forEach { c ->
                        val label = when (c) {
                            NetChoice.STRONG -> R.string.net_strong
                            NetChoice.FAST -> R.string.net_fast
                        }
                        FilterChip(s.net == c, { s = s.copy(net = c) }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.placement), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Placement.entries.forEach { p ->
                        val label = when (p) {
                            Placement.TAP -> R.string.placement_tap
                            Placement.DOUBLE_TAP -> R.string.placement_double_tap
                            Placement.DRAG -> R.string.placement_drag
                        }
                        FilterChip(s.placement == p, { s = s.copy(placement = p) }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.rules), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rules.entries.forEach { r ->
                        val label = if (r == Rules.JAPANESE) R.string.rules_japanese else R.string.rules_chinese
                        FilterChip(s.rules == r, {
                            s = s.copy(rules = r, komi = if (s.handicap > 0) 0.5 else r.defaultKomi(s.boardSize))
                        }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 置石は 0〜9 子をスライダーで選ぶ (こみと同じく、値を大きく表示する)。5路盤は置石を置けないので出さない
                val maxH = maxHandicap(s.boardSize)
                if (maxH > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.handicap_next_reset), style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (s.handicap == 0) stringResource(R.string.handicap_none) else "${s.handicap}",
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = s.handicap.toFloat(),
                        onValueChange = {
                            val h = it.roundToInt().coerceIn(0, maxH)
                            if (h != s.handicap) s = s.copy(handicap = h, komi = if (h > 0) 0.5 else s.rules.defaultKomi(s.boardSize))
                        },
                        valueRange = 0f..maxH.toFloat(),
                        steps = maxH - 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // AI が1手に使う時間と、「この局面から全て解析」で1手あたりに使う時間（単発の解析は停止するまで考え続ける）。
                // 短い時間ほど細かく選べるよう、スライダーは対数目盛り（倍になるごとに同じ距離）で連続的に動かし、
                // 値は0.01秒単位に丸める。つまみは丸めた値ではなく指の位置をそのまま追うので、滑らかに動く
                var thinkSliderPos by remember { mutableFloatStateOf(thinkTimeToSlider(s.analysisTimeMs)) }
                // 表示は常に小数2桁 ("1.00") にし、数字も等幅にして、値によって行の長さが変わらないようにする
                val thinkTimeSec = "%.2f".format(Locale.US, s.analysisTimeMs / 1000.0)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.settings_think_time_label), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = thinkTimeSec,
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(stringResource(R.string.settings_think_time_unit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = thinkSliderPos,
                    onValueChange = {
                        thinkSliderPos = it
                        s = s.copy(analysisTimeMs = sliderToThinkTime(it))
                    },
                    valueRange = 0f..1f
                )
                Spacer(Modifier.height(8.dp))
                // コミ: 0〜10 目を 0.5 目刻みで選ぶ (思考時間と同じく、値を大きく出す)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.komi), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "%.1f".format(Locale.US, s.komi),
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = s.komi.toFloat().coerceIn(0f, MAX_KOMI),
                    onValueChange = { s = s.copy(komi = Math.round(it * 2) / 2.0) },
                    valueRange = 0f..MAX_KOMI,
                    // 0.5 刻みの目盛りの数 (両端を除く)
                    steps = (MAX_KOMI * 2).toInt() - 1,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** 設定で選べるコミの上限 (目) */
private const val MAX_KOMI = 10f

/** 「AI が考えています」のポップアップを出すまでの待ち時間 [ms]。これより早く終わる読みでは出さない */
private const val THINKING_POPUP_DELAY_MS = 400L

// 設定の思考時間（AI の1手と「この局面から全て解析」の1手に使う時間 [ms]）とスライダー位置 (0..1) の対応。
// ShogiGUI と同じく対数目盛り。範囲は 0.1秒〜5秒 (MIN_THINK_TIME_MS)
private const val minThinkTimeMs = MIN_THINK_TIME_MS
private const val maxThinkTimeMs = MAX_THINK_TIME_MS
private fun thinkTimeToSlider(ms: Long): Float {
    val t = ms.coerceIn(minThinkTimeMs, maxThinkTimeMs).toDouble()
    return (kotlin.math.ln(t / minThinkTimeMs) / kotlin.math.ln(maxThinkTimeMs.toDouble() / minThinkTimeMs)).toFloat()
}
private fun sliderToThinkTime(pos: Float): Long {
    val t = minThinkTimeMs * Math.pow(maxThinkTimeMs.toDouble() / minThinkTimeMs, pos.coerceIn(0f, 1f).toDouble())
    // 0.01秒単位に丸める
    return (Math.round(t / 10.0) * 10).coerceIn(minThinkTimeMs, maxThinkTimeMs)
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

// 黒・白の石をアイコンの代わりに表示する
@Composable
private fun MarkIcon(stone: Stone) {
    // ほかの項目のアイコンと同じ 24dp の枠に収めて、項目名の位置をそろえる
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        StoneIcon(stone, Modifier.size(20.dp))
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
