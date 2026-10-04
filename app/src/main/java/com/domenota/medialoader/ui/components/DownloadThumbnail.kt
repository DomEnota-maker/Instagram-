package com.domenota.medialoader.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finished media uses its local URI so the thumbnail survives expired Instagram links. */
@Composable
fun DownloadThumbnail(savedUri: String?, previewUrl: String?, kind: MediaKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var local by remember(savedUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(savedUri, kind) {
        local = if (savedUri == null || kind == MediaKind.AUDIO) null else withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(savedUri)
                if (kind == MediaKind.VIDEO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally { retriever.release() }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                }
            }.getOrNull()
        }
    }
    Box(modifier) {
        if (local != null) Image(local!!.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else RemotePreview(previewUrl, Modifier.fillMaxSize())
    }
}
