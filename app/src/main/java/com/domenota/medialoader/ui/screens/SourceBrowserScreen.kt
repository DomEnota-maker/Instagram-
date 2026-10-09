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
import com.domenota.medialoader.core.logging.AppLog
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
                @JavascriptInterface fun debug(message: String) {
                    AppLog.i("Browser", "${source.id} · $message")
                }
                @JavascriptInterface fun pick(link: String) {
                    view.post {
                        if (!source.isSite(view.url)) return@post
                        val accepted = source.acceptedLink(link)
                        if (accepted == null) {
                            Toast.makeText(context, "Откройте публикацию или видео для загрузки", Toast.LENGTH_SHORT).show()
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
      if (source === 'instagram') return /(^|\.)instagram\.com$/.test(u.hostname) && (/^\/(p|reel|reels|tv)\/[A-Za-z0-9_-]{5,64}/.test(p) || /^\/stories\/[^/]+\/\d{5,25}/.test(p));
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
    var card = anchor.closest(selector);
    if (card || source !== 'instagram') return card;

    // Instagram changes its feed markup frequently and some feed cards are no longer <article>.
    // Walk upwards and choose the nearest reasonably-sized block that actually contains media.
    var node = anchor.parentElement;
    for (var depth = 0; node && node !== document.body && depth < 12; depth++, node = node.parentElement) {
      var rect = node.getBoundingClientRect();
      if (rect.width >= Math.min(240, innerWidth * .65) && rect.height >= 180 &&
          rect.width <= innerWidth * 1.15 && node.querySelector('img,video')) {
        return node;
      }
    }
    return null;
  }
  var lastLocation = '';
  function report() {
    if (lastLocation === location.href) return;
    lastLocation = location.href;
    MediaLoaderBridge.location(lastLocation);
  }
  var recoveryUntil = Date.now() + 15000;
  function recoverInstagramHome() {
    if (source !== 'instagram' || location.pathname !== '/' || Date.now() > recoveryUntil) return;
    var labels = Array.from(document.querySelectorAll('body *')).filter(function(el) {
      return el.children.length === 0 && /^(Использовать приложение|Use the app|Open in app)$/i.test((el.textContent || '').trim());
    });
    labels.forEach(function(label) {
      var bar = label;
      for (var i = 0; i < 4 && bar.parentElement; i++) {
        bar = bar.parentElement;
        var rect = bar.getBoundingClientRect();
        if (rect.width > innerWidth * .7 && rect.height < 140 && rect.bottom > innerHeight * .7) {
          var close = Array.from(bar.querySelectorAll('button,[role="button"]')).find(function(el) {
            var name = (el.getAttribute('aria-label') || el.textContent || '').trim();
            return /^(×|✕|✖|x|close|закрыть|not now|не сейчас)$/i.test(name);
          });
          if (close) close.click();
          break;
        }
      }
    });
    var locked = [document.body, document.documentElement].some(function(el) {
      return ['hidden', 'clip'].indexOf(getComputedStyle(el).overflowY) >= 0;
    });
    var chain = [], top = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    while (top && top !== document.body) { chain.push(top); top = top.parentElement; }
    var covers = chain.filter(function(el) {
      var style = getComputedStyle(el), r = el.getBoundingClientRect();
      return style.position === 'fixed' && r.width >= innerWidth * .95 &&
        r.height >= innerHeight * .85 && style.backgroundColor !== 'rgba(0, 0, 0, 0)' &&
        !el.querySelector('article,main,nav');
    });
    if (!locked && !covers.length) return;
    var dialogs = Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"]'));
    var visible = dialogs.some(function(el) {
      var r = el.getBoundingClientRect();
      if (!(r.width > 50 && r.height > 50 && r.bottom > 0 && r.top < innerHeight)) return false;
      return Array.from(el.querySelectorAll('button,a,input,[role="button"]')).some(function(control) {
        var c = control.getBoundingClientRect();
        return c.width > 0 && c.height > 0 && c.bottom > 0 && c.top < innerHeight;
      });
    });
    if (visible) return;
    dialogs.forEach(function(el) { el.remove(); });
    covers.forEach(function(el) { el.remove(); });
    document.body.style.overflow = 'auto';
    document.documentElement.style.overflow = 'auto';
  }
  function buttonMarkup() {
    return '<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M4 19h16"/></svg>';
  }
  function largestMedia(card) {
    var candidates = Array.from(card.querySelectorAll('video,img')).filter(function(el) {
      var r = el.getBoundingClientRect(), style = getComputedStyle(el);
      return r.width >= 180 && r.height >= 160 &&
        style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
    });
    candidates.sort(function(a, b) {
      var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return (br.width * br.height) - (ar.width * ar.height);
    });
    return candidates[0] || null;
  }
  function positionInstagramButton(button) {
    var media = button.__mediaLoaderMedia;
    if (!media || !document.documentElement.contains(media)) {
      button.style.display = 'none';
      return false;
    }
    var r = media.getBoundingClientRect();
    var visible = r.width >= 180 && r.height >= 160 &&
      r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
    if (!visible) {
      button.style.display = 'none';
      return true;
    }
    var left = Math.min(innerWidth - 48, Math.max(8, r.right - 48));
    var top = Math.max(8, Math.min(innerHeight - 48, r.top + 8));
    button.style.left = left + 'px';
    button.style.top = top + 'px';
    button.style.display = 'inline-flex';
    return true;
  }
  function installInstagramOverlay(card, url) {
    var media = largestMedia(card);
    if (!media) return null;
    var existing = Array.from(document.querySelectorAll('[data-medialoader-overlay="1"]')).find(function(el) {
      return el.dataset.url === url;
    });
    var b = existing || document.createElement('button');
    if (!existing) {
      b.type = 'button';
      b.innerHTML = buttonMarkup();
      b.setAttribute('data-medialoader-overlay', '1');
      b.setAttribute('aria-label', 'Скачать этот материал');
      b.style.cssText = 'position:fixed;z-index:2147483646;width:40px;height:40px;padding:0;border:0;border-radius:12px;background:#7638e8;color:white;align-items:center;justify-content:center;box-shadow:0 2px 10px rgba(0,0,0,.35);cursor:pointer;-webkit-tap-highlight-color:transparent';
      b.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        send(this.dataset.url);
      }, true);
      document.body.appendChild(b);
    }
    b.dataset.url = url;
    b.__mediaLoaderCard = card;
    b.__mediaLoaderMedia = media;
    b.__mediaLoaderSeen = true;
    positionInstagramButton(b);
    return b;
  }
  function reconcileInstagramOverlays() {
    var overlays = Array.from(document.querySelectorAll('[data-medialoader-overlay="1"]'));
    overlays.forEach(function(b) {
      if (!b.__mediaLoaderSeen || !positionInstagramButton(b)) b.remove();
      else b.__mediaLoaderSeen = false;
    });
  }
  function installButton(card, url) {
    if (source === 'instagram') return !!installInstagramOverlay(card, url);
    var existing = card.querySelector('[data-medialoader-download]');
    if (existing) {
      existing.dataset.url = url;
      return false;
    }
    var b = document.createElement('button');
    b.type = 'button';
    b.innerHTML = buttonMarkup();
    b.setAttribute('data-medialoader-download', '1');
    b.setAttribute('aria-label', 'Скачать этот материал');
    b.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;vertical-align:middle;position:relative;z-index:2;width:40px;height:40px;max-width:100%;flex:0 0 40px;margin:6px;border:0;border-radius:12px;background:#7638e8;color:white;font:600 26px system-ui,sans-serif;line-height:1;box-sizing:border-box;cursor:pointer';
    b.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); send(this.dataset.url); });
    b.dataset.url = url;
    card.appendChild(b);
    return true;
  }
  var lastInstagramDebugAt = 0;
  function decorate() {
    report();
    recoverInstagramHome();
    if (source === 'instagram') {
      document.querySelectorAll('[data-medialoader-overlay="1"]').forEach(function(b) { b.__mediaLoaderSeen = false; });
    }
    var anchors = document.querySelectorAll('a[href]'), count = 0, validCount = 0, cardCount = 0, seenUrls = {};
    for (var i = 0; i < anchors.length && count < 240; i++) {
      var a = anchors[i];
      if (!valid(a.href)) continue;
      validCount++;
      var url = new URL(a.href, location.href).href;
      if (seenUrls[url]) continue;
      var card = cardFor(a);
      if (!card) continue;
      cardCount++;
      seenUrls[url] = true;
      installButton(card, url);
      count++;
    }
    if (source === 'instagram') {
      reconcileInstagramOverlays();
      if (Date.now() - lastInstagramDebugAt > 5000) {
        lastInstagramDebugAt = Date.now();
        var overlays = document.querySelectorAll('[data-medialoader-overlay="1"]').length;
        MediaLoaderBridge.debug('anchors=' + anchors.length + ' valid=' + validCount +
          ' cards=' + cardCount + ' overlays=' + overlays + ' path=' + location.pathname);
      }
    }
  }
  var timer;
  function scheduleDecorate(delay) {
    clearTimeout(timer);
    timer = setTimeout(decorate, delay || 180);
  }
  new MutationObserver(function (records) {
    if (records.every(function(r) {
      return r.type === 'childList' && r.addedNodes.length &&
        Array.from(r.addedNodes).every(function(n) {
          return n.nodeType === 1 && n.hasAttribute &&
            (n.hasAttribute('data-medialoader-download') || n.hasAttribute('data-medialoader-overlay'));
        });
    })) return;
    scheduleDecorate(180);
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: source === 'instagram',
    attributeFilter: source === 'instagram' ? ['href'] : undefined
  });
  window.addEventListener('popstate', report);
  window.addEventListener('hashchange', report);
  decorate();
  if (source === 'instagram') {
    // Feed virtualization can update content without adding new DOM nodes. Reconcile periodically
    // so every loaded post gets a current button and recycled cards get the current permalink.
    var overlayFrame = 0;
    function scheduleOverlayPosition() {
      if (overlayFrame) return;
      overlayFrame = requestAnimationFrame(function() {
        overlayFrame = 0;
        document.querySelectorAll('[data-medialoader-overlay="1"]').forEach(function(b) {
          positionInstagramButton(b);
        });
      });
    }
    window.addEventListener('scroll', scheduleOverlayPosition, true);
    window.addEventListener('resize', scheduleOverlayPosition);
    var feedReconcile = setInterval(function() {
      if (!document.documentElement.contains(document.body)) {
        clearInterval(feedReconcile);
        return;
      }
      decorate();
    }, 700);
    var recovery = setInterval(function() {
      if (Date.now() > recoveryUntil || location.pathname !== '/') clearInterval(recovery);
      else recoverInstagramHome();
    }, 900);
  }
})();
""".trimIndent()
