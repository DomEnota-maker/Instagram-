package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.FileSizeFormatter
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun MediaType.kind(): MediaKind = when (this) {
    MediaType.PHOTO -> MediaKind.IMAGE
    MediaType.VIDEO -> MediaKind.VIDEO
    MediaType.AUDIO -> MediaKind.AUDIO
}

private fun MediaType.label(): String = when (this) {
    MediaType.PHOTO -> "Фото · JPG"
    MediaType.VIDEO -> "Видео · MP4"
    MediaType.AUDIO -> "Аудио · MP3 (из видео)"
}

fun MediaItem.toPreviewUi(selected: Boolean) = PreviewMediaUi(
    id = id,
    title = originalName,
    subtitle = qualityLabel?.let { "$it · ${type.label()}" } ?: type.label(),
    sizeLabel = sizeBytes?.let(FileSizeFormatter::format) ?: if (type == MediaType.AUDIO)
        "Размер после извлечения" else "Размер неизвестен",
    kind = type.kind(),
    selected = selected,
    previewUrl = previewUrl,
)

fun DownloadEntity.toUi(): DownloadUiItem {
    val uiState = when (state) {
        DownloadState.QUEUED -> DownloadUiState.QUEUED
        DownloadState.RUNNING -> DownloadUiState.DOWNLOADING
        DownloadState.COMPLETED -> DownloadUiState.COMPLETED
        DownloadState.FAILED -> DownloadUiState.FAILED
        DownloadState.CANCELLED -> DownloadUiState.CANCELLED
    }
    val progress = if (state == DownloadState.RUNNING && sizeBytes != null && sizeBytes > 0) {
        (bytesDownloaded.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
    } else null
    val statusText = when (state) {
        DownloadState.FAILED -> errorMessage ?: "Ошибка загрузки"
        else -> mediaType.label()
    }
    return DownloadUiItem(
        id = id,
        title = originalName,
        subtitle = statusText,
        sizeLabel = if (state == DownloadState.COMPLETED) sizeBytes?.let(FileSizeFormatter::format) else null,
        state = uiState,
        progress = progress,
        dateLabel = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(createdAtEpochMillis)),
        previewUrl = previewUrl,
        savedUri = savedUri,
        kind = mediaType.kind(),
        groupId = groupId,
    )
}

/** Audio tracks are offered next to every video but are never selected by default. */
fun List<MediaItem>.defaultSelectedIds(): Set<String> =
    filter { if (it.providerId == "youtube") it.preselected else it.type != MediaType.AUDIO }
        .map { it.id }.toSet()
