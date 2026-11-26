package com.tksoft.goai

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 共有された URL から棋譜を取ってくる。
 * ブラウザでページやダウンロードのリンクを「共有」すると URL だけが来るので、その URL を開いて SGF を探す
 * (note の添付ファイルのように、URL が SGF ファイルそのものを返すことが多い)
 */
object SgfDownloader {
    private const val MAX_BYTES = 5L * 1024 * 1024

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val urlRegex = Regex("https?://[^\\s\"'<>]+")

    /** text の中の最初の http(s) の URL。無ければ null */
    fun findUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '」', '　') }

    /** url の中身を SGF として読む。SGF でなければ (や読めなければ) null。IO スレッドで呼ぶ */
    fun download(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "GoAI").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.source().let { src ->
                src.request(MAX_BYTES + 1)
                if (src.buffer.size > MAX_BYTES) return null
                src.buffer.readByteArray()
            }
            SgfParser.decode(bytes).takeIf { SgfParser.looksLikeSgf(it) }
        }
    }.getOrNull()
}
