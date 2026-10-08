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
    fun requiresPcm(item: MediaItem): Boolean =
        isInstagramAudio(item) && sourceMetadata(item) == null

    fun sourceMetadata(item: MediaItem): MusicRecognitionResult? {
        if (!isInstagramAudio(item)) return null

        val artist = item.audioArtist?.trim()?.takeIf { it.isNotBlank() }
        val title = item.audioTitle?.trim()?.takeIf { it.isNotBlank() }
        if (artist == null || title == null || isGenericInstagramTitle(title)) return null

        return MusicRecognitionResult(
            title = title,
            artist = artist,
            source = RecognitionSource.SOURCE_METADATA,
        )
    }

    suspend fun recognize(
        item: MediaItem,
        pcmLoader: suspend () -> ShortArray,
    ): MusicRecognitionResult? {
        if (!isInstagramAudio(item)) return null
        sourceMetadata(item)?.let { return it }
        return acousticProvider.recognize(pcmLoader())
    }

    private fun isInstagramAudio(item: MediaItem): Boolean =
        item.providerId == InstagramProvider.ID && item.type == MediaType.AUDIO

    private fun isGenericInstagramTitle(title: String): Boolean {
        val normalized = title.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized in GENERIC_TITLES
    }

    private companion object {
        val GENERIC_TITLES = setOf(
            "original audio",
            "original sound",
            "original music",
            "оригинальное аудио",
            "оригинальный звук",
            "оригинальная аудиодорожка",
            "оригинальная музыка",
        )
    }
}
