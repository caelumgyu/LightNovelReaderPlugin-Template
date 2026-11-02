package io.caelumgyu.shencoulib.utils

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import java.nio.charset.Charset

suspend fun HttpClient.safeGet(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {}
): Result<HttpResponse, Throwable> = runCatching {
    this.get(url, block)
}

/** 神凑的页面是 GBK 编码，需要按响应声明或 GBK 解码。 */
suspend fun HttpResponse.bodyAsDecodedText(): String {
    val bytes = bodyAsChannel().readRemaining().readBytes()
        val declared = contentType()?.charset()
        if (declared != null && declared.name().equals("utf-8", true)) return String(bytes, Charsets.UTF_8)
        return runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
}
