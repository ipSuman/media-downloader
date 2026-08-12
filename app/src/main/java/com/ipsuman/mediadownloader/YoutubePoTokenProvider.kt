package com.ipsuman.mediadownloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Generates a fresh YouTube mweb GVS PO token using the Android WebView's
 * WebPoClient. Tokens are video/session-bound and should not be hard-coded.
 */
@SuppressLint("SetJavaScriptEnabled")
class YoutubePoTokenProvider(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile private var pageReady = false
    @Volatile private var lastError: String? = null

    init { mainHandler.post { ensureWebView() } }

    private fun ensureWebView() {
        if (webView != null) return
        val w = WebView(context.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; lastError = null }
            override fun onReceivedError(
                view: WebView?, request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) { if (request?.isForMainFrame == true) lastError = error?.description?.toString() ?: "WebView error" }
        }
        webView = w
        importCookiesIntoWebView()
        w.loadUrl("https://www.youtube.com/?themeRefresh=1")
    }

    private fun importCookiesIntoWebView() {
        val file = File(context.filesDir, "youtube-cookies.txt")
        if (!file.isFile || file.length() == 0L) return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size < 7) return@forEachLine
                val domain = parts[0]
                val path = parts[2].ifEmpty { "/" }
                val name = parts[5]
                val value = URLDecoder.decode(parts[6], "UTF-8")
                if (!domain.contains("youtube.com") && !domain.contains("google.com")) return@forEachLine
                val host = if (domain.startsWith(".")) domain.substring(1) else domain
                cm.setCookie("https://$host$path", "$name=$value; path=$path")
            }
            cm.flush()
        } catch (_: Exception) {
            // Guest token generation may still work without imported cookies.
        }
    }

    fun getMwebGvsToken(timeoutSeconds: Long = 20): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        mainHandler.post {
            try {
                ensureWebView()
                if (!pageReady) webView?.loadUrl("https://www.youtube.com/?themeRefresh=1")
                val script = """
                    (async function(){
                      try {
                        const ytcfgObj = window.top['ytcfg'];
                        const binding = ytcfgObj && ytcfgObj.get &&
                          (ytcfgObj.get('DATASYNC_ID') || ytcfgObj.get('VISITOR_DATA'));
                        const path = window.top['havuokmhhs-0']?.bevasrs?.wpc;
                        if (!path) return JSON.stringify({ok:false,error:'WebPoClient not available'});
                        if (!binding) return JSON.stringify({ok:false,error:'No YouTube session/visitor binding'});
                        const client = await path();
                        const token = await client.mws({c:String(binding),mc:false,me:false});
                        return JSON.stringify({ok:true,token:String(token)});
                      } catch(e) { return JSON.stringify({ok:false,error:String(e)}); }
                    })();
                """.trimIndent()
                webView?.evaluateJavascript(script) { value ->
                    val decoded = value?.removeSurrounding("\"")?.replace("\\\"", "\"")
                    if (!decoded.isNullOrBlank() && decoded.contains("\"ok\":true")) {
                        val tokenKey = "\"token\":\""
                        val start = decoded.indexOf(tokenKey)
                        if (start >= 0) {
                            val from = start + tokenKey.length
                            val end = decoded.indexOf('"', from)
                            if (end > from) result = decoded.substring(from, end)
                        }
                    } else if (!decoded.isNullOrBlank()) lastError = decoded
                    latch.countDown()
                }
            } catch (e: Exception) { lastError = e.message; latch.countDown() }
        }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return result
    }

    fun lastError(): String? = lastError

    fun close() {
        mainHandler.post {
            try { webView?.stopLoading(); webView?.destroy() } catch (_: Exception) {}
            webView = null; pageReady = false
        }
    }
}
