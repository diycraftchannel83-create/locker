package com.example

import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
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

class MainActivity : FragmentActivity() {

    private val viewModel: BrankasViewModel by viewModels { BrankasViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val lifecycleOwner = LocalLifecycleOwner.current

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

            val mediaDeleteLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                viewModel.clearExternalPickerFlag()
                if (result.resultCode == RESULT_OK) {
                    viewModel.emitMessage("Persetujuan diberikan: file asli dihapus dari Galeri. File terenkripsi tetap tersimpan di brankas internal.")
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
                                    viewModel.markLaunchingExternalPicker()
                                    val pendingIntent = MediaStore.createDeleteRequest(
                                        contentResolver,
                                        event.uris
                                    )
                                    mediaDeleteLauncher.launch(
                                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    )
                                }.onFailure {
                                    viewModel.clearExternalPickerFlag()
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
                onThemeChange = viewModel::setThemePreference,
                onChangePin = viewModel::changePin
            )
        }
    }
}
