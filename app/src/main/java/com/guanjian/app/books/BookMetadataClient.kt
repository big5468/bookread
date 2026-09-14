package com.guanjian.app.books

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 应用自己的稳定模型，网页结构变化不会扩散到界面和数据库。 */
data class BookMetadata(
    val sourceKey: String,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val publishedDate: String = "",
    val isbn: String = "",
    val totalPages: Int? = null,
    val description: String = "",
    val coverUrl: String? = null
)

sealed interface BookMetadataResult {
    data class Success(val books: List<BookMetadata>) : BookMetadataResult
    data class Failure(val message: String) : BookMetadataResult
}

private data class DoubanSearchPayload(val items: List<DoubanSearchItem>?)
private data class DoubanSearchItem(
    val id: Long?,
    val title: String?,
    val url: String?,
    val cover_url: String?
)

/**
 * 从国内可访问的豆瓣读书公开页面读取出版信息。
 * 请求只包含用户主动输入的 ISBN 或书名；个人笔记、位置、状态和数据库 ID 不会离开本机。
 */
class BookMetadataClient internal constructor(
    searchBaseUrl: String,
    detailBaseUrl: String = searchBaseUrl,
    private val allowLocalHttpForTests: Boolean = false,
    timeoutMillis: Long = 20_000
) {
    constructor() : this("https://search.douban.com/", "https://book.douban.com/")

    private val searchBase = checkedBaseUrl(searchBaseUrl)
    private val detailBase = checkedBaseUrl(detailBaseUrl)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
            )
        }
        .build()

    suspend fun search(input: String): BookMetadataResult {
        val raw = input.trim()
        if (raw.isBlank()) return BookMetadataResult.Failure("请输入 ISBN 或书名。")
        val normalized = normalizeIsbn(raw)
        val query = normalized.takeIf { it.length == 10 || it.length == 13 } ?: raw
        return try {
            val searchUrl = searchBase.newBuilder()
                .addPathSegments("book/subject_search")
                .addQueryParameter("search_text", query)
                .addQueryParameter("cat", "1001")
                .build()
            val html = getText(searchUrl) ?: return BookMetadataResult.Failure("豆瓣读书暂时不可用，请稍后重试或继续手工填写。")
            val json = SEARCH_DATA.find(html)?.groupValues?.get(1)
                ?: return BookMetadataResult.Failure("没有读懂豆瓣读书返回的搜索结果，请稍后重试。")
            val candidates = gson.fromJson(json, DoubanSearchPayload::class.java).items.orEmpty()
                .filter { it.id != null && !it.title.isNullOrBlank() }
                .distinctBy { it.id }
                .take(MAX_CANDIDATES)
            val books = coroutineScope {
                candidates.map { item -> async(Dispatchers.IO) { loadDetail(item) } }.awaitAll()
            }.filterNotNull()
            BookMetadataResult.Success(books)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            BookMetadataResult.Failure("无法连接豆瓣读书，请检查网络；手工填写仍可继续使用。")
        } catch (_: Exception) {
            BookMetadataResult.Failure("没有读懂豆瓣读书返回的书籍信息，请换个关键词重试。")
        }
    }

    /** 下载豆瓣封面原始字节；只接受 HTTPS 豆瓣图片域名。 */
    suspend fun downloadCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        if (!isAllowedCoverUrl(parsed)) return@withContext null
        try {
            http.newCall(
                Request.Builder().url(parsed).header("Referer", "https://book.douban.com/").build()
            ).execute().use { response ->
                val body = response.body ?: return@use null
                if (!response.isSuccessful || body.contentLength() > MAX_COVER_BYTES) return@use null
                body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun loadDetail(item: DoubanSearchItem): BookMetadata? {
        val id = item.id ?: return null
        val supplied = item.url?.toHttpUrlOrNull()
        val detailUrl = supplied?.takeIf(::isAllowedDetailUrl)
            ?: detailBase.newBuilder().addPathSegments("subject/$id/").build()
        val html = getText(detailUrl)
        if (html == null) return BookMetadata(
            sourceKey = "douban:$id",
            title = item.title.orEmpty().trim(),
            coverUrl = item.cover_url?.takeIf { it.toHttpUrlOrNull()?.let(::isAllowedCoverUrl) == true }
        )
        val info = INFO_BLOCK.find(html)?.groupValues?.get(1).orEmpty()
        val title = ITEM_REVIEWED.find(html)?.groupValues?.get(1)?.cleanHtml()
            .orEmpty().ifBlank { item.title.orEmpty().trim() }
        if (title.isBlank()) return null
        val author = labeled(info, "作者").replace(Regex("\\s*/\\s*"), "、")
        val isbn = normalizeIsbn(labeled(info, "ISBN")).takeIf { it.length == 10 || it.length == 13 }.orEmpty()
        val description = INTRO.find(html)?.groupValues?.get(1)?.cleanHtml().orEmpty()
        val cover = COVER_LINK.find(html)?.groupValues?.get(1)
            ?.cleanHtml()?.takeIf { it.toHttpUrlOrNull()?.let(::isAllowedCoverUrl) == true }
            ?: item.cover_url?.takeIf { it.toHttpUrlOrNull()?.let(::isAllowedCoverUrl) == true }
        return BookMetadata(
            sourceKey = "douban:$id",
            title = title,
            author = author,
            publisher = labeled(info, "出版社"),
            publishedDate = labeled(info, "出版年"),
            isbn = isbn,
            totalPages = labeled(info, "页数").filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 },
            description = description,
            coverUrl = cover
        )
    }

    private suspend fun getText(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Referer", "https://book.douban.com/").build())
            .execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }

    private fun labeled(info: String, label: String): String {
        val escaped = Regex.escape(label)
        return Regex("<span[^>]*>\\s*$escaped\\s*:?[\\s]*</span>\\s*:?\\s*(.*?)(?:<br\\s*/?>|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(info)?.groupValues?.get(1)?.cleanHtml().orEmpty()
    }

    private fun String.cleanHtml(): String = replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    private fun normalizeIsbn(value: String): String = value.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()

    private fun checkedBaseUrl(value: String): HttpUrl {
        val parsed = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("书籍信息服务地址无效")
        val local = allowLocalHttpForTests && parsed.host in LOCAL_HOSTS
        require(parsed.isHttps || local) { "书籍信息服务必须使用 HTTPS" }
        return (value.trimEnd('/') + "/").toHttpUrlOrNull()!!
    }

    private fun isAllowedDetailUrl(url: HttpUrl): Boolean {
        val local = allowLocalHttpForTests && url.host in LOCAL_HOSTS
        return local || (url.isHttps && url.host == "book.douban.com")
    }

    private fun isAllowedCoverUrl(url: HttpUrl): Boolean {
        val local = allowLocalHttpForTests && url.host in LOCAL_HOSTS
        return local || (url.isHttps && (url.host == "doubanio.com" || url.host.endsWith(".doubanio.com")))
    }

    private companion object {
        const val MAX_CANDIDATES = 5
        const val MAX_COVER_BYTES = 10L * 1024 * 1024
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36 GuanJian/1.0"
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        val SEARCH_DATA = Regex("window\\.__DATA__\\s*=\\s*(\\{.*?\\})\\s*;", RegexOption.DOT_MATCHES_ALL)
        val INFO_BLOCK = Regex("<div[^>]+id=[\"']info[\"'][^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val ITEM_REVIEWED = Regex("<span[^>]+property=[\"']v:itemreviewed[\"'][^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val INTRO = Regex("<div[^>]+class=[\"'][^\"']*intro[^\"']*[\"'][^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val COVER_LINK = Regex("<a[^>]+class=[\"'][^\"']*nbg[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
