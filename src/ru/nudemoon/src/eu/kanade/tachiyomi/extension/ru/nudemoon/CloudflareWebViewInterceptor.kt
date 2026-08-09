package eu.kanade.tachiyomi.extension.ru.nudemoon

import keiyoushi.utils.WebViewSession
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * nude-moon.org runs behind a Cloudflare Turnstile challenge that plain OkHttp requests can
 * never pass on their own, no matter what cookies/headers are attached: Cloudflare re-scores
 * every request by its TLS/JS fingerprint, and OkHttp's isn't a real browser's. A real
 * WebView engine solves it fine (same engine Chrome uses), so on a blocked response this
 * silently re-fetches that one request through a hidden WebView instead, and hands back a
 * normal-looking [Response] so the rest of the source's OkHttp-based parsing is untouched.
 */
class CloudflareWebViewInterceptor(private val domain: String) : Interceptor {

    private val session = WebViewSession()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // A WebView can only rescue this by extracting page HTML, which is meaningless for a
        // page image - if Cloudflare ever blocks those too, let them fail normally instead of
        // handing the reader a page of markup instead of a picture.
        if (!request.url.host.endsWith(domain) || request.method != "GET" || looksLikeImage(request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        if (!isChallenge(response)) return response
        response.close()

        val html = try {
            runWebViewBlocking<String>(chain.call(), session = session, timeout = 45.seconds) {
                useOkHttpNetwork = false

                onReceivedError { req, error ->
                    if (req.isForMainFrame) reject(IllegalStateException("WebView load failed: ${error.description}"))
                }

                poll(interval = 500.milliseconds) {
                    evaluateJs("document.title") { titleJson ->
                        val title = runCatching { titleJson.parseAs<String>() }.getOrDefault("")
                        if (CHALLENGE_TITLE_REGEX.containsMatchIn(title)) return@evaluateJs

                        evaluateJs("document.documentElement.outerHTML") { htmlJson ->
                            val body = runCatching { htmlJson.parseAs<String>() }.getOrNull()
                            if (!body.isNullOrEmpty()) resolve(body)
                        }
                    }
                }

                loadUrl(
                    request.url.toString(),
                    headers = buildMap {
                        request.header("Referer")?.let { put("Referer", it) }
                    },
                )
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // runWebView's own failure modes (timeout, WebView render process death, ...)
            // aren't IOExceptions, so left alone they'd surface as an unhandled crash instead
            // of the normal "couldn't load" error the rest of the app expects from a source.
            throw IOException("Не удалось пройти проверку Cloudflare: ${e.message}", e)
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.header("cf-mitigated") == "challenge") return true
        if (response.code != 403 && response.code != 503) return false
        return CHALLENGE_TITLE_REGEX.containsMatchIn(response.peekBody(4096).string())
    }

    private fun looksLikeImage(path: String): Boolean = IMAGE_EXTENSION_REGEX.containsMatchIn(path)

    private companion object {
        val CHALLENGE_TITLE_REGEX = Regex("Just a moment|Attention Required|Checking your browser", RegexOption.IGNORE_CASE)
        val IMAGE_EXTENSION_REGEX = Regex("""\.(jpe?g|png|gif|webp|avif|bmp)$""", RegexOption.IGNORE_CASE)
    }
}
