package com.domenota.medialoader.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.GradientActionButton
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.ui.model.DownloadUiItem

@Composable
fun HomeScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onCheckClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    recentDownloads: List<DownloadUiItem> = emptyList(),
    onOpenDownload: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onShareDownload: (String) -> Unit = {},
    onHideDownload: (String) -> Unit = {},
    onRetryDownload: (String) -> Unit = {},
    onRenameDownload: (String, String) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Загрузчик", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(5.dp))
        Text("Скачивание медиа из Instagram, YouTube и VK",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceCard("instagram", "Instagram", "Фото и видео", Modifier.weight(1f))
            SourceCard("youtube", "YouTube", "Видео и музыка", Modifier.weight(1f))
            SourceCard("vk", "VK", "Фото и видео", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Ссылка на файл", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = url, onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Вставь ссылку") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (url.isNotEmpty()) IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Очистить ссылку")
                            }
                            IconButton(onClick = onPasteClick) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Вставить из буфера")
                            }
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline))
                Spacer(Modifier.height(14.dp))
                GradientActionButton("Поиск", onClick = onCheckClick,
                    enabled = url.isNotBlank(), leadingIcon = Icons.Rounded.Search)
                Spacer(Modifier.height(13.dp))
                Text("Вставь ссылку и выбери, что скачать",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Скачивание фото, видео и музыки",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeatureCard("Фото", "Посты и альбомы", Icons.Rounded.Image, Modifier.weight(1f))
                    FeatureCard("Видео", "Любое качество", Icons.Rounded.Videocam, Modifier.weight(1f))
                    FeatureCard("Музыка", "Аудиодорожки", Icons.Rounded.MusicNote, Modifier.weight(1f))
                }
            }
        }
        if (recentDownloads.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionTitle(title = "Последние загрузки", action = "Все", onActionClick = onOpenDownloads)
            Spacer(Modifier.height(12.dp))
            recentDownloads.forEach { item ->
                DownloadRow(item = item, onOpen = { onOpenDownload(item.id) },
                    onCancel = { onCancelDownload(item.id) }, onShare = { onShareDownload(item.id) },
                    onHide = { onHideDownload(item.id) }, onRetry = { onRetryDownload(item.id) },
                    onRename = { onRenameDownload(item.id, it) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(id: String, title: String, caption: String, modifier: Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.Start) {
            SourceIcon(id, 34.dp)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeatureCard(title: String, caption: String, icon: ImageVector, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(caption, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
