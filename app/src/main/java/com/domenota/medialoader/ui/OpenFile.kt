package com.domenota.medialoader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType

/** Opens a saved file in whatever app the user has for it. */
fun openSavedFile(context: Context, entity: DownloadEntity) {
    val value = entity.savedUri ?: return
    val mime = when (entity.mediaType) {
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> "audio/mpeg"
        MediaType.PHOTO -> "image/jpeg"
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(value), mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Не найдено приложение для открытия файла.", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Не удалось открыть файл на устройстве.", Toast.LENGTH_SHORT).show()
    }
}

fun shareSavedFile(context: Context, entity: DownloadEntity) {
    val value = entity.savedUri ?: return
    val mime = when (entity.mediaType) {
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> "audio/mpeg"
        MediaType.PHOTO -> "image/jpeg"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, Uri.parse(value))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
}
