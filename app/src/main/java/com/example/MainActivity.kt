package com.example

import android.os.Build
import android.os.Environment
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.BrankasViewModel
import com.example.ui.VaultEvent
import com.example.ui.screens.AlbumDetailScreen
import com.example.ui.screens.SecureViewerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultHomeScreen
import com.example.ui.screens.VaultLockScreen
import com.example.ui.theme.BrankasKuTheme
import kotlinx.serialization.Serializable

@Serializable
object RouteHome

@Serializable
data class RouteAlbumDetail(val albumId: String)

@Serializable
data class RouteSecureViewer(val fileId: String)

@Serializable
object RouteSettings

class MainActivity : ComponentActivity() {

    private val viewModel: BrankasViewModel by viewModels { BrankasViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val lifecycleOwner = LocalLifecycleOwner.current
            val pendingDeleteUris = remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
            val firstLaunchPermissionInfo = remember { mutableStateOf(!getSharedPreferences("brankasku_first_run", MODE_PRIVATE).getBoolean("permission_info_seen", false)) }

            // Enforce FLAG_SECURE when locked or when screenshot blocking is enabled
            val shouldBlockScreenshots = uiState.isLocked || uiState.securityPrefs.blockScreenshots
            LaunchedEffect(shouldBlockScreenshots) {
                if (shouldBlockScreenshots) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Auto-lock when moving to background (unless launching a system picker)
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                        Lifecycle.Event.ON_RESUME -> viewModel.clearExternalPickerFlag()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val mediaPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (results.values.any { it }) {
                    viewModel.emitMessage("Izin foto/video diberikan.")
                } else {
                    viewModel.emitMessage("Izin foto/video belum diberikan. Anda tetap dapat mengimpor menggunakan pemilih media Android.")
                }
                requestAllFilesAccess()
            }

            fun mediaPermissionsForCurrentAndroid(): Array<String> = when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= 33 -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                else -> emptyArray()
            }

            val allFilesAccessLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                    viewModel.emitMessage("Akses semua file diberikan.")
                } else {
                    viewModel.emitMessage("Akses semua file belum diberikan. Fitur folder eksternal mungkin terbatas.")
                }
            }

            fun requestAllFilesAccess() {
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    allFilesAccessLauncher.launch(intent)
                }
            }

            val mediaDeleteLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                viewModel.clearExternalPickerFlag()
                val targets = pendingDeleteUris.value
                pendingDeleteUris.value = emptyList()
                if (result.resultCode == RESULT_OK) {
                    // Consent is not proof of deletion. Verify each URI by querying MediaStore.
                    val verifiedDeleted = targets.count { uri ->
                        runCatching {
                            contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { cursor ->
                                !cursor.moveToFirst()
                            } ?: false
                        }.getOrDefault(false)
                    }
                    val remainingOrUnknown = targets.size - verifiedDeleted
                    val message = when {
                        targets.isEmpty() -> "Permintaan penghapusan selesai, tetapi URI sumber tidak tersedia untuk verifikasi."
                        remainingOrUnknown == 0 -> "Terverifikasi: $verifiedDeleted file asli sudah tidak ada di Galeri. File terenkripsi tetap tersimpan di brankas."
                        verifiedDeleted == 0 -> "Persetujuan diterima, tetapi penghapusan $remainingOrUnknown file asli belum dapat diverifikasi. Periksa Galeri. File brankas tetap aman."
                        else -> "Terverifikasi $verifiedDeleted file asli terhapus; $remainingOrUnknown file masih ada atau belum dapat diverifikasi. Periksa Galeri."
                    }
                    viewModel.emitMessage(message)
                } else {
                    viewModel.emitMessage("Penghapusan file asli dibatalkan. File terenkripsi sudah aman di brankas, tetapi salinan asli masih berada di Galeri.")
                }
            }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is VaultEvent.ShowMessage -> {
                            snackbarHostState.showSnackbar(event.message)
                        }
                        is VaultEvent.RequestMediaStoreDelete -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && event.uris.isNotEmpty()) {
                                runCatching {
                                    require(event.uris.all { it.authority == MediaStore.AUTHORITY }) { "URI sumber bukan URI MediaStore yang valid untuk permintaan penghapusan" }
                                    pendingDeleteUris.value = event.uris
                                    viewModel.markLaunchingExternalPicker()
                                    val pendingIntent = MediaStore.createDeleteRequest(
                                        contentResolver,
                                        event.uris
                                    )
                                    mediaDeleteLauncher.launch(
                                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    )
                                }.onFailure {
                                    pendingDeleteUris.value = emptyList()
                                    viewModel.clearExternalPickerFlag()
                                    viewModel.emitMessage("Gagal meminta izin penghapusan file asli. File tetap tersimpan di brankas dan sumber masih ada.")
                                }
                            }
                        }
                    }
                }
            }

            BrankasKuTheme(themePreference = uiState.securityPrefs.themePreference) {
                Scaffold(
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { outerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(outerPadding)
                    ) {
                        if (!uiState.isLocked && firstLaunchPermissionInfo.value) {
                            AlertDialog(
                                onDismissRequest = { firstLaunchPermissionInfo.value = false; getSharedPreferences("brankasku_first_run", MODE_PRIVATE).edit().putBoolean("permission_info_seen", true).apply() },
                                title = { Text("Akses Folder & Penghapusan File") },
                                text = { Text("BrankasKu memerlukan izin Foto dan Video agar dapat menelusuri media yang ingin Anda amankan. Android akan menampilkan dialog izin. Pada Android yang mendukungnya, Anda dapat memilih akses penuh atau hanya media terpilih. File asli hanya akan diminta untuk dihapus setelah file terenkripsi tersimpan dan terverifikasi.") },
                                confirmButton = { Button(onClick = { firstLaunchPermissionInfo.value = false; getSharedPreferences("brankasku_first_run", MODE_PRIVATE).edit().putBoolean("permission_info_seen", true).apply(); val permissions = mediaPermissionsForCurrentAndroid(); val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }; if (missing.isNotEmpty()) mediaPermissionLauncher.launch(missing.toTypedArray()) else requestAllFilesAccess() }) { Text("Izinkan Foto/Video & Storage") } }
                            )
                        }
                        Crossfade(
                            targetState = uiState.isLocked,
                            label = "vault_lock_transition"
                        ) { locked ->
                            if (locked) {
                                VaultLockScreen(
                                    uiState = uiState,
                                    onDigitEntered = viewModel::onPinDigitEntered,
                                    onBackspace = viewModel::onPinBackspace,
                                    onBiometricSuccess = viewModel::onBiometricUnlockSuccess,
                                    onCompleteInitialSetup = viewModel::completeInitialSetup,
                                    onBiometricError = { msg -> viewModel.emitMessage(msg) }
                                )
                            } else {
                                VaultNavGraph(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultNavGraph(viewModel: BrankasViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = RouteHome
    ) {
        composable<RouteHome> {
            VaultHomeScreen(
                uiState = uiState,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCategorySelected = viewModel::selectCategoryFilter,
                onAlbumClick = { albumId ->
                    navController.navigate(RouteAlbumDetail(albumId))
                },
                onFileClick = { fileId ->
                    navController.navigate(RouteSecureViewer(fileId))
                },
                onCreateAlbum = viewModel::createAlbum,
                onUpdateAlbum = viewModel::updateAlbum,
                onDeleteAlbum = viewModel::deleteAlbum,
                onImportUris = viewModel::importUris,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                onLockNow = viewModel::lockVault,
                onOpenSettings = { navController.navigate(RouteSettings) },
                loadThumbnail = viewModel::loadThumbnail
            )
        }

        composable<RouteAlbumDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteAlbumDetail>()
            val album by viewModel.observeAlbum(route.albumId).collectAsStateWithLifecycle(initialValue = null)
            val files by viewModel.observeFilesInAlbum(route.albumId).collectAsStateWithLifecycle(initialValue = emptyList())

            AlbumDetailScreen(
                album = album,
                files = files,
                allAlbums = uiState.albums,
                importProgress = uiState.importProgress,
                onBack = { navController.popBackStack() },
                onFileClick = { fileId ->
                    navController.navigate(RouteSecureViewer(fileId))
                },
                onImportUris = { uris -> viewModel.importUris(uris, route.albumId) },
                onUpdateAlbum = viewModel::updateAlbum,
                onMoveFiles = viewModel::moveFilesToAlbum,
                onDeleteFiles = viewModel::deleteFiles,
                onExportFile = viewModel::exportFileToUri,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                loadThumbnail = viewModel::loadThumbnail
            )
        }

        composable<RouteSecureViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteSecureViewer>()
            val file by viewModel.observeFileById(route.fileId).collectAsStateWithLifecycle(initialValue = null)

            SecureViewerScreen(
                file = file,
                allAlbums = uiState.albums,
                vaultInternalPath = uiState.vaultInternalPath,
                decryptBytes = viewModel::decryptBytes,
                openMediaDataSource = viewModel::openEncryptedMediaDataSource,
                decryptToEphemeralFile = viewModel::decryptToEphemeralCacheFile,
                secureDeleteEphemeralFile = viewModel::secureDeleteEphemeralFile,
                onBack = { navController.popBackStack() },
                onToggleFavorite = viewModel::toggleFavorite,
                onMoveFile = { fileId, targetAlbumId ->
                    viewModel.moveFilesToAlbum(listOf(fileId), targetAlbumId)
                },
                onDeleteFile = { fileId ->
                    viewModel.deleteFiles(listOf(fileId))
                },
                onExportFile = viewModel::exportFileToUri,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag
            )
        }

        composable<RouteSettings> {
            SettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onBiometricToggle = viewModel::setBiometricEnabled,
                onAutoLockToggle = viewModel::setAutoLockOnBackground,
                onBlockScreenshotsToggle = viewModel::setBlockScreenshots,
                onDeleteOriginalToggle = viewModel::setDeleteOriginalOnImport,
                onExternalVaultFolderSelected = viewModel::setExternalVaultFolder,
                onThemeChange = viewModel::setThemePreference,
                onChangePin = viewModel::changePin
            )
        }
    }
}
