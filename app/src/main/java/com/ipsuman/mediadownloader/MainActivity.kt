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
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
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
    private val notificationRequestCode = 4103

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateYtdlpConfig()
        startLocalEngine()
        startBackgroundService()
        requestNotificationPermissionIfNeeded()
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
                injectSelectionBridge(); injectEngineDiscoveryFix(); injectBuildIteration(); sendSelectedFolderToWeb(); sendYoutubeCookiesStatusToWeb(); installSettingsNativeFallback()
            }
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun logBackgroundEvent(message: String, error: Throwable? = null) {
        val file = File(filesDir, "media-downloader-engine.log")
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        try {
            file.appendText("[$stamp] $message\n")
            if (error != null) file.appendText(error.stackTraceToString() + "\n")
        } catch (_: Exception) {}
        android.util.Log.d("MediaDownloader", message, error)
    }

    private fun startBackgroundService() {
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        logBackgroundEvent("BACKGROUND: start requested from visible Activity; notificationPermission=$notificationGranted")
        try {
            val intent = Intent(this, DownloadService::class.java).apply {
                action = "com.ipsuman.mediadownloader.START_ENGINE"
            }
            ContextCompat.startForegroundService(this, intent)
            logBackgroundEvent("BACKGROUND: startForegroundService() returned successfully")
        } catch (e: Exception) {
            logBackgroundEvent("BACKGROUND: startForegroundService() FAILED", e)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            logBackgroundEvent("NOTIFICATION: POST_NOTIFICATIONS already granted")
            return
        }
        logBackgroundEvent("NOTIFICATION: POST_NOTIFICATIONS not granted; requesting runtime permission")
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            logBackgroundEvent("NOTIFICATION: POST_NOTIFICATIONS permission result=$granted")
            if (granted) DownloadService.instance?.postDownloadStatus("service", "Background engine active")
        }
    }

    private fun startLocalEngine() {
        try {
            if (engineServer == null) engineServer = LocalEngineServer(this)
            if (engineServer?.isAlive != true) {
                android.util.Log.d("MediaDownloader", "Starting local engine on 127.0.0.1:8765")
                engineServer?.start()
            }
            android.util.Log.d("MediaDownloader", "Local engine server alive=${engineServer?.isAlive}")
            warmUpEngineInBackground()
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloader", "Local engine failed to start", e)
            try { engineServer?.stop() } catch (_: Exception) {}
            engineServer = LocalEngineServer(this)
            try { engineServer?.start(); warmUpEngineInBackground() }
            catch (retry: Exception) { android.util.Log.e("MediaDownloader", "Local engine retry failed", retry) }
        }
    }

    private fun warmUpEngineInBackground() {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                android.util.Log.d("MediaDownloader", "ENGINE WARM-UP: requesting /health to force yt-dlp + FFmpeg initialization")
                connection = (URL("http://127.0.0.1:8765/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 5000; readTimeout = 10 * 60 * 1000
                    setRequestProperty("Cache-Control", "no-store")
                }
                val code = connection!!.responseCode
                val body = (if (code in 200..299) connection!!.inputStream else connection!!.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                android.util.Log.d("MediaDownloader", "ENGINE WARM-UP: /health http=$code body=${body.take(1000)}")
                DownloadService.instance?.postDownloadStatus("engine", "Engine ready")
            } catch (e: Exception) {
                android.util.Log.e("MediaDownloader", "ENGINE WARM-UP FAILED", e)
                logBackgroundEvent("BACKGROUND: engine warm-up failed while service should be protecting process", e)
            } finally { connection?.disconnect() }
        }.start()
    }

    private fun injectBuildIteration() {
        val iteration = BuildConfig.VERSION_CODE
        webView.evaluateJavascript("""(function(){var f=document.querySelector('footer');if(!f)return;var t=f.textContent||'Media Downloader';t=t.replace(/\s*•\s*Iteration\s*#\d+\s*$/i,'');f.textContent=t+' • Iteration #$iteration';})();""", null)
    }

    private fun injectEngineDiscoveryFix() {
        webView.evaluateJavascript("""
            (function(){
              if(window.__mdEngineDiscoveryFixInstalled)return;window.__mdEngineDiscoveryFixInstalled=true;
              const ENGINE='http://127.0.0.1:8765';
              function setOnline(data){window.engineBase=ENGINE;window.__mdNativeEngineBase=ENGINE;try{if(typeof setEngineStatus==='function')setEngineStatus('🟢 Local engine connected','online');const yt=document.getElementById('ytVersion'),ff=document.getElementById('ffVersion');if(yt)yt.textContent=(data&&data.ytdlp&&data.ytdlp.installed)||'Unknown';if(ff)ff.textContent=(data&&data.ffmpeg&&data.ffmpeg.installed)||'Bundled'}catch(e){}}
              window.discoverEngine=async function(){try{if(typeof setEngineStatus==='function')setEngineStatus('Checking for local engine…','offline');const controller=new AbortController();const timer=setTimeout(function(){controller.abort()},15000);const response=await fetch(ENGINE+'/health',{method:'GET',signal:controller.signal,cache:'no-store'});clearTimeout(timer);if(!response.ok)throw new Error('HTTP '+response.status);const data=await response.json();if(!data||!data.ytdlp)throw new Error('Invalid engine response');setOnline(data);return true}catch(error){try{if(typeof setEngineStatus==='function')setEngineStatus('⏳ Local engine initializing…','offline')}catch(e){}console.log('Native engine discovery failed',error);return false}};
              window.engineBase=ENGINE;setTimeout(function(){window.discoverEngine()},150);setTimeout(function(){window.discoverEngine()},5000);setTimeout(function(){window.discoverEngine()},15000);
            })();
        """.trimIndent(), null)
    }

    private fun injectSelectionBridge() {
        try { val scripts=listOf("api-compat.js","theme.js","selection-bridge.js","cut-keyboard-fix.js","download-fix.js");for(name in scripts)assets.open(name).bufferedReader(Charsets.UTF_8).use{webView.evaluateJavascript(it.readText(),null)} }
        catch(e:Exception){android.util.Log.e("MediaDownloader","Could not inject WebView bridge/theme",e)}
    }

    private fun installSettingsNativeFallback(){webView.postDelayed({webView.evaluateJavascript("""(function(){var b=document.querySelector('.settings');if(!b||b.dataset.nativeFolderFallback==='1')return;b.dataset.nativeFolderFallback='1';b.addEventListener('click',function(){setTimeout(function(){var o=document.getElementById('mdSettingsOverlay');if(!o||!o.classList.contains('show')){try{if(window.Android&&typeof window.Android.chooseDownloadFolder==='function')window.Android.chooseDownloadFolder()}catch(e){}}},120)},false)})();""".trimIndent(),null)},250)}

    private fun openFolderPicker(){try{val intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);prefs.getString("download_tree_uri",null)?.let{try{putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(it))}catch(_:Exception){}}};startActivityForResult(intent,folderPickerRequestCode)}catch(e:Exception){android.util.Log.e("MediaDownloader","Could not open folder picker",e)}}
    private fun openYoutubeCookiesPicker(){try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},cookiesPickerRequestCode)}catch(e:Exception){android.util.Log.e("MediaDownloader","Could not open YouTube cookies picker",e)}}
    private fun ytdlpConfigFile():File=File(noBackupFilesDir,"youtubedl-android/config.txt")
    private fun updateYtdlpConfig(){try{val config=ytdlpConfigFile();val cookies=File(filesDir,"youtube-cookies.txt");if(cookies.isFile&&cookies.length()>0L){config.parentFile?.mkdirs();config.writeText("--cookies\n${cookies.absolutePath}\n")}else if(config.exists())config.delete()}catch(e:Exception){android.util.Log.e("MediaDownloader","Could not update yt-dlp config",e)}}
    private fun importYoutubeCookies(uri:Uri){try{contentResolver.openInputStream(uri)?.use{input->val target=File(filesDir,"youtube-cookies.txt");target.outputStream().use{out->input.copyTo(out)};if(target.length()==0L)throw IllegalStateException("Selected cookie file is empty")};prefs.edit().putBoolean("youtube_cookies_configured",true).apply();updateYtdlpConfig();sendYoutubeCookiesStatusToWeb()}catch(e:Exception){android.util.Log.e("MediaDownloader","Could not import YouTube cookies",e);webView.post{webView.evaluateJavascript("window.onYoutubeCookiesError&&window.onYoutubeCookiesError(${JSONObjectEscaper.quote(e.message?:"Import failed")});",null)}}}
    private fun clearYoutubeCookies(){try{File(filesDir,"youtube-cookies.txt").delete()}catch(_:Exception){};prefs.edit().putBoolean("youtube_cookies_configured",false).apply();try{ytdlpConfigFile().delete()}catch(_:Exception){};sendYoutubeCookiesStatusToWeb()}
    private fun sendYoutubeCookiesStatusToWeb(){val configured=File(filesDir,"youtube-cookies.txt").let{it.isFile&&it.length()>0L};if(::webView.isInitialized)webView.post{webView.evaluateJavascript("window.onYoutubeCookiesStatus&&window.onYoutubeCookiesStatus(${configured});",null)}}
    private fun sendSelectedFolderToWeb(){if(!::webView.isInitialized)return;val uri=JSONObjectEscaper.escape(prefs.getString("download_tree_uri","")?:"");val name=JSONObjectEscaper.escape(prefs.getString("download_tree_name","")?:"");webView.post{webView.evaluateJavascript("window.onNativeFolderSelected&&window.onNativeFolderSelected('$uri','$name');",null)}}
    private fun clearSelectedFolder(){prefs.edit().remove("download_tree_uri").remove("download_tree_name").apply();sendSelectedFolderToWeb()}

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;if(requestCode==folderPickerRequestCode){val uri=data?.data?:return;try{val flags=data.flags and(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);contentResolver.takePersistableUriPermission(uri,flags)}catch(_:Exception){};val name=try{DocumentFile.fromTreeUri(this,uri)?.name?:"Selected folder"}catch(_:Exception){"Selected folder"};prefs.edit().putString("download_tree_uri",uri.toString()).putString("download_tree_name",name).apply();sendSelectedFolderToWeb()}else if(requestCode==cookiesPickerRequestCode)data?.data?.let{importYoutubeCookies(it)}}

    private fun generateDiagnosticLog(){try{val source=File(filesDir,"media-downloader-engine.log");val stamp=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",java.util.Locale.US).format(java.util.Date());source.appendText("[$stamp] Manual diagnostic log requested from UI\n");val fileStamp=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(java.util.Date());val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"media-downloader-engine-$fileStamp.log");put(android.provider.MediaStore.Downloads.MIME_TYPE,"text/plain");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS);put(android.provider.MediaStore.Downloads.IS_PENDING,1)};val uri=contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:throw IllegalStateException("Could not create log file in Downloads");contentResolver.openOutputStream(uri)?.use{out->source.inputStream().use{input->input.copyTo(out)}}?:throw IllegalStateException("Could not open log output");values.clear();values.put(android.provider.MediaStore.Downloads.IS_PENDING,0);contentResolver.update(uri,values,null,null);webView.post{webView.evaluateJavascript("window.onDiagnosticLogSaved&&window.onDiagnosticLogSaved(true);",null)}}catch(e:Exception){android.util.Log.e("MediaDownloader","Could not generate diagnostic log",e);webView.post{webView.evaluateJavascript("window.onDiagnosticLogSaved&&window.onDiagnosticLogSaved(false);",null)}}}
    private fun nativeControlDownload(jobId:String,action:String,requestId:String){Thread{var connection:HttpURLConnection?=null;try{val encodedId=java.net.URLEncoder.encode(jobId,"UTF-8");connection=(URL("http://127.0.0.1:8765/download/$encodedId/control").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=3000;readTimeout=5000;doOutput=true;setRequestProperty("Content-Type","application/json")};OutputStreamWriter(connection!!.outputStream,Charsets.UTF_8).use{it.write("{\"action\":\"${action.replace("\"","")}\"}")};val code=connection!!.responseCode;val stream=if(code in 200..299)connection!!.inputStream else connection!!.errorStream;val body=stream?.use{InputStreamReader(it,Charsets.UTF_8).use{r->BufferedReader(r).readText()}}?:"";val ok=code in 200..299;webView.post{webView.evaluateJavascript("window.onNativeControlResult&&window.onNativeControlResult(${JSONObjectEscaper.quote(requestId)},$ok,JSON.parse(${JSONObjectEscaper.quote(body)}));",null)}}catch(e:Exception){webView.post{webView.evaluateJavascript("window.onNativeControlResult&&window.onNativeControlResult(${JSONObjectEscaper.quote(requestId)},false,{error:${JSONObjectEscaper.quote(e.message?:"Control request failed")}});",null)}}finally{connection?.disconnect()}}.start()}

    private inner class AndroidBridge{@JavascriptInterface fun chooseDownloadFolder(){runOnUiThread{openFolderPicker()}};@JavascriptInterface fun clearDownloadFolder(){runOnUiThread{clearSelectedFolder()}};@JavascriptInterface fun getDownloadFolderName():String=prefs.getString("download_tree_name","")?:"";@JavascriptInterface fun chooseYoutubeCookies(){runOnUiThread{openYoutubeCookiesPicker()}};@JavascriptInterface fun clearYoutubeCookies(){runOnUiThread{this@MainActivity.clearYoutubeCookies()}};@JavascriptInterface fun hasYoutubeCookies():Boolean=File(filesDir,"youtube-cookies.txt").let{it.isFile&&it.length()>0L};@JavascriptInterface fun generateDiagnosticLog(){runOnUiThread{this@MainActivity.generateDiagnosticLog()}};@JavascriptInterface fun controlDownload(jobId:String,action:String,requestId:String){nativeControlDownload(jobId,action,requestId)}}
    private object JSONObjectEscaper{fun escape(value:String):String=value.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","\\r");fun quote(value:String):String="'${escape(value)}'"}

    override fun onDestroy(){
        // Do NOT stop the engine here when the foreground service is alive.
        // The service keeps this process alive so yt-dlp can continue after the Activity/WebView disappears.
        if (DownloadService.instance == null) {
            logBackgroundEvent("BACKGROUND: Activity destroyed and service is not alive; stopping local engine")
            try{engineServer?.stop()}catch(_:Exception){}
            engineServer=null
        } else {
            logBackgroundEvent("BACKGROUND: Activity destroyed; foreground service remains alive, engine server kept running")
        }
        super.onDestroy()
    }
    override fun onBackPressed(){if(webView.canGoBack())webView.goBack()else super.onBackPressed()}
}
