package com.domenota.medialoader.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.usecase.AnalyzeMedia
import com.domenota.medialoader.data.usecase.QueueMedia
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.defaultSelectedIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val analysis: AnalysisUiState = AnalysisUiState.IDLE,
    val url: String = "",
    val items: List<MediaItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val message: String? = null,
    val enqueuing: Boolean = false,
)

class MediaLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository.get(application)
    private val analyzeMedia = AnalyzeMedia(repository)
    private val queueMedia = QueueMedia(repository)
    private var analysisJob: Job? = null

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    val downloads: StateFlow<List<DownloadEntity>> =
        repository.downloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hiddenCount: StateFlow<Int> =
        repository.hiddenCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val hiddenItems: StateFlow<List<DownloadEntity>> =
        repository.hiddenItems.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _ytDlpUpdate = MutableStateFlow(YoutubeDlAndroid(application).lastUpdateStatus)
    val ytDlpUpdate: StateFlow<String?> = _ytDlpUpdate.asStateFlow()
    private val _ytDlpUpdating = MutableStateFlow(false)
    val ytDlpUpdating: StateFlow<Boolean> = _ytDlpUpdating.asStateFlow()

    private val _youTubeCookies = MutableStateFlow(repository.hasYouTubeCookies)
    val youTubeCookies: StateFlow<Boolean> = _youTubeCookies.asStateFlow()
    private val _youTubeSignedIn = MutableStateFlow(repository.hasYouTubeCookies)
    val youTubeSignedIn: StateFlow<Boolean> = _youTubeSignedIn.asStateFlow()
    private val _vkSignedIn = MutableStateFlow(repository.hasVkCookies)
    val vkSignedIn: StateFlow<Boolean> = _vkSignedIn.asStateFlow()

    fun importYouTubeCookies(uri: Uri) {
        viewModelScope.launch {
            try {
                repository.importYouTubeCookies(uri)
                _youTubeCookies.value = true
                _youTubeSignedIn.value = true
                Toast.makeText(getApplication(), "Cookies YouTube импортированы", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(
                    getApplication(),
                    error.message ?: "Не удалось импортировать cookies",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun clearYouTubeCookies() {
        repository.clearYouTubeCookies()
        _youTubeCookies.value = false
        _youTubeSignedIn.value = false
    }

    fun updateYtDlp() {
        if (_ytDlpUpdating.value) return
        if (downloads.value.any {
                it.state == com.domenota.medialoader.core.model.DownloadState.QUEUED ||
                    it.state == com.domenota.medialoader.core.model.DownloadState.RUNNING
            }) {
            _ytDlpUpdate.value = "Дождитесь завершения загрузок"
            return
        }
        _ytDlpUpdating.value = true
        viewModelScope.launch {
            try {
                _ytDlpUpdate.value = repository.updateYtDlp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ytDlpUpdate.value = "Не удалось обновить yt-dlp: ${error.message ?: "проверьте соединение"}"
            } finally {
                _ytDlpUpdating.value = false
            }
        }
    }

    fun trashedUri(id: String): String? = repository.trashedUri(id)

    private val _signedIn = MutableStateFlow(repository.isLoggedIn())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Folder text for Settings; comes from StorageManager, not from the UI. */
    private val _downloadFolder = MutableStateFlow(repository.storageDisplayPath)
    val downloadFolder: StateFlow<String> = _downloadFolder.asStateFlow()

    fun changeFolder(name: String) {
        runCatching { repository.setDownloadFolder(name) }
            .onSuccess { _downloadFolder.value = repository.storageDisplayPath }
    }

    fun selectFolder(uri: Uri) {
        runCatching { repository.selectDownloadFolder(uri) }
            .onSuccess { _downloadFolder.value = repository.storageDisplayPath }
            .onFailure {
                Toast.makeText(getApplication(), "Не удалось выбрать папку", Toast.LENGTH_SHORT).show()
            }
    }

    fun needsLegacyStoragePermission(): Boolean = !repository.usesSelectedFolder

    /** Public access first; providers fall back to their saved sessions only when needed. */
    fun analyze(url: String) {
        val link = url.trim()
        analysisJob?.cancel()
        _preview.value = PreviewState(analysis = AnalysisUiState.ANALYZING, url = link)
        analysisJob = viewModelScope.launch {
            try {
                val items = analyzeMedia(link)
                _preview.value = PreviewState(
                    analysis = AnalysisUiState.PREVIEW_READY,
                    url = link,
                    items = items,
                    selectedIds = items.defaultSelectedIds(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProviderException) {
                _signedIn.value = repository.isLoggedIn()
                _youTubeSignedIn.value = repository.hasYouTubeCookies
                _youTubeCookies.value = repository.hasYouTubeCookies
                _vkSignedIn.value = repository.hasVkCookies
                _preview.value = PreviewState(
                    analysis = if (error.reason == ProviderException.Reason.ACCESS_REQUIRED) {
                        AnalysisUiState.ACCESS_REQUIRED
                    } else {
                        AnalysisUiState.ERROR
                    },
                    url = link,
                    message = when (error.reason) {
                        ProviderException.Reason.ACCESS_REQUIRED -> error.message ?: "Требуется вход."
                        ProviderException.Reason.TEMPORARY_FAILURE -> error.message ?: "Нет соединения. Повторите."
                        ProviderException.Reason.UNSUPPORTED -> error.message ?: "Медиа недоступно или ссылка не поддерживается."
                    },
                )
            } catch (_: Exception) {
                _preview.value = PreviewState(
                    analysis = AnalysisUiState.ERROR,
                    url = link,
                    message = "Не удалось проверить ссылку. Попробуйте ещё раз.",
                )
            }
        }
    }

    fun toggle(id: String) = _preview.update { state ->
        val item = state.items.firstOrNull { it.id == id }
        val selected = if (id in state.selectedIds) {
            state.selectedIds - id
        } else {
            val alternatives = if (item?.type == MediaType.VIDEO || item?.type == MediaType.AUDIO) {
                state.items.filter {
                    it.providerId == item.providerId &&
                        (it.type == MediaType.VIDEO || it.type == MediaType.AUDIO)
                }.map { it.id }.toSet()
            } else {
                emptySet()
            }
            (state.selectedIds - alternatives) + id
        }
        state.copy(selectedIds = selected)
    }

    fun toggleAllPhotos() = _preview.update { state ->
        val photoIds = state.items.filter { it.type == MediaType.PHOTO }.map { it.id }.toSet()
        state.copy(
            selectedIds = if (photoIds.all { it in state.selectedIds }) {
                state.selectedIds - photoIds
            } else {
                state.selectedIds + photoIds
            },
        )
    }

    fun showMessage(text: String) = _preview.update { it.copy(message = text) }

    fun downloadSelected(onQueued: () -> Unit) {
        downloadSelected(emptyMap(), onQueued)
    }

    fun downloadSelected(names: Map<String, String>, onQueued: () -> Unit) {
        val state = _preview.value
        val chosen = state.items.filter { it.id in state.selectedIds }
        if (chosen.isEmpty() || state.enqueuing) return
        val renamed = try {
            chosen.map { item ->
                item.copy(
                    originalName = StorageNaming.customName(
                        names[item.id] ?: item.originalName,
                        item.originalName,
                    ),
                    customName = true,
                )
            }
        } catch (_: IllegalArgumentException) {
            showMessage("Проверьте имя файла")
            return
        }
        _preview.update { it.copy(enqueuing = true, message = null) }
        viewModelScope.launch {
            try {
                ActiveDownloadService.start(getApplication())
                queueMedia(renamed)
                _preview.update { it.copy(enqueuing = false) }
                onQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _preview.update {
                    it.copy(enqueuing = false, message = "Не удалось добавить загрузку. Повторите.")
                }
            }
        }
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch { repository.cancel(id) }
    }

    fun hideDownload(id: String) {
        viewModelScope.launch {
            try {
                repository.hide(id)
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch { repository.retry(id) }
    }

    fun renameDownload(id: String, name: String) {
        viewModelScope.launch {
            try {
                repository.rename(id, name)
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось переименовать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restoreHidden(ids: Set<String>) {
        viewModelScope.launch {
            try {
                repository.restoreHidden(ids)
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось восстановить файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearHidden() {
        viewModelScope.launch {
            try {
                repository.clearHidden()
            } catch (_: Exception) {
                Toast.makeText(getApplication(), "Не удалось очистить корзину", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun onLoginFinished(success: Boolean) {
        _signedIn.value = repository.isLoggedIn()
        val state = _preview.value
        if (success && state.analysis == AnalysisUiState.ACCESS_REQUIRED && state.url.isNotBlank() &&
            YouTubeLinkParser.parse(state.url) == null && VkLinkParser.parse(state.url) == null) {
            analyze(state.url)
        }
    }

    fun onYouTubeLoginFinished(success: Boolean) {
        _youTubeSignedIn.value = repository.hasYouTubeCookies
        _youTubeCookies.value = repository.hasYouTubeCookies
        val state = _preview.value
        if (success && repository.hasYouTubeCookies && state.analysis == AnalysisUiState.ACCESS_REQUIRED &&
            state.url.isNotBlank() && YouTubeLinkParser.parse(state.url) != null) {
            analyze(state.url)
        }
    }

    fun onVkLoginFinished(success: Boolean) {
        _vkSignedIn.value = repository.hasVkCookies
        val state = _preview.value
        if (success && repository.hasVkCookies && state.analysis == AnalysisUiState.ACCESS_REQUIRED &&
            state.url.isNotBlank() && VkLinkParser.parse(state.url) != null) {
            analyze(state.url)
        }
    }

    fun logout() {
        repository.logout()
        _signedIn.value = false
    }

    fun logoutYouTube() {
        repository.clearYouTubeCookies()
        _youTubeCookies.value = false
        _youTubeSignedIn.value = false
    }

    fun logoutVk() {
        repository.clearVkCookies()
        _vkSignedIn.value = false
    }

    fun importSessionId(value: String): Boolean = runCatching {
        repository.importSessionId(value)
        _signedIn.value = true
        val state = _preview.value
        if (state.analysis == AnalysisUiState.ACCESS_REQUIRED && state.url.isNotBlank() &&
            YouTubeLinkParser.parse(state.url) == null && VkLinkParser.parse(state.url) == null) {
            analyze(state.url)
        }
    }.isSuccess
}
