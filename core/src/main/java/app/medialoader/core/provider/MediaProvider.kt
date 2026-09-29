package app.medialoader.core.provider

import app.medialoader.core.model.MediaItem

/** Each source owns its URL recognition, extraction and optional authentication. */
interface MediaProvider {
    val id: String
    fun supports(url: String): Boolean
    suspend fun resolve(url: String): List<MediaItem>
}

class ProviderException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { ACCESS_REQUIRED, UNSUPPORTED, TEMPORARY_FAILURE }
}

interface MediaResolver {
    /** Select provider and attempt public access before future authenticated fallback. */
    suspend fun resolve(url: String): List<MediaItem>
}
