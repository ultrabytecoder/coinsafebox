package com.ultrabytecoder.coinsafebox.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Key/value storage backed by [EncryptedSharedPreferences] (Jetpack Security).
 *
 * Every value is encrypted at rest with an AES256_GCM key held in the Android
 * Keystore (hardware-backed on devices with StrongBox/TEE). This protects the
 * DEK envelope, PIN salt and lockout state from forensic extraction of the
 * app's data directory: without the Keystore key the file is unintelligible.
 *
 * The previous implementation used plain `MODE_PRIVATE` SharedPreferences,
 * which only blocked other apps — root/jailbreak or ADB-backup forensics
 * yielded the wrapped DEK directly.
 */
actual class SettingsStorage actual constructor(context: Any?) : SettingsStore {
    private val prefs by lazy { (context as Context).encryptedPrefs() }

    actual override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    actual override fun getString(key: String): String? = prefs.getString(key, null)

    actual override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}

private fun Context.encryptedPrefs(): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(this)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        this,
        "coinsafebox_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
