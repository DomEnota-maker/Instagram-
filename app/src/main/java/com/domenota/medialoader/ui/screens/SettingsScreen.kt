package com.domenota.medialoader.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.DownloadUiItem

@Composable
fun SettingsScreen(
    downloadFolder: String,
    versionName: String,
    instagramSignedIn: Boolean,
    onInstagramSignIn: () -> Unit,
    onInstagramSignOut: () -> Unit,
    onManualSessionId: (String) -> Boolean,
    youtubeSignedIn: Boolean,
    onYouTubeSignIn: () -> Unit,
    onYouTubeSignOut: () -> Unit,
    vkSignedIn: Boolean,
    onVkSignIn: () -> Unit,
    onVkSignOut: () -> Unit,
    hiddenCount: Int,
    hiddenItems: List<DownloadUiItem>,
    onChangeFolder: (String) -> Unit,
    onSelectFolder: () -> Unit,
    onRestore: (Set<String>) -> Unit,
    onEmptyTrash: () -> Unit,
    onClearHistory: () -> Unit,
    ytDlpUpdate: String? = null,
    ytDlpUpdating: Boolean = false,
    onUpdateYtDlp: () -> Unit = {},
    hasYouTubeCookies: Boolean = false,
    onImportYouTubeCookies: (Uri) -> Unit = {},
    onClearYouTubeCookies: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    var notificationsEnabled by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    var systemNotificationsAllowed by remember { mutableStateOf(false) }

    fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED)

    DisposableEffect(lifecycleOwner) {
        systemNotificationsAllowed = allowed()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemNotificationsAllowed = allowed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        systemNotificationsAllowed = allowed()
        if (systemNotificationsAllowed) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        }
    }
    val cookiesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportYouTubeCookies(uri)
    }
    var logText by remember { mutableStateOf("") }
    val logSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(AppLog.exportMarkdown())
                } ?: error("Не удалось открыть файл для записи")
            }.onSuccess {
                Toast.makeText(context, "Лог сохранён", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                AppLog.e("Logs", "Log export failed: ${error.message}", error)
                Toast.makeText(context, "Не удалось сохранить лог", Toast.LENGTH_LONG).show()
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemNotificationsAllowed = allowed()
        if (granted) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        } else {
            notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    fun enableNotifications() {
        if (allowed()) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("MediaLoader") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmInstagramSignOut by remember { mutableStateOf(false) }
    var confirmYouTubeSignOut by remember { mutableStateOf(false) }
    var confirmVkSignOut by remember { mutableStateOf(false) }
    var trashDialog by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var selectedTrashIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionDialog by remember { mutableStateOf(false) }
    var sessionValue by remember { mutableStateOf("") }
    var sessionInvalid by remember { mutableStateOf(false) }
    var logsDialog by remember { mutableStateOf(false) }
    var logsUnlocked by remember { mutableStateOf(prefs.getBoolean("logs_unlocked", false)) }
    var aboutTapCount by remember { mutableStateOf(0) }

    if (confirmInstagramSignOut) AlertDialog(
        onDismissRequest = { confirmInstagramSignOut = false },
        title = { Text("Выйти из Instagram?") },
        text = { Text("Сохранённая сессия будет удалена. При необходимости можно войти снова.") },
        confirmButton = {
            TextButton(onClick = { onInstagramSignOut(); confirmInstagramSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmInstagramSignOut = false }) { Text("Отмена") } },
    )

    if (confirmYouTubeSignOut) AlertDialog(
        onDismissRequest = { confirmYouTubeSignOut = false },
        title = { Text("Выйти из YouTube?") },
        text = { Text("Сохранённая для yt-dlp сессия YouTube будет удалена.") },
        confirmButton = {
            TextButton(onClick = { onYouTubeSignOut(); confirmYouTubeSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmYouTubeSignOut = false }) { Text("Отмена") } },
    )

    if (confirmVkSignOut) AlertDialog(
        onDismissRequest = { confirmVkSignOut = false },
        title = { Text("Выйти из VK?") },
        text = { Text("Сохранённая для загрузки видео сессия VK будет удалена.") },
        confirmButton = {
            TextButton(onClick = { onVkSignOut(); confirmVkSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmVkSignOut = false }) { Text("Отмена") } },
    )

    if (trashDialog) AlertDialog(
        onDismissRequest = { trashDialog = false },
        title = { Text("Корзина") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (hiddenItems.isEmpty()) Text("Корзина пуста")
                hiddenItems.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTrashIds = if (item.id in selectedTrashIds) {
                                    selectedTrashIds - item.id
                                } else {
                                    selectedTrashIds + item.id
                                }
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = item.id in selectedTrashIds, onCheckedChange = null)
                        DownloadThumbnail(item.savedUri, item.previewUrl, item.kind, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(item.title, maxLines = 1)
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (hiddenItems.isNotEmpty()) {
                    TextButton(onClick = { confirmEmptyTrash = true }) {
                        Text("Очистить корзину", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedTrashIds.isNotEmpty(),
                onClick = {
                    onRestore(selectedTrashIds)
                    trashDialog = false
                    selectedTrashIds = emptySet()
                },
            ) { Text("Восстановить выбранные") }
        },
        dismissButton = { TextButton(onClick = { trashDialog = false }) { Text("Закрыть") } },
    )

    if (confirmEmptyTrash) AlertDialog(
        onDismissRequest = { confirmEmptyTrash = false },
        title = { Text("Очистить корзину?") },
        text = { Text("Файлы из корзины будут удалены без возможности восстановления.") },
        confirmButton = {
            TextButton(onClick = {
                onEmptyTrash()
                confirmEmptyTrash = false
                trashDialog = false
                selectedTrashIds = emptySet()
            }) { Text("Очистить") }
        },
        dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Отмена") } },
    )

    if (sessionDialog) AlertDialog(
        onDismissRequest = { sessionValue = ""; sessionDialog = false },
        title = { Text("Ввести sessionid") },
        text = {
            Column {
                Text("Это секретный ключ доступа к аккаунту. Вставляй только свой sessionid и никому его не отправляй. Работу сессии проверим по ссылке после сохранения.")
                OutlinedTextField(
                    value = sessionValue,
                    onValueChange = { sessionValue = it; sessionInvalid = false },
                    label = { Text("sessionid") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = sessionInvalid,
                    singleLine = true,
                )
                if (sessionInvalid) Text("Проверь формат sessionid", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onManualSessionId(sessionValue)) {
                    sessionValue = ""
                    sessionDialog = false
                } else {
                    sessionInvalid = true
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = { sessionValue = ""; sessionDialog = false }) { Text("Отмена") }
        },
    )

    if (folderDialog) AlertDialog(
        onDismissRequest = { folderDialog = false },
        title = { Text("Папка загрузок") },
        text = {
            Column {
                Text("Выберите папку через проводник или сохраняйте в Загрузки.")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { folderDialog = false; onSelectFolder() }) { Text("Выбрать в проводнике") }
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Папка внутри Загрузок") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChangeFolder(folderName); folderDialog = false }) {
                Text("Сохранить в Загрузки")
            }
        },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Отмена") } },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Очистить историю?") },
        text = { Text("История и файлы в корзине будут удалены. Сохранённые загрузки останутся на устройстве.") },
        confirmButton = {
            TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Очистить") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )

    if (logsDialog) AlertDialog(
        onDismissRequest = { logsDialog = false },
        title = { Text("Логи") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Локальный журнал приложения. Известные cookie/session-поля при записи скрываются.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { logText = AppLog.readText() }) { Text("Обновить") }
                    TextButton(onClick = { AppLog.clear(); logText = "" }) { Text("Очистить") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    logText.ifBlank { "Журнал пока пуст." },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { logSaver.launch("MediaLoader_${versionName}_logs.md") }) {
                Text("Сохранить .md")
            }
        },
        dismissButton = { TextButton(onClick = { logsDialog = false }) { Text("Закрыть") } },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(18.dp))

        SettingRow(
            icon = Icons.Rounded.Folder,
            title = "Папка загрузок",
            subtitle = downloadFolder,
            onClick = {
                folderName = downloadFolder.substringAfter("Download/", "MediaLoader")
                folderDialog = true
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.PhotoCamera,
            title = "Аккаунт Instagram",
            subtitle = if (instagramSignedIn) {
                "Сессия сохранена · нажмите, чтобы выйти"
            } else {
                "Нужен для Stories и публикаций без публичного доступа"
            },
            onClick = if (instagramSignedIn) ({ confirmInstagramSignOut = true }) else onInstagramSignIn,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.PlayCircle,
            title = "Аккаунт YouTube",
            subtitle = if (youtubeSignedIn) {
                "Сессия сохранена · нажмите, чтобы выйти"
            } else {
                "Для роликов с возрастом, проверкой или требованием входа"
            },
            onClick = if (youtubeSignedIn) ({ confirmYouTubeSignOut = true }) else onYouTubeSignIn,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.VideoLibrary,
            title = "Аккаунт VK",
            subtitle = if (vkSignedIn) {
                "Сессия сохранена · нажмите, чтобы выйти"
            } else {
                "Для закрытых видео, клипов и роликов с ограничением доступа"
            },
            onClick = if (vkSignedIn) ({ confirmVkSignOut = true }) else onVkSignIn,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.Key,
            title = "Ручной sessionid",
            subtitle = "Резервный вход Instagram, если WebView не завершился",
            onClick = { sessionInvalid = false; sessionDialog = true },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.SystemUpdate,
            title = if (ytDlpUpdating) "Обновляем yt-dlp…" else "Обновить yt-dlp",
            subtitle = ytDlpUpdate ?: "Проверка обновлений выполняется автоматически при HTTP 403",
            onClick = if (ytDlpUpdating) null else onUpdateYtDlp,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.Notifications,
            title = "Уведомления",
            subtitle = if (!systemNotificationsAllowed) "Разрешить в настройках Android" else "О завершении загрузок",
            onClick = {
                if (notificationsEnabled && systemNotificationsAllowed) {
                    notificationsEnabled = false
                    prefs.edit().putBoolean("notifications", false).apply()
                } else {
                    enableNotifications()
                }
            },
            trailing = {
                Switch(
                    checked = notificationsEnabled && systemNotificationsAllowed,
                    onCheckedChange = { enabled ->
                        if (enabled) enableNotifications()
                        else {
                            notificationsEnabled = false
                            prefs.edit().putBoolean("notifications", false).apply()
                        }
                    },
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.Palette,
            title = "Тема",
            subtitle = if (darkTheme) "Тёмная" else "Светлая",
            onClick = {
                darkTheme = !darkTheme
                prefs.edit().putBoolean("dark", darkTheme).apply()
                (context as? Activity)?.recreate()
            },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.RestoreFromTrash,
            title = "Восстановление",
            subtitle = "В корзине: $hiddenCount",
            onClick = { selectedTrashIds = emptySet(); trashDialog = true },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.DeleteSweep,
            title = "Очистить историю",
            subtitle = "Файлы на устройстве сохранятся",
            onClick = { confirmClear = true },
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            icon = Icons.Rounded.Info,
            title = "О приложении",
            subtitle = "Версия $versionName",
            onClick = {
                if (!logsUnlocked) {
                    aboutTapCount += 1
                    if (aboutTapCount >= 5) {
                        logsUnlocked = true
                        aboutTapCount = 0
                        prefs.edit().putBoolean("logs_unlocked", true).apply()
                        AppLog.i("Settings", "Diagnostic logs unlocked")
                        Toast.makeText(context, "Логи открыты", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        if (logsUnlocked) {
            Spacer(Modifier.height(10.dp))
            SettingRow(
                icon = Icons.Rounded.BugReport,
                title = "Логи",
                subtitle = "Диагностика ошибок и экспорт .md",
                onClick = { logText = AppLog.readText(); logsDialog = true },
            )
            Spacer(Modifier.height(10.dp))
            SettingRow(
                icon = Icons.Rounded.Description,
                title = "Cookies YouTube (резерв)",
                subtitle = if (hasYouTubeCookies) {
                    "Сессия уже есть · нажмите, чтобы заменить файлом Netscape"
                } else {
                    "Ручной импорт, если вход YouTube через приложение не сработал"
                },
                onClick = { cookiesPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(23.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                trailing != null -> trailing()
                onClick != null -> Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
