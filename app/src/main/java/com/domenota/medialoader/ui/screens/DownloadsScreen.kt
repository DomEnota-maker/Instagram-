package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.EmptyStateCard
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState

@Composable
fun DownloadsScreen(
    items: List<DownloadUiItem>,
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
) {
    val groups = items.groupBy { it.groupId ?: it.id }.values.toList()
    val active = groups.filter { group -> group.any { it.state == DownloadUiState.QUEUED || it.state == DownloadUiState.DOWNLOADING } }
    val history = groups - active.toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Загрузки", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))
        SectionTitle(title = "Активные")
        Spacer(Modifier.height(12.dp))
        if (active.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Rounded.Download,
                title = "Сейчас ничего не скачивается",
                text = "Новые задачи появятся здесь сразу после запуска загрузки",
            )
        } else {
            DownloadRows(active, onOpenDownload, onCancelDownload, onShareDownload, onHideDownload, onRetryDownload, onRenameDownload)
        }

        Spacer(Modifier.height(28.dp))
        SectionTitle(title = "История")
        Spacer(Modifier.height(12.dp))
        if (history.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Rounded.Download,
                title = "История пока пустая",
                text = "Скачанные фото, видео и аудио будут собраны здесь",
            )
        } else {
            DownloadRows(history, onOpenDownload, onCancelDownload, onShareDownload, onHideDownload, onRetryDownload, onRenameDownload)
        }
    }
}

@Composable
private fun DownloadRows(
    items: List<List<DownloadUiItem>>,
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
) {
    items.forEachIndexed { index, group ->
        if (group.size > 1) {
            var expanded by rememberSaveable(group.first().groupId) { mutableStateOf(false) }
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cover = group.first()
                    DownloadThumbnail(cover.savedUri, cover.previewUrl, cover.kind, Modifier.size(44.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val completed = group.count { it.state == DownloadUiState.COMPLETED }
                        val failures = if (group.any { it.state == DownloadUiState.FAILED }) " · Есть ошибки" else ""
                        Text("Публикация · ${group.size} файлов",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Сохранено $completed из ${group.size}$failures",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Свернуть" else "Развернуть")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                group.forEachIndexed { childIndex, item ->
                    DownloadItemRow(item, onOpenDownload, onCancelDownload, onShareDownload,
                        onHideDownload, onRetryDownload, onRenameDownload)
                    if (childIndex != group.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            DownloadItemRow(group.single(), onOpenDownload, onCancelDownload, onShareDownload,
                onHideDownload, onRetryDownload, onRenameDownload)
        }
        if (index != items.lastIndex) Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadUiItem,
    onOpenDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onShareDownload: (String) -> Unit,
    onHideDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRenameDownload: (String, String) -> Unit,
) {
        DownloadRow(
            item = item,
            onOpen = { onOpenDownload(item.id) },
            onCancel = { onCancelDownload(item.id) },
            onShare = { onShareDownload(item.id) },
            onHide = { onHideDownload(item.id) },
            onRetry = { onRetryDownload(item.id) },
            onRename = { onRenameDownload(item.id, it) },
        )
}
