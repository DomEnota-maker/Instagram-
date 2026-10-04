package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.domenota.medialoader.data.preview.RemoteImageLoader

@Composable
fun RemotePreview(url: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { RemoteImageLoader.fetch(url) }.getOrNull()
        }
        bitmap = loaded
    }
    Box(modifier) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
