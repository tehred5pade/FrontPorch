package com.example.frontporch.data.innertube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

data class YouTubeSessionTokens(
    val visitorData: String,
    val poToken: String
)

object PoTokenProvider {
    @Volatile
    private var cachedTokens: YouTubeSessionTokens? = null

    suspend fun getTokens(context: Context): YouTubeSessionTokens? {
        cachedTokens?.let { return it }

        val minted = withTimeoutOrNull(8_000L) {
            mintViaHiddenWebView(context.applicationContext)
        }
        if (minted != null) {
            cachedTokens = minted
        }
        return minted
    }

    fun invalidate() {
        cachedTokens = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun mintViaHiddenWebView(appContext: Context): YouTubeSessionTokens? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var completed = false
                val webView = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                }

                fun finish(result: YouTubeSessionTokens?) {
                    if (!completed) {
                        completed = true
                        webView.stopLoading()
                        webView.destroy()
                        if (cont.isActive) cont.resume(result)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        // Sniff visitorData & poToken from YouTube's own player/log_event requests
                        if (url.contains("/youtubei/v1/player") || url.contains("po_token") || url.contains("pot=")) {
                            val visitor = request?.requestHeaders?.get("X-Goog-Visitor-Id")
                            val potParam = request?.url?.getQueryParameter("pot")
                            if (!visitor.isNullOrBlank() && !potParam.isNullOrBlank()) {
                                webView.post { finish(YouTubeSessionTokens(visitor, potParam)) }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Extract ytcfg visitorData & trigger player initialization if needed
                        view?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var cfg = window.ytcfg && window.ytcfg.data_ ? window.ytcfg.data_ : {};
                                    return JSON.stringify({
                                        visitorData: cfg.VISITOR_DATA || "",
                                        poToken: (cfg.INNERTUBE_CONTEXT && cfg.INNERTUBE_CONTEXT.client && cfg.INNERTUBE_CONTEXT.client.visitorData) || ""
                                    });
                                } catch (e) { return "{}"; }
                            })();
                            """.trimIndent()
                        ) { raw ->
                            val cleaned = raw?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                            val obj = runCatching { JSONObject(cleaned) }.getOrNull()
                            val vd = obj?.optString("visitorData").orEmpty()
                            if (vd.isNotBlank() && cachedTokens != null) {
                                finish(cachedTokens)
                            }
                        }
                    }
                }

                cont.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }

                // Load a public embed page to execute YouTube's BotGuard / PO Token script
                webView.loadUrl("https://www.youtube.com/embed/aqz-KE-bpKQ?autoplay=1&mute=1")
            }
        }
}
