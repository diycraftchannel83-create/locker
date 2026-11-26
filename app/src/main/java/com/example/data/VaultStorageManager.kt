package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.VaultCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class ImportedFileOutcome(
    val entity: VaultFileEntity,
    val sourceUri: Uri,
    val originalDeletedDirectly: Boolean = false,
    val pendingMediaDeleteUri: Uri? = null
)

class VaultStorageManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine
) {
    private val vaultRootDir: File by lazy {
        File(context.filesDir, "secure_vault").apply {
            if (!exists()) mkdirs()
            val noMedia = File(this, ".nomedia")
            if (!noMedia.exists()) {
                runCatching { noMedia.createNewFile() }
            }
        }
    }

    private val blobsDir: File by lazy {
        File(vaultRootDir, "blobs").apply { if (!exists()) mkdirs() }
    }

    private val thumbsDir: File by lazy {
        File(vaultRootDir, "thumbs").apply { if (!exists()) mkdirs() }
    }

    // Decrypted thumbnail bitmaps kept strictly in RAM and cleared on lock
    private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getVaultInternalPathDisplay(): String = vaultRootDir.absolutePath

    /** Lists encrypted backup files in the user-selected external folder. */
    fun listExternalEncryptedFiles(folderUri: Uri): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val vaultFolder = root.findFile("BrankasKu") ?: root
        return vaultFolder.listFiles().filter { it.isFile && it.name?.endsWith(".vault", ignoreCase = true) == true }
    }

    /**
     * Validates a selected external backup by decrypting it to a private temporary file,
     * then copies the original ciphertext into the internal vault. External data is never
     * deleted or replaced during recovery.
     */
    suspend fun recoverExternalEncryptedFile(
        folderUri: Uri,
        backupName: String,
        albumId: String
    ): VaultFileEntity = withContext(Dispatchers.IO) {
        require(backupName.endsWith(".vault", ignoreCase = true) && !backupName.endsWith(".thumb.vault", ignoreCase = true)) {
            "File bukan backup utama .vault"
        }
        require(backupName == backupName.substringAfterLast('/')) { "Nama file backup tidak valid" }
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: throw IllegalStateException("Folder backup tidak dapat dibuka")
        val backupFolder = root.findFile("BrankasKu") ?: root
        val source = backupFolder.findFile(backupName)?.takeIf { it.isFile }
            ?: throw IllegalStateException("Backup $backupName tidak ditemukan")
        val tempDir = File(context.cacheDir, "vault_recovery").apply { if (!exists()) mkdirs() }
        val plaintextTemp = File(tempDir, "recover_${UUID.randomUUID()}.tmp")
        var internalBlob: File? = null
        var internalThumb: File? = null
        try {
            context.contentResolver.openInputStream(source.uri)?.buffered()?.use { input ->
                plaintextTemp.outputStream().buffered().use { output -> cryptoEngine.decryptStream(input, output) }
            } ?: throw IllegalStateException("Backup eksternal tidak dapat dibaca")
            if (plaintextTemp.length() <= 0L) throw IllegalStateException("Backup terdekripsi kosong")
            val info = detectRecoveredFileInfo(plaintextTemp, backupName)
            val id = UUID.randomUUID().toString()
            val encryptedName = "$id.vlt"
            internalBlob = File(blobsDir, encryptedName)
            context.contentResolver.openInputStream(source.uri)?.buffered()?.use { input ->
                internalBlob!!.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Gagal menyalin ciphertext backup")
            var encryptedThumbName: String? = null
            if (info.category == MediaCategory.IMAGE || info.category == MediaCategory.VIDEO) {
                val thumbBytes = createThumbnailJpegFromFile(plaintextTemp, info.category)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val thumbName = "$id.thm"
                    internalThumb = File(thumbsDir, thumbName)
                    internalThumb!!.writeBytes(cryptoEngine.encryptBytes(thumbBytes))
                    encryptedThumbName = thumbName
                    thumbBytes.fill(0)
                }
            }
            VaultFileEntity(
                id = id,
                albumId = albumId,
                originalName = info.displayName,
                mimeType = info.mimeType,
                category = info.category,
                originalSizeBytes = plaintextTemp.length(),
                encryptedFileName = encryptedName,
                encryptedThumbName = encryptedThumbName,
                encryptionAlgorithm = "AES-256-GCM",
                createdAt = System.currentTimeMillis()
            )
        } catch (error: Throwable) {
            internalBlob?.let(::secureDeleteFile)
            internalThumb?.let(::secureDeleteFile)
            throw error
        } finally {
            secureDeleteFile(plaintextTemp)
        }
    }

    private data class RecoveredFileInfo(val mimeType: String, val displayName: String, val category: MediaCategory)

    private fun detectRecoveredFileInfo(file: File, backupName: String): RecoveredFileInfo {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }.coerceAtLeast(0)
        val baseName = backupName.removeSuffix(".vault")
        val baseExt = baseName.substringAfterLast('.', "").lowercase()
        val baseMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(baseExt)
        val mime = when {
            read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
            read >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            read >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8") -> "image/gif"
            read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            read >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> "video/mp4"
            read >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "video/webm"
            read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "%PDF" -> "application/pdf"
            read >= 3 && String(header, 0, 3, Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
            read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE" -> "audio/wav"
            read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "OggS" -> "audio/ogg"
            read >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            baseMime != null -> baseMime
            else -> "application/octet-stream"
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: baseExt.takeIf { it.isNotBlank() && !it.matches(Regex("[0-9a-fA-F-]{20,}")) }
        val looksUuid = baseName.matches(Regex("[0-9a-fA-F-]{20,}"))
        val stableStem = if (looksUuid || baseName.isBlank()) baseName else baseName.substringBeforeLast('.', baseName)
        val preservedExt = if (ext != null) ext else baseExt.takeIf { it.isNotBlank() && !looksUuid }
        val displayName = "Recovered_" + stableStem + (preservedExt?.let { "." + it } ?: "")
        return RecoveredFileInfo(mime, displayName, MediaCategory.fromMimeType(mime, displayName))
    }

    private fun createThumbnailJpegFromFile(file: File, category: MediaCategory): ByteArray? = runCatching {
        val bitmap = when (category) {
            MediaCategory.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })?.let { scaleBitmapDown(it, 360) }
                }
            }
            MediaCategory.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L)
                } finally { retriever.release() }
            }
            else -> null
        }
        bitmap?.let {
            val output = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 82, output)
            it.recycle()
            output.toByteArray()
        }
    }.getOrNull()

    /** Decrypts external ciphertext into app-private temporary storage for secure viewing only. */
    suspend fun decryptExternalEncryptedFileToEphemeralCache(sourceUri: Uri, displayName: String? = null): File = withContext(Dispatchers.IO) {
        val safeName = (displayName ?: "encrypted_preview").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val previewDir = File(context.cacheDir, "vault_ephemeral").apply { if (!exists()) mkdirs() }
        val tempFile = File(previewDir, "external_" + UUID.randomUUID() + "_" + safeName + ".tmp")
        try {
            context.contentResolver.openInputStream(sourceUri)?.buffered()?.use { input ->
                tempFile.outputStream().buffered().use { output -> cryptoEngine.decryptStream(input, output) }
            } ?: throw IllegalStateException("File terenkripsi eksternal tidak dapat dibaca")
            tempFile
        } catch (error: Throwable) {
            secureDeleteFile(tempFile)
            throw error
        }
    }


    fun mirrorEncryptedFileToFolder(folderUri: Uri?, entity: VaultFileEntity) {
        if (folderUri == null) return
        runCatching {
            val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return
            val vaultFolder = parent.findFile("BrankasKu") ?: parent.createDirectory("BrankasKu") ?: return
            if (vaultFolder.findFile(".nomedia") == null) vaultFolder.createFile("text/plain", ".nomedia")
            val sourceBlob = File(blobsDir, entity.encryptedFileName)
            if (sourceBlob.exists()) copyCipherFile(sourceBlob, vaultFolder, "${entity.id}.vault")
            entity.encryptedThumbName?.let { name ->
                val sourceThumb = File(thumbsDir, name)
                if (sourceThumb.exists()) copyCipherFile(sourceThumb, vaultFolder, "${entity.id}.thumb.vault")
            }
        }
    }

    private fun copyCipherFile(source: File, folder: DocumentFile, targetName: String) {
        folder.findFile(targetName)?.delete()
        val target = folder.createFile("application/octet-stream", targetName) ?: return
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    suspend fun importUri(
        uri: Uri,
        albumId: String,
        deleteOriginal: Boolean
    ): ImportedFileOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Some cloud/provider URIs fail metadata queries; use safe fallback values.
        val (displayName, queriedSize) = runCatching { queryDisplayNameAndSize(uri) }
            .getOrElse { "file_${System.currentTimeMillis()}" to 0L }
        val mimeType = runCatching { resolver.getType(uri) }
            .getOrNull() ?: "application/octet-stream"
        val category = MediaCategory.fromMimeType(mimeType, displayName)

        val fileId = UUID.randomUUID().toString()
        val encryptedBlobName = "$fileId.vlt"
        val destBlobFile = File(blobsDir, encryptedBlobName)

        var encryptedThumbName: String? = null
        try {
            val actualPlainSize = resolver.openInputStream(uri)?.buffered()?.use { input ->
                cryptoEngine.encryptToFile(input, destBlobFile)
            } ?: throw IllegalStateException("Tidak dapat membaca file sumber")

            val finalSize = if (actualPlainSize > 0) actualPlainSize else queriedSize.coerceAtLeast(0L)

            if (category == MediaCategory.IMAGE || category == MediaCategory.VIDEO) {
                val thumbBytes = createThumbnailJpeg(uri, category)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val thumbName = "$fileId.thm"
                    val thumbFile = File(thumbsDir, thumbName)
                    thumbFile.writeBytes(cryptoEngine.encryptBytes(thumbBytes))
                    encryptedThumbName = thumbName
                    thumbBytes.fill(0)
                }
            }

            val entity = VaultFileEntity(
                id = fileId,
                albumId = albumId,
                originalName = displayName,
                mimeType = mimeType,
                category = category,
                originalSizeBytes = finalSize,
                encryptedFileName = encryptedBlobName,
                encryptedThumbName = encryptedThumbName,
                encryptionAlgorithm = "AES-256-GCM",
                createdAt = System.currentTimeMillis()
            )

            // Source deletion is intentionally deferred until the repository commits
            // the vault metadata. This prevents losing the original if DB insertion fails.
            ImportedFileOutcome(
                entity = entity,
                sourceUri = uri,
                originalDeletedDirectly = false,
                pendingMediaDeleteUri = if (deleteOriginal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && !DocumentsContract.isDocumentUri(context, uri)) resolveMediaStoreUri(uri) else null
            )
        } catch (error: Throwable) {
            secureDeleteFile(destBlobFile)
            encryptedThumbName?.let { secureDeleteFile(File(thumbsDir, it)) }
            throw error
        }
    }

    /**
     * Delete the source only after encrypted data and its Room metadata are committed.
     * On Android 11+ MediaStore deletion requires a user-approved system request,
     * so this method deliberately returns false for those media items.
     */
    fun deleteOriginalAfterImport(uri: Uri): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.deleteDocument(resolver, uri)
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    uri.authority == MediaStore.AUTHORITY ->
                    resolver.delete(uri, null, null) > 0
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun resolveMediaStoreUri(uri: Uri): Uri? {
        return try {
            if (uri.authority == MediaStore.AUTHORITY) {
                uri
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Photo Picker/provider URIs must be converted to a real MediaStore URI.
                // Never pass a picker/provider URI into createDeleteRequest: Android rejects it.
                MediaStore.getMediaUri(context, uri)?.takeIf { it.authority == MediaStore.AUTHORITY }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadDecryptedThumbnail(fileEntity: VaultFileEntity): Bitmap? = withContext(Dispatchers.IO) {
        val thumbName = fileEntity.encryptedThumbName ?: return@withContext null
        thumbnailCache.get(fileEntity.id)?.let { return@withContext it }

        val thumbFile = File(thumbsDir, thumbName)
        if (!thumbFile.exists()) return@withContext null

        return@withContext runCatching {
            val plainBytes = cryptoEngine.decryptBytes(thumbFile)
            val bitmap = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.size)
            plainBytes.fill(0)
            if (bitmap != null) {
                thumbnailCache.put(fileEntity.id, bitmap)
            }
            bitmap
        }.getOrNull()
    }

    suspend fun decryptToBytes(fileEntity: VaultFileEntity): ByteArray = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        if (!blobFile.exists()) {
            throw IllegalStateException("File terenkripsi tidak ditemukan di brankas")
        }
        cryptoEngine.decryptBytes(blobFile)
    }

    suspend fun decryptToUri(fileEntity: VaultFileEntity, destinationUri: Uri) = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        if (!blobFile.exists()) {
            throw IllegalStateException("File terenkripsi tidak ditemukan")
        }
        context.contentResolver.openOutputStream(destinationUri)?.buffered()?.use { out ->
            blobFile.inputStream().buffered().use { input ->
                cryptoEngine.decryptStream(input, out)
            }
        } ?: throw IllegalStateException("Gagal membuka tujuan ekspor")
    }

    fun openEncryptedMediaDataSource(fileEntity: VaultFileEntity): MediaDataSource {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        return cryptoEngine.openEncryptedMediaDataSource(blobFile)
    }

    /**
     * Decrypts a document (e.g. PDF) into an app-private ephemeral cache file for PdfRenderer.
     * Caller MUST invoke [secureDeleteFile] when exiting the viewer.
     */
    suspend fun decryptToEphemeralCacheFile(fileEntity: VaultFileEntity): File = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        val previewDir = File(context.cacheDir, "vault_ephemeral").apply { if (!exists()) mkdirs() }
        val tempFile = File(previewDir, "${UUID.randomUUID()}.tmp")
        tempFile.outputStream().buffered().use { out ->
            blobFile.inputStream().buffered().use { input ->
                cryptoEngine.decryptStream(input, out)
            }
        }
        tempFile
    }

    fun deleteEncryptedAssets(fileEntity: VaultFileEntity) {
        val blob = File(blobsDir, fileEntity.encryptedFileName)
        val thumb = fileEntity.encryptedThumbName?.let { File(thumbsDir, it) }
        // Do not overwrite ciphertext before delete: failed deletes must leave files readable.
        val blobDeleted = deleteCiphertextFile(blob)
        val thumbDeleted = thumb?.let(::deleteCiphertextFile) ?: true
        if (!blobDeleted || !thumbDeleted) {
            throw IllegalStateException("File terenkripsi gagal dihapus. Ciphertext dipertahankan agar tetap dapat dibaca.")
        }
        thumbnailCache.remove(fileEntity.id)
    }

    /** Delete ciphertext without modifying bytes first. */
    private fun deleteCiphertextFile(file: File): Boolean {
        if (!file.exists()) return true
        runCatching { file.delete() }
        return !file.exists()
    }

    /** Best-effort wipe for plaintext temporary/cache files, then delete. */
    fun secureDeleteFile(file: File): Boolean {
        if (!file.exists()) return true
        runCatching {
            val len = file.length()
            if (len > 0L) {
                RandomAccessFile(file, "rw").use { raf ->
                    val wipeLen = minOf(len, 65536L).toInt()
                    raf.seek(0L)
                    raf.write(ByteArray(wipeLen))
                }
            }
        }
        runCatching { file.delete() }
        return !file.exists()
    }
    fun clearSensitiveCaches() {
        thumbnailCache.evictAll()
        val previewDir = File(context.cacheDir, "vault_ephemeral")
        previewDir.listFiles()?.forEach { secureDeleteFile(it) }
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                if (sizeIdx != -1) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun createThumbnailJpeg(uri: Uri, category: MediaCategory): ByteArray? {
        return runCatching {
            val bitmap: Bitmap? = when (category) {
                MediaCategory.IMAGE -> decodeSampledBitmapFromUri(uri, 360, 360)
                MediaCategory.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(0L)
                        frame?.let { scaleBitmapDown(it, 360) }
                    } finally {
                        retriever.release()
                    }
                }
                else -> null
            }
            bitmap?.let { bmp ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                bmp.recycle()
                baos.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val height = options.outHeight
        val width = options.outWidth
        // Invalid dimensions/provider bounds: skip thumbnail rather than risk decode crash.
        if (height <= 0 || width <= 0) return null

        var inSampleSize = 1
        // Sample aggressively before decode to avoid allocating full-size images.
        while (width / inSampleSize > reqWidth * 2 || height / inSampleSize > reqHeight * 2) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }?.let { scaleBitmapDown(it, 360) }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val newW: Int
        val newH: Int
        if (ratio > 1f) {
            newW = maxDimension
            newH = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            newH = maxDimension
            newW = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }
}
