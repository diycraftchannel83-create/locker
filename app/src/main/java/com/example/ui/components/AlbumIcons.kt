package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector

data class AlbumIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector
)

object AlbumIconRegistry {
    val options: List<AlbumIconOption> = listOf(
        AlbumIconOption("folder", "Folder", Icons.Rounded.Folder),
        AlbumIconOption("photo", "Galeri", Icons.Rounded.PhotoLibrary),
        AlbumIconOption("document", "Dokumen", Icons.Rounded.Description),
        AlbumIconOption("badge", "Identitas", Icons.Rounded.Badge),
        AlbumIconOption("finance", "Keuangan", Icons.Rounded.AccountBalance),
        AlbumIconOption("lock", "Rahasia", Icons.Rounded.Lock),
        AlbumIconOption("key", "Kunci", Icons.Rounded.Key),
        AlbumIconOption("favorite", "Favorit", Icons.Rounded.Favorite),
        AlbumIconOption("work", "Kerja", Icons.Rounded.Work),
        AlbumIconOption("school", "Studi", Icons.Rounded.School),
        AlbumIconOption("travel", "Perjalanan", Icons.Rounded.Flight),
        AlbumIconOption("star", "Penting", Icons.Rounded.Star)
    )

    fun iconForKey(key: String): ImageVector {
        return options.firstOrNull { it.key == key }?.icon ?: Icons.Rounded.Folder
    }
}
