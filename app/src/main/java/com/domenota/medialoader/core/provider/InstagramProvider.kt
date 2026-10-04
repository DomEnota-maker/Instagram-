package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.storage.StorageNaming
import java.math.BigInteger
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instagram extraction. Order: public page → public embed → saved session (authorized API).
 * Stories are never public and always go straight to the session. The account password is never seen.
 */
class InstagramProvider(
    /** Saved session cookies or null. Used only after the public attempt fails (and always for Stories). */
    private val cookies: () -> String? = { null },
    private val apiClient: InstagramApiClient = InstagramApiClient(),
    private val pageSource: suspend (String) -> String = ::fetchInstagramPage,
) : MediaProvider {
    override val id = ID

    override fun supports(url: String): Boolean = InstagramLinkParser.parse(url) != null
    override fun safeDownloadUrl(url: String): Boolean = safeMediaUrl(url)

    override suspend fun resolve(url: String): List<MediaItem> {
        val link = InstagramLinkParser.parse(url) ?: throw ProviderException(
            ProviderException.Reason.UNSUPPORTED, "Нужна ссылка на публикацию, Reel или Stories Instagram.", ID,
        )
        return if (link.isStory) resolveStory(link) else resolvePost(link)
    }

    private suspend fun resolvePost(link: InstagramLink): List<MediaItem> {
        val code = link.id
        var loginWall = false
        // 1) Public access first, without any account. The page keeps the URL kind (p / reel / tv).
        val page = attempt({ pageSource(link.pageUrl()) }) { loginWall = true }
        if (page != null) {
            extractEmbeddedMedia(page, code).takeIf { it.isNotEmpty() }?.let { return it }
            mediaFromMeta(page, code)?.let { return listOf(it) }
        }
        val embedPage = attempt({ pageSource(link.embedUrl()) }) { loginWall = true }
        if (embedPage != null) {
            extractEmbedMedia(embedPage, code)?.let { return listOf(it) }
        }
        // 2) Public access failed: use the saved session if the user already signed in.
        val session = cookies()?.takeIf { it.isNotBlank() }
        if (session != null) {
            return parseApiResponse(apiClient.mediaInfo(mediaId(code), session), code)
        }
        // 3) Otherwise ask the UI to offer sign-in.
        throw ProviderException(
            if (page == null && embedPage == null && !loginWall) ProviderException.Reason.TEMPORARY_FAILURE
            else ProviderException.Reason.ACCESS_REQUIRED,
            "Не удалось получить медиа без входа. Страница может требовать авторизации или формат ответа изменился.",
            ID,
        )
    }

    /** Stories are never public: they always need a session. */
    private suspend fun resolveStory(link: InstagramLink): List<MediaItem> {
        val session = cookies()?.takeIf { it.isNotBlank() } ?: throw ProviderException(
            ProviderException.Reason.ACCESS_REQUIRED, "Stories доступны только после входа в аккаунт Instagram.", ID,
        )
        return parseApiResponse(apiClient.mediaInfo(link.id, session), link.id)
    }

    private suspend fun <T> attempt(block: suspend () -> T, onLoginWall: () -> Unit): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ProviderException) {
        if (error.reason == ProviderException.Reason.ACCESS_REQUIRED) onLoginWall()
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ID = "instagram"

        /** Builds items from one product node. A carousel is numbered from its first element. */
        fun itemsFromProduct(product: JSONObject, code: String): List<MediaItem> {
            val carousel = product.optJSONArray("carousel_media")
            if (carousel != null && carousel.length() > 0) {
                return (0 until carousel.length()).mapNotNull(carousel::optJSONObject)
                    .mapIndexedNotNull { index, entry -> mediaItem(entry, "${code}_${index + 1}", code, index + 1) }
            }
            return listOfNotNull(mediaItem(product, code, code, null))
        }

        /** Parses /api/v1/media/{id}/info/ (posts, Reels, carousels and Stories share this shape). */
        fun parseApiResponse(body: String, code: String): List<MediaItem> {
            val root = try { JSONObject(body) } catch (_: Exception) {
                throw ProviderException(
                    ProviderException.Reason.ACCESS_REQUIRED, "Сессия Instagram недействительна. Войдите снова.", ID,
                )
            }
            val product = root.optJSONArray("items")?.optJSONObject(0) ?: throw ProviderException(
                ProviderException.Reason.UNSUPPORTED, "Instagram не вернул медиа для этой ссылки.", ID,
            )
            return itemsFromProduct(product, code).ifEmpty {
                throw ProviderException(
                    ProviderException.Reason.UNSUPPORTED, "В ответе Instagram нет доступных файлов.", ID,
                )
            }
        }

        private fun mediaFromMeta(html: String, code: String): MediaItem? {
            val video = meta(html, "og:video:secure_url") ?: meta(html, "og:video")
            val image = meta(html, "og:image")
            val url = (video ?: image)?.takeIf(::safeMediaUrl) ?: return null
            return MediaItem(
                id = code, providerId = ID,
                type = if (video != null) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, null, video != null),
                downloadUrl = url, previewUrl = image?.takeIf(::safeMediaUrl), sourceGroupId = code,
            )
        }

        /** Fallback for the public embed page when the regular page exposes no media. */
        fun extractEmbedMedia(html: String, code: String): MediaItem? {
            fun urlProperty(key: String): String? {
                val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                val encoded = pattern.find(html)?.groupValues?.get(1) ?: return null
                val decoded = runCatching { JSONObject("{\"url\":\"$encoded\"}").getString("url") }.getOrNull()
                return decoded?.replace("&amp;", "&")?.takeIf(::safeMediaUrl)
            }
            val video = urlProperty("video_url")
            val image = urlProperty("display_url") ?: meta(html, "og:image")?.takeIf(::safeMediaUrl)
            val url = video ?: image ?: return null
            return MediaItem(
                id = code, providerId = ID,
                type = if (video != null) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, null, video != null),
                downloadUrl = url, previewUrl = image, sourceGroupId = code,
            )
        }

        /** Instagram's shortcode alphabet encodes the numeric media id in base 64. */
        fun mediaId(code: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return code.take(11).fold(BigInteger.ZERO) { id, char ->
                val digit = alphabet.indexOf(char)
                require(digit >= 0) { "Invalid shortcode" }
                id.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
            }.toString()
        }

        fun extractEmbeddedMedia(html: String, code: String): List<MediaItem> {
            val expectedId = mediaId(code)
            val scripts = Regex(
                "<script\\b[^>]*\\bdata-sjs\\b[^>]*>(.*?)</script>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            for (script in scripts.findAll(html)) {
                val data = try { JSONObject(script.groupValues[1]) } catch (_: Exception) { continue }
                val product = findMatchingMedia(data, code, expectedId, 0) ?: continue
                val items = itemsFromProduct(product, code)
                if (items.isNotEmpty()) return items
            }
            return emptyList()
        }

        private fun findMatchingMedia(node: Any?, code: String, expectedId: String, depth: Int): JSONObject? {
            if (depth > 40) return null
            when (node) {
                is JSONObject -> {
                    val candidate = node.optJSONObject("if_not_gated_logged_out") ?: node
                    val matches = candidate.optString("code") == code ||
                        candidate.optString("pk") == expectedId || candidate.optString("id") == expectedId
                    if (matches && (candidate.has("carousel_media") || candidate.has("video_versions") ||
                            candidate.has("image_versions2"))
                    ) return candidate
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

        private fun mediaItem(value: JSONObject, itemId: String, code: String, carouselIndex: Int?): MediaItem? {
            val videos = value.optJSONArray("video_versions")
            val images = value.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val imageEntry = largest(images)
            val videoEntry = largest(videos)
            val image = imageEntry?.optString("url")?.takeIf(::safeMediaUrl)
            val video = videoEntry?.optString("url")?.takeIf(::safeMediaUrl)
            val isVideo = video != null
            val url = video ?: image ?: return null
            return MediaItem(
                id = itemId, providerId = ID,
                type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                originalName = fileName(url, carouselIndex, isVideo,
                    value.optString("original_filename").ifBlank {
                        value.optString("filename").ifBlank {
                            (if (isVideo) videoEntry else imageEntry)?.optString("filename").orEmpty()
                        }
                    }),
                downloadUrl = url, previewUrl = image,
                audioAvailable = if (isVideo && value.has("has_audio")) value.optBoolean("has_audio") else null,
                sourceGroupId = code, position = carouselIndex,
            )
        }

        private fun largest(values: JSONArray?): JSONObject? = values?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { safeMediaUrl(it.optString("url")) }
                .maxByOrNull { it.optInt("width", 0) }
        }

        /** Prefer a supplied filename; otherwise accept readable CDN names, or use Instagram numbering. */
        private fun fileName(url: String, carouselIndex: Int?, isVideo: Boolean, supplied: String? = null): String {
            val pathName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            val stem = StorageNaming.meaningfulStem(supplied?.substringBeforeLast('.'))
                ?: StorageNaming.meaningfulStem(pathName?.substringBeforeLast('.'))
            return StorageNaming.mediaFileName(stem, carouselIndex, if (isVideo) "mp4" else "jpg")
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
                it == "instagram.com" || it.endsWith(".instagram.com") || it == "fbcdn.net" || it.endsWith(".fbcdn.net") ||
                    it == "cdninstagram.com" || it.endsWith(".cdninstagram.com")
            } == true
        } catch (_: Exception) { false }
    }
}
