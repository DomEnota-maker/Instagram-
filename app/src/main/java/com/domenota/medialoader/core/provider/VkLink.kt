package com.domenota.medialoader.core.provider

import java.net.URI

data class VkLink(private val canonical: String) {
    fun canonicalUrl(): String = canonical
}

object VkLinkParser {
    fun parse(raw: String): VkLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (!isVkHost(host)) return null

        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val supportedPath = path.startsWith("/video", ignoreCase = true) ||
            path.startsWith("/clip", ignoreCase = true) ||
            path.equals("/video_ext.php", ignoreCase = true)
        val supportedQuery = query.contains("z=video", ignoreCase = true) ||
            query.contains("z=clip", ignoreCase = true)
        if (!supportedPath && !supportedQuery) return null

        val canonical = runCatching {
            URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path, uri.query, null).toString()
        }.getOrElse { raw.trim() }
        return VkLink(canonical)
    }

    private fun isVkHost(host: String): Boolean =
        host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru") ||
            host == "vk.ru" || host.endsWith(".vk.ru")
}
