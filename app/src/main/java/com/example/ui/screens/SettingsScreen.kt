package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.biometric.BiometricAuthHelper
import com.example.biometric.BiometricAvailability
import com.example.ui.VaultUiState
import com.example.ui.components.AesBadgePill
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.VaultThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onAutoLockToggle: (Boolean) -> Unit,
    onBlockScreenshotsToggle: (Boolean) -> Unit,
    onDeleteOriginalToggle: (Boolean) -> Unit,
    onExternalVaultFolderSelected: (String?) -> Unit,
    onThemeChange: (VaultThemePreference) -> Unit,
    onChangePin: (currentPin: String, newPin: String, onResult: (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var showChangePinDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            onExternalVaultFolderSelected(treeUri.toString())
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = { Text("Pengaturan Keamanan") }
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
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = "Autentikasi & Kunci",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column {
                            val bioAvailable = uiState.biometricAvailability == BiometricAvailability.AVAILABLE
                            SettingsSwitchRow(
                                icon = Icons.Rounded.Fingerprint,
                                title = "Kunci Sidik Jari",
                                subtitle = when (uiState.biometricAvailability) {
                                    BiometricAvailability.AVAILABLE -> "Buka brankas cepat dengan sidik jari perangkat"
                                    BiometricAvailability.NOT_ENROLLED -> "Belum ada sidik jari terdaftar di Pengaturan Perangkat"
                                    BiometricAvailability.UNAVAILABLE -> "Sensor biometrik tidak tersedia di perangkat ini"
                                },
                                checked = uiState.securityPrefs.biometricEnabled,
                                enabled = bioAvailable,
                                testTag = "switch_biometric",
                                onCheckedChange = { wantEnable ->
                                    if (wantEnable && activity != null) {
                                        BiometricAuthHelper.showBiometricPrompt(
                                            activity = activity,
                                            title = "Konfirmasi Sidik Jari",
                                            subtitle = "Verifikasi untuk mengaktifkan pembukaan biometrik",
                                            negativeButtonText = "Batal",
                                            onSuccess = { onBiometricToggle(true) }
                                        )
                                    } else {
                                        onBiometricToggle(false)
                                    }
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showChangePinDialog = true }
                                    .padding(16.dp)
                                    .testTag("change_pin_row"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Ubah PIN Brankas", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Ganti 6 digit PIN utama (PBKDF2-HMAC-SHA256)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Privasi & Penyimpanan",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column {
                            SettingsSwitchRow(
                                icon = Icons.Rounded.LockClock,
                                title = "Kunci Otomatis saat Keluar",
                                subtitle = "Kunci brankas seketika saat aplikasi berpindah ke latar belakang",
                                checked = uiState.securityPrefs.autoLockOnBackground,
                                testTag = "switch_autolock",
                                onCheckedChange = onAutoLockToggle
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SettingsSwitchRow(
                                icon = Icons.Rounded.ScreenshotMonitor,
                                title = "Blokir Tangkapan Layar",
                                subtitle = "Aktifkan FLAG_SECURE agar layar tidak dapat direkam atau di-screenshot",
                                checked = uiState.securityPrefs.blockScreenshots,
                                testTag = "switch_flag_secure",
                                onCheckedChange = onBlockScreenshotsToggle
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SettingsSwitchRow(
                                icon = Icons.Rounded.DeleteSweep,
                                title = "Pindahkan File Asli (Hapus Sumber)",
                                subtitle = "Setelah terenkripsi di brankas, hapus file sumber agar hilang dari galeri & file manager",
                                checked = uiState.securityPrefs.deleteOriginalOnImport,
                                testTag = "switch_delete_original",
                                onCheckedChange = onDeleteOriginalToggle
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Folder Brankas yang Bisa Dibuka File Manager", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Pilih folder tujuan. Aplikasi membuat folder BrankasKu berisi salinan file ciphertext .vault; foto/video asli tidak disimpan di folder ini dan tidak bisa ditampilkan Galeri.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = uiState.securityPrefs.externalVaultFolderUri?.let { "Folder eksternal tersambung" } ?: "Belum ada folder eksternal dipilih",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (uiState.securityPrefs.externalVaultFolderUri == null) "Buat / Pilih Folder Brankas" else "Ganti Folder Brankas")
                            }
                            if (uiState.securityPrefs.externalVaultFolderUri != null) {
                                TextButton(onClick = { onExternalVaultFolderSelected(null) }) { Text("Putuskan Folder Eksternal") }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Tampilan",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Tema Aplikasi", style = MaterialTheme.typography.titleSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = uiState.securityPrefs.themePreference == VaultThemePreference.DARK,
                                    onClick = { onThemeChange(VaultThemePreference.DARK) },
                                    label = { Text("Obsidian Gelap") }
                                )
                                FilterChip(
                                    selected = uiState.securityPrefs.themePreference == VaultThemePreference.LIGHT,
                                    onClick = { onThemeChange(VaultThemePreference.LIGHT) },
                                    label = { Text("Terang") }
                                )
                                FilterChip(
                                    selected = uiState.securityPrefs.themePreference == VaultThemePreference.SYSTEM,
                                    onClick = { onThemeChange(VaultThemePreference.SYSTEM) },
                                    label = { Text("Sistem") }
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Audit Kriptografi",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Status Keamanan Lokal",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                AesBadgePill()
                            }
                            AuditRow("Algoritma File", "AES-256-GCM (Chunked 64 KiB + AAD)")
                            AuditRow(
                                "Master Key",
                                if (uiState.isHardwareBackedKeystore) {
                                    "Android Keystore (Hardware-Backed TEE/StrongBox)"
                                } else {
                                    "Android Keystore AES-256"
                                }
                            )
                            AuditRow("Verifikasi PIN", "PBKDF2WithHmacSHA256 (100.000 iterasi)")
                            AuditRow("Lokasi Internal Tersembunyi", uiState.vaultInternalPath)
                        }
                    }
                }
            }
        }
    }

    if (showChangePinDialog) {
        ChangePinDialog(
            onDismiss = { showChangePinDialog = false },
            onSubmit = { currentPin, newPin, onError ->
                onChangePin(currentPin, newPin) { ok ->
                    if (ok) {
                        showChangePinDialog = false
                    } else {
                        onError("PIN saat ini salah")
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onSubmit: (currentPin: String, newPin: String, onError: (String) -> Unit) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah PIN 6 Digit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) currentPin = it },
                    label = { Text("PIN Saat Ini") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it },
                    label = { Text("PIN Baru (6 digit)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPin = it },
                    label = { Text("Konfirmasi PIN Baru") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        currentPin.length != 6 || newPin.length != 6 ->
                            errorMsg = "PIN harus tepat 6 digit"
                        newPin != confirmPin ->
                            errorMsg = "Konfirmasi PIN baru tidak cocok"
                        else -> onSubmit(currentPin, newPin) { errorMsg = it }
                    }
                }
            ) {
                Text("Simpan PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}
