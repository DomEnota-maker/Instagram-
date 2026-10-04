package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.GradientActionButton
import com.domenota.medialoader.ui.components.RemotePreview
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.PreviewMediaUi
import com.domenota.medialoader.ui.theme.AccentPurple

@Composable
fun PreviewScreen(
    url: String,
    state: AnalysisUiState,
    items: List<PreviewMediaUi> = emptyList(),
    onBack: () -> Unit,
    onToggleItem: (String) -> Unit = {},
    onToggleAllPhotos: () -> Unit = {},
    onDownloadSelected: () -> Unit = {},
    errorMessage: String? = null,
    busy: Boolean = false,
    onLogin: () -> Unit = {},
    onRetry: () -> Unit = {},
    asSheet: Boolean = false,
) {
    if (asSheet && state == AnalysisUiState.PREVIEW_READY &&
        items.isNotEmpty() && items.all { it.kind == MediaKind.IMAGE }) {
        PhotoSelectionSheet(items, onToggleItem, onToggleAllPhotos, onDownloadSelected,
            errorMessage, busy)
        return
    }
    if (asSheet && state == AnalysisUiState.PREVIEW_READY &&
        items.isNotEmpty() && items.none { it.kind == MediaKind.IMAGE }) {
        VideoAudioSelectionSheet(url, items, onToggleItem, onDownloadSelected, errorMessage, busy)
        return
    }
    val contentModifier = if (asSheet) Modifier.fillMaxWidth()
        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
    else Modifier.fillMaxSize()
    Column(
        modifier = contentModifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (!asSheet) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
        }

        if (!asSheet) {
            Spacer(Modifier.height(10.dp))
            HeroPreview(state = state, previewUrl = items.firstOrNull()?.previewUrl,
                isVideo = items.firstOrNull()?.kind == MediaKind.VIDEO)
            Spacer(Modifier.height(18.dp))
        }

        Text(
            text = when (state) {
                AnalysisUiState.PREVIEW_READY -> if (asSheet) "Скопированная ссылка" else "Найденное медиа"
                AnalysisUiState.ACCESS_REQUIRED -> "Нужна авторизация"
                AnalysisUiState.ERROR -> "Не удалось проверить ссылку"
                else -> "Проверяем ссылку…"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        if (asSheet && state == AnalysisUiState.PREVIEW_READY) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(10.dp))) {
                    RemotePreview(items.firstOrNull()?.previewUrl, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(if (url.contains("youtu", ignoreCase = true)) "youtube.com" else "instagram.com",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Text(
            text = url.ifBlank { "Ссылка не указана" },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state == AnalysisUiState.PREVIEW_READY) {
            Text("Instagram · ${items.count { it.kind != MediaKind.AUDIO }} файлов",
                style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        when (state) {
            AnalysisUiState.ANALYZING -> AnalysisCard()
            AnalysisUiState.ACCESS_REQUIRED -> AccessRequiredCard(onLogin = onLogin)
            AnalysisUiState.ERROR -> ErrorCard(message = errorMessage, onRetry = onRetry)
            AnalysisUiState.PREVIEW_READY -> MediaList(
                items = items,
                onToggleItem = onToggleItem,
                onToggleAllPhotos = onToggleAllPhotos,
                onDownloadSelected = onDownloadSelected,
                busy = busy,
                errorMessage = errorMessage,
            )
            AnalysisUiState.IDLE -> AnalysisCard()
        }
    }
}

@Composable
private fun VideoAudioSelectionSheet(
    url: String,
    items: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onDownloadSelected: () -> Unit,
    errorMessage: String?,
    busy: Boolean,
) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Скопированная ссылка", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp))) {
                RemotePreview(items.firstOrNull()?.previewUrl, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (url.contains("youtu", ignoreCase = true)) "youtube.com" else "instagram.com",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.filter { it.kind == MediaKind.AUDIO }.forEach { item ->
                Text("Музыка", color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaRow(item, onToggle = { onToggleItem(item.id) })
            }
            if (items.any { it.kind == MediaKind.AUDIO } && items.any { it.kind == MediaKind.VIDEO }) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline)
            }
            items.filter { it.kind == MediaKind.VIDEO }.forEach { item ->
                Text("Видео", color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaRow(item, onToggle = { onToggleItem(item.id) })
            }
        }
        GradientActionButton(text = if (busy) "Добавляем…" else "Скачать",
            onClick = onDownloadSelected, enabled = items.any { it.selected } && !busy)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PhotoSelectionSheet(
    photos: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onToggleAllPhotos: () -> Unit,
    onDownloadSelected: () -> Unit,
    errorMessage: String?,
    busy: Boolean,
) {
    val selected = photos.count { it.selected }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Column(Modifier.fillMaxWidth().height(maxHeight).padding(horizontal = 16.dp)) {
        Text("Скопированная ссылка", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggleAllPhotos)
            .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionMark(selected == photos.size)
            Text(if (selected == photos.size) "Все выбрано" else "Выбрать все",
                Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            photos.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f).aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { onToggleItem(item.id) }) {
                            RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                                SelectionMark(item.selected)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        GradientActionButton(text = if (busy) "Добавляем…" else "Скачать ($selected)",
            onClick = onDownloadSelected, enabled = selected > 0 && !busy)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroPreview(state: AnalysisUiState, previewUrl: String?, isVideo: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF1A2130), Color(0xFF242A38)),
                ),
                shape = RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state == AnalysisUiState.PREVIEW_READY) {
            RemotePreview(previewUrl, Modifier.fillMaxSize())
        }
        when (state) {
            AnalysisUiState.ANALYZING, AnalysisUiState.IDLE -> CircularProgressIndicator(
                color = AccentPurple,
                strokeWidth = 3.dp,
            )
            AnalysisUiState.ACCESS_REQUIRED -> Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnalysisUiState.ERROR -> Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            AnalysisUiState.PREVIEW_READY -> {
                if (isVideo) Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
                }
                Unit
            }
        }
    }
}

@Composable
private fun AnalysisCard() {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Проверяем ссылку…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ищем доступные фото, видео и аудиодорожку. Обычно это занимает несколько секунд.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessRequiredCard(onLogin: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Требуется авторизация", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Публичного доступа оказалось недостаточно. Войдите на странице Instagram: " +
                    "приложение не видит ваш пароль и хранит только сессию на этом устройстве.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            GradientActionButton(
                text = "Войти в Instagram",
                onClick = onLogin,
                leadingIcon = Icons.Rounded.Lock,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Не удалось получить медиа", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                message ?: "Проверьте ссылку и попробуйте ещё раз.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GradientActionButton(text = "Повторить", onClick = onRetry)
        }
    }
}

@Composable
private fun MediaList(
    items: List<PreviewMediaUi>,
    onToggleItem: (String) -> Unit,
    onToggleAllPhotos: () -> Unit,
    onDownloadSelected: () -> Unit,
    busy: Boolean,
    errorMessage: String?,
) {
    val selectedCount = items.count { it.selected }
    val photos = items.filter { it.kind == MediaKind.IMAGE }
    if (items.isEmpty()) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Instagram не вернул доступных файлов для этой ссылки.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items.filter { it.kind != MediaKind.IMAGE }.forEach { item ->
            MediaRow(item = item, onToggle = { onToggleItem(item.id) })
            Spacer(Modifier.height(10.dp))
        }
        if (photos.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleAllPhotos)
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectionMark(photos.all { it.selected })
                Text(if (photos.all { it.selected }) "Все фото выбраны" else "Выбрать все фото",
                    modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            photos.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { item ->
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { onToggleItem(item.id) }) {
                            RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                            Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                                SelectionMark(item.selected)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    if (photos.isNotEmpty()) Text("Выбрано: $selectedCount файлов", style = MaterialTheme.typography.bodyMedium)
    GradientActionButton(
        text = when {
            busy -> "Добавляем…"
            selectedCount == 0 -> "Выберите файлы"
            else -> "Скачать"
        },
        onClick = onDownloadSelected,
        enabled = selectedCount > 0 && !busy,
        leadingIcon = Icons.Rounded.Download,
    )
    errorMessage?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(modifier = Modifier.size(26.dp).background(
        if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MediaRow(
    item: PreviewMediaUi,
    onToggle: () -> Unit,
) {
    val icon = when (item.kind) {
        MediaKind.VIDEO -> Icons.Rounded.VideoFile
        MediaKind.AUDIO -> Icons.Rounded.AudioFile
        MediaKind.IMAGE -> Icons.Rounded.Image
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                RemotePreview(item.previewUrl, Modifier.fillMaxSize())
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = when (item.kind) {
                        MediaKind.VIDEO -> "MP4"
                        MediaKind.AUDIO -> "Классический MP3"
                        MediaKind.IMAGE -> item.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.sizeLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RadioButton(selected = item.selected, onClick = onToggle)
        }
    }
}
