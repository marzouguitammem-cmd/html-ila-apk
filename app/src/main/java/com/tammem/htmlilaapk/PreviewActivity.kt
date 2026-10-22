package com.tammem.htmlilaapk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/** Shows the app before building it, the same way the made app will show it. */
class PreviewActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val www = intent.getStringExtra(EXTRA_WWW)?.let { File(it) }
        val url = intent.getStringExtra(EXTRA_URL)
        val fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        when (intent.getStringExtra(EXTRA_ORIENTATION)) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(this).apply {
            text = "معاينة"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 8, 24, 8)
            alpha = 0.85f
        }
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        if (fullscreen && Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(false)
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (www == null || u.host != HOST) return null
                var path = u.path ?: "/"
                if (path == "/" || path.isEmpty()) path = "/index.html"
                if (path.endsWith("/")) path += "index.html"
                val f = File(www, path.removePrefix("/"))
                if (!f.canonicalPath.startsWith(www.canonicalPath) || !f.isFile) {
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                val mime = mimeFor(f.name)
                val text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")
                return WebResourceResponse(mime, if (text) "UTF-8" else null, FileInputStream(f))
            }
        }
        if (savedInstanceState == null) {
            web.loadUrl(url ?: "https://$HOST/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        private const val EXTRA_WWW = "www"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FULLSCREEN = "fullscreen"
        private const val EXTRA_ORIENTATION = "orientation"

        fun open(context: Context, www: File?, url: String?, p: Project) {
            context.startActivity(
                Intent(context, PreviewActivity::class.java)
                    .putExtra(EXTRA_WWW, www?.absolutePath)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_FULLSCREEN, p.fullscreen)
                    .putExtra(EXTRA_ORIENTATION, p.orientation),
            )
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
