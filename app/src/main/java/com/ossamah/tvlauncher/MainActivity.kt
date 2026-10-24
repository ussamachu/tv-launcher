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
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
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

        webView.addJavascriptInterface(AndroidTVBridge(this), "AndroidTV")
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
        if (hasFocus) hideSystemBars()
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
}

/**
 * JavaScript bridge exposed to the web app as `window.AndroidTV`.
 * All methods are safe-guarded — the launcher must never crash on TV hardware.
 */
class AndroidTVBridge(private val activity: MainActivity) {

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
     * [{ "pkg": "...", "label": "...", "icon": "data:image/png;base64,..." }, ...]
     * The launcher's own package is always excluded.
     */
    @android.webkit.JavascriptInterface
    fun getInstalledApps(): String {
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
            arr.toString()
        } catch (_: Exception) {
            "[]"
        }
    }

    /** Scales any app icon down to a 96x96 PNG data URL for fast transfer to the web side. */
    private fun drawIconAsDataUrl(drawable: Drawable): String {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
