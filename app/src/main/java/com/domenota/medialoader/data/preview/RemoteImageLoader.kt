package com.domenota.medialoader.data.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.domenota.medialoader.core.provider.InstagramProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One bounded cache for publication covers. The UI only receives decoded images. */
object RemoteImageLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun fetch(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (!InstagramProvider.safeMediaUrl(url)) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
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
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = 4 })?.also { cache.put(url, it) }
        } finally { connection.disconnect() }
    }

    private const val MAX_IMAGE_BYTES = 8_000_000
}
