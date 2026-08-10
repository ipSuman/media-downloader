package com.ipsuman.mediadownloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var engineServer: LocalEngineServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start local engine API and diagnostic logging.
        engineServer = LocalEngineServer(this)

        try {
            engineServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.allowUniversalAccessFromFileURLs = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectSelectionBridge()
            }
        }

        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun injectSelectionBridge() {
        try {
            val script = assets.open("selection-bridge.js")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not inject selection bridge", e)
        }
    }

    override fun onDestroy() {
        engineServer?.stop()
        engineServer = null

        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
