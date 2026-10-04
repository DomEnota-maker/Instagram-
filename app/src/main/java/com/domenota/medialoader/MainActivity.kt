package com.domenota.medialoader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.ui.MediaLoaderApp
import com.domenota.medialoader.ui.theme.MediaLoaderTheme

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.init(applicationContext)
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) must not re-import the link the user already saw.
        if (savedInstanceState == null) sharedText = textFrom(intent)
        setContent {
            MediaLoaderTheme {
                MediaLoaderApp(
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText = textFrom(intent)
        AppLog.i("App", "Received shared text intent")
    }

    private fun textFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
}
