package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.theme.AccentPurple
import com.domenota.medialoader.ui.theme.Success

@Composable
fun DownloadRow(
    item: DownloadUiItem,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: () -> Unit = {},
    onHide: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRename: (String) -> Unit = {},
) {
    var renameDialog by remember { mutableStateOf(false) }
    var newName by remember(item.title) { mutableStateOf(StorageNaming.editableStem(item.title)) }
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("Переименовать файл") },
        text = {
            OutlinedTextField(
                newName,
                { newName = it },
                singleLine = true,
                label = { Text("Имя файла") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(newName); renameDialog = false },
                enabled = newName.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Отмена") } },
    )

    val (statusText, statusColor) = when (item.state) {
        DownloadUiState.QUEUED -> "В очереди" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadUiState.DOWNLOADING -> "Загружается…" to AccentPurple
        DownloadUiState.COMPLETED -> "Сохранено" to if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
            Color(0xFF16734D) else Success
        DownloadUiState.FAILED -> "Ошибка загрузки" to MaterialTheme.colorScheme.error
        DownloadUiState.CANCELLED -> "Отменено" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val sourceLabel = when (item.providerId?.lowercase()) {
        "instagram" -> "Instagram"
        "youtube" -> "YouTube"
        "vk" -> "VK"
        "rutube" -> "RUTUBE"
        else -> "Другое"
    }
    val type = item.title.substringAfterLast('.', "").uppercase().ifBlank { item.subtitle }
    val quality = Regex("[0-9]{3,4}p(?:60)?", RegexOption.IGNORE_CASE).find(item.title)?.value
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(82.dp).background(MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(14.dp))) {
                DownloadThumbnail(item.savedUri, item.previewUrl, item.kind, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceIcon(item.providerId, 22.dp)
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                val details = listOfNotNull(type, quality, item.sizeLabel).joinToString(" · ")
                Text(if (item.state == DownloadUiState.FAILED) item.subtitle else details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.state == DownloadUiState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.dateLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.state != DownloadUiState.COMPLETED) {
                    Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Действия с файлом")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (item.state == DownloadUiState.COMPLETED) {
                        DropdownMenuItem(text = { Text("Открыть") }, onClick = { menuExpanded = false; onOpen() })
                        DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menuExpanded = false; onShare() })
                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                            menuExpanded = false
                            newName = StorageNaming.editableStem(item.title)
                            renameDialog = true
                        })
                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menuExpanded = false; onHide() })
                    } else if (item.state == DownloadUiState.DOWNLOADING || item.state == DownloadUiState.QUEUED) {
                        DropdownMenuItem(text = { Text("Отмена") }, onClick = { menuExpanded = false; onCancel() })
                    } else {
                        DropdownMenuItem(text = { Text("Повторить") }, onClick = { menuExpanded = false; onRetry() })
                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menuExpanded = false; onHide() })
                    }
                }
            }
        }
        if (item.state == DownloadUiState.DOWNLOADING) {
            SimpleProgressBar(item.progress)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SimpleProgressBar(progress: Float?) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2f
        val width = size.width
        drawLine(track, Offset(0f, y), Offset(width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        val fraction = progress?.coerceIn(0f, 1f) ?: 0.12f
        if (fraction > 0f) {
            drawLine(
                AccentPurple,
                Offset(0f, y),
                Offset(width * fraction, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}
