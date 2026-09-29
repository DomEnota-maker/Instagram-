package app.medialoader.core.provider

import app.medialoader.core.model.MediaItem
import app.medialoader.core.model.MediaType
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Public post/reel extraction. No account credentials or third-party download service. */
class InstagramProvider(
    private val pageSource: suspend (String) -> String = ::fetchPage,
) : MediaProvider {
    override val id = "instagram"

    override fun supports(url: String): Boolean = shortcode(url) != null

    override suspend fun resolve(url: String): List<MediaItem> {
        val code = shortcode(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED, "Нужна ссылка на публикацию или Reel Instagram."
        )
        val page = try {
            pageSource("https://www.instagram.com/p/$code/")
        } catch (error: Exception) {
            throw ProviderException(
                ProviderException.Reason.TEMPORARY_FAILURE,
                "Не удалось открыть Instagram: ${error.localizedMessage ?: "ошибка сети"}"
            )
        }
        val embedded = extractEmbeddedMedia(page, code)
        if (embedded.isNotEmpty()) return embedded

        val video = meta(page, "og:video:secure_url") ?: meta(page, "og:video")
        val image = meta(page, "og:image")
        val mediaUrl = video ?: image
        if (mediaUrl != null && safeMediaUrl(mediaUrl)) {
            val isVideo = video != null
            return listOf(MediaItem(
                id = code, providerId = id,
                type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(mediaUrl, code, 0, isVideo),
                downloadUrl = mediaUrl, previewUrl = image,
            ))
        }
        throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED,
            "Не удалось получить медиа из этой страницы Instagram. Публикация может требовать входа или Instagram изменил ответ."
        )
    }

    companion object {
        fun shortcode(input: String): String? = try {
            val uri = URI(input.trim())
            val host = uri.host?.lowercase()?.removePrefix("www.")
            val path = uri.path?.trim('/')?.split('/') ?: emptyList()
            if (uri.scheme !in listOf("http", "https") || host != "instagram.com" ||
                path.size < 2 || path[0] !in setOf("p", "reel", "tv")) null
            else path[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,64}")) }
        } catch (_: Exception) { null }

        /** Instagram's shortcode alphabet encodes the numeric media id in base 64. */
        fun mediaId(code: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return code.fold(BigInteger.ZERO) { id, char ->
                val digit = alphabet.indexOf(char)
                require(digit >= 0) { "Invalid shortcode" }
                id.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
            }.toString()
        }

        fun extractEmbeddedMedia(html: String, code: String): List<MediaItem> {
            val expectedId = mediaId(code)
            val scripts = Regex("<script\\b[^>]*\\bdata-sjs\\b[^>]*>(.*?)</script>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (script in scripts.findAll(html)) {
                val data = try { JSONObject(script.groupValues[1]) } catch (_: Exception) { continue }
                val product = findMatchingMedia(data, code, expectedId, 0) ?: continue
                val carousel = product.optJSONArray("carousel_media")
                val entries = if (carousel != null && carousel.length() > 0)
                    (0 until carousel.length()).mapNotNull(carousel::optJSONObject)
                else listOf(product)
                val items = entries.mapIndexedNotNull { index, entry -> mediaItem(entry, code, index) }
                if (items.isNotEmpty()) return items
            }
            return emptyList()
        }

        private fun findMatchingMedia(node: Any?, code: String, expectedId: String, depth: Int): JSONObject? {
            if (depth > 40) return null
            when (node) {
                is JSONObject -> {
                    val gated = node.optJSONObject("if_not_gated_logged_out")
                    val candidate = gated ?: node
                    val matches = candidate.optString("code") == code ||
                        candidate.optString("pk") == expectedId || candidate.optString("id") == expectedId
                    if (matches && (candidate.has("carousel_media") || candidate.has("video_versions") ||
                            candidate.has("image_versions2"))) return candidate
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findMatchingMedia(node.opt(keys.next()), code, expectedId, depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> for (index in 0 until node.length()) {
                    findMatchingMedia(node.opt(index), code, expectedId, depth + 1)?.let { return it }
                }
            }
            return null
        }

        private fun mediaItem(value: JSONObject, code: String, index: Int): MediaItem? {
            val videos = value.optJSONArray("video_versions")
            val images = value.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val image = largest(images)?.optString("url")?.takeIf(::safeMediaUrl)
            val video = largest(videos)?.optString("url")?.takeIf(::safeMediaUrl)
            val isVideo = video != null
            val url = video ?: image ?: return null
            return MediaItem(
                id = "${code}_$index", providerId = "instagram",
                type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, code, index, isVideo),
                downloadUrl = url, previewUrl = image,
            )
        }

        private fun largest(values: JSONArray?): JSONObject? = values?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { safeMediaUrl(it.optString("url")) }
                .maxByOrNull { it.optInt("width", 0) }
        }

        private fun fileName(url: String, code: String, index: Int, isVideo: Boolean): String {
            val extension = if (isVideo) "mp4" else "jpg"
            val pathName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            val stem = pathName?.substringBeforeLast('.')?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
                ?: "instagram_$code"
            val suffix = if (index == 0) "" else "_${index + 1}"
            return "$stem$suffix.$extension"
        }

        fun meta(html: String, property: String): String? {
            val tags = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
            val attrs = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            return tags.findAll(html).mapNotNull { tag ->
                val values = attrs.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
                if (values["property"] == property || values["name"] == property) values["content"]?.let(::unescape) else null
            }.firstOrNull()
        }

        private fun unescape(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")
        fun safeMediaUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.host?.lowercase()?.let {
                it == "instagram.com" || it.endsWith(".instagram.com") || it == "fbcdn.net" || it.endsWith(".fbcdn.net")
            } == true
        } catch (_: Exception) { false }

        private suspend fun fetchPage(url: String): String = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                connection.setRequestProperty("Accept", "text/html")
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { it.readText().take(6_000_000) }
            } finally { connection.disconnect() }
        }
    }
}

class DefaultMediaResolver(private val providers: List<MediaProvider>) : MediaResolver {
    override suspend fun resolve(url: String): List<MediaItem> =
        (providers.firstOrNull { it.supports(url) } ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED, "Пока поддерживаются ссылки Instagram на публикации и Reels."
        )).resolve(url)
}
