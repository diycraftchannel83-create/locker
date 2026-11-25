package com.example

import android.app.Application
import com.example.crypto.AndroidKeystoreMasterKeyProvider
import com.example.crypto.VaultCryptoEngine
import com.example.data.SecurityPreferencesDataStore
import com.example.data.VaultDatabase
import com.example.data.VaultRepository
import com.example.data.VaultStorageManager

class BrankasKuApp : Application() {
    val masterKeyProvider by lazy { AndroidKeystoreMasterKeyProvider() }
    val cryptoEngine by lazy { VaultCryptoEngine(masterKeyProvider) }
    val database by lazy { VaultDatabase.getInstance(this) }
    val securityPreferences by lazy { SecurityPreferencesDataStore(this) }
    val storageManager by lazy { VaultStorageManager(this, cryptoEngine) }
    val vaultRepository by lazy {
        VaultRepository(
            dao = database.vaultDao(),
            storageManager = storageManager,
            securityPrefs = securityPreferences,
            masterKeyProvider = masterKeyProvider
        )
    }
}
