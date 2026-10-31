package com.tksoft.goai

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * KataGo (cpp/KataGo, native-lib-katago.cpp) を同じプロセス内で動かし、GTP の1行ずつをやり取りする。
 * 出力はエンジンのスレッドから onOutputReceived に渡される (メインスレッドではない)
 */
class KataGoEngine {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile var onOutputReceived: ((String) -> Unit)? = null
    /** nativeStart から戻った (quit またはエラーで終了した) とき */
    @Volatile var onExited: ((Int) -> Unit)? = null

    companion object {
        // プロセス内で動作中のインスタンスを追跡し、重複起動を防ぐ
        @Volatile private var activeInstance: KataGoEngine? = null

        init {
            System.loadLibrary("katago")
        }
    }

    private external fun nativeStart(modelPath: String, configPath: String): Int
    private external fun nativeSendCommand(command: String)
    private external fun nativeStop()
    private external fun nativeSetWorkDir(path: String)

    fun start(workDir: String, modelPath: String, configPath: String) {
        // 別インスタンスが既に動いている場合（Activity再生成など）は先に停止
        val prev = activeInstance
        if (prev != null && prev !== this) {
            prev.nativeStop()
            activeInstance = null
        }
        activeInstance = this
        executor.execute {
            var result = -1
            try {
                nativeSetWorkDir(workDir)
                result = nativeStart(modelPath, configPath)
            } catch (e: Exception) {
                onOutputReceived?.invoke("? Error: " + e.message)
            } finally {
                if (activeInstance === this) activeInstance = null
                onExited?.invoke(result)
            }
        }
    }

    private val commandQueue = LinkedBlockingQueue<String>()
    // stop() で入れてコマンド送信スレッドを終わらせる印（参照で比べるので、他のコマンド文字列とは一致しない）
    private val quitCommand = String(charArrayOf())

    init {
        Thread({
            while (true) {
                val command = commandQueue.take()
                if (command === quitCommand) break
                try {
                    nativeSendCommand(command)
                } catch (_: Exception) {}
            }
        }, "GTP-Command-Thread").also { it.isDaemon = true }.start()
    }

    fun sendCommand(command: String) {
        commandQueue.put(command)
    }

    fun stop() {
        commandQueue.put(quitCommand)
        nativeStop()
        if (activeInstance === this) activeInstance = null
    }

    /** native から呼ばれる */
    @Suppress("unused")
    fun onOutput(line: String) {
        onOutputReceived?.invoke(line)
    }
}

/** KataGo に渡すファイル (モデルと設定) を filesDir に用意する */
/**
 * 使うネット (重み)。9路盤は 9路専用に追加学習された強いネット、13路・19路は
 * スマホの CPU でも速く打てる小さなネットにする。どちらもビルド時に assets へダウンロードする (app/build.gradle.kts)。
 * assets の .gz は aapt が展開してしまうので .dat を付けて置き、コピー先で元の名前に戻す
 * (KataGo は拡張子 .gz を見て gzip として読む)
 */
enum class KataGoModel(val asset: String, val file: String, val displayName: String) {
    NET_9X9("katago_model_9x9.bin.gz.dat", "katago_model_9x9.bin.gz", "kata9x9-b18c384nbt"),
    NET_GENERAL("katago_model_b18.bin.gz.dat", "katago_model_b18.bin.gz", "kata1-b18c384nbt");

    companion object {
        fun forBoardSize(size: Int) = if (size == 9) NET_9X9 else NET_GENERAL
    }
}

object KataGoFiles {
    private const val CONFIG_NAME = "gtp.cfg"

    /** assets のモデルを filesDir にコピーする (サイズが同じならコピー済みとみなす)。IO スレッドで呼ぶこと */
    fun prepareModel(context: Context, model: KataGoModel): File {
        val target = File(context.filesDir, model.file)
        // 以前の版がコピーしたテキスト形式のネット (今はバイナリ形式に変換して入れている)
        // 以前の版のネット (13路・19路用の g170e-b10c128) が残っていれば消す
        File(context.filesDir, "katago_model_b10.txt.gz").delete()
        File(context.filesDir, "katago_model_b10.bin.gz").delete()
        val assetSize = context.assets.openFd(model.asset).use { it.length }
        if (target.exists() && target.length() == assetSize) return target
        val tmp = File(context.filesDir, "${model.file}.part")
        context.assets.open(model.asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) throw IllegalStateException("Failed to move model file")
        return target
    }

    /**
     * GTP 用の設定ファイル。探索の強さ (maxVisits) やルールは対局ごとに GTP コマンドで変えるので、ここでは既定値だけ
     */
    fun writeConfig(context: Context): File {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val text = """
            # GoAI が生成する KataGo の設定 (KataGo/cpp/configs/gtp_example.cfg を元にした最小限のもの)
            logToStderr = false
            logAllGTPCommunication = false
            logSearchInfo = false
            startupPrintMessageToStderr = true
            # 解析結果の勝率・目数は常に黒番から見た値にする
            reportAnalysisWinratesAs = BLACK
            rules = japanese
            allowResignation = true
            resignThreshold = -0.95
            resignConsecTurns = 3
            resignMinScoreDifference = 10
            maxVisits = 200
            ponderingEnabled = false
            lagBuffer = 0.5
            numSearchThreads = $threads
            nnMaxBatchSize = $threads
            numEigenThreadsPerModel = $threads
            nnCacheSizePowerOfTwo = 18
            nnMutexPoolSizePowerOfTwo = 14
        """.trimIndent()
        val file = File(context.filesDir, CONFIG_NAME)
        file.writeText(text + "\n")
        return file
    }
}
