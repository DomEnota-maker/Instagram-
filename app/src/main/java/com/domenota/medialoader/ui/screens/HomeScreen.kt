package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.DownloadRow
import com.domenota.medialoader.ui.components.EmptyStateCard
import com.domenota.medialoader.ui.components.GradientActionButton
import com.domenota.medialoader.ui.components.SectionTitle
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.theme.AccentPurple
import com.domenota.medialoader.ui.theme.AccentPurpleSoft

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(AccentPurple, AccentPurpleSoft))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Загрузчик",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Сохраняйте фото, видео и музыку из Instagram, YouTube и VK",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Вставьте ссылку из Instagram, YouTube или VK…") },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Link, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить ссылку")
                        }
                    }
                    IconButton(onClick = onPasteClick) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Вставить из буфера")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )

        Spacer(Modifier.height(14.dp))

        GradientActionButton(
            text = "Проверить",
            onClick = onCheckClick,
            enabled = url.isNotBlank(),
            trailingIcon = Icons.Rounded.ArrowForward,
        )

        Spacer(Modifier.height(30.dp))

        SectionTitle(title = "Последние загрузки", action = "Все", onActionClick = onOpenDownloads)
        Spacer(Modifier.height(12.dp))

        if (recentDownloads.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Rounded.Download,
                title = "Нет загрузок",
                text = "Вставьте ссылку Instagram, YouTube или VK, чтобы начать",
            )
        } else {
            recentDownloads.forEachIndexed { index, item ->
                DownloadRow(
                    item = item,
                    onOpen = { onOpenDownload(item.id) },
                    onCancel = { onCancelDownload(item.id) },
                    onShare = { onShareDownload(item.id) },
                    onHide = { onHideDownload(item.id) },
                    onRetry = { onRetryDownload(item.id) },
                    onRename = { onRenameDownload(item.id, it) },
                )
                if (index != recentDownloads.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}
