package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.HistoryRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {
    private class FakeDao : DownloadDao {
        private val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
        override fun observeAll(): Flow<List<DownloadEntity>> = rows
        override fun observeHiddenCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHidden(): Flow<List<DownloadEntity>> = rows
        override suspend fun restoreHidden() { rows.update { list -> list.map { it.copy(hidden = false) } } }
        override suspend fun clearHidden() { rows.update { list -> list.filterNot { it.hidden } } }
        override suspend fun deleteHidden(id: String) { rows.update { list -> list.filterNot { it.id == id && it.hidden } } }
        override suspend fun clearHistory() { rows.update { list ->
            list.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        } }
        override suspend fun all(): List<DownloadEntity> = rows.value.sortedByDescending { it.createdAtEpochMillis }
        override suspend fun byId(id: String): DownloadEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(item: DownloadEntity) {
            rows.update { list -> list.filterNot { it.id == item.id } + item }
        }
    }

    private class FakeEngine(
        private val durationMs: Long = 50,
        private val failing: Set<String> = emptySet(),
    ) : DownloadEngine {
        val started = CopyOnWriteArrayList<String>()
        val maxActive = AtomicInteger(0)
        private val active = AtomicInteger(0)

        override suspend fun download(
            task: DownloadTask,
            onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        ): DownloadResult {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            started.add(task.fileName)
            try {
                delay(durationMs)
                if (task.fileName in failing) throw DownloadFailure("boom")
                return DownloadResult("content://saved/${task.fileName}", 10L)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun item(name: String, type: MediaType = MediaType.PHOTO, size: Long? = null) = MediaItem(
        id = name, providerId = "instagram", type = type, originalName = name,
        downloadUrl = "https://scontent.fbcdn.net/$name", sizeBytes = size,
    )

    private suspend fun FakeDao.waitUntil(condition: (List<DownloadEntity>) -> Boolean) {
        withTimeout(5_000) { while (!condition(all())) delay(10) }
    }

    private fun withQueue(
        engine: DownloadEngine,
        existing: Set<String> = emptySet(),
        block: suspend (FakeDao, DownloadQueue) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val dao = FakeDao()
                val queue = DownloadQueue(HistoryRepository(dao), engine, { existing }, scope)
                block(dao, queue)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun videoAndItsAudioRunOneAfterAnotherInOrder() {
        val engine = FakeEngine()
        withQueue(engine) { dao, queue ->
            queue.enqueue(listOf(item("clip.mp4", MediaType.VIDEO), item("clip.mp3", MediaType.AUDIO)))
            dao.waitUntil { rows -> rows.size == 2 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(1, engine.maxActive.get())
            assertEquals(listOf("clip.mp4", "clip.mp3"), engine.started.toList())
            assertTrue(dao.all().all { it.savedUri != null })
        }
    }

    @Test fun manyTasksNeverOverlap() {
        val engine = FakeEngine(durationMs = 20)
        withQueue(engine) { dao, queue ->
            queue.enqueue((1..6).map { item("photo_$it.jpg") })
            dao.waitUntil { rows -> rows.size == 6 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(1, engine.maxActive.get())
        }
    }

    @Test fun onlyKnownSmallPhotosMayOverlapAndVideoWaits() {
        val engine = FakeEngine(durationMs = 150)
        withQueue(engine) { dao, queue ->
            val photos = (1..5).map { item("photo_$it.jpg", size = 350_000L) }
            queue.enqueue(photos + item("video.mp4", MediaType.VIDEO))
            dao.waitUntil { rows -> rows.size == 6 && rows.all { it.state == DownloadState.COMPLETED } }
            assertTrue(engine.maxActive.get() in 2..3)
            assertEquals("video.mp4", engine.started.last())
        }
    }

    @Test fun parallelPhotosPublishInSelectionOrder() {
        val started = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<String>()
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                started.add(task.fileName)
                delay(if (task.fileName == "photo_1.jpg") 180 else 20)
                task.publishAfter?.await()
                published.add(task.fileName)
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            queue.enqueue((1..3).map { item("photo_$it.jpg", size = 350_000L) })
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(3, started.size)
            assertEquals(listOf("photo_1.jpg", "photo_2.jpg", "photo_3.jpg"), published.toList())
        }
    }

    @Test fun existingFileGetsNextFreeSuffix() {
        withQueue(FakeEngine(), existing = setOf("clip.mp4", "clip_1.mp4")) { dao, queue ->
            val saved = queue.enqueue(listOf(item("clip.mp4", MediaType.VIDEO))).single()
            assertEquals("clip_2.mp4", saved.originalName)
            dao.waitUntil { rows -> rows.all { it.state == DownloadState.COMPLETED } }
        }
    }

    @Test fun generatedCdnNameIsReplacedBeforeItIsSaved() {
        withQueue(FakeEngine()) { dao, queue ->
            val saved = queue.enqueue(listOf(item("803925799_181200858_n.jpg"))).single()
            assertEquals("Instagram.jpg", saved.originalName)
            dao.waitUntil { rows -> rows.any { it.id == saved.id && it.state == DownloadState.COMPLETED } }
            assertEquals("Instagram.jpg", dao.byId(saved.id)?.originalName)
        }
    }

    @Test fun failedTaskShowsItsMessageAndDoesNotStopTheQueue() {
        withQueue(FakeEngine(failing = setOf("bad.jpg"))) { dao, queue ->
            val ids = queue.enqueue(listOf(item("bad.jpg"), item("good.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.size == 2 && rows.none { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING } }
            val bad = dao.byId(ids[0])!!
            assertEquals(DownloadState.FAILED, bad.state)
            assertEquals("boom", bad.errorMessage)
            assertEquals(DownloadState.COMPLETED, dao.byId(ids[1])!!.state)
        }
    }

    @Test fun retryKeepsPhotoInOriginalGroup() {
        val attempts = AtomicInteger(0)
        val engine = object : DownloadEngine {
            override suspend fun download(task: DownloadTask,
                onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                if (task.fileName == "photo_2.jpg" && attempts.getAndIncrement() == 0)
                    throw DownloadFailure("temporary")
                return DownloadResult("content://saved/${task.fileName}", 10L)
            }
        }
        withQueue(engine) { dao, queue ->
            val first = queue.enqueue(listOf(item("photo_1.jpg"), item("photo_2.jpg"), item("photo_3.jpg")))
            dao.waitUntil { rows -> rows.size == 3 && rows.none {
                it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
            } }
            val failed = dao.byId(first[1].id)!!
            assertEquals(DownloadState.FAILED, failed.state)
            queue.retry(failed)
            dao.waitUntil { rows -> rows.size == 3 && rows.all { it.state == DownloadState.COMPLETED } }
            assertEquals(first[0].groupId, dao.byId(failed.id)?.groupId)
            assertEquals(3, dao.all().size)
        }
    }

    @Test fun waitingTaskCanBeCancelledAndIsNeverStarted() {
        val engine = FakeEngine(durationMs = 300)
        withQueue(engine) { dao, queue ->
            val ids = queue.enqueue(listOf(item("first.jpg"), item("second.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.RUNNING } }
            queue.cancel(ids[1])
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.COMPLETED } }
            delay(100)
            assertEquals(DownloadState.CANCELLED, dao.byId(ids[1])!!.state)
            assertEquals(listOf("first.jpg"), engine.started.toList())
        }
    }

    @Test fun runningTaskCanBeCancelledAndNextOneStarts() {
        val engine = FakeEngine(durationMs = 10_000)
        withQueue(engine) { dao, queue ->
            val ids = queue.enqueue(listOf(item("long.jpg"), item("next.jpg"))).map { it.id }
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.RUNNING } }
            queue.cancel(ids[0])
            dao.waitUntil { rows -> rows.any { it.id == ids[0] && it.state == DownloadState.CANCELLED } }
            dao.waitUntil { rows -> rows.any { it.id == ids[1] && it.state == DownloadState.RUNNING } }
            // RUNNING is persisted before the worker enters the engine; wait for that handoff.
            withTimeout(5_000) { while (engine.started.size < 2) delay(10) }
            assertEquals(listOf("long.jpg", "next.jpg"), engine.started.toList())
        }
    }
}
