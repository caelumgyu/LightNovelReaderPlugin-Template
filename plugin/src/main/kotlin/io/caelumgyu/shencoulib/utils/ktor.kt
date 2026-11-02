package io.caelumgyu.shencoulib.utils

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
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
): Result<HttpResponse, Throwable> = try {
    Ok(this.get(url, block))
} catch (t: Throwable) {
    Err(t)
}

/** 神凑的页面多为 GBK：按响应声明解码，否则按 meta 声明退回 GBK。 */
suspend fun HttpResponse.bodyAsDecodedText(): String {
    val bytes = this.bodyAsBytes()
    val declared = this.contentType()?.charset()
    if (declared != null && !declared.name().equals("utf-8", ignoreCase = true)) {
        return try { String(bytes, Charset.forName(declared.name())) } catch (t: Throwable) { String(bytes, Charsets.UTF_8) }
    }
    val head = String(bytes, 0, minOf(bytes.size, 600), Charsets.ISO_8859_1)
    if (Regex("charset=[\"']?gbk", RegexOption.IGNORE_CASE).containsMatchIn(head)) {
        return try { String(bytes, Charset.forName("GBK")) } catch (t: Throwable) { String(bytes, Charsets.UTF_8) }
    }
    return String(bytes, Charsets.UTF_8)
}
