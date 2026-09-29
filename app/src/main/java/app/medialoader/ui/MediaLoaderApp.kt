package app.medialoader.ui

import android.content.Intent
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.medialoader.core.database.DownloadEntity
import app.medialoader.core.model.FileSizeFormatter
import app.medialoader.core.model.MediaItem
import app.medialoader.core.model.MediaType
import app.medialoader.data.MediaRepository
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory

private val purple = Color(0xFF7952F5)
private val panel = Color(0xFF1C202A)
private val theme = darkColorScheme(primary = purple, background = Color(0xFF101218), surface = panel)

@Composable
fun MediaLoaderApp(sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val history by repository.history.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableStateOf(0) }
    var link by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun enqueueSelected() {
        busy = true
        scope.launch {
            try {
                results.filter { it.id in selected }.forEach { repository.enqueue(it) }
                results = emptyList(); tab = 1; error = null
            } catch (cause: Exception) { error = cause.localizedMessage ?: "Ошибка загрузки" }
            finally { busy = false }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enqueueSelected() else error = "Для сохранения в Загрузки на Android 8–9 нужен доступ к хранилищу."
    }


    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            link = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(sharedText)?.value?.trimEnd('.', ')') ?: sharedText
            tab = 0
            results = emptyList()
            onSharedTextConsumed()
        }
    }
    LaunchedEffect(repository) {
        while (true) {
            try { repository.refresh() } catch (_: Exception) { /* Retry on next foreground poll. */ }
            delay(2500)
        }
    }
    MaterialTheme(colorScheme = theme) {
        Scaffold(containerColor = theme.background, bottomBar = {
            if (results.isEmpty()) NavigationBar(containerColor = panel) {
                listOf("Главная", "Загрузки", "Настройки").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("⌂", "↓", "⚙")[index], style = MaterialTheme.typography.titleLarge) },
                        label = { Text(label) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (results.isNotEmpty()) PreviewScreen(results, selected, onSelect = { id ->
                    selected = if (id in selected) selected - id else selected + id
                }, onBack = { results = emptyList() }, onDownload = {
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else enqueueSelected()
                }, busy = busy, error = error)
                else when (tab) {
                    0 -> HomeScreen(link, { link = it }, busy, error, history.take(3), onAnalyze = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                results = repository.resolve(link.trim())
                                selected = results.map { it.id }.toSet()
                            } catch (cause: Exception) { error = cause.localizedMessage ?: "Не удалось проверить ссылку" }
                            finally { busy = false }
                        }
                    }, onDownloads = { tab = 1 })
                    1 -> DownloadsScreen(history, onOpen = { entry ->
                        entry.savedUri?.let { value ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).apply {
                                    setDataAndType(Uri.parse(value), if (entry.mediaType == "VIDEO") "video/mp4" else "image/jpeg")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: Exception) { error = "Не удалось открыть файл на устройстве." }
                        }
                    }, onCancel = { entry -> scope.launch { repository.cancel(entry) } }, error = error)
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun HomeScreen(link: String, onLink: (String) -> Unit, busy: Boolean, error: String?, recent: List<DownloadEntity>, onAnalyze: () -> Unit, onDownloads: () -> Unit) {
    Page("Загрузчик") {
        Box(Modifier.size(56.dp).background(purple, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("↓", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        }
        Text("Сохраняйте медиа из Instagram", color = Color.LightGray)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = link, onValueChange = onLink, label = { Text("Ссылка на публикацию или Reel") },
            leadingIcon = { Text("↗") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onAnalyze, enabled = link.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = purple)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Проверить ссылку →")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Последние загрузки", fontWeight = FontWeight.SemiBold)
            Text("Все →", color = purple, modifier = Modifier.clickable(onClick = onDownloads))
        }
        if (recent.isEmpty()) Panel {
            Text("↓", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth(), color = purple)
            Text("Нет загрузок", modifier = Modifier.fillMaxWidth())
            Text("Вставьте ссылку, чтобы начать", modifier = Modifier.fillMaxWidth(), color = Color.Gray)
        } else recent.forEach { DownloadRow(it) }
    }
}

@Composable
private fun PreviewScreen(items: List<MediaItem>, selected: Set<String>, onSelect: (String) -> Unit, onBack: () -> Unit, onDownload: () -> Unit, busy: Boolean, error: String?) {
    Page("Предпросмотр") {
        TextButton(onClick = onBack) { Text("← Назад") }
        items.firstOrNull()?.previewUrl?.let { PreviewImage(it) }
        Text("Instagram · найдено: ${items.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Выберите файлы для сохранения", color = Color.LightGray)
        items.forEach { item ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.id in selected, onCheckedChange = { onSelect(item.id) })
                    Column {
                        Text(if (item.type == MediaType.VIDEO) "Видео · MP4" else "Фото · JPG", fontWeight = FontWeight.SemiBold)
                        Text(item.originalName, color = Color.LightGray)
                        Text(FileSizeFormatter.format(item.sizeBytes), color = Color.Gray)
                    }
                }
            }
        }
        Text("Размер станет известен после начала загрузки. Доступность зависит от Instagram.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onDownload, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (busy) "Добавляем…" else "↓ Скачать (${selected.size})")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PreviewImage(url: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.medialoader.core.provider.InstagramProvider.safeMediaUrl(url)) null
                else (URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 })
                    .getInputStream().use(BitmapFactory::decodeStream)
            } catch (_: Exception) { null }
        }
    }
    bitmap?.let { image -> Card(shape = RoundedCornerShape(18.dp)) {
        Image(image.asImageBitmap(), contentDescription = "Предпросмотр медиа", modifier = Modifier.fillMaxWidth().aspectRatio(1.4f))
    } }
}

@Composable
private fun DownloadRow(entry: DownloadEntity) {
    Panel {
        Text(entry.originalName, fontWeight = FontWeight.SemiBold)
        Text("${if (entry.mediaType == "VIDEO") "MP4" else "JPG"} · ${FileSizeFormatter.format(entry.sizeBytes)}", color = Color.LightGray)
        Text(when (entry.state) {
            "COMPLETED" -> "Сохранено"
            "FAILED" -> "Ошибка загрузки"
            "CANCELLED" -> "Отменено"
            "RUNNING" -> "Загружается…"
            else -> "В очереди"
        }, color = if (entry.state == "FAILED") MaterialTheme.colorScheme.error else purple)
    }
}

@Composable
private fun DownloadsScreen(history: List<DownloadEntity>, onOpen: (DownloadEntity) -> Unit, onCancel: (DownloadEntity) -> Unit, error: String?) {
    Page("Загрузки") {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (history.isEmpty()) Panel { Text("Нет загрузок"); Text("Проверенные файлы появятся здесь.", color = Color.Gray) }
        else history.forEach { entry ->
            DownloadRow(entry)
            entry.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (entry.state == "COMPLETED") TextButton(onClick = { onOpen(entry) }) { Text("Открыть файл") }
            if (entry.state in listOf("RUNNING", "QUEUED")) TextButton(onClick = { onCancel(entry) }) { Text("Отменить") }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Page("Настройки") {
        Panel { Text("Папка загрузок", fontWeight = FontWeight.SemiBold); Text("Download/MediaLoader", color = Color.LightGray) }
        Panel { Text("Имена файлов", fontWeight = FontWeight.SemiBold); Text("При совпадении добавляется _1, _2 и далее.", color = Color.LightGray) }
        Panel { Text("Уведомления", fontWeight = FontWeight.SemiBold); Text("Системный менеджер показывает ход и завершение загрузки.", color = Color.LightGray) }
        Panel { Text("О приложении", fontWeight = FontWeight.SemiBold); Text("MediaLoader 0.1.0-alpha02", color = Color.LightGray) }
    }
}
