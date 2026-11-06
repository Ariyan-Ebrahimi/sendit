package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.DeviceAvatar
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import kotlinx.coroutines.delay

@Composable
fun ProfileScreen(
    viewModel: BeamDropViewModel,
    onOpenAuth: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onOpenPlans: () -> Unit = {}
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val appLock by viewModel.appLock.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val keepAwake by viewModel.keepAwake.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val userName by viewModel.userDisplayName.collectAsStateWithLifecycle()
    val sendSmaller by viewModel.sendSmaller.collectAsStateWithLifecycle()
    val saveToGallery by viewModel.saveToGallery.collectAsStateWithLifecycle()
    val encryptDefault by viewModel.encryptByDefaultFlow.collectAsStateWithLifecycle()
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    LaunchedEffect(isLoggedIn) { viewModel.refreshPlans() }
    val language by viewModel.language.collectAsStateWithLifecycle()

    var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
    var nameSaved by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "logout" | "clear"

    LaunchedEffect(nameSaved) { if (nameSaved) { delay(1600); nameSaved = false } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { DetailHeader(title = tr("Settings"), onBack = null) }

        // Account
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceAvatar(name = userName ?: deviceName, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isLoggedIn) userName ?: tr("Signed in") else tr("Not signed in"),
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        userEmail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                        }
                    }
                    if (isLoggedIn) {
                        SmallAction(tr("Sign out"), Error) { confirm = "logout" }
                    } else {
                        SmallAction(tr("Sign in"), Secondary, onOpenAuth)
                    }
                }
            }
        }

        // Name shown to nearby phones
        item {
            Card {
                Label(tr("Your phone's name"))
                Text(
                    text = tr("Nearby phones see this name when you send over Wi-Fi."),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BeamTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(60) },
                        placeholder = tr("e.g. Alex's phone"),
                        modifier = Modifier.weight(1f)
                    )
                    val canSave = nameDraft.isNotBlank() && nameDraft.trim() != deviceName
                    if (canSave || nameSaved) {
                        Spacer(Modifier.width(8.dp))
                        SmallAction(if (nameSaved) tr("Saved") else tr("Save"), if (nameSaved) Tertiary else Secondary) {
                            if (canSave) {
                                viewModel.updateDeviceName(nameDraft)
                                nameSaved = true
                            }
                        }
                    }
                }
            }
        }

        // Options
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Keep screen on"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                        Text(tr("While files are sending or receiving"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Switch(
                        checked = keepAwake,
                        onCheckedChange = viewModel::setKeepAwake,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = PrimaryContainer,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainer,
                            uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Send smaller photos & videos"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                        Text(tr("Saves mobile data with Anywhere. Quality is a bit lower."), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = sendSmaller,
                        onCheckedChange = viewModel::setSendSmaller,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = PrimaryContainer,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainer,
                            uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("🔐 Encrypt files I send to people"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                        Text(tr("Only their phone can open them, not even the server"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = encryptDefault,
                        onCheckedChange = viewModel::setEncryptByDefault,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = PrimaryContainer,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainer,
                            uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Save photos & videos to Gallery"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                        Text(tr("Off: everything goes to Downloads/Sendit"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = saveToGallery,
                        onCheckedChange = viewModel::setSaveToGallery,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = PrimaryContainer,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainer,
                            uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { confirm = "clear" }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Clear history"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                        Text(tr("Your received files are kept"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                }
            }
        }

        // Support
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenSupport),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("Contact support"))
                        Text(tr("Questions or problems? Write to the Sendit team"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = OnSurfaceVariant)
                }
            }
        }

        // Plan
        if (isLoggedIn) item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenPlans),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("★ Sendit Plus"))
                        Text(
                            plans?.let { p -> if (p.current.name == "Free") tr("More space, bigger files, 30-day links") else tr("Your plan: %s", p.current.name) }
                                ?: tr("More space, bigger files, 30-day links"),
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = OnSurfaceVariant)
                }
            }
        }

        // Vault
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenVault),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("🔒 Hidden vault"))
                        Text(tr("Private files behind their own PIN, hidden from the Gallery"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = OnSurfaceVariant)
                }
            }
        }

        // Stats
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenStats),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("Your stats"))
                        Text(tr("How much you sent and received, and with whom"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = OnSurfaceVariant)
                }
            }
        }

        // Contacts
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenContacts),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("Contacts"))
                        Text(tr("People you send to often, and who can send to you automatically"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = OnSurfaceVariant)
                }
            }
        }

        // Appearance
        item {
            Card {
                Label(tr("Appearance"))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Phone's setting", "dark" to "Dark", "light" to "Light").forEach { (value, label) ->
                        val selected = theme == value
                        Text(
                            text = tr(label),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) androidx.compose.ui.graphics.Color.White else OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) PrimaryContainer else SurfaceContainer)
                                .clickable { viewModel.setTheme(value) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("Colour"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.beamdrop.app.ui.theme.Accent.entries.forEach { a ->
                        val selected = accent == a.key
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(a.swatch)
                                .then(if (selected) Modifier.border(3.dp, OnSurface, CircleShape) else Modifier)
                                .clickable { viewModel.setAccent(a.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Rounded.Check, tr(a.label), tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // App lock
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(tr("App lock"))
                        Text(tr("Ask for your fingerprint, face or screen lock when opening Sendit"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = appLock,
                        onCheckedChange = { on ->
                            if (on && !com.beamdrop.app.AppLock.canUse(context)) {
                                android.widget.Toast.makeText(context, tr("Set a screen lock on your phone first."), android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                viewModel.setAppLock(on)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = PrimaryContainer,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainer,
                            uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // Language
        item {
            Card {
                Label(tr("Language"))
                Spacer(Modifier.height(10.dp))
                listOf("system" to "Phone's language", "en" to "English", "fa" to "فارسی", "tr" to "Türkçe").chunked(2).forEachIndexed { row, pair ->
                if (row > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (value, label) ->
                        val selected = language == value
                        Text(
                            text = if (value == "system") com.beamdrop.app.i18n.tr(label) else label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) androidx.compose.ui.graphics.Color.White else OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) PrimaryContainer else SurfaceContainer)
                                .clickable { viewModel.setLanguage(value) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                }
            }
        }

        // Delete account
        item {
            Text(
                text = tr("Delete my account"),
                style = MaterialTheme.typography.labelLarge,
                color = Error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable { confirm = "delete" }
                    .padding(14.dp)
            )
        }

        item {
            Text(
                text = "Sendit " + com.beamdrop.app.BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirm == "delete") {
        DeleteAccountDialog(viewModel = viewModel, onDismiss = { confirm = null })
    } else confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(if (what == "logout") tr("Sign out?") else tr("Clear history?")) },
            text = {
                Text(
                    if (what == "logout") tr("You'll need your email and password to sign in again.")
                    else tr("This removes the list of past transfers. Files you received stay on your phone.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "logout") viewModel.logout() else viewModel.clearHistory()
                    confirm = null
                }) {
                    Text(if (what == "logout") tr("Sign out") else tr("Clear"), color = Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(tr("Cancel"), color = OnSurfaceVariant) }
            }
        )
    }
}

@Composable
private fun DeleteAccountDialog(viewModel: BeamDropViewModel, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SurfaceContainer,
        titleContentColor = OnSurface,
        textContentColor = OnSurfaceVariant,
        title = { Text(tr("Delete your account?")) },
        text = {
            Column {
                Text(tr("Your account and every file you shared will be deleted from the server. This can't be undone. Files already on your phone stay."))
                Spacer(Modifier.height(14.dp))
                BeamTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    placeholder = tr("Your password"),
                    keyboardType = KeyboardType.Password,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    busy = true
                    viewModel.deleteAccount(password) { err ->
                        busy = false
                        if (err == null) onDismiss() else error = tr(err)
                    }
                }
            ) { Text(if (busy) tr("Deleting…") else tr("Delete forever"), color = Error, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = OnSurface)
}

@Composable
private fun SmallAction(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
