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

    suspend fun importUri(
        uri: Uri,
        albumId: String,
        deleteOriginal: Boolean
    ): ImportedFileOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, queriedSize) = queryDisplayNameAndSize(uri)
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
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
                MediaStore.getMediaUri(context, uri)
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
        thumbnailCache.remove(fileEntity.id)
        secureDeleteFile(File(blobsDir, fileEntity.encryptedFileName))
        fileEntity.encryptedThumbName?.let { secureDeleteFile(File(thumbsDir, it)) }
    }

    fun secureDeleteFile(file: File) {
        if (!file.exists()) return
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
        file.delete()
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

        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
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
