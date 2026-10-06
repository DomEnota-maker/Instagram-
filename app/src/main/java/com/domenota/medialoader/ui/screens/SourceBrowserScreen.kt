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
                }

                override fun onPageFinished(view: WebView, url: String?) {
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
        Button(
            onClick = {
                if (source.isSite(web.url)) {
                    web.evaluateJavascript(browserScript.replace("__SOURCE__", source.id), null)
                    web.evaluateJavascript("window.MediaLoaderPick && window.MediaLoaderPick()", null)
                } else Toast.makeText(context, "Вернись на страницу ${source.title}", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Скачать")
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

/** In-page buttons follow dynamic feeds. The fixed native button remains visible if a site changes markup. */
private val browserScript = """
(function () {
  if (window.__mediaLoaderInstalled) return;
  window.__mediaLoaderInstalled = true;
  var source = '__SOURCE__';
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
  function send(url) { MediaLoaderBridge.pick(valid(url) ? new URL(url, location.href).href : ''); }
  function nearestLink(card) {
    var links = card.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) if (valid(links[i].href)) return links[i].href;
    return '';
  }
  function cardFor(anchor) {
    var selector = source === 'instagram' ? 'article' :
      source === 'youtube' ? 'ytd-rich-grid-media,ytd-video-renderer,ytm-rich-item-renderer,ytm-compact-video-renderer' :
      source === 'vk' ? '[data-post-id],.VideoCard,.video_item' :
      '.video-card,.video-card-container,article';
    return anchor.closest(selector) || anchor.parentElement;
  }
  function decorate() {
    var anchors = document.querySelectorAll('a[href]'), count = 0;
    for (var i = 0; i < anchors.length && count < 150; i++) {
      var a = anchors[i];
      if (!valid(a.href)) continue;
      var card = cardFor(a);
      if (!card || card.querySelector('[data-medialoader-download]')) continue;
      var url = a.href;
      var b = document.createElement('button');
      b.type = 'button'; b.textContent = '⬇ Скачать';
      b.setAttribute('data-medialoader-download', '1');
      b.style.cssText = 'display:block;position:relative;z-index:2147483647;margin:8px 12px;padding:11px 18px;border:0;border-radius:14px;background:#7638e8;color:white;font:bold 15px system-ui,sans-serif;box-shadow:0 3px 12px #0006;cursor:pointer';
      b.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); send(this.dataset.url); });
      b.dataset.url = url;
      card.appendChild(b); count++;
    }
  }
  window.MediaLoaderPick = function () {
    if (valid(location.href)) return send(location.href);
    var buttons = Array.from(document.querySelectorAll('[data-medialoader-download]'));
    var visible = buttons.find(function(b) { var r = b.getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight - 40; });
    send(visible ? visible.dataset.url : '');
  };
  var timer;
  new MutationObserver(function () { clearTimeout(timer); timer = setTimeout(decorate, 180); })
    .observe(document.documentElement, {childList:true,subtree:true});
  decorate();
})();
""".trimIndent()
