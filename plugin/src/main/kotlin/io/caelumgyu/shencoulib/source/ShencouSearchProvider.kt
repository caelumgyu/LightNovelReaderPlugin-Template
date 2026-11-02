package io.caelumgyu.shencoulib.source

import android.util.Log
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.caelumgyu.shencoulib.utils.bodyAsDecodedText
import io.caelumgyu.shencoulib.utils.safeGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup
import java.net.URLEncoder

class ShencouSearchProvider(
    private val dataSource: ShencouDataSource
) : SearchProvider {
    private val client get() = dataSource.ktorClient

    override val searchTypes: List<SearchType> = listOf(
        SearchType("keyword", "按书名或作者搜索".local(), "请输入书名或作者".local())
    )

    private fun candidates(keyword: String): List<String> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        return listOf(
            "${ShencouDataSource.HOST}/search.php?keyword=$q",
            "${ShencouDataSource.HOST}/search.php?q=$q",
            "${ShencouDataSource.HOST}/s.php?q=$q",
            "${ShencouDataSource.HOST}/modules/article/search.php?searchkey=$q"
        )
    }

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        var emitted = 0
        for (url in candidates(keyword)) {
            val response: HttpResponse = client.safeGet(url)
                .onErr { Log.w(ShencouDataSource.TAG, "search request failed: $url"); return@flow }
                .onOk { if (!it.status.isSuccess()) return@onOk }
                .component1() ?: continue

            val doc = Jsoup.parse(response.bodyAsDecodedText())
            val ids = doc.select("a[href*=\"info.php\"]")
                .mapNotNull { Regex("aid=(\\d+)").find(it.attr("href"))?.groupValues?.get(1) }
                .distinct()
            for (id in ids) {
                emit(SearchResult.MultipleBook(id))
                emitted++
            }
            if (emitted > 0) return@flow
        }
        if (emitted == 0) Log.i(ShencouDataSource.TAG, "神凑搜索没有结果: $keyword")
    }
}
