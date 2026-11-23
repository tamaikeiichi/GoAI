package com.tksoft.goai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.floor

/** OGS の対局の段階 */
enum class OgsPhase { PLAY, STONE_REMOVAL, FINISHED;
    companion object {
        fun of(s: String?) = when (s) {
            "stone removal" -> STONE_REMOVAL
            "finished" -> FINISHED
            else -> PLAY
        }
    }
}

/** OGS で対局中の1局。サーバーから来た内容 (gamedata・move・clock など) を持つ */
class OgsGame(val id: Long) {
    /** gamedata を一度でも受け取ったか (受け取るまでは盤を作らない) */
    var loaded by mutableStateOf(false)
    var size = 19
    var komi = 6.5
    var rules = Rules.JAPANESE
    var handicap = 0
    /** 置石を黒が好きな点に置く (最初の handicap 手が黒の置石になる) */
    var freeHandicap = false
    /** 最初から置いてある黒石 (決まった点の置石) */
    var initialBlack: List<Pair<Int, Int>> = emptyList()
    var blackId = 0L
    var whiteId = 0L
    var blackName = ""
    var whiteName = ""
    /** レーティングに数える対局か */
    var ranked = false
    /** 自分の石の色。自分の対局でなければ null */
    var myColor by mutableStateOf<Stone?>(null)
    /** サーバーが受け付けた手 (自由置石の置石も含む) */
    var moves: List<Move> = emptyList()
    /** 送ったが、まだサーバーから返ってこない自分の手 */
    var pending by mutableStateOf<Move?>(null)
    var phase by mutableStateOf(OgsPhase.PLAY)
    /** 手番の対局者の OGS の id */
    var currentPlayerId by mutableLongStateOf(0L)
    /** 整地で死に石とした石 */
    var removed by mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    var clock by mutableStateOf<JSONObject?>(null)
    /** この対局の棋譜の根 (ほかの棋譜の木を使い回さないよう、この対局で作ったものだけを続ける) */
    var root: GoNode? = null

    /** 自由置石で、まだ置石を置いている途中か */
    val placingHandicap: Boolean get() = freeHandicap && handicap > 1 && moves.size + (if (pending != null) 1 else 0) < handicap

    fun idOf(color: Stone) = if (color == Stone.BLACK) blackId else whiteId

    /**
     * color の残り時間 [秒] と秒読みの残り回数 (秒読みでなければ null)。now はサーバーの今の時刻 [ms]。
     * 手番の側は、最後の手からの経過時間を引く
     */
    fun timeLeft(color: Stone, now: Long): Pair<Double, Int?>? {
        val c = clock ?: return null
        val t = c.opt(if (color == Stone.BLACK) "black_time" else "white_time") ?: return null
        val paused = c.optJSONObject("pause")?.optBoolean("paused", false) == true
        val running = phase == OgsPhase.PLAY && !paused && !c.optBoolean("start_mode", false) &&
            c.optLong("current_player", -1) == idOf(color)
        val lastMove = c.optLong("last_move", 0L)
        val elapsed = if (running && lastMove > 0) ((now - lastMove).coerceAtLeast(0L)) / 1000.0 else 0.0
        return when (t) {
            is Number -> (t.toDouble() - elapsed).coerceAtLeast(0.0) to null
            is JSONObject -> {
                val thinking = t.optDouble("thinking_time", 0.0)
                if (!t.has("periods")) return (thinking - elapsed).coerceAtLeast(0.0) to null
                val periods = t.optInt("periods", 0)
                val periodTime = t.optDouble("period_time", 0.0)
                val periodLeft = t.optDouble("period_time_left", periodTime)
                val remain = thinking - elapsed
                if (remain >= 0 && thinking > 0) return remain to periods
                // 持ち時間を使い切っていれば秒読み。途中の秒読みの残り (period_time_left) から数える
                val over = if (thinking > 0) -remain else elapsed
                if (periodTime <= 0) return 0.0 to 0
                val first = periodLeft.coerceIn(0.0, periodTime)
                if (over < first) return (first - over) to periods
                val used = 1 + floor((over - first) / periodTime).toInt()
                val left = periods - used
                if (left <= 0) 0.0 to 0 else (periodTime - (over - first) % periodTime) to left
            }
            else -> null
        }
    }
}
