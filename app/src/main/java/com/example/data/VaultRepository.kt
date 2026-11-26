package com.example.data

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.net.Uri
import com.example.crypto.MasterKeyProvider
import com.example.crypto.PinCrypto
import com.example.crypto.VaultCryptoEngine
import com.example.ui.theme.AlbumAccentPalette
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class VaultRepository(
    private val dao: VaultDao,
    private val storageManager: VaultStorageManager,
    private val securityPrefs: SecurityPreferencesDataStore,
    private val masterKeyProvider: MasterKeyProvider
) {
    val securityPreferencesFlow: Flow<SecurityPreferencesState> = securityPrefs.preferencesFlow
    val albumsWithStatsFlow: Flow<List<AlbumWithStats>> = dao.observeAlbumsWithStats()
    val allFilesFlow: Flow<List<VaultFileEntity>> = dao.observeAllFiles()

    fun observeAlbum(albumId: String): Flow<AlbumEntity?> = dao.observeAlbum(albumId)

    fun observeFilesInAlbum(albumId: String): Flow<List<VaultFileEntity>> =
        dao.observeFilesInAlbum(albumId)

    fun observeFileById(fileId: String): Flow<VaultFileEntity?> =
        dao.observeFileById(fileId)

    fun isHardwareKeystoreBacked(): Boolean = masterKeyProvider.isHardwareBacked()

    fun getVaultDirectoryDisplay(): String = storageManager.getVaultInternalPathDisplay()

    suspend fun setupInitialPin(pin: String, enableBiometric: Boolean) {
        val salt = PinCrypto.generateSalt()
        val hash = PinCrypto.hashPin(pin, salt)
        securityPrefs.savePinAndSetup(hash, salt, enableBiometric)
        ensureDefaultAlbums()
    }

    suspend fun verifyPin(pin: String): Boolean {
        val state = securityPrefs.preferencesFlow.first()
        val expectedHash = state.pinHash ?: return false
        val salt = state.pinSalt ?: return false
        val matches = PinCrypto.verifyPin(pin, salt, expectedHash)
        if (matches) {
            securityPrefs.resetFailedAttempts()
        } else {
            val newAttempts = state.failedAttempts + 1
            val lockoutUntil = if (newAttempts >= 5) {
                System.currentTimeMillis() + 30_000L
            } else {
                0L
            }
            securityPrefs.recordFailedAttempt(newAttempts, lockoutUntil)
        }
        return matches
    }

    suspend fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        val newSalt = PinCrypto.generateSalt()
        val newHash = PinCrypto.hashPin(newPin, newSalt)
        securityPrefs.updatePin(newHash, newSalt)
        return true
    }

    suspend fun ensureDefaultAlbums() {
        if (dao.getAlbumCount() == 0) {
            val now = System.currentTimeMillis()
            val defaults = listOf(
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Foto & Video Pribadi",
                    iconKey = "photo",
                    colorHex = AlbumAccentPalette[0],
                    isPinned = true,
                    createdAt = now,
                    sortOrder = 0
                ),
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Dokumen Penting",
                    iconKey = "document",
                    colorHex = AlbumAccentPalette[1],
                    isPinned = false,
                    createdAt = now + 1,
                    sortOrder = 1
                ),
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Identitas & Keuangan",
                    iconKey = "badge",
                    colorHex = AlbumAccentPalette[2],
                    isPinned = false,
                    createdAt = now + 2,
                    sortOrder = 2
                )
            )
            dao.insertAlbums(defaults)
        }
    }

    suspend fun createAlbum(
        name: String,
        iconKey: String,
        colorHex: Long,
        isPinned: Boolean
    ): AlbumEntity {
        val album = AlbumEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            iconKey = iconKey,
            colorHex = colorHex,
            isPinned = isPinned,
            createdAt = System.currentTimeMillis(),
            sortOrder = dao.getAlbumCount()
        )
        dao.insertAlbum(album)
        return album
    }

    suspend fun updateAlbum(album: AlbumEntity) {
        dao.updateAlbum(album)
    }

    suspend fun deleteAlbumAndFiles(albumId: String) {
        val files = dao.getFilesInAlbumSync(albumId)
        files.forEach { storageManager.deleteEncryptedAssets(it) }
        dao.deleteAlbumById(albumId)
    }

    suspend fun deleteAlbumMovingFilesTo(albumId: String, targetAlbumId: String) {
        val files = dao.getFilesInAlbumSync(albumId)
        if (files.isNotEmpty()) {
            dao.moveFilesToAlbum(files.map { it.id }, targetAlbumId)
        }
        dao.deleteAlbumById(albumId)
    }

    suspend fun importFile(
        uri: Uri,
        albumId: String,
        deleteOriginal: Boolean
    ): ImportedFileOutcome {
        val outcome = storageManager.importUri(uri, albumId, deleteOriginal)
        // Commit metadata first: never delete the original if the vault record fails.
        try {
            dao.insertFile(outcome.entity)
        } catch (error: Throwable) {
            // Remove the encrypted blob/thumbnail if the metadata commit failed.
            storageManager.deleteEncryptedAssets(outcome.entity)
            throw error
        }
        val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let { Uri.parse(it) }
        storageManager.mirrorEncryptedFileToFolder(folderUri, outcome.entity)
        if (!deleteOriginal) return outcome

        val deletedDirectly = storageManager.deleteOriginalAfterImport(uri)
        return outcome.copy(
            originalDeletedDirectly = deletedDirectly,
            pendingMediaDeleteUri = if (deletedDirectly) null else outcome.pendingMediaDeleteUri
        )
    }

    suspend fun moveFilesToAlbum(fileIds: List<String>, targetAlbumId: String) {
        if (fileIds.isNotEmpty()) {
            dao.moveFilesToAlbum(fileIds, targetAlbumId)
        }
    }

    suspend fun toggleFavorite(fileId: String, currentFavorite: Boolean) {
        dao.setFavorite(fileId, !currentFavorite)
    }

    suspend fun deleteFiles(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        val entities = dao.getFilesByIds(fileIds)
        entities.forEach { storageManager.deleteEncryptedAssets(it) }
        dao.deleteFilesByIds(fileIds)
    }

    suspend fun exportFile(
        fileEntity: VaultFileEntity,
        destinationUri: Uri,
        removeFromVault: Boolean
    ) {
        storageManager.decryptToUri(fileEntity, destinationUri)
        if (removeFromVault) {
            storageManager.deleteEncryptedAssets(fileEntity)
            dao.deleteFilesByIds(listOf(fileEntity.id))
        }
    }

    suspend fun loadThumbnail(fileEntity: VaultFileEntity): Bitmap? =
        storageManager.loadDecryptedThumbnail(fileEntity)

    suspend fun decryptBytes(fileEntity: VaultFileEntity): ByteArray =
        storageManager.decryptToBytes(fileEntity)

    fun openEncryptedMediaDataSource(fileEntity: VaultFileEntity): MediaDataSource =
        storageManager.openEncryptedMediaDataSource(fileEntity)

    suspend fun decryptToEphemeralCacheFile(fileEntity: VaultFileEntity): File =
        storageManager.decryptToEphemeralCacheFile(fileEntity)

    fun secureDeleteEphemeralFile(file: File) {
        storageManager.secureDeleteFile(file)
    }

    fun clearSensitiveCaches() {
        storageManager.clearSensitiveCaches()
    }

    suspend fun setBiometricEnabled(enabled: Boolean) = securityPrefs.setBiometricEnabled(enabled)
    suspend fun setAutoLockOnBackground(enabled: Boolean) = securityPrefs.setAutoLockOnBackground(enabled)
    suspend fun setBlockScreenshots(enabled: Boolean) = securityPrefs.setBlockScreenshots(enabled)
    suspend fun setDeleteOriginalOnImport(enabled: Boolean) = securityPrefs.setDeleteOriginalOnImport(enabled)

    suspend fun setExternalVaultFolder(uri: String?) {
        securityPrefs.setExternalVaultFolder(uri)
        val parsed = uri?.let { Uri.parse(it) } ?: return
        dao.getAllFilesForMirror().forEach { storageManager.mirrorEncryptedFileToFolder(parsed, it) }
    }
    suspend fun setThemePreference(theme: VaultThemePreference) = securityPrefs.setThemePreference(theme)
}
