package io.caelumgyu.shencoulib.source

import android.net.Uri
import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.coroutines.coroutineBinding
import io.caelumgyu.shencoulib.utils.bodyAsDecodedText
import io.caelumgyu.shencoulib.utils.selectSingleXPath
import io.caelumgyu.shencoulib.utils.safeGet
import io.caelumgyu.shencoulib.utils.textByLabel
import io.caelumgyu.shencoulib.utils.toId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.ANDROID
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.mapAsWebRequestError
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.DurationUnit

@Suppress("unused")
@WebDataSource(
    name = "神凑轻小说",
    provider = "m.wowenku.com"
)
class ShencouDataSource : WebBookDataSource {
    companion object {
        const val TAG = "ShencouDataSource"
        const val HOST = "https://m.wowenku.com"
    }

    private val offlineState = MutableStateFlow(true)

    val ktorClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        install(HttpCookies)
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            retryOnExceptionIf { _, cause -> cause is IOException }
        }
        install(HttpTimeout)
        install(Logging) {
            logger = Logger.ANDROID
            level = LogLevel.INFO
        }
    }

    override fun onLoad() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                offlineState.value = isOffLine()
                kotlinx.coroutines.delay(if (offLine) 5_000L else 60_000L)
            }
        }
    }

    override val permits = 2
    override val cache = Cache(timeout = Duration.parse("1h").toMillis().toInt())
    override val id = "shencou_lib".toId()
    override val offLine get() = offlineState.value
    override val isOffLineFlow: StateFlow<Boolean> = offlineState

    override suspend fun isOffLine(): Boolean =
        runCatching { !ktorClient.get(HOST).status.isSuccess() }.getOrDefault(true)

    private suspend fun document(url: String, why: String): Document? =
        ktorClient.safeGet(url)
            .mapAsWebRequestError("网络请求失败", "请求$why时失败($url)")
            .let {
                when (it) {
                    is Ok -> if (it.value.status.isSuccess()) it.value else null else null
                }
            }?.let { Jsoup.parse(it.bodyAsDecodedText()) }

    override suspend fun getBookInformation(id: String) = coroutineBinding {
        val doc = document("$HOST/info.php?aid=$id", "书本详情")
            ?: Err(WebRequestError("解析失败", "无法读取书本详情(id=$id)")).bind()

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore("-").trim()
            ?: id
        val author = doc.textByLabel("作者", "小说作者") ?: ""
        val description = doc.selectFirst("#intro, .intro, div.intro, article")?.text()
            ?: doc.textByLabel("简介", "内容简介") ?: ""
        val coverUri = doc.selectFirst("#bookimg img, img[src*=\"cover\"], img[src*=\"files\"]")
            ?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val category = doc.textByLabel("小说分类", "分类") ?: ""
        val status = doc.textByLabel("连载状态", "状态") ?: ""
        val wordCount = doc.textByLabel("小说字数", "字数")
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
        val updated = doc.textByLabel("更新时间")

        return@coroutineBinding BookInformation(
            id = id,
            title = title,
            subtitle = category,
            coverUri = coverUri,
            author = author,
            description = description,
            tags = listOfNotNull(category.takeIf { it.isNotBlank() }),
            publishingHouse = status,
            wordCount = WordCount(wordCount),
            lastUpdated = updated?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?: LocalDateTime.now(),
            isComplete = status.contains("完结") || status.contains("完本")
        )
    }

    private fun chapterLinks(doc: Document): List<Pair<String, String>> =
        doc.select("a[href*=\"chapter.php\"], a[href*=\"/files/\"]")
            .mapNotNull { a ->
                val href = a.attr("href").removePrefix("/")
                val title = a.text().trim()
                if (href.isBlank() || title.isBlank() || !href.contains("aid=")) null
                else href to title
            }
            .distinctBy { it.first }

    override suspend fun getBookVolumes(id: String) = coroutineBinding {
        val fromInfo = document("$HOST/info.php?aid=$id", "书本目录")?.let { chapterLinks(it) } ?: emptyList()
        val chapters = if (fromInfo.isNotEmpty()) fromInfo
        else document("$HOST/list.php?aid=$id", "书本目录")?.let { chapterLinks(it) } ?: emptyList()

        if (chapters.isEmpty()) Err(WebRequestError("解析失败", "没有解析到章节目录(id=$id)")).bind()

        return@coroutineBinding BookVolumes(
            bookId = id,
            volumes = listOf(
                Volume(
                    volumeId = id + "_0",
                    volumeTitle = "正文".local(),
                    chapters = chapters.map { (href, title) ->
                        ChapterInformation(id = href, title = title)
                    }
                )
            )
        )
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String) = coroutineBinding {
        val doc = document("$HOST/${chapterId.removePrefix("/")}", "章节内容")
            ?: Err(WebRequestError("解析失败", "无法读取章节(chapter=$chapterId)")).bind()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val node = doc.selectFirst("#novelcontent, #novelbody, .content, article")
            ?: Err(WebRequestError("解析失败", "没有解析到正文(chapter=$chapterId)")).bind()

        val builder = ContentBuilder()
        node.html().split(Regex("(?i)<br\\s*/?>|</p>|</div>")).forEach { raw ->
            val text = Jsoup.parse(raw).text().trim()
            if (text.isNotEmpty()) builder.simpleText(text)
        }

        val prev = doc.select("a").firstOrNull { it.text().contains("上一章") }
            ?.attr("href")?.removePrefix("/")?.takeIf { it.contains("aid=") } ?: ""
        val next = doc.select("a").firstOrNull { it.text().contains("下一页") || it.text().contains("下一章") }
            ?.attr("href")?.removePrefix("/")?.takeIf { it.contains("aid=") } ?: ""

        return@coroutineBinding ChapterContent(
            id = chapterId,
            title = title,
            content = builder.build(),
            prevChapter = prev,
            nextChapter = next
        )
    }

    override val searchProvider: SearchProvider = ShencouSearchProvider(this)
    override val explorePageProvider: ExplorePageProvider = ShencouExplorePageProvider()
}
