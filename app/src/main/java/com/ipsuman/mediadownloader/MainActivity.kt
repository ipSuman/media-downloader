package com.ipsuman.mediadownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var engineServer: LocalEngineServer? = null
    private val prefs by lazy { getSharedPreferences("media_downloader", MODE_PRIVATE) }
    private val folderPickerRequestCode = 4101
    private val cookiesPickerRequestCode = 4102
    private val notificationPermissionRequestCode = 7101

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()
        updateYtdlpConfig()
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
                injectSelectionBridge()
                injectEngineDiscoveryFix()
                injectBuildIteration()
                sendSelectedFolderToWeb()
                sendYoutubeCookiesStatusToWeb()
                installSettingsNativeFallback()
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
        }
    }

    private fun startLocalEngine() {
        try {
            if (EngineHolder.server == null) EngineHolder.server = LocalEngineServer(this)
            engineServer = EngineHolder.server
            if (engineServer?.isAlive == true) return
            android.util.Log.d("MediaDownloader", "Starting local engine on 127.0.0.1:8765")
            engineServer?.start()
            android.util.Log.d("MediaDownloader", "Local engine start requested; alive=${engineServer?.isAlive}")
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Local engine failed to start", e)
            try { engineServer?.stop() } catch (_: Exception) {}
            EngineHolder.server = LocalEngineServer(this)
            engineServer = EngineHolder.server
            try {
                engineServer?.start()
                android.util.Log.d("MediaDownloader", "Local engine retry; alive=${engineServer?.isAlive}")
            } catch (retry: Exception) {
                android.util.Log.e("MediaDownloader", "Local engine retry failed", retry)
            }
        }
    }

    private fun injectBuildIteration() {
        val iteration = BuildConfig.VERSION_CODE
        webView.evaluateJavascript(
            """
            (function(){
              var footer = document.querySelector('footer');
              if (!footer) return;
              var text = footer.textContent || 'Media Downloader';
              text = text.replace(/\s*•\s*Iteration\s*#\d+\s*$/i, '');
              footer.textContent = text + ' • Iteration #$iteration';
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectEngineDiscoveryFix() {
        webView.evaluateJavascript(
            """
            (function(){
              if(window.__mdEngineDiscoveryFixInstalled) return;
              window.__mdEngineDiscoveryFixInstalled=true;

              const ENGINE='http://127.0.0.1:8765';

              function setOnline(data){
                window.engineBase=ENGINE;
                window.__mdNativeEngineBase=ENGINE;
                try{
                  if(typeof setEngineStatus==='function')
                    setEngineStatus('🟢 Local engine connected','online');
                  const yt=document.getElementById('ytVersion');
                  const ff=document.getElementById('ffVersion');
                  if(yt) yt.textContent=(data && data.ytdlp && data.ytdlp.installed) || 'Unknown';
                  if(ff) ff.textContent=(data && data.ffmpeg && data.ffmpeg.installed) || 'Bundled';
                }catch(e){}
              }

              window.discoverEngine=async function(){
                try{
                  if(typeof setEngineStatus==='function')
                    setEngineStatus('Checking for local engine…','offline');
                  const controller=new AbortController();
                  const timer=setTimeout(function(){controller.abort();},2500);
                  const response=await fetch(ENGINE+'/health',{method:'GET',signal:controller.signal,cache:'no-store'});
                  clearTimeout(timer);
                  if(!response.ok) throw new Error('HTTP '+response.status);
                  const data=await response.json();
                  if(!data || !data.ytdlp) throw new Error('Invalid engine response');
                  setOnline(data);
                  return true;
                }catch(error){
                  try{
                    if(typeof setEngineStatus==='function')
                      setEngineStatus('🔴 Local engine not detected','offline');
                  }catch(e){}
                  console.log('Native engine discovery failed',error);
                  return false;
                }
              };

              window.engineBase=ENGINE;
              setTimeout(function(){window.discoverEngine();},150);
              setTimeout(function(){
                if(!window.engineBase) window.discoverEngine();
              },1200);
              setTimeout(function(){
                if(!window.engineBase) window.discoverEngine();
              },3000);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectNotificationBridge() {
        webView.evaluateJavascript(
            """
            (function(){
              function install(){
                if(window.__mdNotificationHookInstalled) return true;
                if(typeof window.monitorDownload !== 'function') return false;
                var original = window.monitorDownload;
                window.monitorDownload = function(jobId, item){
                  try{
                    var label = item && item.querySelector ? (item.querySelector('.queue-name')?.textContent || 'Media download') : 'Media download';
                    if(window.Android && typeof window.Android.startDownloadNotification === 'function'){
                      window.Android.startDownloadNotification(String(jobId), String(label));
                    }
                  }catch(e){ console.log('Notification bridge failed',e); }
                  return original.apply(this, arguments);
                };
                window.__mdNotificationHookInstalled=true;
                return true;
              }
              if(!install()) setTimeout(install,100);
              if(!install()) setTimeout(install,500);
              if(!install()) setTimeout(install,1200);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectSelectionBridge() {
        try {
            val scripts = listOf("api-compat.js", "theme.js", "selection-bridge.js", "cut-keyboard-fix.js", "download-fix.js")
            for (scriptName in scripts) {
                val script = assets.open(scriptName).bufferedReader(Charsets.UTF_8).use { it.readText() }
                webView.evaluateJavascript(script, null)
            }
            injectNotificationBridge()
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
                """.trimIndent(), null
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
                    try { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) } catch (_: Exception) {}
                }
            }
            android.util.Log.d("MediaDownloader", "Opening folder picker")
            startActivityForResult(intent, folderPickerRequestCode)
        } catch (e: Exception) { android.util.Log.e("MediaDownloader", "Could not open folder picker", e) }
    }

    private fun openYoutubeCookiesPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            android.util.Log.d("MediaDownloader", "Opening YouTube cookies picker")
            startActivityForResult(intent, cookiesPickerRequestCode)
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not open YouTube cookies picker", e)
        }
    }

    private fun ytdlpConfigFile(): File = File(noBackupFilesDir, "youtubedl-android/config.txt")

    private fun updateYtdlpConfig() {
        try {
            val config = ytdlpConfigFile()
            val cookies = File(filesDir, "youtube-cookies.txt")
            if (cookies.isFile && cookies.length() > 0L) {
                config.parentFile?.mkdirs()
                config.writeText("--cookies\n${cookies.absolutePath}\n")
                android.util.Log.d("MediaDownloader", "yt-dlp config updated with private YouTube cookie file")
            } else if (config.exists()) {
                config.delete()
                android.util.Log.d("MediaDownloader", "yt-dlp cookie config removed")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not update yt-dlp config", e)
        }
    }

    private fun importYoutubeCookies(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val target = File(filesDir, "youtube-cookies.txt")
                target.outputStream().use { output -> input.copyTo(output) }
                if (target.length() == 0L) throw IllegalStateException("Selected cookie file is empty")
                prefs.edit().putBoolean("youtube_cookies_configured", true).apply()
                updateYtdlpConfig()
                android.util.Log.d("MediaDownloader", "Imported YouTube cookies: ${target.length()} bytes")
                sendYoutubeCookiesStatusToWeb()
            } ?: throw IllegalStateException("Could not read selected cookie file")
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not import YouTube cookies", e)
            webView.post { webView.evaluateJavascript("window.onYoutubeCookiesError && window.onYoutubeCookiesError(${JSONObjectEscaper.quote(e.message ?: "Import failed")});", null) }
        }
    }

    private fun clearYoutubeCookies() {
        try {
            val cookieFile = File(filesDir, "youtube-cookies.txt")
            if (cookieFile.exists() && !cookieFile.delete()) {
                android.util.Log.w("MediaDownloader", "Could not delete YouTube cookie file")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not delete YouTube cookies", e)
        }
        try {
            prefs.edit().putBoolean("youtube_cookies_configured", false).apply()
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not clear YouTube cookie preference", e)
        }
        try {
            val config = ytdlpConfigFile()
            if (config.exists() && !config.delete()) {
                android.util.Log.w("MediaDownloader", "Could not delete yt-dlp cookie config")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not remove yt-dlp cookie config", e)
        }
        android.util.Log.d("MediaDownloader", "YouTube cookies cleared")
    }

    private fun sendYoutubeCookiesStatusToWeb() {
        val configured = File(filesDir, "youtube-cookies.txt").isFile && File(filesDir, "youtube-cookies.txt").length() > 0L
        webView.post {
            webView.evaluateJavascript("window.onYoutubeCookiesStatus && window.onYoutubeCookiesStatus(${if (configured) "true" else "false"});", null)
        }
    }

    private fun sendSelectedFolderToWeb() {
        val uriString = prefs.getString("download_tree_uri", null)
        val name = prefs.getString("download_tree_name", "") ?: ""
        val escapedUri = JSONObjectEscaper.escape(uriString ?: "")
        val escapedName = JSONObjectEscaper.escape(name)
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeFolderSelected && window.onNativeFolderSelected('$escapedUri','$escapedName');", null
            )
        }
    }

    private fun clearSelectedFolder() {
        prefs.edit().remove("download_tree_uri").remove("download_tree_name").apply()
        sendSelectedFolderToWeb()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderPickerRequestCode) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) { android.util.Log.w("MediaDownloader", "Could not persist folder permission", e) }

            val folderName = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name ?: "Selected folder"
            } catch (_: Exception) { "Selected folder" }

            prefs.edit().putString("download_tree_uri", uri.toString()).putString("download_tree_name", folderName).apply()
            sendSelectedFolderToWeb()
            return
        }

        if (requestCode == cookiesPickerRequestCode) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            importYoutubeCookies(uri)
        }
    }

    private fun generateDiagnosticLog() {
        try {
            val source = File(filesDir, "media-downloader-engine.log")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            source.appendText("[$stamp] Manual diagnostic log requested from UI\n")

            val fileStamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "media-downloader-engine-$fileStamp.log")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create log file in Downloads")

            contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open log output")

            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            android.util.Log.d("MediaDownloader", "Diagnostic log exported: $uri")
            webView.post {
                webView.evaluateJavascript(
                    "window.onDiagnosticLogSaved && window.onDiagnosticLogSaved(true);",
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not generate diagnostic log", e)
            webView.post {
                webView.evaluateJavascript(
                    "window.onDiagnosticLogSaved && window.onDiagnosticLogSaved(false);",
                    null
                )
            }
        }
    }

    private fun nativeControlDownload(jobId: String, action: String, requestId: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val encodedId = java.net.URLEncoder.encode(jobId, "UTF-8")
                val url = URL("http://127.0.0.1:8765/download/$encodedId/control")
                android.util.Log.d("MediaDownloader", "CONTROL native POST: job=$jobId action=$action")
                logControlEvent("CONTROL native POST: job=$jobId action=$action")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Cache-Control", "no-store")
                }
                OutputStreamWriter(connection!!.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write("{\"action\":\"${action.replace("\"", "")}\"}")
                    writer.flush()
                }
                val code = connection!!.responseCode
                val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
                val body = stream?.use { InputStreamReader(it, Charsets.UTF_8).use { reader -> BufferedReader(reader).readText() } } ?: ""
                android.util.Log.d("MediaDownloader", "CONTROL native response: job=$jobId action=$action http=$code body=${body.take(300)}")
                logControlEvent("CONTROL native response: job=$jobId action=$action http=$code body=${body.take(300)}")
                val ok = code in 200..299
                val jsPayload = JSONObjectEscaper.quote(body)
                webView.post {
                    webView.evaluateJavascript("window.onNativeControlResult && window.onNativeControlResult(${JSONObjectEscaper.quote(requestId)},$ok,JSON.parse($jsPayload));", null)
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaDownloader", "CONTROL native request failed: job=$jobId action=$action", e)
                logControlEvent("CONTROL native request failed: job=$jobId action=$action: ${e.message}")
                webView.post {
                    webView.evaluateJavascript("window.onNativeControlResult && window.onNativeControlResult(${JSONObjectEscaper.quote(requestId)},false,{error:${JSONObjectEscaper.quote(e.message ?: "Control request failed")}});", null)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun logControlEvent(message: String) {
        try {
            val source = File(filesDir, "media-downloader-engine.log")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            source.appendText("[$stamp] $message\n")
        } catch (_: Exception) {}
    }

    private fun startDownloadNotification(jobId: String, title: String) {
        try {
            DownloadNotificationService.start(this, jobId, title)
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Could not start download notification service", e)
        }
    }

    private inner class AndroidBridge {
        @JavascriptInterface fun chooseDownloadFolder() { runOnUiThread { openFolderPicker() } }
        @JavascriptInterface fun clearDownloadFolder() { runOnUiThread { clearSelectedFolder() } }
        @JavascriptInterface fun getDownloadFolderName(): String = prefs.getString("download_tree_name", "") ?: ""
        @JavascriptInterface fun chooseYoutubeCookies() { runOnUiThread { openYoutubeCookiesPicker() } }
        @JavascriptInterface fun clearYoutubeCookies() { runOnUiThread { this@MainActivity.clearYoutubeCookies() } }
        @JavascriptInterface fun hasYoutubeCookies(): Boolean = File(filesDir, "youtube-cookies.txt").isFile && File(filesDir, "youtube-cookies.txt").length() > 0L
        @JavascriptInterface fun generateDiagnosticLog() { runOnUiThread { this@MainActivity.generateDiagnosticLog() } }
        @JavascriptInterface fun controlDownload(jobId: String, action: String, requestId: String) { nativeControlDownload(jobId, action, requestId) }
        @JavascriptInterface fun startDownloadNotification(jobId: String, title: String) { startDownloadNotification(jobId, title) }
    }

    private object JSONObjectEscaper {
        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        fun quote(value: String): String = "'${escape(value)}'"
    }

    override fun onDestroy() {
        if (!DownloadNotificationService.isRunning) {
            try { engineServer?.stop() } catch (_: Exception) {}
            if (EngineHolder.server === engineServer) EngineHolder.server = null
        }
        engineServer = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
