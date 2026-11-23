package com.tksoft.goai

import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * online-go.com (OGS) で対局するための通信。
 *
 * OGS の Web クライアント (online-go.com, AGPL-3.0) のコードは使っていない。通信の形式は、OGS が公開している
 * goban ライブラリ (Apache-2.0) の型定義 (engine/protocol/ClientToServer.ts・ServerToClient.ts) と
 * 実際の応答を見て、このアプリで書き直したもの。
 *
 * - ログイン: OGS のログイン画面を WebView で開き、利用者が OGS に直接ログインする (パスワードはこのアプリを通らない)。
 *   ログインすると WebView にセッションの Cookie が残るので、それを付けて /api/v1/ui/config を読み、
 *   利用者の情報と WebSocket の認証に使う JWT (user_jwt) を得る
 * - 対局: wss://online-go.com/ の WebSocket。メッセージは JSON 配列 [コマンド, データ] (応答が要るときは [コマンド, データ, 番号])
 */

private const val OGS_HOST = "https://online-go.com"
private const val OGS_SOCKET_URL = "wss://online-go.com/"
const val OGS_SIGN_IN_URL = "$OGS_HOST/sign-in"

/** ログイン中の OGS の利用者 */
data class OgsUser(val id: Long, val username: String, val ranking: Double)

/** 自動マッチングの速さ (OGS の speed)。通信対局 (correspondence) はこのアプリでは扱わない */
enum class OgsSpeed(val api: String) { BLITZ("blitz"), RAPID("rapid"), LIVE("live") }

/** 持ち時間の方式 */
enum class OgsTimeSystem(val api: String) { BYOYOMI("byoyomi"), FISCHER("fischer") }

/** 置き碁: STANDARD は相手と棋力が違えば置き碁にする (どちらでもよい)、ENABLED は置き碁だけ、DISABLED は互先だけ */
enum class OgsHandicap { STANDARD, ENABLED, DISABLED }

/** 自動マッチングの対局条件 */
data class OgsMatchPrefs(
    val sizes: Set<Int> = setOf(9),
    val speeds: Set<OgsSpeed> = setOf(OgsSpeed.RAPID),
    val system: OgsTimeSystem = OgsTimeSystem.BYOYOMI,
    val rules: Rules = Rules.JAPANESE,
    val handicap: OgsHandicap = OgsHandicap.STANDARD,
    /** 相手の棋力の幅 (自分より何段級下・上まで) */
    val lowerRankDiff: Int = 3,
    val upperRankDiff: Int = 3,
) {
    /** automatch/find_match に送るデータ */
    fun toFindMatch(uuid: String): JSONObject {
        val options = JSONArray()
        for (size in sizes.sorted()) for (speed in speeds.sortedBy { it.ordinal }) {
            options.put(JSONObject().put("size", "${size}x$size").put("speed", speed.api).put("system", system.api))
        }
        return JSONObject()
            .put("uuid", uuid)
            .put("size_speed_options", options)
            .put("lower_rank_diff", lowerRankDiff)
            .put("upper_rank_diff", upperRankDiff)
            .put("rules", JSONObject().put("condition", "required").put("value", rules.gtp))
            .put(
                "handicap",
                JSONObject()
                    .put("condition", if (handicap == OgsHandicap.STANDARD) "preferred" else "required")
                    .put("value", if (handicap == OgsHandicap.DISABLED) "disabled" else "enabled"),
            )
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("sizes", sizes.joinToString(","))
            .putString("speeds", speeds.joinToString(",") { it.name })
            .putString("system", system.name)
            .putString("rules", rules.name)
            .putString("handicap", handicap.name)
            .putInt("lowerRankDiff", lowerRankDiff)
            .putInt("upperRankDiff", upperRankDiff)
            .apply()
    }

    companion object {
        val SIZES = listOf(9, 13, 19)
        const val MAX_RANK_DIFF = 9

        fun load(prefs: SharedPreferences): OgsMatchPrefs {
            val d = OgsMatchPrefs()
            val sizes = prefs.getString("sizes", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
                ?.filter { it in SIZES }?.toSet()?.takeIf { it.isNotEmpty() } ?: d.sizes
            val speeds = prefs.getString("speeds", null)?.split(",")
                ?.mapNotNull { runCatching { OgsSpeed.valueOf(it) }.getOrNull() }?.toSet()?.takeIf { it.isNotEmpty() } ?: d.speeds
            return OgsMatchPrefs(
                sizes = sizes,
                speeds = speeds,
                system = prefs.getString("system", null)?.let { runCatching { OgsTimeSystem.valueOf(it) }.getOrNull() } ?: d.system,
                rules = prefs.getString("rules", null)?.let { runCatching { Rules.valueOf(it) }.getOrNull() } ?: d.rules,
                handicap = prefs.getString("handicap", null)?.let { runCatching { OgsHandicap.valueOf(it) }.getOrNull() } ?: d.handicap,
                lowerRankDiff = prefs.getInt("lowerRankDiff", d.lowerRankDiff).coerceIn(0, MAX_RANK_DIFF),
                upperRankDiff = prefs.getInt("upperRankDiff", d.upperRankDiff).coerceIn(0, MAX_RANK_DIFF),
            )
        }
    }
}

/** OGS の座標 (SGF と同じく左上が "aa"、1文字目が列)。パスは ".." */
object OgsCoord {
    fun encode(move: Move): String = when (move) {
        is Move.Pass -> ".."
        is Move.Play -> encode(move.x, move.y)
    }

    fun encode(x: Int, y: Int): String = "${'a' + x}${'a' + y}"

    /** "ddpp.." のような並び。パスや範囲外の点は飛ばす */
    fun decodePoints(s: String, size: Int): List<Pair<Int, Int>> =
        s.chunked(2).filter { it.length == 2 }.map { (it[0] - 'a') to (it[1] - 'a') }
            .filter { (x, y) -> x in 0 until size && y in 0 until size }

    /** 盤上の点を、OGS と同じ順 (上の行から、行の中は左から) に並べた文字列 */
    fun encodePoints(points: Collection<Pair<Int, Int>>): String =
        points.sortedWith(compareBy({ it.second }, { it.first })).joinToString("") { (x, y) -> encode(x, y) }

    /** game/<id>/move・gamedata の手 ([x, y, 時間, ...])。x が負ならパス */
    fun decodeMove(arr: JSONArray): Move {
        val x = arr.optInt(0, -1)
        val y = arr.optInt(1, -1)
        return if (x < 0 || y < 0) Move.Pass else Move.Play(x, y)
    }

    /** OGS の棋力 (30 が初段) を "5k" "2d" のように書く */
    fun rankText(ranking: Double): String? {
        if (ranking.isNaN() || ranking < 0) return null
        val r = ranking.toInt()
        return if (r >= 30) "${r - 29}d" else "${30 - r}k"
    }
}

/**
 * OGS との通信。WebSocket の受信はメインスレッドで listener に渡す。
 * 切れたら少しずつ間を空けてつなぎ直す (OGS は頻繁な再接続を控えるよう求めている)
 */
class OgsClient(
    private val scope: CoroutineScope,
    /** authenticate で OGS に知らせるこのアプリの版 */
    private val clientVersion: String,
    private val listener: Listener,
) {
    interface Listener {
        /** サーバーからのイベント (メインスレッド) */
        fun onOgsEvent(command: String, data: Any?)

        /** 接続 (認証を送った後) と切断 (メインスレッド) */
        fun onOgsConnection(connected: Boolean)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // WebSocket は無通信の時間があるので読み取りのタイムアウトは付けない (生存確認は net/ping で行う)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var jwt: String = ""
    private var socket: WebSocket? = null
    private var connected = false
    private var wantConnected = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    /** つながる前に送ろうとしたメッセージ。つながって認証を送った後に送る */
    private val queue = mutableListOf<String>()
    private var latency = 0L
    private var drift = 0L

    /**
     * WebView に残っている OGS のセッションで利用者の情報と JWT を読む。ログインしていなければ null。
     * 通信できなければ IOException
     */
    suspend fun fetchUser(): OgsUser? = withContext(Dispatchers.IO) {
        val cookies = runCatching { CookieManager.getInstance().getCookie(OGS_HOST) }.getOrNull()
        val request = Request.Builder().url("$OGS_HOST/api/v1/ui/config")
            .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val config = JSONObject(response.body?.string() ?: throw IOException("empty response"))
            val user = config.optJSONObject("user")
            if (user == null || user.optBoolean("anonymous", true) || user.optLong("id", -1) <= 0) {
                jwt = ""
                return@use null
            }
            jwt = config.optString("user_jwt", "")
            if (jwt.isEmpty()) return@use null
            OgsUser(user.getLong("id"), user.optString("username"), user.optDouble("ranking", Double.NaN))
        }
    }

    /** WebSocket につなぐ (つながっていればそのまま) */
    fun connect() {
        wantConnected = true
        if (socket == null) open()
    }

    /** WebSocket を切る。送っていないメッセージは捨てる */
    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        queue.clear()
        socket?.close(1000, null)
        socket = null
        if (connected) {
            connected = false
            listener.onOgsConnection(false)
        }
    }

    /** ログアウト: WebView の OGS のセッション (Cookie) を消して、接続を切る */
    fun signOut() {
        disconnect()
        jwt = ""
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun send(command: String, data: JSONObject? = null) {
        val text = JSONArray().put(command).apply { if (data != null) put(data) }.toString()
        val ws = socket
        if (connected && ws != null && ws.send(text)) return
        queue.add(text)
        if (wantConnected && socket == null) open()
    }

    private fun open() {
        reconnectJob?.cancel()
        val request = Request.Builder().url(OGS_SOCKET_URL).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch(Dispatchers.Main) {
                    if (socket !== webSocket) return@launch
                    connected = true
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                    webSocket.send(
                        JSONArray().put("authenticate").put(
                            JSONObject().put("jwt", jwt).put("client", "goai").put("client_version", clientVersion)
                        ).toString()
                    )
                    val pending = queue.toList()
                    queue.clear()
                    pending.forEach { webSocket.send(it) }
                    startPing(webSocket)
                    listener.onOgsConnection(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
                val command = arr.opt(0) as? String ?: return // 番号で始まるのは送ったメッセージへの応答 (使っていない)
                val data = arr.opt(1)
                scope.launch(Dispatchers.Main) {
                    if (socket !== webSocket) return@launch
                    when (command) {
                        "net/pong" -> (data as? JSONObject)?.let { onPong(it) }
                        "user/jwt" -> (data as? String)?.let { jwt = it }
                        else -> listener.onOgsEvent(command, data)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onLost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("GoAI", "OGS socket failure", t)
                onLost(webSocket)
            }
        })
    }

    private fun onLost(webSocket: WebSocket) {
        scope.launch(Dispatchers.Main) {
            if (socket !== webSocket) return@launch
            socket = null
            pingJob?.cancel()
            if (connected) {
                connected = false
                listener.onOgsConnection(false)
            }
            if (!wantConnected) return@launch
            val wait = reconnectDelayMs
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            reconnectJob = scope.launch {
                delay(wait)
                if (wantConnected && socket == null) open()
            }
        }
    }

    /** 接続の生存確認と、サーバーとの時計のずれ (持ち時間の表示に使う) の測定 */
    private fun startPing(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && socket === webSocket) {
                webSocket.send(
                    JSONArray().put("net/ping").put(
                        JSONObject().put("client", System.currentTimeMillis()).put("drift", drift).put("latency", latency)
                    ).toString()
                )
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun onPong(data: JSONObject) {
        val now = System.currentTimeMillis()
        val client = data.optLong("client", now)
        val server = data.optLong("server", now)
        latency = now - client
        drift = now - latency / 2 - server
    }

    /** サーバーの今の時刻 [ms] (端末の時計をずれの分だけ直したもの) */
    fun serverNow(): Long = System.currentTimeMillis() - drift

    companion object {
        private const val PING_INTERVAL_MS = 10_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        fun newUuid(): String = UUID.randomUUID().toString()
    }
}
