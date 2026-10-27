package com.ossamah.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    /** Single bridge instance — kept referenced so onResume() can invalidate its app cache. */
    private lateinit var bridge: AndroidTVBridge

    // ─────────────────────────────────────────── REMOTE OK: LONG PRESS (native)
    // Android TV often swallows KEYCODE_DPAD_CENTER inside the WebView (or turns
    // it into a click before the JS timer completes). We therefore own the timer
    // here, at the root of the activity, and only swallow the matching ACTION_UP
    // when the long press actually opened something.
    private val okHandler = Handler(Looper.getMainLooper())
    private var okLongFired = false
    private var okLongOpened = false
    private var okDownTime = 0L
    private var okDownConsumed = true

    /** Native wallpaper picker (ACTION_GET_CONTENT -> Base64 -> WebView). */
    // Kotlin bridge (AndroidTVBridge) needs access to this launcher.
    val wallpaperPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            onWallpaperPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardware acceleration for the whole window (zero-lag WebView compositing).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        webView = WebView(this)
        setupWebView()
        setContentView(webView)

        hideSystemBars()
        webView.loadUrl("https://appassets.androidplatform.net/assets/site/index.html")

        // Back key never closes the launcher — it forwards an Escape key to the web UI.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = sendEscapeToWebView()
        })
    }

    // ---------------------------------------------------------------- WebView

    @Suppress("DEPRECATION")
    private fun setupWebView() {
        // no intermediate layer: the window is already hardware-accelerated via
        // the manifest. A full-screen LAYER_TYPE_HARDWARE forces an extra
        // SurfaceTexture copy on every animation frame — a known jank source
        // on old Amlogic quad-core GPUs.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // Serve local assets over a stable https:// origin so the web app behaves
        // exactly like a hosted site (no file:// restrictions on fonts / storage).
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.addJavascriptInterface(AndroidTVBridge(this).also { bridge = it }, "AndroidTV")
    }

    // ------------------------------------------------------------ Fullscreen

    private fun hideSystemBars() {
        // Immersive sticky — fully hides status and navigation bars.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        } else {
            okHandler.removeCallbacksAndMessages(null)
            okLongFired = false
        }
    }

    override fun onPause() {
        okHandler.removeCallbacksAndMessages(null)
        okLongFired = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // الرجوع من حوار إزالة تطبيق (أو أي تطبيق خارجي): نسقط كاش قائمة التطبيقات
        // ونحدّث شريط الويب فوراً حتى تختفي أيقونة التطبيق المحذوف على الفور.
        runCatching { bridge.onHostResume() }
    }

    /** نقطة عامة للجسر: webView خاص، فدوال الجسر تستدعي الويب عبر هذه الطريقة فقط. */
    fun refreshAppsFromWebView() {
        runCatching {
            webView.evaluateJavascript(
                "if(window.syncInstalledApps){syncInstalledApps();renderDock();}", null
            )
        }
    }

    // ------------------------------------------------------------- Back key

    /** Sends a synthetic Escape key event into the web app instead of exiting. */
    private fun sendEscapeToWebView() {
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown', " +
                "{key:'Escape', code:'Escape', keyCode:27, bubbles:true}));",
            null
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sendEscapeToWebView()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------ OK long press (native)

    /**
     * Root-level key handling for the remote OK/Center button.
     *
     * Timeline: DOWN(repeat 0) starts a 700ms timer and is forwarded to the
     * WebView (so focus, IME and short clicks keep working). If the timer fires
     * — or the remote starts repeating — we open the quick-options window from
     * JS and set [okLongFired]. The matching ACTION_UP is then swallowed, which
     * is what stops the WebView from launching the app on top of the dialog.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Remote MENU key = direct shortcut for the quick-options window.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                runCatching {
                    webView.evaluateJavascript(
                        "window.__tvRemoteOkLong&&window.__tvRemoteOkLong()", null
                    )
                }
            }
            return true
        }

        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!isOk) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    okDownTime = SystemClock.uptimeMillis()
                    okLongFired = false
                    okLongOpened = false
                    okHandler.removeCallbacksAndMessages(null)
                    okHandler.postDelayed({ fireOkLong() }, OK_LONG_MS)
                    okDownConsumed = super.dispatchKeyEvent(event)
                    return okDownConsumed
                }
                // Remote auto-repeat: fire as soon as the hold passes the limit,
                // then swallow every repeat so JS never sees a burst of keydowns.
                if (!okLongFired &&
                    (event.repeatCount >= 2 ||
                        SystemClock.uptimeMillis() - okDownTime >= OK_LONG_MS)
                ) {
                    fireOkLong()
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                okHandler.removeCallbacksAndMessages(null)
                val fired = okLongFired
                val opened = okLongOpened
                okLongFired = false
                if (fired) {
                    // Long press on a non app-card (a chip, a switch...) → behave like a click.
                    if (!opened) shortClickInWebView()
                    return true
                }
                if (!okDownConsumed) {
                    // The WebView never received the DOWN: emulate a short click.
                    shortClickInWebView()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Fires the JS long-press handler and records whether it opened a window. */
    private fun fireOkLong() {
        okLongFired = true
        okLongOpened = false
        runCatching {
            webView.evaluateJavascript(
                "(function(){try{return (window.__tvRemoteOkLong&&window.__tvRemoteOkLong())?'1':'0';}" +
                    "catch(e){return '0';}})()"
            ) { result -> if (result != null && result.contains('1')) okLongOpened = true }
        }
    }

    /** Emulates a normal OK click inside the web UI (used when WebView missed the key). */
    private fun shortClickInWebView() {
        runCatching {
            webView.evaluateJavascript("window.__tvRemoteOkShort&&window.__tvRemoteOkShort()", null)
        }
    }

    // ------------------------------------------------------------ Wallpaper

    private fun onWallpaperPicked(uri: Uri) {
        Thread {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return@Thread

                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                bitmap.recycle()
                val dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                runOnUiThread {
                    webView.evaluateJavascript("setWallpaperFromNative('$dataUrl')", null)
                }
            } catch (_: Exception) {
                // Never crash on a bad image pick.
            }
        }.start()
    }

    private companion object {
        /** Long-press threshold for the remote OK button (ms). */
        const val OK_LONG_MS = 700L
    }
}

/**
 * JavaScript bridge exposed to the web app as `window.AndroidTV`.
 * All methods are safe-guarded — the launcher must never crash on TV hardware.
 */
class AndroidTVBridge(private val activity: MainActivity) {

    /** Session cache of the serialized app list so icons are encoded only once. */
    private var cachedAppsJson: String? = null

    /** Called from MainActivity.onResume(): drops the cached app list and refreshes the web dock. */
    fun onHostResume() {
        cachedAppsJson = null
        activity.runOnUiThread {
            runCatching { activity.refreshAppsFromWebView() }
        }
    }

    /** Launches an installed TV app by package name (leanback first, then phone starter). */
    @android.webkit.JavascriptInterface
    fun launchApp(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        activity.runOnUiThread {
            try {
                val pm = activity.packageManager
                val intent: Intent? =
                    runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
                        ?: runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Returns all installed (TV-first) apps as:
     * [{ "pkg": "...", "label": "...", "icon": "data:image/webp;base64,..." }, ...]
     * The launcher's own package is always excluded.
     */
    @android.webkit.JavascriptInterface
    fun getInstalledApps(): String {
        // Session cache: encoding icons is expensive, so return the cached JSON
        // immediately when it was already built during this app session.
        cachedAppsJson?.let { return it }
        return try {
            val pm = activity.packageManager
            val selfPkg = activity.packageName
            val seen = HashSet<String>()
            val arr = JSONArray()

            val queries = listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            )

            for (query in queries) {
                val resolveInfos =
                    runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())

                for (ri in resolveInfos) {
                    val pkg = ri?.activityInfo?.packageName ?: continue
                    if (!seen.add(pkg)) continue     // dedupe across both categories
                    if (pkg == selfPkg) continue      // never launch ourselves

                    val label = runCatching { ri.loadLabel(pm).toString() }
                        .getOrDefault(pkg)
                    val icon = runCatching { drawIconAsDataUrl(ri.loadIcon(pm)) }
                        .getOrNull() ?: ""

                    val obj = JSONObject()
                    obj.put("pkg", pkg)
                    obj.put("label", label)
                    obj.put("icon", icon)
                    arr.put(obj)
                }
            }
            val json = arr.toString()
            if (arr.length() > 0) cachedAppsJson = json
            json
        } catch (_: Exception) {
            "[]"
        }
    }

    /**
     * Scales any app icon down to a tiny 72x72 WEBP (quality 75) data URL.
     * WEBP @ 72px is roughly 5-8x smaller than a 96px PNG, which makes the
     * base64 transfer to the web side dramatically cheaper.
     */
    private fun drawIconAsDataUrl(drawable: Drawable): String {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.WEBP, 75, out)
        bmp.recycle()
        return "data:image/webp;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Reports the active network as "ethernet", "wifi", or "none". */
    @android.webkit.JavascriptInterface
    fun getNetworkType(): String {
        return try {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                else -> "none"
            }
        } catch (_: Exception) {
            "none"
        }
    }

    /** Keeps the TV screen awake while the launcher is visible. */
    @android.webkit.JavascriptInterface
    fun keepScreenOn(on: Boolean) {
        activity.runOnUiThread {
            if (on) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Opens an external URL in the system browser. */
    @android.webkit.JavascriptInterface
    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        activity.runOnUiThread {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    /** Uninstalls an app from the TV box via the system uninstall dialog. */
    @android.webkit.JavascriptInterface
    fun uninstallApp(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        activity.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Opens the Android TV input-source selector; silently ignored if unavailable. */
    @android.webkit.JavascriptInterface
    fun openInputSources() {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent("android.intent.action.SHOW_INPUT_LIST"))
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent("com.android.tv.settings.INPUT_SOURCES"))
                } catch (_: Exception) {
                    runCatching {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                }
            }
        }
    }

    /** Opens a native image chooser; the picked photo is returned via setWallpaperFromNative(). */
    @android.webkit.JavascriptInterface
    fun pickWallpaper() {
        activity.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                activity.wallpaperPicker.launch(intent)
            } catch (_: Exception) {
            }
        }
    }
}
