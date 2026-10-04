package com.domenota.medialoader.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.domenota.medialoader.BuildConfig
import com.domenota.medialoader.core.provider.InstagramLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.ui.model.AnalysisUiState
import com.domenota.medialoader.ui.model.toPreviewUi
import com.domenota.medialoader.ui.model.toUi
import com.domenota.medialoader.ui.navigation.AppDestination
import com.domenota.medialoader.ui.navigation.PREVIEW_ROUTE
import com.domenota.medialoader.ui.screens.DownloadsScreen
import com.domenota.medialoader.ui.screens.HomeScreen
import com.domenota.medialoader.ui.screens.PreviewScreen
import com.domenota.medialoader.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLoaderApp(
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    viewModel: MediaLoaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var pendingUrl by rememberSaveable { mutableStateOf("") }
    var lastImportedClipboard by remember { mutableStateOf("") }
    var dismissedClipboard by remember { mutableStateOf("") }
    var checkedClipboardAtLaunch by remember { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var lastHandledLink by rememberSaveable { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    var pendingNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val pickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val hiddenItems by viewModel.hiddenItems.collectAsStateWithLifecycle()
    val downloadFolder by viewModel.downloadFolder.collectAsStateWithLifecycle()
    val downloadItems = remember(downloads) { downloads.map { it.toUi() } }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val goToDownloads: () -> Unit = {
        showPicker = false
        navigateTo(AppDestination.Downloads.route)
    }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onLoginFinished(result.resultCode == Activity.RESULT_OK)
    }
    val startLogin: () -> Unit = { loginLauncher.launch(Intent(context, LoginActivity::class.java)) }

    // Android 8–9 need the legacy storage permission for the public Downloads folder.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.downloadSelected(pendingNames, goToDownloads)
        } else {
            viewModel.showMessage("Для сохранения в Загрузки на Android 8–9 нужен доступ к хранилищу.")
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.selectFolder(uri)
    }
    val confirmDownload: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT < 29 && viewModel.needsLegacyStoragePermission() && ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else viewModel.downloadSelected(pendingNames, goToDownloads)
    }
    val requestDownload: () -> Unit = {
        pendingNames = preview.items.filter { it.id in preview.selectedIds }
            .associate { it.id to it.originalName }
        if (pendingNames.isNotEmpty()) showNameDialog = true
    }
    if (showNameDialog) AlertDialog(
        onDismissRequest = { showNameDialog = false },
        title = { Text("Имена файлов") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                preview.items.filter { it.id in preview.selectedIds }.forEach { item ->
                    OutlinedTextField(
                        value = pendingNames[item.id] ?: item.originalName,
                        onValueChange = { pendingNames = pendingNames + (item.id to it) },
                        label = { Text("Файл ${item.position ?: 1}") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showNameDialog = false; confirmDownload() }) {
            Text("Скачать")
        } },
        dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Отмена") } },
    )
    val openDownload: (String) -> Unit = { id ->
        downloads.firstOrNull { it.id == id }?.let { openSavedFile(context, it) }
    }
    val shareDownload: (String) -> Unit = { id ->
        downloads.firstOrNull { it.id == id }?.let { shareSavedFile(context, it) }
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            pendingUrl = extractFirstUrl(sharedText) ?: sharedText
            lastHandledLink = pendingUrl
            navigateTo(AppDestination.Home.route)
            showPicker = true
            viewModel.analyze(pendingUrl)
            onSharedTextConsumed()
        }
    }

    // Read only while the app is visible. Never replace a link the user typed herself.
    DisposableEffect(lifecycleOwner, currentRoute, sharedText) {
        fun importInstagramLink() {
            if (currentRoute != AppDestination.Home.route || sharedText != null) return
            val firstCheck = !checkedClipboardAtLaunch
            checkedClipboardAtLaunch = true
            val candidate = clipboardManager.getText()?.text?.let(::extractFirstUrl) ?: return
            if (InstagramLinkParser.parse(candidate) == null && YouTubeLinkParser.parse(candidate) == null) return
            if (candidate == dismissedClipboard) return
            if (firstCheck || pendingUrl.isBlank() || pendingUrl == lastImportedClipboard) {
                pendingUrl = candidate
                if (candidate != lastHandledLink) {
                    showPicker = true
                    viewModel.analyze(candidate)
                    lastHandledLink = candidate
                }
                lastImportedClipboard = candidate
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) importInstagramLink()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) importInstagramLink()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute != PREVIEW_ROUTE) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledIconColor = Color.Gray,
                                disabledTextColor = Color.Gray,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.Home.route) {
                HomeScreen(
                    url = pendingUrl,
                    onUrlChange = {
                        if (it.isEmpty() && pendingUrl == lastImportedClipboard)
                            dismissedClipboard = lastImportedClipboard
                        pendingUrl = it
                    },
                    onPasteClick = {
                        clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }?.let {
                            pendingUrl = it
                        }
                    },
                    onCheckClick = {
                        lastHandledLink = pendingUrl
                        viewModel.analyze(pendingUrl)
                        showPicker = true
                    },
                    onOpenDownloads = goToDownloads,
                    recentDownloads = downloadItems.take(3),
                    onOpenDownload = openDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = viewModel::hideDownload,
                    onRetryDownload = viewModel::retryDownload,
                    onRenameDownload = viewModel::renameDownload,
                )
            }
            composable(AppDestination.Downloads.route) {
                DownloadsScreen(
                    items = downloadItems,
                    onOpenDownload = openDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onShareDownload = shareDownload,
                    onHideDownload = viewModel::hideDownload,
                    onRetryDownload = viewModel::retryDownload,
                    onRenameDownload = viewModel::renameDownload,
                )
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    downloadFolder = downloadFolder,
                    versionName = BuildConfig.VERSION_NAME,
                    instagramSignedIn = signedIn,
                    onInstagramSignIn = startLogin,
                    onInstagramSignOut = viewModel::logout,
                    onManualSessionId = viewModel::importSessionId,
                    hiddenCount = hiddenCount,
                    hiddenItems = hiddenItems.map { entry ->
                        entry.toUi().copy(savedUri = viewModel.trashedUri(entry.id))
                    },
                    onChangeFolder = viewModel::changeFolder,
                    onSelectFolder = { folderLauncher.launch(null) },
                    onRestore = viewModel::restoreHidden,
                    onEmptyTrash = viewModel::clearHidden,
                    onClearHistory = viewModel::clearHistory,
                    ytDlpUpdate = viewModel.ytDlpUpdate.collectAsStateWithLifecycle().value,
                    ytDlpUpdating = viewModel.ytDlpUpdating.collectAsStateWithLifecycle().value,
                    onUpdateYtDlp = viewModel::updateYtDlp,
                    hasYouTubeCookies = viewModel.youTubeCookies.collectAsStateWithLifecycle().value,
                    onImportYouTubeCookies = viewModel::importYouTubeCookies,
                    onClearYouTubeCookies = viewModel::clearYouTubeCookies,
                )
            }
            composable(PREVIEW_ROUTE) {
                // After process death the analysis result is gone: leave the empty preview.
                LaunchedEffect(preview.analysis) {
                    if (preview.analysis == AnalysisUiState.IDLE) navController.popBackStack()
                }
                PreviewScreen(
                    url = preview.url.ifBlank { pendingUrl },
                    state = preview.analysis,
                    items = preview.items.map { it.toPreviewUi(selected = it.id in preview.selectedIds) },
                    onBack = { navController.popBackStack() },
                    onToggleItem = viewModel::toggle,
                    onToggleAllPhotos = viewModel::toggleAllPhotos,
                    onDownloadSelected = requestDownload,
                    errorMessage = preview.message,
                    busy = preview.enqueuing,
                    onLogin = startLogin,
                    onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }) },
                )
            }
        }
    }
    if (showPicker && currentRoute == AppDestination.Home.route) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = pickerState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PreviewScreen(
                url = preview.url.ifBlank { pendingUrl },
                state = preview.analysis,
                items = preview.items.map { it.toPreviewUi(selected = it.id in preview.selectedIds) },
                onBack = { showPicker = false },
                onToggleItem = viewModel::toggle,
                onToggleAllPhotos = viewModel::toggleAllPhotos,
                onDownloadSelected = requestDownload,
                errorMessage = preview.message,
                busy = preview.enqueuing,
                onLogin = startLogin,
                onRetry = { viewModel.analyze(preview.url.ifBlank { pendingUrl }) },
                asSheet = true,
            )
        }
    }
}
