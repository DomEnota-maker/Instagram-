package app.medialoader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.medialoader.core.model.MediaItem
import app.medialoader.core.model.MediaType

private val colors = darkColorScheme()
private val tabs = listOf("home" to "Скачать", "downloads" to "Загрузки", "settings" to "Настройки")

@Composable
fun MediaLoaderApp() {
    MaterialTheme(colorScheme = colors) {
        val navigation = rememberNavController()
        val entry by navigation.currentBackStackEntryAsState()
        val selected = entry?.destination?.route
        Scaffold(bottomBar = {
            NavigationBar {
                tabs.forEach { (route, title) ->
                    NavigationBarItem(
                        selected = selected == route,
                        onClick = {
                            navigation.navigate(route) {
                                popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(when (route) { "home" -> "↓"; "downloads" -> "▣"; else -> "⚙" }) },
                        label = { Text(title) },
                    )
                }
            }
        }) { padding ->
            NavHost(navController = navigation, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(onDemoPreview = { navigation.navigate("preview") }) }
                composable("downloads") { DownloadsScreen() }
                composable("settings") { SettingsScreen() }
                composable("preview") { PreviewScreen() }
            }
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text("Foundation · 0.1.0-alpha01", style = MaterialTheme.typography.labelMedium)
        content()
    }
}

@Composable
fun HomeScreen(onDemoPreview: () -> Unit) {
    var link by remember { mutableStateOf("") }
    Page("Сохранение медиа") {
        Text("Вставь ссылку на публикацию. Анализ ссылок появится на следующем этапе.")
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Ссылка на медиа") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {}, enabled = false) { Text("Анализ ссылки пока недоступен") }
        Button(onClick = onDemoPreview) { Text("Посмотреть пример предпросмотра") }
    }
}

@Composable
fun DownloadsScreen() {
    Page("Загрузки") {
        Text("История пока пуста. Загрузка и сохранение файлов появятся позже.")
    }
}

@Composable
fun SettingsScreen() {
    Page("Настройки") {
        Text("Папка: Download/MediaLoader")
        Text("Названия файлов сохраняются; при совпадении добавляется _1, _2 и далее.")
        Text("Настройки темы и уведомлений появятся позже.")
    }
}

private val demoItems = listOf(
    MediaItem("demo-photo", "demo", MediaType.PHOTO, "sample.jpg", "", sizeBytes = 1_250_000),
    MediaItem("demo-video", "demo", MediaType.VIDEO, "clip.mp4", "", sizeBytes = 8_400_000),
)

@Composable
fun PreviewScreen() {
    Page("Предпросмотр · пример") {
        Text("Демонстрационные данные. Эти файлы не найдены по ссылке и не скачиваются.")
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(demoItems) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(item.originalName)
                        Text("${item.type} · ${item.sizeBytes ?: 0} байт")
                    }
                }
            }
        }
    }
}
