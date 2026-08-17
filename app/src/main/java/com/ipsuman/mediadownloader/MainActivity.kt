package com.ipsuman.mediadownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var engineServer: LocalEngineServer? = null
    private val prefs by lazy { getSharedPreferences("media_downloader", MODE_PRIVATE) }
    private val folderPickerRequestCode = 4101
    private val cookiesPickerRequestCode = 4102

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startLocalEngine()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectUiScripts()
                injectBuildIteration()
                sendSelectedFolderToWeb()
                sendYoutubeCookiesStatusToWeb()
            }
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startLocalEngine() {
        try {
            if (engineServer == null) engineServer = LocalEngineServer(applicationContext)
            if (engineServer?.isAlive != true) {
                android.util.Log.d("MediaDownloader", "Starting local engine server on 127.0.0.1:8765")
                engineServer?.start()
            }
            android.util.Log.d("MediaDownloader", "Starting mandatory yt-dlp + FFmpeg warm-up")
            engineServer?.warmUpEngine()
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Local engine failed to start", e)
            try { engineServer?.stop() } catch (_: Exception) {}
            engineServer = LocalEngineServer(applicationContext)
            try {
                engineServer?.start()
                engineServer?.warmUpEngine()
            } catch (retry: Exception) {
                android.util.Log.e("MediaDownloader", "Local engine retry failed", retry)
            }
        }
    }

    private fun injectBuildIteration() {
        val iteration = BuildConfig.VERSION_CODE
        webView.evaluateJavascript(
            """(function(){var f=document.querySelector('footer');if(!f)return;var t=f.textContent||'Media Downloader';t=t.replace(/\s*•\s*Build\s*#\d+\s*$/i,'');f.textContent=t+' • Build #$iteration';})();""",
            null
        )
    }

    private fun injectUiScripts() {
        try {
            val scripts = listOf("theme.js", "selection-bridge.js", "cut-keyboard-fix.js", "download-fix.js")
            for (name in scripts) {
                val script = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
                webView.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not inject WebView compatibility scripts", e)
        }
    }

    private fun openFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                prefs.getString("download_tree_uri", null)?.let { try { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) } catch (_: Exception) {} }
            }
            startActivityForResult(intent, folderPickerRequestCode)
        } catch (e: Exception) { android.util.Log.e("MediaDownloader", "Could not open folder picker", e) }
    }

    private fun openYoutubeCookiesPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, cookiesPickerRequestCode)
        } catch (e: Exception) { android.util.Log.e("MediaDownloader", "Could not open YouTube cookies picker", e) }
    }

    private fun importYoutubeCookies(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val target = File(filesDir, "youtube-cookies.txt")
                target.outputStream().use { output -> input.copyTo(output) }
                if (target.length() == 0L) throw IllegalStateException("Selected cookie file is empty")
            } ?: throw IllegalStateException("Could not read selected cookie file")
            prefs.edit().putBoolean("youtube_cookies_configured", true).apply()
            android.util.Log.d("MediaDownloader", "Imported YouTube cookies")
            sendYoutubeCookiesStatusToWeb()
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not import YouTube cookies", e)
            webView.post { webView.evaluateJavascript("window.onYoutubeCookiesError&&window.onYoutubeCookiesError(${JSONObjectEscaper.quote(e.message ?: "Import failed")});", null) }
        }
    }

    private fun clearYoutubeCookies() {
        try { File(filesDir, "youtube-cookies.txt").delete() } catch (_: Exception) {}
        prefs.edit().putBoolean("youtube_cookies_configured", false).apply()
        sendYoutubeCookiesStatusToWeb()
    }

    private fun sendYoutubeCookiesStatusToWeb() {
        if (!::webView.isInitialized) return
        val configured = File(filesDir, "youtube-cookies.txt").isFile && File(filesDir, "youtube-cookies.txt").length() > 0L
        webView.post { webView.evaluateJavascript("window.onYoutubeCookiesStatus&&window.onYoutubeCookiesStatus(${configured});", null) }
    }

    private fun sendSelectedFolderToWeb() {
        if (!::webView.isInitialized) return
        val uri = JSONObjectEscaper.escape(prefs.getString("download_tree_uri", "") ?: "")
        val name = JSONObjectEscaper.escape(prefs.getString("download_tree_name", "") ?: "")
        webView.post { webView.evaluateJavascript("window.onNativeFolderSelected&&window.onNativeFolderSelected('$uri','$name');", null) }
    }

    private fun clearSelectedFolder() {
        prefs.edit().remove("download_tree_uri").remove("download_tree_name").apply()
        sendSelectedFolderToWeb()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == folderPickerRequestCode) {
            val uri = data?.data ?: return
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            val name = try { DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder" } catch (_: Exception) { "Selected folder" }
            prefs.edit().putString("download_tree_uri", uri.toString()).putString("download_tree_name", name).apply()
            sendSelectedFolderToWeb()
        } else if (requestCode == cookiesPickerRequestCode) {
            data?.data?.let { importYoutubeCookies(it) }
        }
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun chooseDownloadFolder() { runOnUiThread { openFolderPicker() } }
        @JavascriptInterface fun clearDownloadFolder() { runOnUiThread { clearSelectedFolder() } }
        @JavascriptInterface fun getDownloadFolderName(): String = prefs.getString("download_tree_name", "") ?: ""
        @JavascriptInterface fun chooseYoutubeCookies() { runOnUiThread { openYoutubeCookiesPicker() } }
        @JavascriptInterface fun clearYoutubeCookies() { runOnUiThread { this@MainActivity.clearYoutubeCookies() } }
        @JavascriptInterface fun hasYoutubeCookies(): Boolean = File(filesDir, "youtube-cookies.txt").isFile && File(filesDir, "youtube-cookies.txt").length() > 0L
    }

    private object JSONObjectEscaper {
        fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        fun quote(value: String): String = "'${escape(value)}'"
    }

    override fun onDestroy() {
        try { engineServer?.stop() } catch (_: Exception) {}
        engineServer = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
}
