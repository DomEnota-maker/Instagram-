package com.domenota.medialoader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.domenota.medialoader.core.provider.InstagramLinkParser
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.rutube.RutubeYtDlpRuntime
import com.domenota.medialoader.data.vk.VkYtDlpRuntime
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid

/** The browser shares cookies with the existing sign-in WebViews. Only validated links reach analysis. */
enum class BrowserSource(val id: String, val title: String, val startUrl: String) {
    INSTAGRAM("instagram", "Instagram", "https://www.instagram.com/"),
    YOUTUBE("youtube", "YouTube", "https://m.youtube.com/"),
    VK("vk", "VK", "https://m.vk.com/"),
    RUTUBE("rutube", "RUTUBE", "https://rutube.ru/");

    fun isSite(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val domains = when (this) {
            INSTAGRAM -> listOf("instagram.com")
            YOUTUBE -> listOf("youtube.com")
            VK -> listOf("vk.com", "vk.ru", "vkvideo.ru")
            RUTUBE -> listOf("rutube.ru")
        }
        return domains.any { host == it || host.endsWith(".$it") }
    }

    fun allowsNavigation(url: String?): Boolean {
        if (isSite(url)) return true
        val uri = Uri.parse(url ?: return false)
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val authDomains = when (this) {
            INSTAGRAM -> listOf("facebook.com")
            YOUTUBE -> listOf("google.com", "gstatic.com", "googleusercontent.com")
            VK -> listOf("vkid.ru")
            RUTUBE -> emptyList()
        }
        return authDomains.any { host == it || host.endsWith(".$it") }
    }

    fun acceptedLink(url: String): String? {
        if (!isSite(url)) return null
        return when (this) {
            INSTAGRAM -> {
                val normalized = Uri.parse(url).buildUpon().authority("www.instagram.com").build().toString()
                InstagramLinkParser.parse(normalized)?.let { normalized }
            }
            YOUTUBE -> YouTubeLinkParser.parse(url)?.canonicalUrl()
            VK -> VkLinkParser.parse(url)?.canonicalUrl()
            RUTUBE -> RutubeLinkParser.parse(url)?.canonicalUrl()
        }
    }

    companion object {
        fun fromId(id: String?): BrowserSource? = entries.firstOrNull { it.id == id }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SourceBrowserScreen(source: BrowserSource, onBack: () -> Unit, onDownload: (String) -> Unit) {
    val context = LocalContext.current
    val currentDownload by rememberUpdatedState(onDownload)
    var progress by remember { mutableIntStateOf(0) }
    var pageUrl by remember(source) { mutableStateOf(source.startUrl) }

    val web = remember(source) {
        WebView(context).apply {
            val view = this
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(view, true)
            settings.apply {
                userAgentString = userAgentString
                    .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun location(url: String) {
                    view.post { if (source.isSite(url)) pageUrl = url }
                }
                @JavascriptInterface fun pick(link: String) {
                    view.post {
                        if (!source.isSite(view.url)) return@post
                        val accepted = source.acceptedLink(link)
                        if (accepted == null) {
                            Toast.makeText(context, "Открой публикацию или видео для загрузки", Toast.LENGTH_SHORT).show()
                            return@post
                        }
                        runCatching { syncSession(source, context.applicationContext) }
                        currentDownload(accepted)
                    }
                }
            }, "MediaLoaderBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !source.allowsNavigation(request.url.toString())

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    progress = 0
                    pageUrl = url.orEmpty()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    pageUrl = url.orEmpty()
                    if (source.isSite(url)) view.evaluateJavascript(browserScript.replace("__SOURCE__", source.id), null)
                }
            }
            loadUrl(source.startUrl)
        }
    }
    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.removeJavascriptInterface("MediaLoaderBridge")
            web.webViewClient = WebViewClient()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
    BackHandler {
        if (web.canGoBack()) web.goBack() else onBack()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { if (web.canGoBack()) web.goBack() else onBack() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Text(source.title, style = MaterialTheme.typography.titleLarge)
        }
        if (progress in 0..99) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (source.acceptedLink(pageUrl) != null) {
            Button(
                onClick = {
                    source.acceptedLink(pageUrl)?.let { link ->
                        runCatching { syncSession(source, context.applicationContext) }
                        currentDownload(link)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Скачать")
            }
        }
    }
}

private fun syncSession(source: BrowserSource, context: android.content.Context) {
    val manager = CookieManager.getInstance()
    manager.flush()
    when (source) {
        BrowserSource.INSTAGRAM -> {
            val cookies = manager.getCookie("https://www.instagram.com").orEmpty()
            if (cookies.split(';').any { it.trim().startsWith("sessionid=") }) {
                MediaRepository.get(context).sessions.save(cookies)
            }
        }
        BrowserSource.YOUTUBE -> YoutubeDlAndroid(context).saveWebSession(
            manager.getCookie("https://www.youtube.com"), manager.getCookie("https://www.google.com"),
            manager.getCookie("https://accounts.google.com"))
        BrowserSource.VK -> VkYtDlpRuntime(context).saveWebSession(
            manager.getCookie("https://vk.com"), manager.getCookie("https://m.vk.com"),
            manager.getCookie("https://id.vk.com"), manager.getCookie("https://vkvideo.ru"))
        BrowserSource.RUTUBE -> RutubeYtDlpRuntime(context).saveWebSession(
            manager.getCookie("https://rutube.ru"), manager.getCookie("https://www.rutube.ru"),
            manager.getCookie("https://studio.rutube.ru"))
    }
}

/** Compact in-page actions follow feed cards; the native action appears only on a media URL. */
private val browserScript = """
(function () {
  var source = '__SOURCE__';
  if (window.__mediaLoaderInstalled === source) return;
  window.__mediaLoaderInstalled = source;
  function valid(url) {
    try {
      var u = new URL(url, location.href), p = u.pathname;
      if (u.protocol !== 'https:') return false;
      if (source === 'instagram') return /(^|\.)instagram\.com$/.test(u.hostname) && (/^\/(p|reel|tv)\/[A-Za-z0-9_-]{5,64}/.test(p) || /^\/stories\/[^/]+\/\d{5,25}/.test(p));
      if (source === 'youtube') return /(^|\.)youtube\.com$/.test(u.hostname) && (/^\/(shorts|live)\/[A-Za-z0-9_-]{11}/.test(p) || (p === '/watch' && /^[A-Za-z0-9_-]{11}$/.test(u.searchParams.get('v') || '')));
      if (source === 'vk') return /(^|\.)(vk\.com|vk\.ru|vkvideo\.ru)$/.test(u.hostname) && (/^\/(video|clip)/.test(p) || /(^|[?&])z=(video|clip)/.test(u.search));
      return /(^|\.)rutube\.ru$/.test(u.hostname) && (/^\/(live\/)?video\/(private\/)?[0-9a-fA-F]{32}\/?.*$/.test(p) || /^\/(play\/)?embed\/[0-9A-Za-z]+\/?$/.test(p));
    } catch (_) { return false; }
  }
  function send(url) { if (valid(url)) MediaLoaderBridge.pick(new URL(url, location.href).href); }
  function cardFor(anchor) {
    var selector = source === 'instagram' ? 'article' :
      source === 'youtube' ? 'ytm-rich-item-renderer,ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-item-section-renderer ytm-video-card-renderer,ytd-rich-grid-media,ytd-video-renderer' :
      source === 'vk' ? '[data-post-id],.VideoCard,.video_item,.video_card' :
      '.video-card,.video-card-container,article';
    return anchor.closest(selector);
  }
  var lastLocation = '';
  function report() {
    if (lastLocation === location.href) return;
    lastLocation = location.href;
    MediaLoaderBridge.location(lastLocation);
  }
  function decorate() {
    report();
    var anchors = document.querySelectorAll('a[href]'), count = 0;
    for (var i = 0; i < anchors.length && count < 80; i++) {
      var a = anchors[i];
      if (!valid(a.href)) continue;
      var card = cardFor(a);
      if (!card || card.querySelector('[data-medialoader-download]')) continue;
      var url = a.href;
      var b = document.createElement('button');
      b.type = 'button';
      b.innerHTML = '<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M4 19h16"/></svg>';
      b.setAttribute('data-medialoader-download', '1');
      b.setAttribute('aria-label', 'Скачать этот материал');
      b.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;vertical-align:middle;position:relative;z-index:2;width:40px;height:40px;max-width:100%;flex:0 0 40px;margin:6px;border:0;border-radius:12px;background:#7638e8;color:white;font:600 26px system-ui,sans-serif;line-height:1;box-sizing:border-box;cursor:pointer';
      b.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); send(this.dataset.url); });
      b.dataset.url = url;
      card.appendChild(b); count++;
    }
  }
  var timer;
  new MutationObserver(function (records) {
    if (records.every(function(r) { return r.addedNodes.length && Array.from(r.addedNodes).every(function(n) { return n.nodeType === 1 && n.hasAttribute('data-medialoader-download'); }); })) return;
    clearTimeout(timer); timer = setTimeout(decorate, 300);
  })
    .observe(document.documentElement, {childList:true,subtree:true});
  window.addEventListener('popstate', report);
  window.addEventListener('hashchange', report);
  decorate();
})();
""".trimIndent()
