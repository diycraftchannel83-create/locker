package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.VaultFileEntity
import com.example.ui.ImportProgressState
import com.example.ui.components.AlbumEditorDialog
import com.example.ui.components.AlbumIconRegistry
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.EncryptedThumbnailBox
import com.example.ui.components.VaultActionSheet
import com.example.ui.components.formatFileSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumEntity?,
    files: List<VaultFileEntity>,
    allAlbums: List<AlbumWithStats>,
    importProgress: ImportProgressState?,
    onBack: () -> Unit,
    onFileClick: (String) -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onUpdateAlbum: (AlbumEntity) -> Unit,
    onMoveFiles: (List<String>, String) -> Unit,
    onDeleteFiles: (List<String>) -> Unit,
    onExportFile: (VaultFileEntity, Uri, Boolean) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    var isGridView by remember { mutableStateOf(true) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showEditAlbumDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingExportFile by remember { mutableStateOf<VaultFileEntity?>(null) }

    val inSelectionMode = selectedIds.isNotEmpty()
    val accentColor = album?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onClearExternalPickerFlag()
        if (uris.isNotEmpty()) onImportUris(uris)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onClearExternalPickerFlag()
        if (uris.isNotEmpty()) onImportUris(uris)
    }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        onClearExternalPickerFlag()
        val fileToExport = pendingExportFile
        pendingExportFile = null
        if (uri != null && fileToExport != null) {
            onExportFile(fileToExport, uri, true)
            selectedIds = emptySet()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (inSelectionMode) selectedIds = emptySet() else onBack()
                        }
                    ) {
                        Icon(
                            imageVector = if (inSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Kembali"
                        )
                    }
                },
                title = {
                    if (inSelectionMode) {
                        Text("${selectedIds.size} dipilih")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (album != null) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accentColor.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AlbumIconRegistry.iconForKey(album.iconKey),
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column {
                                Text(
                                    text = album?.name ?: "Album",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${files.size} file • ${formatFileSize(files.sumOf { it.originalSizeBytes })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!inSelectionMode) {
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                contentDescription = "Ganti Tampilan"
                            )
                        }
                        if (album != null) {
                            IconButton(onClick = { showEditAlbumDialog = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit Album")
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showActionSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Tambah File") },
                    containerColor = accentColor,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("album_add_file_fab")
                )
            }
        },
        bottomBar = {
            if (inSelectionMode) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(
                            onClick = { showMoveDialog = true },
                            enabled = allAlbums.size > 1
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pindah")
                        }
                        if (selectedIds.size == 1) {
                            val singleFile = files.firstOrNull { it.id == selectedIds.first() }
                            if (singleFile != null) {
                                TextButton(onClick = {
                                    pendingExportFile = singleFile
                                    onMarkLaunchingExternalPicker()
                                    exportDocumentLauncher.launch(singleFile.originalName)
                                }) {
                                    Icon(Icons.Rounded.LockOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Keluarkan")
                                }
                            }
                        }
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hapus")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            importProgress?.let { progress ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(progress.currentFileName, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Album Ini Masih Kosong",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tekan tombol di bawah untuk mengunci dan menyembunyikan foto, video, atau dokumen ke dalam album ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                onMarkLaunchingExternalPicker()
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Foto & Video")
                            }
                            OutlinedButton(onClick = {
                                onMarkLaunchingExternalPicker()
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Dokumen")
                            }
                        }
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(files, key = { it.id }) { file ->
                        val isSelected = file.id in selectedIds
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) accentColor else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (inSelectionMode) {
                                            selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                        } else {
                                            onFileClick(file.id)
                                        }
                                    },
                                    onLongClick = {
                                        selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                    }
                                )
                        ) {
                            EncryptedThumbnailBox(
                                file = file,
                                loadThumbnail = loadThumbnail,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = file.originalName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            if (inSelectionMode) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) accentColor else Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(files, key = { it.id }) { file ->
                        val isSelected = file.id in selectedIds
                        VaultFileRowCard(
                            file = file,
                            albumName = null,
                            loadThumbnail = loadThumbnail,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                } else {
                                    onFileClick(file.id)
                                }
                            },
                            modifier = if (isSelected) {
                                Modifier.border(2.dp, accentColor, RoundedCornerShape(16.dp))
                            } else Modifier
                        )
                    }
                }
            }
        }
    }

    if (showActionSheet) {
        VaultActionSheet(
            showCreateAlbumOption = false,
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
            }
        )
    }

    if (showEditAlbumDialog && album != null) {
        AlbumEditorDialog(
            existingAlbum = album,
            onDismiss = { showEditAlbumDialog = false },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onUpdateAlbum(album.copy(name = name, iconKey = iconKey, colorHex = colorHex, isPinned = isPinned))
                showEditAlbumDialog = false
            }
        )
    }

    if (showMoveDialog) {
        AlbumPickerDialog(
            title = "Pindahkan ${selectedIds.size} File ke:",
            albums = allAlbums,
            excludedAlbumId = album?.id,
            onDismiss = { showMoveDialog = false },
            onAlbumSelected = { destination ->
                onMoveFiles(selectedIds.toList(), destination.album.id)
                selectedIds = emptySet()
                showMoveDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus ${selectedIds.size} File Permanen?") },
            text = { Text("File terenkripsi yang dihapus tidak dapat dipulihkan lagi.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFiles(selectedIds.toList())
                        selectedIds = emptySet()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus Permanen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
