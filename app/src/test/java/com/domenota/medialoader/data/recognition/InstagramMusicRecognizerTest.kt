package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramMusicRecognizerTest {
    @Test fun completeInstagramMetadataSkipsAcousticRecognition() = runBlocking {
        var calls = 0
        val acoustic = object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
                calls++
                return null
            }
        }
        val recognizer = InstagramMusicRecognizer(acoustic)
        val item = MediaItem(
            id = "music",
            providerId = "instagram",
            type = MediaType.AUDIO,
            originalName = "Artist - Song.mp3",
            downloadUrl = "https://video.xx.fbcdn.net/song.m4a",
            audioArtist = "Artist",
            audioTitle = "Song",
        )

        val result = recognizer.recognize(item, ShortArray(0))

        assertEquals("Artist", result?.artist)
        assertEquals("Song", result?.title)
        assertEquals(RecognitionSource.SOURCE_METADATA, result?.source)
        assertEquals(0, calls)
    }

    @Test fun incompleteInstagramMetadataFallsBackToAcousticRecognition() = runBlocking {
        var calls = 0
        val acoustic = object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
                calls++
                return MusicRecognitionResult(
                    trackId = "55",
                    title = "Found Song",
                    artist = "Found Artist",
                    source = RecognitionSource.SHAZAM_DIRECT,
                )
            }
        }
        val recognizer = InstagramMusicRecognizer(acoustic)
        val item = MediaItem(
            id = "music",
            providerId = "instagram",
            type = MediaType.AUDIO,
            originalName = "Instagram.mp3",
            downloadUrl = "https://video.xx.fbcdn.net/song.m4a",
            audioArtist = "Artist only",
        )

        val result = recognizer.recognize(item, ShortArray(64_000))

        assertEquals("55", result?.trackId)
        assertEquals(1, calls)
    }
}
