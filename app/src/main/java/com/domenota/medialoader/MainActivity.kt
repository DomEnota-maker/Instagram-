package com.domenota.medialoader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.ui.MediaLoaderApp
import com.domenota.medialoader.ui.theme.MediaLoaderTheme

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedTextGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.init(applicationContext)
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) must not re-import the link the user already saw.
        if (savedInstanceState == null) {
            textFrom(intent)?.let {
                sharedText = it
                sharedTextGeneration++
            }
        }
        setContent {
            MediaLoaderTheme {
                MediaLoaderApp(
                    sharedText = sharedText,
                    sharedTextGeneration = sharedTextGeneration,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        textFrom(intent)?.let {
            sharedText = it
            sharedTextGeneration++
            AppLog.i("App", "Received shared text intent · generation=$sharedTextGeneration")
        }
    }

    private fun textFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
}
