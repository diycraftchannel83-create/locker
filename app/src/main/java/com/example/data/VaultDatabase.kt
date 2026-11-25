package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class VaultTypeConverters {
    @TypeConverter
    fun fromMediaCategory(category: MediaCategory): String = category.name

    @TypeConverter
    fun toMediaCategory(value: String): MediaCategory =
        runCatching { MediaCategory.valueOf(value) }.getOrDefault(MediaCategory.OTHER)
}

@Database(
    entities = [AlbumEntity::class, VaultFileEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(VaultTypeConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        fun getInstance(context: Context): VaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "brankasku_vault.db"
                ).build().also { instance = it }
            }
        }
    }
}
