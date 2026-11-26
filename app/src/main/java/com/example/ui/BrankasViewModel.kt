package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BrankasKuApp
import com.example.biometric.BiometricAuthHelper
import com.example.biometric.BiometricAvailability
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.MediaCategory
import com.example.data.SecurityPreferencesState
import com.example.data.VaultFileEntity
import com.example.data.VaultRepository
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class SetupStep {
    CREATE_PIN,
    CONFIRM_PIN,
    BIOMETRIC_OPT_IN
}

data class ImportProgressState(
    val processed: Int,
    val total: Int,
    val currentFileName: String
)

data class VaultUiState(
    val isPrefsLoaded: Boolean = false,
    val isLocked: Boolean = true,
    val isSetupComplete: Boolean = false,
    val setupStep: SetupStep = SetupStep.CREATE_PIN,
    val pinInput: String = "",
    val firstPinCandidate: String = "",
    val pinError: String? = null,
    val lockoutSecondsRemaining: Long = 0L,
    val biometricAvailability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    val securityPrefs: SecurityPreferencesState = SecurityPreferencesState(),
    val isHardwareBackedKeystore: Boolean = false,
    val vaultInternalPath: String = "",
    val albums: List<AlbumWithStats> = emptyList(),
    val allFiles: List<VaultFileEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: MediaCategory? = null,
    val importProgress: ImportProgressState? = null
)

sealed interface VaultEvent {
    data class ShowMessage(val message: String) : VaultEvent
    data class RequestMediaStoreDelete(val uris: List<Uri>) : VaultEvent
}

class BrankasViewModel(
    application: Application,
    private val repository: VaultRepository
) : AndroidViewModel(application) {

    private val _localState = MutableStateFlow(
        VaultUiState(
            biometricAvailability = BiometricAuthHelper.checkAvailability(application),
            isHardwareBackedKeystore = repository.isHardwareKeystoreBacked(),
            vaultInternalPath = repository.getVaultDirectoryDisplay()
        )
    )

    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    @Volatile
    private var suppressNextAutoLock = false

    val uiState: StateFlow<VaultUiState> = combine(
        _localState,
        repository.securityPreferencesFlow,
        repository.albumsWithStatsFlow,
        repository.allFilesFlow
    ) { local, prefs, albums, files ->
        val now = System.currentTimeMillis()
        val lockoutRemaining = if (prefs.lockoutUntilMillis > now) {
            ((prefs.lockoutUntilMillis - now) + 999L) / 1000L
        } else {
            0L
        }
        local.copy(
            isPrefsLoaded = true,
            isSetupComplete = prefs.isSetupComplete,
            securityPrefs = prefs,
            albums = albums,
            allFiles = files,
            lockoutSecondsRemaining = lockoutRemaining
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _localState.value
    )

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val prefs = uiState.value.securityPrefs
                if (prefs.lockoutUntilMillis > System.currentTimeMillis()) {
                    _localState.update { it.copy() }
                }
            }
        }
    }

    fun observeAlbum(albumId: String): Flow<AlbumEntity?> = repository.observeAlbum(albumId)

    fun observeFilesInAlbum(albumId: String): Flow<List<VaultFileEntity>> =
        repository.observeFilesInAlbum(albumId)

    fun observeFileById(fileId: String): Flow<VaultFileEntity?> =
        repository.observeFileById(fileId)

    fun markLaunchingExternalPicker() {
        suppressNextAutoLock = true
    }

    fun clearExternalPickerFlag() {
        suppressNextAutoLock = false
    }

    fun onAppBackgrounded() {
        if (suppressNextAutoLock) {
            return
        }
        val state = uiState.value
        if (state.isSetupComplete && state.securityPrefs.autoLockOnBackground && !state.isLocked) {
            lockVault()
        }
    }

    fun lockVault() {
        repository.clearSensitiveCaches()
        _localState.update {
            it.copy(
                isLocked = true,
                pinInput = "",
                pinError = null
            )
        }
    }

    fun onBiometricUnlockSuccess() {
        viewModelScope.launch {
            repository.ensureDefaultAlbums()
            _localState.update {
                it.copy(
                    isLocked = false,
                    pinInput = "",
                    pinError = null
                )
            }
        }
    }

    fun onPinDigitEntered(digit: Char) {
        val current = uiState.value
        if (current.lockoutSecondsRemaining > 0L) return
        if (current.pinInput.length >= 6) return

        val updated = current.pinInput + digit
        _localState.update { it.copy(pinInput = updated, pinError = null) }

        if (updated.length == 6) {
            if (!current.isSetupComplete) {
                handleSetupPinComplete(updated)
            } else {
                verifyUnlockPin(updated)
            }
        }
    }

    fun onPinBackspace() {
        _localState.update { state ->
            if (state.pinInput.isNotEmpty()) {
                state.copy(pinInput = state.pinInput.dropLast(1), pinError = null)
            } else {
                state
            }
        }
    }

    fun onPinClear() {
        _localState.update { it.copy(pinInput = "", pinError = null) }
    }

    private fun handleSetupPinComplete(pin: String) {
        val step = _localState.value.setupStep
        if (step == SetupStep.CREATE_PIN) {
            _localState.update {
                it.copy(
                    firstPinCandidate = pin,
                    pinInput = "",
                    setupStep = SetupStep.CONFIRM_PIN,
                    pinError = null
                )
            }
        } else if (step == SetupStep.CONFIRM_PIN) {
            if (pin == _localState.value.firstPinCandidate) {
                val bioAvailable = _localState.value.biometricAvailability == BiometricAvailability.AVAILABLE
                if (bioAvailable) {
                    _localState.update {
                        it.copy(
                            setupStep = SetupStep.BIOMETRIC_OPT_IN,
                            pinInput = "",
                            pinError = null
                        )
                    }
                } else {
                    completeInitialSetup(enableBiometric = false)
                }
            } else {
                _localState.update {
                    it.copy(
                        pinInput = "",
                        firstPinCandidate = "",
                        setupStep = SetupStep.CREATE_PIN,
                        pinError = "PIN tidak cocok. Silakan buat ulang PIN 6 digit."
                    )
                }
            }
        }
    }

    fun completeInitialSetup(enableBiometric: Boolean) {
        val pin = _localState.value.firstPinCandidate
        if (pin.length != 6) return
        viewModelScope.launch {
            repository.setupInitialPin(pin, enableBiometric)
            _localState.update {
                it.copy(
                    isLocked = false,
                    pinInput = "",
                    firstPinCandidate = "",
                    pinError = null
                )
            }
            _events.tryEmit(VaultEvent.ShowMessage("BrankasKu siap. Enkripsi AES-256-GCM aktif."))
        }
    }

    private fun verifyUnlockPin(pin: String) {
        viewModelScope.launch {
            val ok = repository.verifyPin(pin)
            if (ok) {
                repository.ensureDefaultAlbums()
                _localState.update {
                    it.copy(
                        isLocked = false,
                        pinInput = "",
                        pinError = null
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        pinInput = "",
                        pinError = "PIN salah. Coba lagi."
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _localState.update { it.copy(searchQuery = query) }
    }

    fun selectCategoryFilter(category: MediaCategory?) {
        _localState.update { it.copy(selectedCategory = category) }
    }

    fun createAlbum(name: String, iconKey: String, colorHex: Long, isPinned: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createAlbum(name, iconKey, colorHex, isPinned)
            _events.tryEmit(VaultEvent.ShowMessage("Album \"$name\" berhasil dibuat"))
        }
    }

    fun updateAlbum(album: AlbumEntity) {
        if (album.name.isBlank()) return
        viewModelScope.launch {
            repository.updateAlbum(album.copy(name = album.name.trim()))
            _events.tryEmit(VaultEvent.ShowMessage("Album diperbarui"))
        }
    }

    fun deleteAlbum(albumId: String, moveToAlbumId: String?) {
        viewModelScope.launch {
            if (moveToAlbumId != null) {
                repository.deleteAlbumMovingFilesTo(albumId, moveToAlbumId)
                _events.tryEmit(VaultEvent.ShowMessage("Album dihapus & file dipindahkan"))
            } else {
                repository.deleteAlbumAndFiles(albumId)
                _events.tryEmit(VaultEvent.ShowMessage("Album dan file di dalamnya dihapus permanen"))
            }
        }
    }

    fun importUris(uris: List<Uri>, albumId: String) {
        clearExternalPickerFlag()
        if (uris.isEmpty()) return
        val deleteOriginal = uiState.value.securityPrefs.deleteOriginalOnImport

        viewModelScope.launch {
            val pendingMediaDeleteUris = mutableListOf<Uri>()
            var importedCount = 0
            var deletedCount = 0
            var pendingDeleteCount = 0
            var sourceDeleteFailedCount = 0
            var failedCount = 0
            val failureDetails = mutableListOf<String>()

            uris.forEachIndexed { index, uri ->
                _localState.update {
                    it.copy(
                        importProgress = ImportProgressState(
                            processed = index,
                            total = uris.size,
                            currentFileName = "Mengenkripsi file ${index + 1} dari ${uris.size}..."
                        )
                    )
                }
                runCatching {
                    repository.importFile(uri, albumId, deleteOriginal)
                }.onSuccess { outcome ->
                    importedCount++
                    if (outcome.originalDeletedDirectly) {
                        deletedCount++
                    } else if (outcome.pendingMediaDeleteUri != null) {
                        pendingMediaDeleteUris.add(outcome.pendingMediaDeleteUri)
                        pendingDeleteCount++
                    } else if (deleteOriginal) {
                        sourceDeleteFailedCount++
                    }
                }.onFailure { error ->
                    failedCount++
                    failureDetails.add("File ${index + 1}: ${error.javaClass.simpleName}")
                }
            }

            _localState.update { it.copy(importProgress = null) }

            if (pendingMediaDeleteUris.isNotEmpty()) {
                _events.tryEmit(VaultEvent.RequestMediaStoreDelete(pendingMediaDeleteUris))
            }

            val msg = when {
                importedCount == 0 && failedCount > 0 ->
                    "Import gagal untuk $failedCount file. Penyebab: ${failureDetails.firstOrNull() ?: "gagal membaca file"}"
                importedCount == 0 -> "Tidak ada file yang berhasil diimpor"
                failedCount > 0 ->
                    "$importedCount file berhasil diamankan; $failedCount file gagal. ${failureDetails.firstOrNull() ?: ""}"
                deleteOriginal && deletedCount == importedCount ->
                    "$importedCount file berhasil dipindahkan ke brankas; file asli dihapus"
                deleteOriginal && pendingDeleteCount > 0 && sourceDeleteFailedCount == 0 ->
                    "$importedCount file terenkripsi di brankas. Konfirmasi sistem diperlukan untuk menghapus $pendingDeleteCount file asli."
                deleteOriginal && sourceDeleteFailedCount > 0 ->
                    "$importedCount file aman di brankas, tetapi $sourceDeleteFailedCount file asli gagal dihapus. Periksa Galeri."
                else ->
                    "$importedCount file berhasil dienkripsi (AES-256) ke brankas"
            }
            _events.tryEmit(VaultEvent.ShowMessage(msg))
        }
    }

    fun moveFilesToAlbum(fileIds: List<String>, targetAlbumId: String) {
        viewModelScope.launch {
            repository.moveFilesToAlbum(fileIds, targetAlbumId)
            _events.tryEmit(VaultEvent.ShowMessage("${fileIds.size} file dipindahkan"))
        }
    }

    fun toggleFavorite(file: VaultFileEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(file.id, file.isFavorite)
        }
    }

    fun deleteFiles(fileIds: List<String>) {
        viewModelScope.launch {
            repository.deleteFiles(fileIds)
            _events.tryEmit(VaultEvent.ShowMessage("${fileIds.size} file dihapus permanen"))
        }
    }

    fun exportFileToUri(file: VaultFileEntity, destinationUri: Uri, removeFromVault: Boolean) {
        clearExternalPickerFlag()
        viewModelScope.launch {
            runCatching {
                repository.exportFile(file, destinationUri, removeFromVault)
            }.onSuccess {
                val msg = if (removeFromVault) {
                    "\"${file.originalName}\" didekripsi & dikeluarkan dari brankas"
                } else {
                    "Salinan dekripsi \"${file.originalName}\" berhasil disimpan"
                }
                _events.tryEmit(VaultEvent.ShowMessage(msg))
            }.onFailure { err ->
                _events.tryEmit(VaultEvent.ShowMessage("Gagal mengekspor: ${err.localizedMessage}"))
            }
        }
    }

    suspend fun loadThumbnail(file: VaultFileEntity): Bitmap? = repository.loadThumbnail(file)

    suspend fun decryptBytes(file: VaultFileEntity): ByteArray = repository.decryptBytes(file)

    fun openEncryptedMediaDataSource(file: VaultFileEntity): MediaDataSource =
        repository.openEncryptedMediaDataSource(file)

    suspend fun decryptToEphemeralCacheFile(file: VaultFileEntity): File =
        repository.decryptToEphemeralCacheFile(file)

    fun listExternalEncryptedFiles(folderUri: android.net.Uri) = repository.listExternalEncryptedFiles(folderUri)

    fun scanExternalVaultFolder(folderUri: String, onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            val names = runCatching {
                repository.listExternalEncryptedFiles(android.net.Uri.parse(folderUri))
                    .mapNotNull { it.name }
                    .filterNot { it.endsWith(".thumb.vault", ignoreCase = true) }
                    .sorted()
            }.getOrElse { emptyList() }
            onResult(names)
            _events.tryEmit(VaultEvent.ShowMessage("Ditemukan ${names.size} backup utama terenkripsi"))
        }
    }

    fun restoreExternalVaultFile(
        folderUri: String,
        backupName: String,
        onResult: (VaultFileEntity?) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.restoreExternalEncryptedFile(android.net.Uri.parse(folderUri), backupName)
            }
            result.onSuccess { entity ->
                _events.tryEmit(VaultEvent.ShowMessage("Backup dipulihkan: ${entity.originalName}"))
            }.onFailure { error ->
                _events.tryEmit(VaultEvent.ShowMessage("Pemulihan gagal: ${error.message ?: "backup tidak kompatibel"}"))
            }
            onResult(result.getOrNull())
        }
    }

    suspend fun decryptExternalEncryptedFileToEphemeralCache(sourceUri: android.net.Uri, displayName: String? = null): File =
        repository.decryptExternalEncryptedFileToEphemeralCache(sourceUri, displayName)

    fun secureDeleteEphemeralFile(file: File) = repository.secureDeleteEphemeralFile(file)

    fun changePin(currentPin: String, newPin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repository.changePin(currentPin, newPin)
            if (ok) {
                _events.tryEmit(VaultEvent.ShowMessage("PIN Brankas berhasil diperbarui"))
            }
            onResult(ok)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBiometricEnabled(enabled) }
    }

    fun setAutoLockOnBackground(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoLockOnBackground(enabled) }
    }

    fun setBlockScreenshots(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockScreenshots(enabled) }
    }

    fun setExternalVaultFolder(uri: String?) {
        viewModelScope.launch {
            runCatching { repository.setExternalVaultFolder(uri) }
                .onSuccess { _events.tryEmit(VaultEvent.ShowMessage("Folder BrankasKu berhasil disiapkan. Salinan file terenkripsi disinkronkan.")) }
                .onFailure { _events.tryEmit(VaultEvent.ShowMessage("Gagal menyiapkan folder BrankasKu: ${it.localizedMessage}")) }
        }
    }

    fun setDeleteOriginalOnImport(enabled: Boolean) {
        viewModelScope.launch { repository.setDeleteOriginalOnImport(enabled) }
    }

    fun setThemePreference(theme: VaultThemePreference) {
        viewModelScope.launch { repository.setThemePreference(theme) }
    }

    fun emitMessage(msg: String) {
        _events.tryEmit(VaultEvent.ShowMessage(msg))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ): T {
                val app = checkNotNull(
                    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ) as BrankasKuApp
                return BrankasViewModel(app, app.vaultRepository) as T
            }
        }
    }
}
