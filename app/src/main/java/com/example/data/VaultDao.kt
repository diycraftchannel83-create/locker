package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    @Query(
        """
        SELECT a.*,
               COUNT(f.id) AS fileCount,
               COALESCE(SUM(f.originalSizeBytes), 0) AS totalSizeBytes
        FROM albums a
        LEFT JOIN vault_files f ON a.id = f.albumId
        GROUP BY a.id
        ORDER BY a.isPinned DESC, a.sortOrder ASC, a.createdAt ASC
        """
    )
    fun observeAlbumsWithStats(): Flow<List<AlbumWithStats>>

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    fun observeAlbum(albumId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    suspend fun getAlbumById(albumId: String): AlbumEntity?

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumCount(): Int

    @Query("SELECT * FROM albums ORDER BY isPinned DESC, sortOrder ASC, createdAt ASC LIMIT 1")
    suspend fun getRecoveryTargetAlbum(): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Update
    suspend fun updateAlbum(album: AlbumEntity)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbumById(albumId: String)

    @Query("SELECT * FROM vault_files WHERE albumId = :albumId ORDER BY createdAt DESC")
    fun observeFilesInAlbum(albumId: String): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE albumId = :albumId")
    suspend fun getFilesInAlbumSync(albumId: String): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files")
    suspend fun getAllFilesForMirror(): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files ORDER BY createdAt DESC")
    fun observeAllFiles(): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE id = :fileId LIMIT 1")
    fun observeFileById(fileId: String): Flow<VaultFileEntity?>

    @Query("SELECT * FROM vault_files WHERE id IN (:fileIds)")
    suspend fun getFilesByIds(fileIds: List<String>): List<VaultFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: VaultFileEntity)

    @Query("SELECT * FROM vault_files WHERE originalName = :originalName LIMIT 1")
    suspend fun findFileByOriginalName(originalName: String): VaultFileEntity?

    @Query("SELECT * FROM vault_files WHERE id = :fileId LIMIT 1")
    suspend fun findFileById(fileId: String): VaultFileEntity?

    @Query("UPDATE vault_files SET albumId = :targetAlbumId WHERE id IN (:fileIds)")
    suspend fun moveFilesToAlbum(fileIds: List<String>, targetAlbumId: String)

    @Query("UPDATE vault_files SET isFavorite = :favorite WHERE id = :fileId")
    suspend fun setFavorite(fileId: String, favorite: Boolean)

    @Query("DELETE FROM vault_files WHERE id IN (:fileIds)")
    suspend fun deleteFilesByIds(fileIds: List<String>)
}
