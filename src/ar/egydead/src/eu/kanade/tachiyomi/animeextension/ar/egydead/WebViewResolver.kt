package eu.kanade.tachiyomi.animeextension.ar.egydead

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewResolver(private val client: OkHttpClient, private val headers: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsInterface(private val latch: CountDownLatch) {
        @Volatile
        var result: String = ""

        @JavascriptInterface
        fun passPayload(payload: String) {
            result = payload
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(baseUrl: String, epUrl: String): String {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val webViewHeaders = headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }
        val jsi = JsInterface(latch)

        handler.post {
            val webview = WebView(context)
            webView = webview

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webview, true)

            // Initial cookie sync from OkHttp
            val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            for (cookie in cookies) {
                cookieManager.setCookie(baseUrl, cookie.toString())
            }
            cookieManager.flush()

            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                userAgentString = DEFAULT_UA
            }

            webview.addJavascriptInterface(jsi, "android")

            webview.webViewClient = object : WebViewClient() {
                private var isChallengeSolved = false

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (finishedUrl == null) return

                    // Detect if we are on a Cloudflare challenge page
                    view?.evaluateJavascript(
                        "(function() { " +
                            "  return (document.getElementById('challenge-running') != null || " +
                            "          document.querySelector('iframe[src*=\"cloudflare\"]') != null || " +
                            "          document.title.includes('Just a moment')); " +
                            "})()",
                    ) { isChallenge ->
                        if (isChallenge == "true") {
                            // Still on challenge page, let WebView handle it
                            return@evaluateJavascript
                        }

                        if (!isChallengeSolved) {
                            isChallengeSolved = true
                            // Challenge seems solved, now perform our POST request
                            view.postUrl(baseUrl + epUrl, POST_DATA)
                        } else {
                            // Reached destination after POST, extract HTML
                            view.evaluateJavascript("android.passPayload(document.documentElement.outerHTML)") {}
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (failingUrl == baseUrl + epUrl) latch.countDown()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true && request.url.toString() == baseUrl + epUrl) {
                        latch.countDown()
                    }
                }
            }

            // First load the URL normally (GET) to trigger/solve Cloudflare challenge
            webview.loadUrl(baseUrl, webViewHeaders)
        }

        try {
            // Increase timeout to allow for Cloudflare solving
            latch.await(45, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }
        return jsi.result
    }

    companion object {
        private val POST_DATA = "View=1".toByteArray()
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
