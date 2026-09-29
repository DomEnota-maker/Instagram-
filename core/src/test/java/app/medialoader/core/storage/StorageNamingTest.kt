package app.medialoader.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageNamingTest {
    @Test fun preservesOriginalNameAndNumbersCollisions() {
        assertEquals("image.jpg", StorageNaming.availableName("image.jpg", emptySet()))
        assertEquals(
            "image_2.jpg",
            StorageNaming.availableName("image.jpg", setOf("image.jpg", "image_1.jpg")),
        )
        assertEquals("video_1", StorageNaming.availableName("video", setOf("video")))
    }

    @Test fun rejectsPathAsFilename() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageNaming.availableName("../image.jpg", emptySet())
        }
    }
}
