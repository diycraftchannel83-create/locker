package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.AlbumWithStats
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.ui.components.AesBadgePill
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureViewerScreen(
    file: VaultFileEntity?,
    allAlbums: List<AlbumWithStats>,
    vaultInternalPath: String,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource,
    decryptToEphemeralFile: suspend (VaultFileEntity) -> File,
    secureDeleteEphemeralFile: (File) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: (VaultFileEntity) -> Unit,
    onMoveFile: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onExportFile: (VaultFileEntity, Uri, Boolean) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit
) {
    if (file == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExportChoiceDialog by remember { mutableStateOf(false) }
    var removeAfterExport by remember { mutableStateOf(true) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(file.mimeType)
    ) { uri ->
        onClearExternalPickerFlag()
        if (uri != null) {
            onExportFile(file, uri, removeAfterExport)
            if (removeAfterExport) {
                onBack()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color(0xFF07090D),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = file.originalName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatFileSize(file.originalSizeBytes)} • ${file.encryptionAlgorithm}",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleFavorite(file) }) {
                        Icon(
                            imageVector = if (file.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorit",
                            tint = if (file.isFavorite) Color(0xFFF43F5E) else Color.White
                        )
                    }
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "Info Enkripsi",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = Color(0xFF10141B),
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = { showMoveDialog = true },
                        enabled = allAlbums.size > 1
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pindah", color = Color.White)
                    }
                    TextButton(onClick = { showExportChoiceDialog = true }) {
                        Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Keluarkan", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            when {
                file.category == MediaCategory.IMAGE -> {
                    ZoomableEncryptedImageViewer(file = file, decryptBytes = decryptBytes)
                }
                file.category == MediaCategory.VIDEO -> {
                    EncryptedVideoPlayer(file = file, openMediaDataSource = openMediaDataSource)
                }
                file.category == MediaCategory.AUDIO -> {
                    EncryptedAudioPlayer(file = file, openMediaDataSource = openMediaDataSource)
                }
                file.mimeType == "application/pdf" || file.originalName.endsWith(".pdf", ignoreCase = true) -> {
                    EncryptedPdfViewer(
                        file = file,
                        decryptToEphemeralFile = decryptToEphemeralFile,
                        secureDeleteEphemeralFile = secureDeleteEphemeralFile
                    )
                }
                isTextReadable(file) -> {
                    EncryptedTextViewer(file = file, decryptBytes = decryptBytes)
                }
                else -> {
                    EncryptedGenericFileCard(
                        file = file,
                        onExportClick = { showExportChoiceDialog = true }
                    )
                }
            }
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Detail Keamanan File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AesBadgePill(text = "AES-256-GCM • AUTHENTICATED")
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoLine("Nama Asli", file.originalName)
                    InfoLine("Tipe MIME", file.mimeType)
                    InfoLine("Ukuran Asli", formatFileSize(file.originalSizeBytes))
                    InfoLine("Terkunci Pada", formatTimestamp(file.createdAt))
                    InfoLine("ID Blob Terenkripsi", file.encryptedFileName)
                    InfoLine("Lokasi Terisolasi", "$vaultInternalPath/blobs/")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Tutup") }
            }
        )
    }

    if (showExportChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showExportChoiceDialog = false },
            title = { Text("Dekripsi & Simpan File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Pilih lokasi di perangkat untuk menyimpan hasil dekripsi \"${file.originalName}\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hapus dari Brankas setelah diekspor",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = removeAfterExport,
                            onCheckedChange = { removeAfterExport = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportChoiceDialog = false
                    onMarkLaunchingExternalPicker()
                    exportLauncher.launch(file.originalName)
                }) {
                    Text("Pilih Lokasi Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportChoiceDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showMoveDialog) {
        AlbumPickerDialog(
            title = "Pindahkan ke Album:",
            albums = allAlbums,
            excludedAlbumId = file.albumId,
            onDismiss = { showMoveDialog = false },
            onAlbumSelected = { target ->
                onMoveFile(file.id, target.album.id)
                showMoveDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus Permanen?") },
            text = { Text("\"${file.originalName}\" akan dihapus permanen dari penyimpanan terenkripsi.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteFile(file.id)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ZoomableEncryptedImageViewer(
    file: VaultFileEntity,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray
) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(file.id) {
        runCatching {
            val bytes = decryptBytes(file)
            val decoded = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            bytes.fill(0)
            decoded ?: throw IllegalStateException("Format gambar tidak didukung")
        }.onSuccess {
            bitmap = it
        }.onFailure {
            errorMsg = it.localizedMessage ?: "Gagal mendekripsi gambar"
        }
    }

    when {
        errorMsg != null -> Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        bitmap == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                Offset(offset.x + pan.x, offset.y + pan.y)
                            } else {
                                Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = file.originalName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }
    }
}

@Composable
private fun EncryptedVideoPlayer(
    file: VaultFileEntity,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource
) {
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var dataSource by remember { mutableStateOf<MediaDataSource?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(file.id) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            runCatching { dataSource?.close() }
            mediaPlayer = null
            dataSource = null
        }
    }

    LaunchedEffect(mediaPlayer, isPlaying) {
        while (mediaPlayer != null && isPlaying) {
            runCatching {
                positionMs = mediaPlayer?.currentPosition ?: 0
            }
            delay(300L)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (errorMsg != null) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
            } else {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    runCatching {
                                        val ds = openMediaDataSource(file)
                                        dataSource = ds
                                        val player = MediaPlayer().apply {
                                            setDisplay(holder)
                                            setDataSource(ds)
                                            setOnPreparedListener { mp ->
                                                durationMs = mp.duration.coerceAtLeast(1)
                                                mp.start()
                                                isPlaying = true
                                            }
                                            setOnCompletionListener {
                                                isPlaying = false
                                            }
                                            prepareAsync()
                                        }
                                        mediaPlayer = player
                                    }.onFailure {
                                        errorMsg = "Gagal memutar video terenkripsi"
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    runCatching { mediaPlayer?.release() }
                                    mediaPlayer = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Surface(color = Color.Black.copy(alpha = 0.75f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val mp = mediaPlayer ?: return@IconButton
                    if (mp.isPlaying) {
                        mp.pause()
                        isPlaying = false
                    } else {
                        mp.start()
                        isPlaying = true
                    }
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Jeda" else "Putar",
                        tint = Color.White
                    )
                }
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { frac ->
                        val target = (frac * durationMs).toInt()
                        positionMs = target
                        runCatching { mediaPlayer?.seekTo(target) }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun EncryptedAudioPlayer(
    file: VaultFileEntity,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource
) {
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(file.id) {
        val ds = openMediaDataSource(file)
        val player = MediaPlayer().apply {
            setDataSource(ds)
            setOnPreparedListener { mp ->
                durationMs = mp.duration.coerceAtLeast(1)
            }
            setOnCompletionListener { isPlaying = false }
            prepareAsync()
        }
        mediaPlayer = player

        onDispose {
            runCatching { player.release() }
            runCatching { ds.close() }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            runCatching { positionMs = mediaPlayer?.currentPosition ?: 0 }
            delay(300L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp)
            )
        }
        Text(file.originalName, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { frac ->
                val target = (frac * durationMs).toInt()
                positionMs = target
                runCatching { mediaPlayer?.seekTo(target) }
            }
        )
        Text(
            text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
        Button(
            onClick = {
                val mp = mediaPlayer ?: return@Button
                if (mp.isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
                    isPlaying = true
                }
            }
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPlaying) "Jeda" else "Putar Audio")
        }
    }
}

@Composable
private fun EncryptedPdfViewer(
    file: VaultFileEntity,
    decryptToEphemeralFile: suspend (VaultFileEntity) -> File,
    secureDeleteEphemeralFile: (File) -> Unit
) {
    var pages by remember(file.id) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }
    var tempFile by remember(file.id) { mutableStateOf<File?>(null) }

    DisposableEffect(file.id) {
        onDispose {
            tempFile?.let { secureDeleteEphemeralFile(it) }
            pages.forEach { it.recycle() }
        }
    }

    LaunchedEffect(file.id) {
        runCatching {
            val decrypted = decryptToEphemeralFile(file)
            tempFile = decrypted
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(decrypted, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val rendered = mutableListOf<Bitmap>()
                        val maxPages = minOf(renderer.pageCount, 30)
                        for (i in 0 until maxPages) {
                            renderer.openPage(i).use { page ->
                                val bmp = Bitmap.createBitmap(
                                    (page.width * 2).coerceAtMost(1800),
                                    (page.height * 2).coerceAtMost(2600),
                                    Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                rendered.add(bmp)
                            }
                        }
                        rendered
                    }
                }
            }
        }.onSuccess {
            pages = it
        }.onFailure {
            errorMsg = "Gagal merender PDF: ${it.localizedMessage}"
        }
    }

    when {
        errorMsg != null -> Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        pages.isEmpty() -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pages.size) { index ->
                    Image(
                        bitmap = pages[index].asImageBitmap(),
                        contentDescription = "Halaman ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EncryptedTextViewer(
    file: VaultFileEntity,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray
) {
    var content by remember(file.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.id) {
        runCatching {
            val bytes = decryptBytes(file)
            val text = bytes.decodeToString()
            bytes.fill(0)
            text
        }.onSuccess { content = it }
    }

    val text = content
    if (text == null) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    } else {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF11161F)
        ) {
            Text(
                text = text,
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE2E8F0),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun EncryptedGenericFileCard(
    file: VaultFileEntity,
    onExportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = file.originalName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "File terenkripsi AES-256-GCM (${formatFileSize(file.originalSizeBytes)}). Dekripsi & simpan untuk membukanya dengan aplikasi eksternal.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f)
        )
        Button(onClick = onExportClick) {
            Icon(Icons.Rounded.LockOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Dekripsi & Simpan")
        }
    }
}

private fun isTextReadable(file: VaultFileEntity): Boolean {
    val ext = file.originalName.substringAfterLast('.', "").lowercase()
    return file.mimeType.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "xml", "log")
}

private fun formatMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
