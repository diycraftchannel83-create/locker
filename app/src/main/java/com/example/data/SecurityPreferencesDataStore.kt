package com.example.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "brankasku_security_prefs")

data class SecurityPreferencesState(
    val isSetupComplete: Boolean = false,
    val pinHash: ByteArray? = null,
    val pinSalt: ByteArray? = null,
    val biometricEnabled: Boolean = false,
    val autoLockOnBackground: Boolean = true,
    val blockScreenshots: Boolean = true,
    val deleteOriginalOnImport: Boolean = true,
    val themePreference: VaultThemePreference = VaultThemePreference.DARK,
    val externalVaultFolderUri: String? = null,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

class SecurityPreferencesDataStore(private val context: Context) {

    private object Keys {
        val IS_SETUP_COMPLETE = booleanPreferencesKey("is_setup_complete")
        val PIN_HASH = stringPreferencesKey("pin_hash_b64")
        val PIN_SALT = stringPreferencesKey("pin_salt_b64")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val AUTO_LOCK_ON_BG = booleanPreferencesKey("auto_lock_on_bg")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val DELETE_ORIGINAL = booleanPreferencesKey("delete_original_on_import")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val EXTERNAL_VAULT_FOLDER = stringPreferencesKey("external_vault_folder_uri")
        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val LOCKOUT_UNTIL = longPreferencesKey("lockout_until_millis")
    }

    val preferencesFlow: Flow<SecurityPreferencesState> = context.securityDataStore.data.map { prefs ->
        val hashB64 = prefs[Keys.PIN_HASH]
        val saltB64 = prefs[Keys.PIN_SALT]
        val themeStr = prefs[Keys.THEME_PREF] ?: VaultThemePreference.DARK.name
        SecurityPreferencesState(
            isSetupComplete = prefs[Keys.IS_SETUP_COMPLETE] ?: false,
            pinHash = hashB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            pinSalt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: false,
            autoLockOnBackground = prefs[Keys.AUTO_LOCK_ON_BG] ?: true,
            blockScreenshots = prefs[Keys.BLOCK_SCREENSHOTS] ?: true,
            deleteOriginalOnImport = prefs[Keys.DELETE_ORIGINAL] ?: true,
            themePreference = runCatching { VaultThemePreference.valueOf(themeStr) }
                .getOrDefault(VaultThemePreference.DARK),
            externalVaultFolderUri = prefs[Keys.EXTERNAL_VAULT_FOLDER],
            failedAttempts = prefs[Keys.FAILED_ATTEMPTS] ?: 0,
            lockoutUntilMillis = prefs[Keys.LOCKOUT_UNTIL] ?: 0L
        )
    }

    suspend fun savePinAndSetup(
        pinHash: ByteArray,
        pinSalt: ByteArray,
        biometricEnabled: Boolean
    ) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.PIN_HASH] = Base64.encodeToString(pinHash, Base64.NO_WRAP)
            prefs[Keys.PIN_SALT] = Base64.encodeToString(pinSalt, Base64.NO_WRAP)
            prefs[Keys.BIOMETRIC_ENABLED] = biometricEnabled
            prefs[Keys.IS_SETUP_COMPLETE] = true
            prefs[Keys.FAILED_ATTEMPTS] = 0
            prefs[Keys.LOCKOUT_UNTIL] = 0L
        }
    }

    suspend fun updatePin(pinHash: ByteArray, pinSalt: ByteArray) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.PIN_HASH] = Base64.encodeToString(pinHash, Base64.NO_WRAP)
            prefs[Keys.PIN_SALT] = Base64.encodeToString(pinSalt, Base64.NO_WRAP)
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setAutoLockOnBackground(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.AUTO_LOCK_ON_BG] = enabled }
    }

    suspend fun setBlockScreenshots(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.BLOCK_SCREENSHOTS] = enabled }
    }

    suspend fun setDeleteOriginalOnImport(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.DELETE_ORIGINAL] = enabled }
    }

    suspend fun setExternalVaultFolder(uri: String?) {
        context.securityDataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.EXTERNAL_VAULT_FOLDER) else prefs[Keys.EXTERNAL_VAULT_FOLDER] = uri
        }
    }

    suspend fun setThemePreference(theme: VaultThemePreference) {
        context.securityDataStore.edit { it[Keys.THEME_PREF] = theme.name }
    }

    suspend fun recordFailedAttempt(attempts: Int, lockoutUntilMillis: Long) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.FAILED_ATTEMPTS] = attempts
            prefs[Keys.LOCKOUT_UNTIL] = lockoutUntilMillis
        }
    }

    suspend fun resetFailedAttempts() {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.FAILED_ATTEMPTS] = 0
            prefs[Keys.LOCKOUT_UNTIL] = 0L
        }
    }
}
