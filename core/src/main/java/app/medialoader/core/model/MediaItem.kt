package app.medialoader.core.model

/** Source-neutral metadata supplied by a future provider. */
data class MediaItem(
    val id: String,
    val providerId: String,
    val type: MediaType,
    val originalName: String,
    val downloadUrl: String,
    val previewUrl: String? = null,
    val sizeBytes: Long? = null,
)

enum class MediaType { PHOTO, VIDEO, AUDIO }
