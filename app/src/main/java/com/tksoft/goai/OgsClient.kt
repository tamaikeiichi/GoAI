package com.tksoft.goai

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
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
/** ranking は OGS の段級位 (30 = 初段)、rating は Glicko-2 のレーティング (分からなければ NaN) */
data class OgsUser(val id: Long, val username: String, val ranking: Double, val rating: Double = Double.NaN)

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
    /** null はルールにこだわらない (相手が決めたルールで打つ) */
    val rules: Rules? = Rules.JAPANESE,
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
            .put(
                "rules",
                if (rules == null) JSONObject().put("condition", "no-preference").put("value", Rules.JAPANESE.gtp)
                else JSONObject().put("condition", "required").put("value", rules.gtp),
            )
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
            .putString("rules", rules?.name ?: RULES_ANY)
            .putString("handicap", handicap.name)
            .putInt("lowerRankDiff", lowerRankDiff)
            .putInt("upperRankDiff", upperRankDiff)
            .apply()
    }

    companion object {
        val SIZES = listOf(9, 13, 19)
        const val MAX_RANK_DIFF = 9
        private const val RULES_ANY = "ANY"

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
                rules = when (val r = prefs.getString("rules", null)) {
                    RULES_ANY -> null
                    null -> d.rules
                    else -> runCatching { Rules.valueOf(r) }.getOrNull() ?: d.rules
                },
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

    /**
     * OGS の国のコード ("jp" など) を国旗の絵文字にする。国を登録していない ("un") ときや、
     * OGS 独自のコード (2文字の英字でないもの) は null
     */
    fun flag(country: String?): String? {
        val c = country?.trim()?.lowercase() ?: return null
        if (c.length != 2 || !c.all { it in 'a'..'z' } || c == "un") return null
        return c.map { String(Character.toChars(0x1F1E6 + (it - 'a'))) }.joinToString("")
    }

    /** 名前の欄 ("tamaikeiichi [6k]") から OGS のユーザー名だけを取り出す */
    fun usernameOf(name: String): String = name.substringBefore(" [")

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
    private val context: Context,
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
    /** 最後に net/pong を受け取った時刻 (つながった時刻から数え始める) */
    private var lastPongAt = 0L
    /** checkNow で送った確認の時刻 (その後に pong が来れば 0 に戻す) */
    private var probeSentAt = 0L
    /** 回線の切り替わりを見るため、今使っている回線 */
    private var currentNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            val id = user.getLong("id")
            // レーティングは ui/config に無ければ、公開のプロフィールから取る
            val rating = ratingOf(user) ?: runCatching { fetchJson("$OGS_HOST/api/v1/players/$id", cookies) }.getOrNull()?.let { ratingOf(it) }
            OgsUser(id, user.optString("username"), user.optDouble("ranking", Double.NaN), rating ?: Double.NaN)
        }
    }

    /** 公開のプロフィールから登録している国 ("jp" など) を取る */
    suspend fun fetchCountry(id: Long): String? = withContext(Dispatchers.IO) {
        val cookies = runCatching { CookieManager.getInstance().getCookie(OGS_HOST) }.getOrNull()
        fetchJson("$OGS_HOST/api/v1/players/$id", cookies).optString("country").ifEmpty { null }
    }

    /** 公開のプロフィールから今のレーティングを取る (対局の後の変動を見るため) */
    suspend fun fetchRating(id: Long): Double? = withContext(Dispatchers.IO) {
        val cookies = runCatching { CookieManager.getInstance().getCookie(OGS_HOST) }.getOrNull()
        ratingOf(fetchJson("$OGS_HOST/api/v1/players/$id", cookies))
    }

    private fun ratingOf(o: JSONObject): Double? =
        o.optJSONObject("ratings")?.optJSONObject("overall")?.optDouble("rating", Double.NaN)?.takeIf { !it.isNaN() }

    private fun fetchJson(url: String, cookies: String?): JSONObject {
        val request = Request.Builder().url(url)
            .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
            .build()
        return http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string() ?: throw IOException("empty response"))
        }
    }

    /** WebSocket につなぐ (つながっていればそのまま) */
    fun connect() {
        wantConnected = true
        watchNetwork()
        if (socket == null) open()
    }

    /**
     * 自分の手を送ったのにサーバーから返ってこないときなどに、すぐ生存確認を送る。
     * PROBE_TIMEOUT_MS のうちに返事が無ければ、切れたとみなしてつなぎ直す
     */
    fun checkNow() {
        val ws = socket ?: return
        if (!connected || probeSentAt != 0L) return
        probeSentAt = System.currentTimeMillis()
        sendPing(ws)
    }

    /** WebSocket を切る。送っていないメッセージは捨てる */
    fun disconnect() {
        wantConnected = false
        unwatchNetwork()
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
                    lastPongAt = System.currentTimeMillis()
                    probeSentAt = 0L
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

    /** 返事の無い接続を捨てて、すぐにつなぎ直す */
    private fun dropAndReconnect(webSocket: WebSocket) {
        if (socket !== webSocket) return
        Log.w("GoAI", "OGS socket is not responding; reconnecting")
        reconnectDelayMs = 0L
        webSocket.cancel() // onFailure から onLost に来て、つなぎ直す
        onLost(webSocket)
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
            reconnectDelayMs = (reconnectDelayMs * 2).coerceIn(INITIAL_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS)
            reconnectJob = scope.launch {
                delay(wait)
                if (wantConnected && socket == null) open()
            }
        }
    }

    /**
     * 接続の生存確認と、サーバーとの時計のずれ (持ち時間の表示に使う) の測定。
     * PING_INTERVAL_MS ごとに net/ping を送り、PONG_TIMEOUT_MS のあいだ返事が無ければ
     * (回線の切り替わりなどで、切れたことが端末に分からないまま止まっている) 接続を捨ててつなぎ直す
     */
    private fun startPing(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.Main) {
            var nextPingAt = 0L
            while (isActive && socket === webSocket) {
                val now = System.currentTimeMillis()
                val probeExpired = probeSentAt != 0L && now - probeSentAt > PROBE_TIMEOUT_MS
                if (now - lastPongAt > PONG_TIMEOUT_MS || probeExpired) {
                    dropAndReconnect(webSocket)
                    return@launch
                }
                if (now >= nextPingAt) {
                    sendPing(webSocket)
                    nextPingAt = now + PING_INTERVAL_MS
                }
                delay(WATCHDOG_TICK_MS)
            }
        }
    }

    private fun sendPing(webSocket: WebSocket) {
        webSocket.send(
            JSONArray().put("net/ping").put(
                JSONObject().put("client", System.currentTimeMillis()).put("drift", drift).put("latency", latency)
            ).toString()
        )
    }

    /**
     * 回線が切り替わった (Wi-Fi からモバイル回線へなど) り戻ったりしたら、待たずにすぐつなぎ直す。
     * 切り替わる前の接続は、端末からは切れたと分からないまま使えなくなっていることが多い
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.Main) {
                    val previous = currentNetwork
                    currentNetwork = network
                    if (!wantConnected || previous == network) return@launch
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                    val ws = socket
                    when {
                        ws == null -> { reconnectJob?.cancel(); open() }
                        // 前の回線で張った接続は使えないことが多いので、張り直す
                        previous != null -> dropAndReconnect(ws)
                    }
                }
            }

            override fun onLost(network: Network) {
                scope.launch(Dispatchers.Main) { if (currentNetwork == network) currentNetwork = null }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w("GoAI", "cannot watch network", it) }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        currentNetwork = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun onPong(data: JSONObject) {
        val now = System.currentTimeMillis()
        lastPongAt = now
        probeSentAt = 0L
        val client = data.optLong("client", now)
        val server = data.optLong("server", now)
        latency = now - client
        drift = now - latency / 2 - server
    }

    /** サーバーの今の時刻 [ms] (端末の時計をずれの分だけ直したもの) */
    fun serverNow(): Long = System.currentTimeMillis() - drift

    companion object {
        private const val PING_INTERVAL_MS = 5_000L
        /** これだけ pong が来なければ、切れたとみなす (ping 3回ぶん) */
        private const val PONG_TIMEOUT_MS = 15_000L
        /** checkNow の確認に、これだけ返事が無ければ切れたとみなす */
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        fun newUuid(): String = UUID.randomUUID().toString()
    }
}
