package com.tksoft.goai

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * OGS のログイン画面。OGS のサイトを WebView で開き、利用者が OGS に直接ログインする
 * (パスワードはこのアプリを通らない)。ページを開くたびに onPage を呼び、ログインできたかを確かめる
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OgsLoginDialog(onPage: () -> Unit, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ogs_login_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                Text(
                    stringResource(R.string.ogs_login_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    CookieManager.getInstance().flush()
                                    onPage()
                                }

                                // OGS の画面はページを読み直さずに移ることがあるので、履歴が変わったときも確かめる
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    CookieManager.getInstance().flush()
                                    onPage()
                                }
                            }
                            loadUrl(OGS_SIGN_IN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

/** 自動マッチングの相手を探している間のポップアップ。対局条件を変えるか、キャンセルできる */
@Composable
fun OgsSearchDialog(prefs: OgsMatchPrefs, onChangeSettings: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.ogs_play)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.ogs_searching), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    ogsPrefsSummary(prefs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
        dismissButton = { TextButton(onClick = onChangeSettings) { Text(stringResource(R.string.ogs_change_settings)) } },
    )
}

/** 対局条件を1行にまとめたもの ("9路・13路 / ラピッド / 秒読み / 日本ルール") */
@Composable
private fun ogsPrefsSummary(p: OgsMatchPrefs): String {
    val sizes = p.sizes.sorted().map { stringResource(R.string.board_size_format, it) }
    val speeds = p.speeds.sortedBy { it.ordinal }.map { stringResource(speedLabel(it)) }
    return listOf(
        sizes.joinToString("・"),
        speeds.joinToString("・"),
        stringResource(if (p.system == OgsTimeSystem.BYOYOMI) R.string.ogs_byoyomi else R.string.ogs_fischer),
        stringResource(ogsRulesLabel(p.rules)),
    ).joinToString(" / ")
}

private fun speedLabel(s: OgsSpeed) = when (s) {
    OgsSpeed.BLITZ -> R.string.ogs_speed_blitz
    OgsSpeed.RAPID -> R.string.ogs_speed_rapid
    OgsSpeed.LIVE -> R.string.ogs_speed_live
}

/** OGS の対局条件。盤の大きさと速さは複数選べる (どれか1つは選ぶ) */
@Composable
fun OgsMatchSettingsDialog(
    initial: OgsMatchPrefs,
    user: OgsUser?,
    onDismiss: () -> Unit,
    onSave: (OgsMatchPrefs) -> Unit,
    onSignOut: () -> Unit,
) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // ログインしていれば、タイトルにアカウント名・段級位・レーティングを出す
        title = {
            if (user == null) Text(stringResource(R.string.ogs_match_settings))
            else {
                // 「アカウント名 [段級位] レーティング」
                val rank = OgsCoord.rankText(user.ranking)?.let { " [$it]" } ?: ""
                val rating = user.rating.takeIf { !it.isNaN() }?.let { " ${it.roundToInt()}" } ?: ""
                Text(user.username + rank + rating)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ogs_board_size), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OgsMatchPrefs.SIZES.forEach { n ->
                        val on = n in p.sizes
                        FilterChip(on, {
                            val next = if (on) p.sizes - n else p.sizes + n
                            if (next.isNotEmpty()) p = p.copy(sizes = next)
                        }, { Text(stringResource(R.string.board_size_format, n)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ogs_speed), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OgsSpeed.entries.forEach { s ->
                        val on = s in p.speeds
                        FilterChip(on, {
                            val next = if (on) p.speeds - s else p.speeds + s
                            if (next.isNotEmpty()) p = p.copy(speeds = next)
                        }, { Text(stringResource(speedLabel(s))) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ogs_time_system), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OgsTimeSystem.entries.forEach { t ->
                        val label = if (t == OgsTimeSystem.BYOYOMI) R.string.ogs_byoyomi else R.string.ogs_fischer
                        FilterChip(p.system == t, { p = p.copy(system = t) }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.rules), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // null は「こだわらない」
                    (Rules.entries + null).forEach { r ->
                        FilterChip(p.rules == r, { p = p.copy(rules = r) }, { Text(stringResource(ogsRulesLabel(r))) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ogs_handicap), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OgsHandicap.entries.forEach { h ->
                        val label = when (h) {
                            OgsHandicap.STANDARD -> R.string.ogs_handicap_standard
                            OgsHandicap.ENABLED -> R.string.ogs_handicap_enabled
                            OgsHandicap.DISABLED -> R.string.ogs_handicap_disabled
                        }
                        FilterChip(p.handicap == h, { p = p.copy(handicap = h) }, { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ogs_rank_range), style = MaterialTheme.typography.labelLarge)
                RankDiffSlider(stringResource(R.string.ogs_rank_lower, p.lowerRankDiff), p.lowerRankDiff) {
                    p = p.copy(lowerRankDiff = it)
                }
                RankDiffSlider(stringResource(R.string.ogs_rank_upper, p.upperRankDiff), p.upperRankDiff) {
                    p = p.copy(upperRankDiff = it)
                }
                if (user != null) {
                    Spacer(Modifier.height(8.dp))
                    // アカウント名はタイトルに出しているので、ここはログアウトのボタンだけ
                    TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.ogs_sign_out))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun RankDiffSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(0, OgsMatchPrefs.MAX_RANK_DIFF)) },
        valueRange = 0f..OgsMatchPrefs.MAX_RANK_DIFF.toFloat(),
        steps = OgsMatchPrefs.MAX_RANK_DIFF - 1,
    )
}

/** 整地中に盤の上に出すカード: 死に石をタップで選び、同意するか対局に戻る */
@Composable
fun OgsStoneRemovalCard(onAccept: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.ogs_stone_removal), style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onResume) { Text(stringResource(R.string.ogs_resume)) }
                TextButton(onClick = onAccept) { Text(stringResource(R.string.ogs_accept)) }
            }
        }
    }
}

private fun ogsRulesLabel(r: Rules?) = when (r) {
    Rules.JAPANESE -> R.string.rules_japanese
    Rules.CHINESE -> R.string.rules_chinese
    null -> R.string.ogs_rules_any
}
