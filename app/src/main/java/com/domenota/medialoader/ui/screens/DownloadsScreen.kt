package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.EmptyStateCard
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState

private enum class DownloadSourceFilter(
    val label: String,
    val preferenceKey: String,
) {
    INSTAGRAM("Instagram", "downloads_filter_instagram"),
    YOUTUBE("YouTube", "downloads_filter_youtube"),
    VK("VK", "downloads_filter_vk"),
    OTHER("Другие", "downloads_filter_other"),
}

private fun DownloadSourceFilter.icon(): ImageVector = when (this) {
    DownloadSourceFilter.INSTAGRAM -> Icons.Rounded.PhotoCamera
    DownloadSourceFilter.YOUTUBE -> Icons.Rounded.PlayCircle
    DownloadSourceFilter.VK -> Icons.Rounded.VideoLibrary
    DownloadSourceFilter.OTHER -> Icons.Rounded.Folder
}

private fun sourceOf(providerId: String?): DownloadSourceFilter = when (providerId?.lowercase()) {
    "instagram" -> DownloadSourceFilter.INSTAGRAM
    "youtube" -> DownloadSourceFilter.YOUTUBE
    "vk" -> DownloadSourceFilter.VK
    else -> DownloadSourceFilter.OTHER
}

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
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var instagramEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.INSTAGRAM.preferenceKey, true)) }
    var youtubeEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.YOUTUBE.preferenceKey, true)) }
    var vkEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.VK.preferenceKey, true)) }
    var otherEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean(DownloadSourceFilter.OTHER.preferenceKey, true)) }
    var filterMenuExpanded by rememberSaveable { mutableStateOf(false) }

    val hasOther = items.any { sourceOf(it.providerId) == DownloadSourceFilter.OTHER }
    val visibleSources = buildList {
        add(DownloadSourceFilter.INSTAGRAM)
        add(DownloadSourceFilter.YOUTUBE)
        add(DownloadSourceFilter.VK)
        if (hasOther) add(DownloadSourceFilter.OTHER)
    }

    fun isEnabled(source: DownloadSourceFilter): Boolean = when (source) {
        DownloadSourceFilter.INSTAGRAM -> instagramEnabled
        DownloadSourceFilter.YOUTUBE -> youtubeEnabled
        DownloadSourceFilter.VK -> vkEnabled
        DownloadSourceFilter.OTHER -> otherEnabled
    }

    fun setEnabled(source: DownloadSourceFilter, enabled: Boolean) {
        val enabledCount = visibleSources.count(::isEnabled)
        if (!enabled && isEnabled(source) && enabledCount <= 1) return

        when (source) {
            DownloadSourceFilter.INSTAGRAM -> instagramEnabled = enabled
            DownloadSourceFilter.YOUTUBE -> youtubeEnabled = enabled
            DownloadSourceFilter.VK -> vkEnabled = enabled
            DownloadSourceFilter.OTHER -> otherEnabled = enabled
        }
        prefs.edit().putBoolean(source.preferenceKey, enabled).apply()
    }

    fun enableAll() {
        instagramEnabled = true
        youtubeEnabled = true
        vkEnabled = true
        if (hasOther) otherEnabled = true
        prefs.edit()
            .putBoolean(DownloadSourceFilter.INSTAGRAM.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.YOUTUBE.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.VK.preferenceKey, true)
            .putBoolean(DownloadSourceFilter.OTHER.preferenceKey, true)
            .apply()
    }

    val allEnabled = visibleSources.all(::isEnabled)
    val filteredItems = items.filter { isEnabled(sourceOf(it.providerId)) }
    val groups = filteredItems.groupBy { it.groupId ?: it.id }.values.toList()
    val active = groups.filter { group -> group.any { it.state == DownloadUiState.QUEUED || it.state == DownloadUiState.DOWNLOADING } }
    val history = groups - active.toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Загрузки",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(22.dp)).clickable { filterMenuExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("Источник", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp))
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = filterMenuExpanded,
                    onDismissRequest = { filterMenuExpanded = false },
                ) {
                    Text(
                        "Источники",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("Все источники") },
                        onClick = { enableAll() },
                        leadingIcon = {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = allEnabled,
                                onCheckedChange = { checked -> if (checked) enableAll() },
                            )
                        },
                    )
                    HorizontalDivider()
                    visibleSources.forEach { source ->
                        val checked = isEnabled(source)
                        DropdownMenuItem(
                            text = { Text(source.label) },
                            onClick = { setEnabled(source, !checked) },
                            leadingIcon = {
                                if (source == DownloadSourceFilter.OTHER)
                                    Icon(source.icon(), contentDescription = null)
                                else SourceIcon(source.name.lowercase(), 24.dp)
                            },
                            trailingIcon = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { setEnabled(source, it) },
                                )
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("Все", null, allEnabled, onClick = ::enableAll)
            visibleSources.forEach { source ->
                SourceChip(source.label,
                    source.name.lowercase().takeIf { source != DownloadSourceFilter.OTHER },
                    isEnabled(source), onClick = { setEnabled(source, !isEnabled(source)) })
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionTitle(title = "Активные")
        Spacer(Modifier.height(12.dp))
        if (active.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Rounded.Download,
                title = if (items.isNotEmpty() && filteredItems.isEmpty()) {
                    "Нет задач из выбранных источников"
                } else {
                    "Сейчас ничего не скачивается"
                },
                text = if (items.isNotEmpty() && filteredItems.isEmpty()) {
                    "Измените фильтр источников в правом верхнем углу"
                } else {
                    "Новые задачи появятся здесь сразу после запуска загрузки"
                },
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
                title = if (items.isNotEmpty() && filteredItems.isEmpty()) {
                    "Нет файлов из выбранных источников"
                } else {
                    "История пока пустая"
                },
                text = if (items.isNotEmpty() && filteredItems.isEmpty()) {
                    "Выберите другой источник или включите все источники"
                } else {
                    "Скачанные фото, видео и аудио будут собраны здесь"
                },
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

@Composable
private fun SourceChip(label: String, source: String?, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    Row(Modifier.background(color, shape)
        .border(1.dp, if (active) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
        .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (source != null) SourceIcon(source, 22.dp)
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else
            MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
    }
}
