package app.medialoader.core.provider

import app.medialoader.core.model.MediaItem
import app.medialoader.core.model.MediaType
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public page metadata only. No credentials, private posts, or third-party extraction service. */
class InstagramProvider(
    private val pageSource: suspend (String) -> String = ::fetchPage,
) : MediaProvider {
    override val id = "instagram"

    override fun supports(url: String): Boolean = shortcode(url) != null

    override suspend fun resolve(url: String): List<MediaItem> {
        val code = shortcode(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED, "Нужна ссылка на публикацию или Reel Instagram."
        )
        val page = try { pageSource("https://www.instagram.com/${URI(url.trim()).path.trim('/').substringBefore('/')}/$code/") }
        catch (error: Exception) {
            throw ProviderException(ProviderException.Reason.TEMPORARY_FAILURE, "Не удалось открыть страницу Instagram: ${error.localizedMessage ?: "ошибка сети"}")
        }
        val video = meta(page, "og:video:secure_url") ?: meta(page, "og:video")
        val photo = meta(page, "og:image")
        val mediaUrl = video ?: photo ?: throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED,
            "Instagram не открыл медиа без входа. Закрытые публикации и страницы с ограничением доступа пока не поддерживаются."
        )
        if (!safeMediaUrl(mediaUrl)) throw ProviderException(
            ProviderException.Reason.TEMPORARY_FAILURE, "Страница не содержит безопасного адреса медиа."
        )
        val isVideo = video != null
        val type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO
        return listOf(MediaItem(
            id = code, providerId = id, type = type,
            originalName = "instagram_$code.${if (isVideo) "mp4" else "jpg"}",
            downloadUrl = mediaUrl, previewUrl = photo,
        ))
    }

    companion object {
        fun shortcode(input: String): String? {
            return try {
            val uri = URI(input.trim())
            val host = uri.host?.lowercase()?.removePrefix("www.")
            if (uri.scheme !in listOf("http", "https") || host != "instagram.com") return null
            val segments = uri.path.trim('/').split('/')
            if (segments.size < 2 || segments[0] !in setOf("p", "reel", "tv")) return null
            segments[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,64}")) }
            } catch (_: Exception) { null }
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
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "text/html")
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
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
