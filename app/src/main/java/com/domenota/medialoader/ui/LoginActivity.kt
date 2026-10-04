package com.domenota.medialoader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.domenota.medialoader.data.MediaRepository

/** Web login on Instagram's own page. Only session cookies are retained; credentials are never read. */
class LoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollSession = object : Runnable {
        override fun run() {
            if (!finished && !isFinishing) {
                checkSession()
                handler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val view = WebView(this)
        web = view
        val mobileUserAgent = view.settings.userAgentString
            .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
        view.settings.apply {
            userAgentString = mobileUserAgent
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        cookies.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { checkSession() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return !isAllowedHost(request.url.scheme, request.url.host)
                val uri = request.url
                if (isAllowedHost(uri.scheme, uri.host)) return false
                if (uri.scheme == "intent" || uri.scheme == "instagram") checkSession()
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { checkSession() }
            override fun onPageFinished(view: WebView, url: String?) { checkSession() }
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        handler.post(pollSession)
        view.loadUrl(LOGIN_URL)
    }

    private fun checkSession(): Boolean {
        if (finished) return true
        val manager = CookieManager.getInstance()
        val cookies = listOf("https://www.instagram.com", "https://instagram.com")
            .mapNotNull(manager::getCookie).flatMap { it.split(';') }
            .map(String::trim).filter(String::isNotEmpty).distinct().joinToString("; ")
        val hasSession = cookies.split(';').map { it.trim() }
            .any { it.startsWith("sessionid=") && it.substringAfter('=').isNotBlank() }
        if (!hasSession) return false
        finished = true
        manager.flush()
        MediaRepository.get(applicationContext).sessions.save(cookies)
        setResult(RESULT_OK)
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollSession)
        web?.let { view ->
            view.stopLoading()
            view.webViewClient = WebViewClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        web = null
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

        fun isAllowedHost(scheme: String?, host: String?): Boolean {
            if (scheme != "https" || host == null) return false
            val name = host.lowercase()
            return name == "instagram.com" || name.endsWith(".instagram.com") ||
                name == "facebook.com" || name.endsWith(".facebook.com")
        }
    }
}
