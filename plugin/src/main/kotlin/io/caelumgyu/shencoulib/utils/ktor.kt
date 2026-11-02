package io.caelumgyu.shencoulib.utils

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.charset
import io.ktor.http.contentType
import java.nio.charset.Charset

suspend fun HttpClient.safeGet(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): Result<HttpResponse, Throwable> = runCatching {
    this.get(url, block)
}

/** 神凑的页面多为 GBK 编码，按响应声明的字符集解码，否则退回 GBK。 */
suspend fun HttpResponse.bodyAsDecodedText(): String {
    val bytes = this.bodyAsBytes()
    val declared = this.contentType()?.charset()
    if (declared != null && !declared.name().equals("utf-8", ignoreCase = true)) {
        return runCatching { String(bytes, Charset.forName(declared.name())) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }
    val text = String(bytes, Charsets.UTF_8)
    if (Regex("charset=[\"']?gbk", RegexOption.IGNORE_CASE).containsMatchIn(text.take(600))) {
        return String(bytes, Charset.forName("GBK"))
    }
    return text
}
