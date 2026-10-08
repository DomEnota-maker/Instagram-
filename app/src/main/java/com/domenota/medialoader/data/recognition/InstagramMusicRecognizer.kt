package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource

/**
 * Instagram-first policy:
 * source artist/title wins; PCM decoding and acoustic recognition are lazy fallbacks.
 */
class InstagramMusicRecognizer(
    private val acousticProvider: MusicRecognitionProvider = ShazamRecognitionProvider(),
) {
    suspend fun recognize(
        item: MediaItem,
        pcmLoader: suspend () -> ShortArray,
    ): MusicRecognitionResult? {
        if (item.providerId != InstagramProvider.ID || item.type != MediaType.AUDIO) return null

        val artist = item.audioArtist?.trim()?.takeIf { it.isNotBlank() }
        val title = item.audioTitle?.trim()?.takeIf { it.isNotBlank() }
        if (artist != null && title != null) {
            return MusicRecognitionResult(
                title = title,
                artist = artist,
                source = RecognitionSource.SOURCE_METADATA,
            )
        }

        return acousticProvider.recognize(pcmLoader())
    }
}
