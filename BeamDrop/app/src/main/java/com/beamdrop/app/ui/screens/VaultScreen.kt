package com.beamdrop.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.beamdrop.app.AppLock
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.util.Files
import com.beamdrop.app.util.VaultItem

/** Hidden vault: private files behind their own PIN (or fingerprint). */
@Composable
fun VaultScreen(viewModel: BeamDropViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    // Nothing in the vault shows in screenshots or the recent-apps preview.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        DetailHeader(title = tr("Vault"), onBack = onBack)
        if (!unlocked) PinGate(viewModel) { unlocked = true } else VaultContent(viewModel) { unlocked = false }
    }
}

@Composable
private fun PinGate(viewModel: BeamDropViewModel, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val vault = viewModel.vault
    val creating = !vault.hasPin
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf(true) }
    val canBio = remember { AppLock.canUse(context) }
    val activity = context as? androidx.fragment.app.FragmentActivity
    LaunchedEffect(Unit) {
        if (!creating && vault.useFingerprint && canBio && activity != null) AppLock.prompt(activity, onUnlocked)
    }

    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(SurfaceLow),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Lock, null, tint = Secondary, modifier = Modifier.size(42.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(if (creating) tr("Make a vault PIN") else tr("Enter your vault PIN"), style = MaterialTheme.typography.titleLarge, color = OnSurface)
        Text(
            if (creating) tr("Files in the vault stay inside Sendit: not in your Gallery or Downloads, and other apps can't see them.")
            else tr("Your hidden files are behind this PIN."),
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
        )
        PinField(pin, if (creating) tr("New PIN (4–8 digits)") else tr("PIN")) { pin = it; error = null }
        if (creating) {
            Spacer(Modifier.height(10.dp))
            PinField(again, tr("Same PIN again")) { again = it; error = null }
            if (canBio) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
                        .combinedClickableCompat { fingerprint = !fingerprint }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(checked = fingerprint, onCheckedChange = { fingerprint = it })
                    Text(tr("Also open with fingerprint"), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                }
            }
        }
        error?.let { Text(tr(it), color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        BeamButton(
            text = if (creating) tr("Create vault") else tr("Open"),
            onClick = {
                when {
                    pin.length !in 4..8 -> error = "The PIN must be 4 to 8 digits."
                    creating && pin != again -> error = "The two PINs are different."
                    creating -> { vault.setPin(pin); vault.useFingerprint = fingerprint && canBio; onUnlocked() }
                    vault.checkPin(pin) -> onUnlocked()
                    else -> { error = "Wrong PIN."; pin = "" }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (!creating && vault.useFingerprint && canBio && activity != null) {
            Spacer(Modifier.height(8.dp))
            BeamButton(text = tr("Use fingerprint"), onClick = { AppLock.prompt(activity, onUnlocked) }, icon = Icons.Rounded.Fingerprint,
                style = BeamButtonStyle.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
            focusedBorderColor = Secondary, unfocusedBorderColor = OnSurfaceVariant.copy(alpha = 0.4f),
            focusedLabelColor = Secondary, unfocusedLabelColor = OnSurfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultContent(viewModel: BeamDropViewModel, onLock: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.vaultItems.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<VaultItem?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshVault() }
    val add = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            com.beamdrop.app.util.Files.query(context, uri)?.let { f ->
                viewModel.moveToVaultCopyOnly(uri, f.name, f.mimeType)
            }
        }
    }

    menu?.let { item ->
        AlertDialog(
            onDismissRequest = { menu = null },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(tr("Move it back to your phone's Gallery/Downloads, or delete it for good."), color = OnSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.exportFromVault(item) { ok ->
                        android.widget.Toast.makeText(context, if (ok) tr("Moved out of the vault") else tr("Couldn't move it"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    menu = null
                }) { Text(tr("Move out"), color = Secondary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.deleteFromVault(item); menu = null }) { Text(tr("Delete"), color = Error) }
            }
        )
    }

    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BeamButton(text = tr("Add files"), onClick = { add.launch(arrayOf("*/*")) }, icon = Icons.Rounded.Add,
            style = BeamButtonStyle.Tonal, modifier = Modifier.weight(1f))
        BeamButton(text = tr("Lock"), onClick = onLock, icon = Icons.Rounded.Lock, style = BeamButtonStyle.Ghost, modifier = Modifier.weight(1f))
    }
    if (items.isEmpty()) {
        Text(
            tr("The vault is empty.\n\nAfter receiving files, tap \"Hide in vault\". Or in My files, hold a file and tap Vault."),
            style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp)
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceLow)
                    .combinedClickable(
                        onClick = { com.beamdrop.app.util.openFile(context, viewModel.vault.uriFor(item).toString(), item.mimeType) },
                        onLongClick = { menu = item }
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = viewModel.vault.file(item), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else FileKindBadge(name = item.name, mimeType = item.mimeType, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Files.formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
                TextButton(onClick = { menu = item }) { Text("⋯", color = OnSurfaceVariant) }
            }
        }
        item {
            Text(tr("Tap to open · hold for more"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}
