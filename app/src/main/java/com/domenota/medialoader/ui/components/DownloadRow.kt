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

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    DownloadThumbnail(item.savedUri, item.previewUrl, item.kind, Modifier.fillMaxSize())
                    SourceBadge(item.providerId)
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                item.sizeLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.state == DownloadUiState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.dateLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.state == DownloadUiState.DOWNLOADING) {
                Spacer(Modifier.height(10.dp))
                SimpleProgressBar(progress = item.progress)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
            }
            when (item.state) {
                DownloadUiState.COMPLETED -> Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = onOpen,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) { Text("Открыть", maxLines = 1, fontSize = 12.sp) }
                        TextButton(
                            onClick = onShare,
                            modifier = Modifier.weight(1.35f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) { Text("Поделиться", maxLines = 1, fontSize = 12.sp) }
                        TextButton(
                            onClick = onHide,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) { Text("Удалить", maxLines = 1, fontSize = 12.sp) }
                    }
                    TextButton(onClick = {
                        newName = StorageNaming.editableStem(item.title)
                        renameDialog = true
                    }) {
                        Text("Переименовать")
                    }
                }
                DownloadUiState.QUEUED, DownloadUiState.DOWNLOADING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Отмена", maxLines = 1) }
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onRetry) { Text("Повторить", maxLines = 1) }
                    TextButton(onClick = onHide) { Text("Удалить", maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(providerId: String?) {
    when (providerId) {
        "youtube" -> Box(
            modifier = Modifier
                .padding(2.dp)
                .size(19.dp)
                .background(Color(0xFFFF0033), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "YouTube",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        "instagram" -> Box(
            modifier = Modifier
                .padding(2.dp)
                .size(19.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFC107),
                            Color(0xFFF44336),
                            Color(0xFFE1306C),
                            Color(0xFF833AB4),
                        ),
                        start = Offset(0f, 19f),
                        end = Offset(19f, 0f),
                    ),
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) {
                val stroke = 1.35.dp.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(3.2.dp.toPx(), 3.2.dp.toPx()),
                    style = Stroke(width = stroke),
                )
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension * 0.22f,
                    center = center,
                    style = Stroke(width = stroke),
                )
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension * 0.075f,
                    center = Offset(size.width * 0.75f, size.height * 0.25f),
                )
            }
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
