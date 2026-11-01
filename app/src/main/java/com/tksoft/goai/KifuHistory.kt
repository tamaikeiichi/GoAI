package com.tksoft.goai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 棋譜1局分。樹形図 (分岐・読み筋・形勢) と、SGF のヘッダに相当する情報 */
class KifuRecord(
    val root: GoNode,
    val komi: Double,
    val rules: Rules,
    val blackName: String,
    val whiteName: String,
    val gameDate: String?,
    val timeSettings: TimeSettings? = null,
)

/** 樹形図を JSON にする (「過去の棋譜」と、表示中の棋譜の保存用)。SGF と違い読み筋の分岐と形勢も残す */
object KifuJson {
    fun toJson(r: KifuRecord): JSONObject = JSONObject().apply {
        put("size", r.root.game.size)
        put("handicap", JSONArray().apply { r.root.game.handicapStones.forEach { (x, y) -> put(JSONArray(listOf(x, y))) } })
        put("komi", r.komi)
        put("rules", r.rules.name)
        put("black", r.blackName)
        put("white", r.whiteName)
        r.gameDate?.let { put("date", it) }
        r.timeSettings?.mainTimeSec?.let { put("tm", it) }
        r.timeSettings?.overtime?.let { put("ot", it) }
        put("root", nodeToJson(r.root))
    }

    private fun nodeToJson(n: GoNode): JSONObject = JSONObject().apply {
        n.move?.let { put("m", GtpCoord.toGtp(it.move, n.game.size)) }
        if (n.isPvBranch) { put("pv", true); put("pc", n.pvColorIndex) }
        n.winrateBlack?.let { put("wr", it) }
        n.scoreLeadBlack?.let { put("sl", it) }
        n.analysis?.let { a -> analysisToJson(a, n.game.size)?.let { put("an", it) } }
        n.timeLeft?.let { put("tl", it) }
        n.periodsLeft?.let { put("pl", it) }
        n.result?.let { res ->
            put("re", res.code)
            if (res.deadStones.isNotEmpty()) {
                put("dead", JSONArray().apply { res.deadStones.forEach { (x, y) -> put(JSONArray(listOf(x, y))) } })
            }
            res.ownershipBlack?.let { put("own", JSONArray(it)) }
        }
        if (n.children.isNotEmpty()) put("c", JSONArray().apply { n.children.forEach { put(nodeToJson(it)) } })
    }

    /** 読み筋カードに出す候補手だけを残す (形勢の地図 ownership は大きいので残さない) */
    private const val SAVED_CANDIDATES = 3

    private fun analysisToJson(a: Analysis, size: Int): JSONArray? {
        val cands = a.candidates.sortedBy { it.order }.take(SAVED_CANDIDATES)
        if (cands.isEmpty()) return null
        return JSONArray().apply {
            cands.forEach { c ->
                put(JSONObject().apply {
                    put("m", GtpCoord.toGtp(c.move, size))
                    put("v", c.visits)
                    put("wr", c.winrateBlack)
                    put("sl", c.scoreLeadBlack)
                    put("pv", c.pv.joinToString(" ") { GtpCoord.toGtp(it, size) })
                })
            }
        }
    }

    private fun analysisFromJson(arr: JSONArray, size: Int): Analysis? {
        val cands = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val move = GtpCoord.fromGtp(o.optString("m"), size) ?: return@mapNotNull null
            Candidate(
                move = move,
                visits = o.optInt("v", 0),
                winrateBlack = o.optDouble("wr", 0.5),
                scoreLeadBlack = o.optDouble("sl", 0.0),
                order = i,
                pv = o.optString("pv").split(' ').filter { it.isNotEmpty() }.mapNotNull { GtpCoord.fromGtp(it, size) },
            )
        }
        return if (cands.isEmpty()) null else Analysis(cands, null)
    }

    fun fromJson(o: JSONObject): KifuRecord {
        val size = o.optInt("size", 9)
        val hArr = o.optJSONArray("handicap") ?: JSONArray()
        val handicap = (0 until hArr.length()).map { hArr.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } }
        val root = GoNode(GoGame(size, handicap))
        readNode(root, o.getJSONObject("root"))
        return KifuRecord(
            root = root,
            komi = o.optDouble("komi", Rules.JAPANESE.defaultKomi(size)),
            rules = runCatching { Rules.valueOf(o.optString("rules")) }.getOrDefault(Rules.JAPANESE),
            blackName = o.optString("black", ""),
            whiteName = o.optString("white", ""),
            gameDate = o.optString("date").takeIf { it.isNotBlank() },
            timeSettings = TimeSettings(
                mainTimeSec = if (o.has("tm")) o.getDouble("tm") else null,
                overtime = o.optString("ot").takeIf { it.isNotBlank() },
            ).takeIf { it.mainTimeSec != null || it.overtime != null },
        )
    }

    private fun readNode(n: GoNode, o: JSONObject) {
        if (o.has("wr")) n.winrateBlack = o.getDouble("wr")
        if (o.has("sl")) n.scoreLeadBlack = o.getDouble("sl")
        o.optJSONArray("an")?.let { n.analysis = analysisFromJson(it, n.game.size) }
        if (o.has("tl")) n.timeLeft = o.getDouble("tl")
        if (o.has("pl")) n.periodsLeft = o.getInt("pl")
        if (o.has("re")) {
            val dArr = o.optJSONArray("dead") ?: JSONArray()
            val own = o.optJSONArray("own")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
            n.result = GameResult(
                o.getString("re"),
                (0 until dArr.length()).map { dArr.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } }.toSet(),
                own,
            )
        }
        val c = o.optJSONArray("c") ?: return
        for (i in 0 until c.length()) {
            val co = c.getJSONObject(i)
            val move = GtpCoord.fromGtp(co.optString("m"), n.game.size) ?: continue
            val child = n.childFor(move, co.optBoolean("pv", false), co.optInt("pc", 0)) ?: continue
            readNode(child, co)
        }
    }
}

data class KifuHistoryEntry(
    val id: String,
    val blackName: String,
    val whiteName: String,
    val gameResult: String,
    val savedAt: Long,
    val moveCount: Int,
    val gameDate: String? = null,
    val displayDate: String = "",
    // 削除した時刻。null でなければゴミ箱に入っていて、TRASH_RETENTION_MS 後に完全に削除される
    val deletedAt: Long? = null
)

/** 「過去の棋譜」(ShogiGUI の KifuHistoryManager を囲碁向けにしたもの。書き出し・読み込みは CSA の代わりに SGF) */
object KifuHistoryManager {
    // フォルダや圧縮ファイルからまとめて取り込めるよう多めにしている (index.json は 1件 200 バイトほど)
    private const val MAX_ENTRIES = 10000
    private const val HISTORY_DIR = "kifu_history"
    private const val INDEX_FILE = "index.json"
    const val TRASH_RETENTION_MS = 24L * 60 * 60 * 1000

    // index.json の読み書きは UI からの操作と自動保存が別スレッドで重なるので、まとめて排他する
    private val lock = Any()
    // 着手のたびの自動保存はメインスレッドを止めないよう、順番を保って裏で書く
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val dateFmt get() = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

    private val kifuFileNameRegex = Regex("^\\d+\\.json$")

    private fun historyDir(context: Context): File =
        File(context.filesDir, HISTORY_DIR).also { if (!it.exists()) it.mkdirs() }

    private fun indexFile(context: Context) = File(historyDir(context), INDEX_FILE)
    private fun kifuFile(context: Context, id: String) = File(historyDir(context), "$id.json")

    private fun computeDisplayDate(gameDate: String?, savedAt: Long): String =
        gameDate?.take(10)?.replace('-', '/') ?: formatDate(savedAt)

    // 書き込み中のプロセス強制終了（アプリ更新時の再起動等）でファイルが
    // 中途半端な内容になり全履歴が読めなくなるのを避けるため、一時ファイル経由で置き換える
    private fun writeTextAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }

    fun loadIndex(context: Context): List<KifuHistoryEntry> = synchronized(lock) {
        val file = indexFile(context)
        val parsed: List<KifuHistoryEntry> = if (!file.exists()) emptyList() else try {
            val arr = JSONArray(file.readText())
            // 1件でも解析に失敗した場合に全履歴が消えて見えることがないよう、エントリ単位で読み飛ばす
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val gameDate = obj.optString("gameDate").takeIf { it.isNotBlank() && it != "null" }
                    val savedAt = obj.optLong("savedAt", 0L)
                    KifuHistoryEntry(
                        id = obj.getString("id"),
                        blackName = obj.optString("blackName", ""),
                        whiteName = obj.optString("whiteName", ""),
                        gameResult = obj.optString("gameResult", ""),
                        savedAt = savedAt,
                        moveCount = obj.optInt("moveCount", 0),
                        gameDate = gameDate,
                        displayDate = computeDisplayDate(gameDate, savedAt),
                        deletedAt = obj.optLong("deletedAt", 0L).takeIf { it > 0L }
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }

        val reconciled = reconcileWithOrphanFiles(context, parsed)
        // ゴミ箱に入れてから一定時間たったものは完全に削除する
        val now = System.currentTimeMillis()
        val (expired, kept) = reconciled.partition { it.deletedAt != null && now - it.deletedAt >= TRASH_RETENTION_MS }
        expired.forEach { kifuFile(context, it.id).delete() }
        if (kept.size != parsed.size) saveIndex(context, kept)
        return kept
    }

    // index.json の破損・欠落や、保存処理の途中終了で index に登録され損ねた
    // 対局ファイル（本体の棋譜データ自体は無事）を見つけて復旧する
    private fun reconcileWithOrphanFiles(context: Context, entries: List<KifuHistoryEntry>): List<KifuHistoryEntry> {
        val dir = historyDir(context)
        val knownIds = entries.map { it.id }.toSet()
        val orphanFiles = dir.listFiles { f -> f.isFile && f.name != INDEX_FILE && kifuFileNameRegex.matches(f.name) }
            ?.filter { it.nameWithoutExtension !in knownIds }
            ?: emptyList()
        if (orphanFiles.isEmpty()) return entries

        val recovered = orphanFiles.mapNotNull { f ->
            try {
                val r = KifuJson.fromJson(JSONObject(f.readText()))
                val id = f.nameWithoutExtension
                val savedAt = id.toLongOrNull() ?: f.lastModified()
                KifuHistoryEntry(
                    id = id, blackName = r.blackName, whiteName = r.whiteName, gameResult = mainLineResult(r.root),
                    savedAt = savedAt, moveCount = countMainLineMoves(r.root),
                    gameDate = r.gameDate, displayDate = computeDisplayDate(r.gameDate, savedAt)
                )
            } catch (e: Exception) { null }
        }
        if (recovered.isEmpty()) return entries

        val merged = (entries + recovered).sortedByDescending { it.savedAt }
        saveIndex(context, merged)
        return merged
    }

    private fun saveIndex(context: Context, entries: List<KifuHistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("blackName", e.blackName)
                put("whiteName", e.whiteName)
                put("gameResult", e.gameResult)
                put("savedAt", e.savedAt)
                put("moveCount", e.moveCount)
                e.gameDate?.let { put("gameDate", it) }
                e.deletedAt?.let { put("deletedAt", it) }
            })
        }
        writeTextAtomic(indexFile(context), arr.toString())
    }

    /** 新しく履歴に登録する棋譜の id。保存日時を兼ねる */
    fun newId(): String = System.currentTimeMillis().toString()

    /**
     * 棋譜を履歴に保存する。同じ id があれば内容を置き換えて一覧の先頭へ移し、無ければ新しく登録する。
     * 棋譜の木は呼び出し側 (メインスレッド) で書き換わるので、JSON と手数は呼び出し側で作って渡す
     */
    fun saveOrUpdateAsync(
        context: Context, id: String, treeJson: String, blackName: String, whiteName: String,
        gameResult: String, gameDate: String?, moveCount: Int,
    ) {
        val appContext = context.applicationContext
        writer.execute {
            try {
                saveOrUpdate(appContext, id, treeJson, blackName, whiteName, gameResult, gameDate, moveCount)
            } catch (_: Exception) {}
        }
    }

    private fun saveOrUpdate(
        context: Context, id: String, treeJson: String, blackName: String, whiteName: String,
        gameResult: String, gameDate: String?, moveCount: Int,
    ) = synchronized(lock) {
        // 先に index を読み込んでおく。書き込み後に loadIndex すると、まだ index に登録されていない
        // このファイルを reconcileWithOrphanFiles が孤児ファイルとみなし、重複エントリを作ってしまう
        val entries = loadIndex(context).toMutableList()
        writeTextAtomic(kifuFile(context, id), treeJson)

        val idx = entries.indexOfFirst { it.id == id }
        val existing = if (idx >= 0) entries.removeAt(idx) else null
        val savedAt = existing?.savedAt ?: id.toLongOrNull() ?: System.currentTimeMillis()
        entries.add(0, KifuHistoryEntry(
            id = id, blackName = blackName, whiteName = whiteName,
            gameResult = gameResult, savedAt = savedAt,
            moveCount = moveCount,
            gameDate = gameDate,
            displayDate = computeDisplayDate(gameDate, savedAt),
            deletedAt = existing?.deletedAt
        ))

        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).forEach { kifuFile(context, it.id).delete() }
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        }
        saveIndex(context, entries)
    }

    /** ゴミ箱に入れる (deleted = true) / ゴミ箱から戻す (false) */
    fun setDeleted(context: Context, id: String, deleted: Boolean) = setDeleted(context, setOf(id), deleted)

    /** まとめてゴミ箱に入れる / 戻す (検索した棋譜の一括削除)。index の読み書きは1回で済ませる */
    fun setDeleted(context: Context, ids: Set<String>, deleted: Boolean): Unit = synchronized(lock) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed = false
        val entries = loadIndex(context).map { e ->
            if (e.id in ids && (e.deletedAt != null) != deleted) {
                changed = true
                e.copy(deletedAt = if (deleted) now else null)
            } else e
        }
        if (changed) saveIndex(context, entries)
    }

    fun bumpToTop(context: Context, id: String): Unit = synchronized(lock) {
        val entries = loadIndex(context).toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx <= 0) return
        entries.add(0, entries.removeAt(idx))
        saveIndex(context, entries)
    }

    fun loadKifu(context: Context, id: String): KifuRecord? {
        return try {
            val file = kifuFile(context, id)
            if (!file.exists()) null else KifuJson.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) { null }
    }

    fun countMainLineMoves(root: GoNode): Int {
        var count = 0; var node = root
        while (true) { node = node.children.firstOrNull { !it.isPvBranch } ?: break; count++ }
        return count
    }

    /** 本譜の最後に付いている結果 (無ければ "") */
    fun mainLineResult(root: GoNode): String {
        var node = root
        var result = node.result?.code
        while (true) {
            node = node.children.firstOrNull { !it.isPvBranch } ?: break
            node.result?.let { result = it.code }
        }
        return result ?: ""
    }

    // 過去の棋譜すべてを、対局ごとに1つの SGF ファイルとして ZIP へ書き出す。
    // 変化図 (待ったして打ち直した分岐) は残り、読み筋の分岐と形勢は残らない
    fun exportAllToUri(context: Context, uri: Uri): Int {
        var count = 0
        context.contentResolver.openOutputStream(uri)?.let { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                loadIndex(context).filter { it.deletedAt == null }.forEach { entry ->
                    val r = loadKifu(context, entry.id) ?: return@forEach
                    zip.putNextEntry(ZipEntry("${entry.id}.sgf"))
                    zip.write(Sgf.write(r).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /**
     * 取り込んだ結果。imported: 追加した数、skipped: 読めなかった数、
     * overflow: 「過去の棋譜」が上限 (MAX_ENTRIES 件) に達したため追加しなかった数
     */
    data class KifuImportResult(val imported: Int, val skipped: Int, val overflow: Int = 0)

    /**
     * SGF ファイル・圧縮ファイル (ZIP, tar, .tar.gz, .tar.bz2 など) を読み込み、中の SGF を過去の棋譜として追加する。
     * exportAllToUri で書き出した ZIP も、他のソフトで作った SGF をまとめたものも読める。
     * 複数のファイルをまとめて渡せる。onProgress には、これまでに読んだ SGF の数を渡す
     */
    fun importFromUris(context: Context, uris: List<Uri>, onProgress: (Int) -> Unit = {}): KifuImportResult = synchronized(lock) {
        val session = ImportSession(context, onProgress)
        for (uri in uris) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    KifuSources.forEachSgf(input, displayName(context, uri) ?: "", session::add)
                }
            } catch (e: Exception) {
                // 途中で壊れていても、それまでに読めた分は残す
                session.skipped++
            }
        }
        session.finish()
    }

    /** フォルダ (treeUri) の中の SGF と圧縮ファイルを、下のフォルダまですべて読み込んで過去の棋譜として追加する */
    fun importFromTree(context: Context, treeUri: Uri, onProgress: (Int) -> Unit = {}): KifuImportResult = synchronized(lock) {
        val session = ImportSession(context, onProgress)
        try {
            KifuSources.forEachSgfInTree(context, treeUri, session::add) { session.skipped++ }
        } catch (e: Exception) {
            session.skipped++
        }
        session.finish()
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    /**
     * 1回の取り込み。1ファイルごとに独立して解析するため、一部が壊れていても他の対局は読み込める。
     * 棋譜は読むたびにファイルへ書き (途中で終了しても reconcileWithOrphanFiles で戻る)、index は最後に1回だけ書く
     */
    private class ImportSession(private val context: Context, private val onProgress: (Int) -> Unit) {
        private val entries = loadIndex(context).toMutableList()
        private val knownIds = entries.mapTo(HashSet()) { it.id }
        private val startedAt = System.currentTimeMillis()
        private var seq = 0
        var imported = 0
        var skipped = 0
        private var overflow = 0

        fun add(name: String, bytes: ByteArray) {
            try {
                if (entries.size >= MAX_ENTRIES) { overflow++; return }
                val loaded = SgfParser.parse(SgfParser.decode(bytes))
                if (loaded.root.children.isEmpty()) throw IllegalArgumentException("no moves")

                // このアプリで書き出した棋譜はファイル名が id (保存した時刻のミリ秒)。
                // 他のソフトの棋譜 (ファイル名は対局番号など) は、対局日 (DT) を保存日時とみなし、一覧で日付順に並ぶようにする
                val fileId = File(name).nameWithoutExtension
                    .takeIf { it.length >= 13 && it.all(Char::isDigit) && it !in knownIds }
                seq++
                var savedAt = fileId?.toLong() ?: parseGameDate(loaded.date)?.plus(seq) ?: (startedAt - seq)
                // 対局中の新しい棋譜の id (今の時刻) とぶつからないよう、未来の時刻にはしない
                if (fileId == null && savedAt >= startedAt) savedAt = startedAt - seq
                while (savedAt.toString() in knownIds) savedAt--
                val id = savedAt.toString()

                val record = KifuRecord(
                    loaded.root, loaded.komi ?: Rules.JAPANESE.defaultKomi(loaded.root.game.size), loaded.rules ?: Rules.JAPANESE,
                    loaded.blackName ?: "", loaded.whiteName ?: "", loaded.date, loaded.timeSettings,
                )
                writeTextAtomic(kifuFile(context, id), KifuJson.toJson(record).toString())
                knownIds.add(id)
                entries.add(KifuHistoryEntry(
                    id = id,
                    blackName = record.blackName,
                    whiteName = record.whiteName,
                    gameResult = loaded.result?.trim() ?: "",
                    savedAt = savedAt,
                    moveCount = countMainLineMoves(record.root),
                    gameDate = loaded.date,
                    displayDate = computeDisplayDate(loaded.date, savedAt)
                ))
                imported++
            } catch (e: Exception) {
                skipped++
            } finally {
                onProgress(imported + skipped + overflow)
            }
        }

        fun finish(): KifuImportResult {
            if (imported > 0) saveIndex(context, entries.sortedByDescending { it.savedAt })
            return KifuImportResult(imported, skipped, overflow)
        }

        /** SGF の DT (例: "2026-09-01", "2026-09-01,02") の最初の日の 0 時 */
        private fun parseGameDate(date: String?): Long? {
            val d = date?.trim()?.take(10) ?: return null
            if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) return null
            return try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(d)?.time
            } catch (e: Exception) { null }
        }
    }

    fun formatDate(timestamp: Long): String = dateFmt.format(Date(timestamp))
}
