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

        // Start local engine API
        engineServer = LocalEngineServer()

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

        webView.webViewClient = WebViewClient()

        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
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