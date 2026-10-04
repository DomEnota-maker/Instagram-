package com.domenota.medialoader.core.provider

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authorized Instagram endpoint. The endpoint URL, the app id and request headers live only here,
 * so the whole client can be replaced without touching InstagramProvider.
 */
class InstagramApiClient(
    private val transport: suspend (url: String, cookies: String) -> String = ::fetchInstagramApi,
) {
    /** Raw JSON of /api/v1/media/{id}/info/ (posts, Reels, carousels and Stories share this shape). */
    suspend fun mediaInfo(mediaId: String, cookies: String): String =
        transport("https://www.instagram.com/api/v1/media/$mediaId/info/", cookies)
}

private const val INSTAGRAM_WEB_APP_ID = "936619743392459"
private const val API_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal suspend fun fetchInstagramApi(url: String, cookies: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", API_USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cookie", cookies)
        connection.setRequestProperty("X-IG-App-ID", INSTAGRAM_WEB_APP_ID)
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?.let { connection.setRequestProperty("X-CSRFToken", it) }
        val status = connection.responseCode
        when {
            status in 200..299 -> connection.inputStream.bufferedReader().use { it.readText().take(6_000_000) }
            status == 301 || status == 302 || status == 401 || status == 403 -> throw ProviderException(
                ProviderException.Reason.ACCESS_REQUIRED,
                "Сессия Instagram недействительна или нет доступа. Войдите снова.", "instagram",
            )
            status == 404 -> throw ProviderException(
                ProviderException.Reason.UNSUPPORTED,
                "Публикация не найдена или недоступна для этого аккаунта.", "instagram",
            )
            else -> throw ProviderException(
                ProviderException.Reason.TEMPORARY_FAILURE,
                "Instagram временно не отвечает (HTTP $status).", "instagram",
            )
        }
    } finally {
        connection.disconnect()
    }
}
