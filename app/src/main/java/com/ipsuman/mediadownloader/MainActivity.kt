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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var engineServer: LocalEngineServer? = null
    private val prefs by lazy { getSharedPreferences("media_downloader", MODE_PRIVATE) }
    private val folderPickerRequestCode = 4101

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectSelectionBridge()
                sendSelectedFolderToWeb()
                installSettingsNativeFallback()
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun injectSelectionBridge() {
        try {
            // Keep the proven cut workflow. Do not inject the experimental
            // cut-input-fix layer which was causing the sequence to become
            // confusing/non-repeatable.
            val scripts = listOf("theme.js", "selection-bridge.js")
            for (scriptName in scripts) {
                val script = assets.open(scriptName)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                webView.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not inject WebView bridge/theme", e)
        }
    }

    private fun installSettingsNativeFallback() {
        webView.postDelayed({
            webView.evaluateJavascript(
                """
                (function(){
                  var b=document.querySelector('.settings');
                  if(!b || b.dataset.nativeFolderFallback==='1') return;
                  b.dataset.nativeFolderFallback='1';
                  b.addEventListener('click',function(){
                    setTimeout(function(){
                      var overlay=document.getElementById('mdSettingsOverlay');
                      if(!overlay || !overlay.classList.contains('show')){
                        try{ if(window.Android && typeof window.Android.chooseDownloadFolder==='function') window.Android.chooseDownloadFolder(); }catch(e){}
                      }
                    },120);
                  },false);
                })();
                """.trimIndent(),
                null
            )
        }, 250)
    }

    private fun openFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                prefs.getString("download_tree_uri", null)?.let {
                    try {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                    } catch (_: Exception) {}
                }
            }
            android.util.Log.d("MediaDownloader", "Opening folder picker")
            startActivityForResult(intent, folderPickerRequestCode)
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not open folder picker", e)
        }
    }

    private fun sendSelectedFolderToWeb() {
        val uriString = prefs.getString("download_tree_uri", null)
        val name = prefs.getString("download_tree_name", "") ?: ""
        val escapedUri = JSONObjectEscaper.escape(uriString ?: "")
        val escapedName = JSONObjectEscaper.escape(name)
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeFolderSelected && window.onNativeFolderSelected('$escapedUri','$escapedName');",
                null
            )
        }
    }

    private fun clearSelectedFolder() {
        prefs.edit()
            .remove("download_tree_uri")
            .remove("download_tree_name")
            .apply()
        sendSelectedFolderToWeb()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != folderPickerRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            android.util.Log.w("MediaDownloader", "Could not persist folder permission", e)
        }

        val folderName = try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"
        } catch (_: Exception) {
            "Selected folder"
        }

        prefs.edit()
            .putString("download_tree_uri", uri.toString())
            .putString("download_tree_name", folderName)
            .apply()
        sendSelectedFolderToWeb()
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun chooseDownloadFolder() {
            runOnUiThread { openFolderPicker() }
        }

        @JavascriptInterface
        fun clearDownloadFolder() {
            runOnUiThread { clearSelectedFolder() }
        }

        @JavascriptInterface
        fun getDownloadFolderName(): String {
            return prefs.getString("download_tree_name", "") ?: ""
        }
    }

    private object JSONObjectEscaper {
        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    override fun onDestroy() {
        engineServer?.stop()
        engineServer = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
