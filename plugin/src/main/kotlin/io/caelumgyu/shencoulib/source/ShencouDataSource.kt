package io.caelumgyu.shencoulib.source

import android.net.Uri
import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.coroutines.coroutineBinding
import io.caelumgyu.shencoulib.utils.bodyAsDecodedText
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.mapAsWebRequestError
import io.nightfish.lightnovelreader.api.text.ParagraphNode
import io.nightfish.lightnovelreader.api.text.TextNode
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.ConnectException
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.io.EOFException

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

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val offlineMutaStateFlow = MutableStateFlow(true)

    val ktorClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        install(HttpCookies)
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
            retryIf { _, response -> !response.status.isSuccess() }
            retryOnExceptionIf { _, cause -> cause is EOFException || cause is ConnectException }
        }
        install(HttpTimeout)
        install(Logging) {
            logger = Logger.ANDROID
            level = LogLevel.INFO
        }
    }

    override fun onLoad() {
        coroutineScope.launch {
            while (true) {
                offlineMutaStateFlow.value = isOffLine()
                if (offLine) delay(1.seconds()) else delay(40.seconds())
            }
        }
    }

    private fun Int.seconds() = kotlin.time.Duration.parse("${this}s")

    override val permits = 2
    override val cache = Cache(
        maxCountEachType = 30,
        timeout = Duration.parse("1h").toInt(DurationUnit.MILLISECONDS)
    )
    override val id = "shencou_lib".toId()
    override val offLine get() = offlineMutaStateFlow.value
    override val isOffLineFlow: StateFlow<Boolean> = offlineMutaStateFlow

    override suspend fun isOffLine(): Boolean =
        runCatching { !ktorClient.get(HOST).status.isSuccess() }.getOrDefault(true)

    private suspend fun getDocument(url: String): Document? = try {
        val res = ktorClient.get(url)
        if (!res.status.isSuccess()) null else Jsoup.parse(res.bodyAsDecodedText())
    } catch (t: Throwable) {
        Log.w(TAG, "请求失败: $url", t)
        null
    }

    override suspend fun getBookInformation(id: String) = coroutineBinding {
        val doc = getDocument("$HOST/info.php?aid=$id")
            ?: Err(WebRequestError("解析失败", "无法读取书本详情(id=$id)")).bind()

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore("-").trim()
        val author = doc.textByLabel("作者", "小说作者") ?: ""
        val description = doc.selectFirst("#intro, .intro, div.intro")?.text()
            ?: doc.textByLabel("简介", "内容简介") ?: ""
        val category = doc.textByLabel("小说分类", "分类") ?: ""
        val status = doc.textByLabel("连载状态", "状态") ?: ""
        val wordCount = (doc.textByLabel("小说字数", "字数") ?: "")
            .replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

        return@coroutineBinding BookInformation(
            id = id,
            title = title.ifBlank { id },
            subtitle = category,
            coverUri = doc.selectFirst("#bookimg img, img[src*=\"cover\"]")
                ?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Uri.EMPTY,
            author = author,
            description = description,
            tags = listOfNotNull(category.takeIf { it.isNotBlank() }),
            publishingHouse = status,
            wordCount = WordCount(wordCount),
            lastUpdated = LocalDateTime.now(),
            isComplete = status.contains("完结") || status.contains("完本")
        )
    }

    private fun chapterLinks(doc: Document): List<Pair<String, String>> =
        doc.select("a[href*=\"chapter.php\"], a[href*=\"aid=\"]")
            .mapNotNull { a ->
                val href = a.attr("href").removePrefix("/")
                val text = a.text().trim()
                if (href.isBlank() || text.isBlank() || !href.contains("aid=")) null else href to text
            }
            .distinctBy { it.first }

    override suspend fun getBookVolumes(id: String) = coroutineBinding {
        val fromInfo = getDocument("$HOST/info.php?aid=$id")?.let { chapterLinks(it) } ?: emptyList()
        val chapters = if (fromInfo.isNotEmpty()) fromInfo
        else getDocument("$HOST/list.php?aid=$id")?.let { chapterLinks(it) } ?: emptyList()

        if (chapters.isEmpty()) Err(WebRequestError("解析失败", "没有解析到章节目录(id=$id)")).bind()

        return@coroutineBinding BookVolumes(
            bookId = id,
            volumes = listOf(
                Volume(
                    volumeId = id + "_0",
                    volumeTitle = "正文",
                    chapters = chapters.map { (href, title) ->
                        ChapterInformation(id = href, title = title)
                    }
                )
            )
        )
    }

    override suspend fun getChapterContent(chapterId: String, bookId: String) = coroutineBinding {
        val doc = getDocument("$HOST/${chapterId.removePrefix("/")}")
            ?: Err(WebRequestError("解析失败", "无法读取章节($chapterId)")).bind()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val node = doc.selectFirst("#novelcontent, #novelbody, .content, article")
            ?: Err(WebRequestError("解析失败", "没有解析到正文($chapterId)")).bind()

        val builder = ContentBuilder()
        node.html().split(Regex("(?i)<br\\s*/?>|</p>|</div>")).forEach { raw ->
            val text = Jsoup.parse(raw).text().trim()
            if (text.isNotEmpty()) {
                builder.component(
                    ParagraphComponentData(ParagraphNode(textNodes = listOf(TextNode(text))))
                )
            }
        }
        node.select("img[src]").forEach { img ->
            val src = img.attr("abs:src")
            if (src.isNotBlank()) builder.component(ImageComponentData(Uri.parse(src)))
        }

        val prev = doc.select("a").firstOrNull { it.text().contains("上一章") }
            ?.attr("href")?.removePrefix("/")?.takeIf { it.contains("aid=") }
        val next = doc.select("a").firstOrNull { it.text().contains("下一页") || it.text().contains("下一章") }
            ?.attr("href")?.removePrefix("/")?.takeIf { it.contains("aid=") }

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
