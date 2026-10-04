package com.domenota.medialoader.ui.model

enum class AnalysisUiState {
    IDLE,
    ANALYZING,
    PREVIEW_READY,
    ACCESS_REQUIRED,
    ERROR,
}

enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
}

data class PreviewMediaUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeLabel: String?,
    val kind: MediaKind,
    val selected: Boolean,
    val previewUrl: String? = null,
)

enum class DownloadUiState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadUiItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeLabel: String?,
    val state: DownloadUiState,
    val progress: Float? = null,
    val dateLabel: String? = null,
    val previewUrl: String? = null,
    val savedUri: String? = null,
    val kind: MediaKind = MediaKind.IMAGE,
    val groupId: String? = null,
)
