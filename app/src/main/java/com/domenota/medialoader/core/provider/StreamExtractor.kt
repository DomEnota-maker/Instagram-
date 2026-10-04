package com.domenota.medialoader.core.provider

/** Everything the app needs to know about one video, independent of the library that found it. */
data class StreamInfo(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val formats: List<StreamFormat>,
)

data class StreamFormat(
    val formatId: String,
    val height: Int?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val ext: String?,
    val sizeBytes: Long?,
    val fps: Int?,
)

class ExtractionException(message: String, val kind: Kind = Kind.FAILED, cause: Throwable? = null) :
    Exception(message, cause) {
    /** UNAVAILABLE: private, removed or blocked video. FAILED: anything that may work later. */
    enum class Kind { UNAVAILABLE, FAILED }
}

/** Narrow seam in front of the extraction library (yt-dlp), so the library can be replaced. */
interface StreamExtractor {
    suspend fun extract(url: String): StreamInfo
}
