package app.medialoader.core.provider

import app.medialoader.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InstagramProviderTest {
    @Test fun acceptsCanonicalPostsAndRejectsOtherHosts() {
        assertEquals("Ab_12-z", InstagramProvider.shortcode("https://www.instagram.com/reel/Ab_12-z/?utm_source=share"))
        assertNull(InstagramProvider.shortcode("https://instagram.com.evil.test/p/Ab_12-z/"))
        assertNull(InstagramProvider.shortcode("https://www.instagram.com/accounts/login/"))
    }

    @Test fun extractsEscapedVideoMetadataRegardlessOfAttributeOrder() = runBlocking {
        val provider = InstagramProvider { """<meta content="https://scontent.fbcdn.net/video.mp4?a=1&amp;b=2" property="og:video" /><meta property="og:image" content="https://scontent.fbcdn.net/cover.jpg">""" }
        val item = provider.resolve("https://instagram.com/p/Ab_12-z/").single()
        assertEquals(MediaType.VIDEO, item.type)
        assertEquals("https://scontent.fbcdn.net/video.mp4?a=1&b=2", item.downloadUrl)
        assertEquals("instagram_Ab_12-z.mp4", item.originalName)
    }

    @Test fun loginWallIsExplicit() = runBlocking {
        val provider = InstagramProvider { "<html>Log in</html>" }
        try {
            provider.resolve("https://instagram.com/p/Ab_12-z/")
            fail("Expected access error")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Reason.ACCESS_REQUIRED, error.reason)
        }
    }

    @Test fun rejectsUntrustedMediaHost() {
        assertFalse(InstagramProvider.safeMediaUrl("https://fbcdn.net.evil.test/a.jpg"))
        assertTrue(InstagramProvider.safeMediaUrl("https://scontent.fbcdn.net/a.jpg"))
    }
}
