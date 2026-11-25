package com.example.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaCategory {
    IMAGE,
    VIDEO,
    DOCUMENT,
    AUDIO,
    OTHER;

    companion object {
        fun fromMimeType(mimeType: String?, fileName: String): MediaCategory {
            val mime = mimeType?.lowercase().orEmpty()
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> IMAGE
                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> VIDEO
                mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "aac", "ogg", "flac") -> AUDIO
                mime == "application/pdf" ||
                    mime.startsWith("text/") ||
                    mime.contains("document") ||
                    mime.contains("spreadsheet") ||
                    mime.contains("presentation") ||
                    ext in setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: Long,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

@Entity(
    tableName = "vault_files",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["albumId"])]
)
data class VaultFileEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val originalName: String,
    val mimeType: String,
    val category: MediaCategory,
    val originalSizeBytes: Long,
    val encryptedFileName: String,
    val encryptedThumbName: String?,
    val encryptionAlgorithm: String = "AES-256-GCM",
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

data class AlbumWithStats(
    @Embedded val album: AlbumEntity,
    val fileCount: Int,
    val totalSizeBytes: Long
)
