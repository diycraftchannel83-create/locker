package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.ui.VaultUiState
import com.example.ui.components.AesBadgePill
import com.example.ui.components.AlbumEditorDialog
import com.example.ui.components.AlbumIconRegistry
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.EncryptedThumbnailBox
import com.example.ui.components.VaultActionSheet
import com.example.ui.components.categoryLabel
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHomeScreen(
    uiState: VaultUiState,
    onSearchQueryChange: (String) -> Unit,
    onCategorySelected: (MediaCategory?) -> Unit,
    onAlbumClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
    onCreateAlbum: (String, String, Long, Boolean) -> Unit,
    onUpdateAlbum: (AlbumEntity) -> Unit,
    onDeleteAlbum: (albumId: String, moveToAlbumId: String?) -> Unit,
    onImportUris: (List<Uri>, albumId: String) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    onLockNow: () -> Unit,
    onOpenSettings: () -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<AlbumEntity?>(null) }
    var deletingAlbum by remember { mutableStateOf<AlbumWithStats?>(null) }
    var showMoveOnDeletePicker by remember { mutableStateOf<AlbumWithStats?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var pendingTargetAlbumId by remember(uiState.albums) {
        mutableStateOf(uiState.albums.firstOrNull()?.album?.id)
    }

    val targetAlbumId = pendingTargetAlbumId ?: uiState.albums.firstOrNull()?.album?.id

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onClearExternalPickerFlag()
        val albumId = targetAlbumId
        if (uris.isNotEmpty() && albumId != null) {
            onImportUris(uris, albumId)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onClearExternalPickerFlag()
        val albumId = targetAlbumId
        if (uris.isNotEmpty() && albumId != null) {
            onImportUris(uris, albumId)
        }
    }

    val totalFiles = uiState.allFiles.size
    val totalBytes = remember(uiState.allFiles) { uiState.allFiles.sumOf { it.originalSizeBytes } }

    val filteredFiles = remember(uiState.allFiles, uiState.searchQuery, uiState.selectedCategory) {
        uiState.allFiles.filter { file ->
            val matchesQuery = uiState.searchQuery.isBlank() ||
                file.originalName.contains(uiState.searchQuery, ignoreCase = true)
            val matchesCat = uiState.selectedCategory == null || file.category == uiState.selectedCategory
            matchesQuery && matchesCat
        }
    }
    val albumNameById = remember(uiState.albums) {
        uiState.albums.associate { it.album.id to it.album.name }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "BrankasKu",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onLockNow,
                        modifier = Modifier.testTag("lock_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Kunci Brankas Sekarang"
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Pengaturan Keamanan"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showActionSheet = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Kunci File") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_add_vault")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Import progress banner
                uiState.importProgress?.let { progress ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = progress.currentFileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.total > 0) {
                                            progress.processed.toFloat() / progress.total.toFloat()
                                        } else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Security Hero Status Card
                item {
                    VaultHeroCard(
                        totalFiles = totalFiles,
                        totalBytes = totalBytes,
                        albumCount = uiState.albums.size,
                        isHardwareBacked = uiState.isHardwareBackedKeystore,
                        biometricEnabled = uiState.securityPrefs.biometricEnabled
                    )
                }

                // Folder browser: browse vault by media type; search is intentionally removed.
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Jelajahi Folder Brankas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = uiState.selectedCategory == null,
                                onClick = { onCategorySelected(null) },
                                label = { Text("Folder & Album") }
                            )
                            listOf(
                                MediaCategory.IMAGE,
                                MediaCategory.VIDEO,
                                MediaCategory.DOCUMENT,
                                MediaCategory.AUDIO
                            ).forEach { cat ->
                                FilterChip(
                                    selected = uiState.selectedCategory == cat,
                                    onClick = {
                                        onCategorySelected(if (uiState.selectedCategory == cat) null else cat)
                                    },
                                    label = { Text(categoryLabel(cat)) }
                                )
                            }
                        }
                    }
                }

                // If searching or filtering by category, display matching files directly
                if (uiState.selectedCategory != null) {
                    item {
                        Text(
                            text = "Hasil (${filteredFiles.size} file)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (filteredFiles.isEmpty()) {
                        item {
                            EmptySearchCard()
                        }
                    } else {
                        items(filteredFiles, key = { it.id }) { file ->
                            VaultFileRowCard(
                                file = file,
                                albumName = albumNameById[file.albumId] ?: "Brankas",
                                loadThumbnail = loadThumbnail,
                                onClick = { onFileClick(file.id) }
                            )
                        }
                    }
                } else {
                    // Albums section header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Folder Brankas",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Folder terisolasi & terenkripsi AES-256",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showCreateAlbumDialog = true },
                                modifier = Modifier.testTag("new_album_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CreateNewFolder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Album Baru")
                            }
                        }
                    }

                    // 2-column adaptive rows of album cards
                    val albumRows = uiState.albums.chunked(2)
                    items(albumRows) { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { albumStats ->
                                AlbumCard(
                                    albumWithStats = albumStats,
                                    onClick = { onAlbumClick(albumStats.album.id) },
                                    onEditClick = { editingAlbum = albumStats.album },
                                    onDeleteClick = { deletingAlbum = albumStats },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Recent locked files section
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "File Terkunci Terbaru",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    if (uiState.allFiles.isEmpty()) {
                        item {
                            EmptyVaultWelcomeCard(
                                onPickMedia = {
                                    onMarkLaunchingExternalPicker()
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onPickDocuments = {
                                    onMarkLaunchingExternalPicker()
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    } else {
                        items(uiState.allFiles.take(8), key = { it.id }) { file ->
                            VaultFileRowCard(
                                file = file,
                                albumName = albumNameById[file.albumId] ?: "Brankas",
                                loadThumbnail = loadThumbnail,
                                onClick = { onFileClick(file.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showActionSheet) {
        VaultActionSheetWithTargetAlbum(
            albums = uiState.albums,
            selectedAlbumId = targetAlbumId,
            onSelectAlbum = { pendingTargetAlbumId = it },
            onDismiss = { showActionSheet = false },
            onPickMedia = {
                onMarkLaunchingExternalPicker()
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickDocuments = {
                onMarkLaunchingExternalPicker()
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
            onCreateAlbum = { showCreateAlbumDialog = true }
        )
    }

    if (showCreateAlbumDialog) {
        AlbumEditorDialog(
            onDismiss = { showCreateAlbumDialog = false },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onCreateAlbum(name, iconKey, colorHex, isPinned)
                showCreateAlbumDialog = false
            }
        )
    }

    editingAlbum?.let { album ->
        AlbumEditorDialog(
            existingAlbum = album,
            onDismiss = { editingAlbum = null },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onUpdateAlbum(
                    album.copy(
                        name = name,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        isPinned = isPinned
                    )
                )
                editingAlbum = null
            }
        )
    }

    deletingAlbum?.let { target ->
        val hasOtherAlbums = uiState.albums.size > 1
        AlertDialog(
            onDismissRequest = { deletingAlbum = null },
            title = { Text("Hapus Album \"${target.album.name}\"?") },
            text = {
                Text(
                    if (target.fileCount > 0) {
                        "Album ini berisi ${target.fileCount} file terenkripsi. Anda dapat memindahkan file ke album lain atau menghapus semuanya secara permanen."
                    } else {
                        "Album kosong ini akan dihapus dari brankas."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAlbum(target.album.id, null)
                        deletingAlbum = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (target.fileCount > 0) "Hapus Permanen" else "Hapus")
                }
            },
            dismissButton = {
                Row {
                    if (target.fileCount > 0 && hasOtherAlbums) {
                        TextButton(onClick = {
                            showMoveOnDeletePicker = target
                            deletingAlbum = null
                        }) {
                            Text("Pindah File")
                        }
                    }
                    TextButton(onClick = { deletingAlbum = null }) {
                        Text("Batal")
                    }
                }
            }
        )
    }

    showMoveOnDeletePicker?.let { source ->
        AlbumPickerDialog(
            title = "Pindahkan ${source.fileCount} File ke Album:",
            albums = uiState.albums,
            excludedAlbumId = source.album.id,
            onDismiss = { showMoveOnDeletePicker = null },
            onAlbumSelected = { destination ->
                onDeleteAlbum(source.album.id, destination.album.id)
                showMoveOnDeletePicker = null
            }
        )
    }
}

@Composable
private fun VaultActionSheetWithTargetAlbum(
    albums: List<AlbumWithStats>,
    selectedAlbumId: String?,
    onSelectAlbum: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit,
    onCreateAlbum: () -> Unit
) {
    VaultActionSheet(
        showCreateAlbumOption = true,
        albums = albums,
        selectedAlbumId = selectedAlbumId,
        onSelectAlbum = onSelectAlbum,
        onDismiss = onDismiss,
        onPickMedia = onPickMedia,
        onPickDocuments = onPickDocuments,
        onCreateAlbum = onCreateAlbum
    )
}

@Composable
private fun VaultHeroCard(
    totalFiles: Int,
    totalBytes: Long,
    albumCount: Int,
    isHardwareBacked: Boolean,
    biometricEnabled: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(22.dp)
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_vault_shield_hero_1795646836203),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .alpha(0.24f)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AesBadgePill(
                    text = if (isHardwareBacked) "AES-256-GCM • HW KEYSTORE" else "AES-256-GCM • TERENKRIPSI"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Tersembunyi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroStatBlock(label = "File Terkunci", value = totalFiles.toString())
                HeroStatBlock(label = "Album", value = albumCount.toString())
                HeroStatBlock(label = "Ukuran Brankas", value = formatFileSize(totalBytes))
            }

            Text(
                text = if (biometricEnabled) {
                    "Sidik jari aktif • Penyimpanan internal privat & tidak terindeks galeri."
                } else {
                    "PIN aktif • Penyimpanan internal privat & tidak terindeks galeri."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeroStatBlock(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AlbumCard(
    albumWithStats: AlbumWithStats,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val album = albumWithStats.album
    val accent = Color(album.colorHex)
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AlbumIconRegistry.iconForKey(album.iconKey),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu Album",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Kustomisasi Album") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onEditClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus Album") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (album.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Disematkan",
                        tint = accent,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${albumWithStats.fileCount} item",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Text(
                    text = formatFileSize(albumWithStats.totalSizeBytes),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun VaultFileRowCard(
    file: VaultFileEntity,
    albumName: String?,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("vault_file_row_${file.id}"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EncryptedThumbnailBox(
                file = file,
                loadThumbnail = loadThumbnail,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.originalName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        if (albumName != null) {
                            append(albumName)
                            append(" • ")
                        }
                        append(formatFileSize(file.originalSizeBytes))
                        append(" • ")
                        append(categoryLabel(file.category))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(file.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EmptyVaultWelcomeCard(
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "Brankas Masih Kosong",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tambahkan foto, video, atau dokumen rahasia. Semua file dienkripsi AES-256-GCM dan disembunyikan dari galeri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickMedia,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Foto/Video")
                }
                OutlinedButton(
                    onClick = onPickDocuments,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dokumen")
                }
            }
        }
    }
}

@Composable
private fun EmptySearchCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Tidak ada file yang cocok",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Coba kata kunci lain atau ubah filter kategori.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
