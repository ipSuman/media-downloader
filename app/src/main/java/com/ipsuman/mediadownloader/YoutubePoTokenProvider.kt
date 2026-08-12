package com.ipsuman.mediadownloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android-native Web PO-token generator based on the BotGuard/WebPoClient flow
 * used by established Android YouTube clients. It runs YouTube's BotGuard VM
 * inside an isolated WebView and talks to the Integrity API directly.
 */
@SuppressLint("SetJavaScriptEnabled")
class YoutubePoTokenProvider(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile private var initialized = false
    @Volatile private var initializing = false
    @Volatile private var lastError: String? = null
    private val initLatch = CountDownLatch(1)
    private val tokenLatchLock = Any()
    private val tokenResults = HashMap<String, String>()
    private val tokenErrors = HashMap<String, String>()
    private var webPoSignalReady = false
    private var integrityTokenReady = false

    companion object {
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"
        // Matches the WEB client family used by current yt-dlp web_safari requests.
        private const val WEB_CLIENT_VERSION = "2.20260708.00.00"
        private const val WEB_CLIENT_NAME = "WEB"
        private const val WEB_CLIENT_ID = "1"
    }

    init {
        mainHandler.post { startInitialization() }
    }

    private fun startInitialization() {
        if (initializing || initialized) return
        initializing = true
        try {
            val w = WebView(context.applicationContext)
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
                userAgentString = USER_AGENT
                blockNetworkLoads = true
            }
            w.addJavascriptInterface(this, JS_INTERFACE)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mainHandler.post { runBotguardPreparation() }
                }
                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        failInitialization(error?.description?.toString() ?: "WebView initialization error")
                    }
                }
            }
            webView = w
            importCookiesIntoWebView()
            val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            w.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        } catch (e: Exception) {
            failInitialization(e.message ?: e.toString())
        }
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
        } catch (e: Exception) {
            lastError = "Cookie import warning: ${e.message}"
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Thread {
            try {
                val challengeRaw = makeIntegrityRequest("https://www.youtube.com/api/jnn/v1/Create", listOf(REQUEST_KEY))
                val challenge = parseChallengeData(challengeRaw)
                mainHandler.post {
                    try {
                        webView?.evaluateJavascript(
                            """try {
                                data = $challenge;
                                runBotGuard(data).then(function(result) {
                                    webPoSignalOutput = result.webPoSignalOutput;
                                    $JS_INTERFACE.onRunBotguardResult(result.botguardResponse);
                                }, function(error) {
                                    $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                                });
                            } catch (error) {
                                $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                            }""".trimIndent(), null
                        )
                    } catch (e: Exception) {
                        failInitialization(e.message ?: e.toString())
                    }
                }
            } catch (e: Exception) {
                failInitialization("BotGuard Create failed: ${e.message}")
            }
        }.start()
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        Thread {
            try {
                val response = makeIntegrityRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    listOf(REQUEST_KEY, botguardResponse)
                )
                val parsed = JSONArray(response)
                val integrityToken = base64ToJsU8(parsed.getString(0))
                val ttl = parsed.optLong(1, 3600L)
                mainHandler.post {
                    try {
                        webView?.evaluateJavascript("this.integrityToken = $integrityToken") {
                            integrityTokenReady = true
                            webPoSignalReady = true
                            initialized = true
                            initializing = false
                            initLatch.countDown()
                        }
                    } catch (e: Exception) {
                        failInitialization(e.message ?: e.toString())
                    }
                }
                lastError = "BotGuard initialized; token TTL=${ttl}s"
            } catch (e: Exception) {
                failInitialization("GenerateIT failed: ${e.message}")
            }
        }.start()
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        failInitialization("BotGuard JavaScript failed: $error")
    }

    private fun runBotguardPreparation() {
        try { webView?.evaluateJavascript("typeof runBotGuard === 'function'") { } } catch (_: Exception) {}
    }

    private fun failInitialization(message: String) {
        lastError = message
        initializing = false
        initialized = false
        initLatch.countDown()
    }

    private fun makeIntegrityRequest(urlString: String, data: List<String>): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json+protobuf")
        connection.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
        connection.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
        val body = JSONArray().apply { data.forEach { put(it) } }.toString()
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code != 200) throw IllegalStateException("HTTP $code: ${response.take(500)}")
        return response
    }

    /** Obtain the same visitorData value used by NewPipe before generating the streaming PO token. */
    private fun getVisitorData(): String {
        // First try an existing VISITOR_INFO1_LIVE cookie. This is useful when cookies were imported.
        val cookieVisitor = runCatching {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("VISITOR_INFO1_LIVE=") }
                ?.substringAfter('=')
        }.getOrNull()
        if (!cookieVisitor.isNullOrBlank()) {
            // This cookie is a visitor identifier, but yt-dlp's visitor_data argument expects the
            // Innertube visitorData value. Keep it only as a fallback identifier for BotGuard.
            lastError = "Using YouTube VISITOR_INFO1_LIVE as PO-token binding fallback"
        }

        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", WEB_CLIENT_NAME)
                    put("clientVersion", WEB_CLIENT_VERSION)
                })
            })
        }.toString()

        val connection = URL("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-YouTube-Client-Name", WEB_CLIENT_ID)
        connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code != 200) throw IllegalStateException("visitor_id HTTP $code: ${response.take(500)}")
        val visitor = JSONObject(response).optJSONObject("responseContext")?.optString("visitorData")
        if (visitor.isNullOrBlank()) throw IllegalStateException("visitor_id response contained no visitorData")
        lastError = "Visitor data acquired from Innertube"
        return visitor
    }

    private fun parseChallengeData(raw: String): String {
        val scrambled = JSONArray(raw)
        val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
            val encoded = scrambled.getString(1)
            JSONArray(descramble(encoded))
        } else {
            scrambled.getJSONArray(1)
        }
        val messageId = challengeData.getString(0)
        val interpreterHash = challengeData.getString(3)
        val program = challengeData.getString(4)
        val globalName = challengeData.getString(5)
        val clientExperimentsStateBlob = challengeData.getString(7)
        val safe = findFirstString(challengeData.optJSONArray(1))
        val trusted = findFirstString(challengeData.optJSONArray(2))
        return JSONObject().apply {
            put("messageId", messageId)
            put("interpreterJavascript", JSONObject().apply {
                put("privateDoNotAccessOrElseSafeScriptWrappedValue", safe ?: JSONObject.NULL)
                put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trusted ?: JSONObject.NULL)
            })
            put("interpreterHash", interpreterHash)
            put("program", program)
            put("globalName", globalName)
            put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        }.toString()
    }

    private fun findFirstString(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) if (array.opt(i) is String) return array.getString(i)
        return null
    }

    private fun descramble(value: String): String {
        val bytes = decodeYoutubeBase64(value)
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() + 97).toByte()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun base64ToJsU8(value: String): String {
        val bytes = decodeYoutubeBase64(value)
        return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xff).toString() }}])"
    }

    private fun decodeYoutubeBase64(value: String): ByteArray {
        var normalized = value.replace('-', '+').replace('_', '/').replace('.', '=')
        while (normalized.length % 4 != 0) normalized += "="
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    fun getMwebGvsToken(timeoutSeconds: Long = 25): String? {
        ensureFfmpegExpatRuntime()
        if (!initialized) {
            if (!initLatch.await(timeoutSeconds, TimeUnit.SECONDS) || !initialized) {
                lastError = lastError ?: "Android BotGuard provider initialization timed out"
                return null
            }
        }

        val visitorData = try {
            getVisitorData()
        } catch (e: Exception) {
            lastError = "Could not obtain YouTube visitorData: ${e.message}"
            return null
        }

        val resultLatch = CountDownLatch(1)
        synchronized(tokenLatchLock) {
            tokenResults.remove(visitorData)
            tokenErrors.remove(visitorData)
        }

        mainHandler.post {
            try {
                val escaped = JSONObject.quote(visitorData)
                webView?.evaluateJavascript(
                    """try {
                        const tokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, $escaped);
                        let s=''; for (let i=0;i<tokenU8.length;i++) { if(i) s+=','; s+=tokenU8[i]; }
                        $JS_INTERFACE.onObtainPoTokenResult($escaped, s);
                    } catch(e) { $JS_INTERFACE.onObtainPoTokenError($escaped, String(e) + '\\n' + (e.stack || '')); }""".trimIndent(), null
                )
            } catch (e: Exception) {
                synchronized(tokenLatchLock) { tokenErrors[visitorData] = "PO-token JavaScript failed: ${e.message}" }
                resultLatch.countDown()
            }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            synchronized(tokenLatchLock) {
                tokenResults[visitorData]?.let {
                    lastError = "mweb GVS PO Token generated successfully"
                    return it
                }
                tokenErrors[visitorData]?.let {
                    lastError = "PO-token generation failed: $it"
                    return null
                }
            }
            Thread.sleep(50)
        }
        lastError = lastError ?: "PO-token generation timed out"
        return null
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        try {
            val bytes = poTokenU8.split(',').filter { it.isNotBlank() }.map { it.toInt().toByte() }.toByteArray()
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
            synchronized(tokenLatchLock) { tokenResults[identifier] = token }
        } catch (e: Exception) {
            synchronized(tokenLatchLock) { tokenErrors[identifier] = e.message ?: e.toString() }
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        synchronized(tokenLatchLock) { tokenErrors[identifier] = error }
    }

    fun lastError(): String? = lastError

    private fun ensureFfmpegExpatRuntime() {
        try {
            val source = File(context.applicationInfo.nativeLibraryDir, "libexpat.so.1")
            val targetDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
            val target = File(targetDir, "libexpat.so.1")
            if (!source.isFile || source.length() == 0L) {
                lastError = lastError ?: "Bundled Android Expat library missing from nativeLibraryDir"
                return
            }
            if (!targetDir.isDirectory) targetDir.mkdirs()
            if (!target.isFile || target.length() != source.length()) {
                FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            }
            target.setReadable(true, false)
        } catch (e: Exception) {
            lastError = "Could not prepare FFmpeg Expat runtime: ${e.message}"
        }
    }

    fun close() {
        mainHandler.post {
            try { webView?.stopLoading(); webView?.destroy() } catch (_: Exception) {}
            webView = null
        }
    }
}
