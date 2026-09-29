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
        assertEquals("video.mp4", item.originalName)
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

    @Test fun extractsPublicCarouselWhenOpenGraphMetadataIsMissing() = runBlocking {
        val id = InstagramProvider.mediaId("Ab_12-z")
        val page = """<script type="application/json" data-sjs>{"payload":{"item":{"if_not_gated_logged_out":{"pk":"$id","carousel_media":[{"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/photoA.jpg","width":640},{"url":"https://scontent.fbcdn.net/photoA_hd.jpg","width":1080}]}},{"video_versions":[{"url":"https://scontent.fbcdn.net/movie.mp4","width":720}],"image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/cover.jpg","width":400}]}}]}}}}</script>"""
        val items = InstagramProvider { page }.resolve("https://instagram.com/p/Ab_12-z/")
        assertEquals(2, items.size)
        assertEquals("https://scontent.fbcdn.net/photoA_hd.jpg", items[0].downloadUrl)
        assertEquals(MediaType.PHOTO, items[0].type)
        assertEquals(MediaType.VIDEO, items[1].type)
        assertEquals("https://scontent.fbcdn.net/cover.jpg", items[1].previewUrl)
        assertTrue(items[1].originalName.endsWith("_2.mp4"))
    }

    @Test fun ignoresUnrelatedProductsAndUnsafeUrls() = runBlocking {
        val page = """<script data-sjs>{"item":{"code":"Other9","image_versions2":{"candidates":[{"url":"https://scontent.fbcdn.net/wrong.jpg"}]}}}</script>"""
        assertTrue(InstagramProvider.extractEmbeddedMedia(page, "Ab_12-z").isEmpty())
    }
}
