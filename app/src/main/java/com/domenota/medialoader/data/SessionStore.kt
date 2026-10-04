package com.domenota.medialoader.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Instagram session cookies, encrypted with a key from Android Keystore.
 * The app never sees the account password: the user signs in inside a WebView.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context.applicationContext, "sessions",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    @Volatile
    private var memoryOnly: String? = null // fallback if Keystore is unavailable on a device

    fun cookies(): String? = (prefs?.getString(KEY, null) ?: memoryOnly)?.takeIf { it.isNotBlank() }

    fun isLoggedIn(): Boolean = cookies()?.contains("sessionid=") == true

    fun save(cookies: String) {
        memoryOnly = cookies
        prefs?.edit()?.putString(KEY, cookies)?.apply()
    }

    /** A manually supplied session token is a credential. Never display or log it. */
    fun saveManualSessionId(input: String) {
        val token = input.trim().removePrefix("sessionid=")
        require(token.matches(Regex("[A-Za-z0-9._~%:+/=-]{10,4096}")))
        save("sessionid=$token")
    }

    fun clear() {
        memoryOnly = null
        prefs?.edit()?.remove(KEY)?.apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private companion object { const val KEY = "instagram_cookies" }
}
