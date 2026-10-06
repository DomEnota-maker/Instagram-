package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.domenota.medialoader.core.provider.InstagramProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** One bounded cache for publication covers. The UI only receives decoded images. */
object RemoteImageLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun fetch(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val source = previewSource(url) ?: return null
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Referer", source.referer)
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            )
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (output.size() <= MAX_IMAGE_BYTES) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_IMAGE_BYTES) return null
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = 4 },
            )?.also { cache.put(url, it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun previewSource(url: String): PreviewSource? {
        if (InstagramProvider.safeMediaUrl(url)) return PreviewSource("https://www.instagram.com/")
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        val youtubeImage = host == "i.ytimg.com" || host.endsWith(".ytimg.com") ||
            host == "i9.ytimg.com" || host.endsWith(".googleusercontent.com")
        val rutubeImage = host == "rutubelist.ru" || host.endsWith(".rutubelist.ru") ||
            host == "rutube.ru" || host.endsWith(".rutube.ru")
        return when {
            youtubeImage -> PreviewSource("https://www.youtube.com/")
            rutubeImage -> PreviewSource("https://rutube.ru/")
            else -> null
        }
    }

    private data class PreviewSource(val referer: String)

    private const val MAX_IMAGE_BYTES = 8_000_000
}
