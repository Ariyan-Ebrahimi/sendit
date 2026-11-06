package com.beamdrop.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.InfoBanner
import com.beamdrop.app.ui.components.Pill
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceHigh
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files
import com.beamdrop.app.i18n.tr

@Composable
fun ReceivePreviewScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onScanAgain: () -> Unit,
    onDownload: (Set<Int>) -> Unit
) {
    val offer by viewModel.receiveOffer.collectAsStateWithLifecycle()
    val error by viewModel.receiveError.collectAsStateWithLifecycle()
    val fetching by viewModel.isFetchingOffer.collectAsStateWithLifecycle()
    val needsPassword by viewModel.receiveNeedsPassword.collectAsStateWithLifecycle()
    val needsLocation by viewModel.receiveNeedsLocation.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailHeader(
            title = tr("Receive"),
            onBack = onBack
        )

        when {
            fetching -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(SurfaceLow)
                            .border(1.dp, Secondary.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Secondary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = tr("Connecting to sender"),
                        style = MaterialTheme.typography.titleLarge,
                        color = OnSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tr("Fetching the file list..."),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }

            needsLocation != null && offer == null -> {
                LocationPrompt(message = needsLocation ?: "", viewModel = viewModel, onBack = onScanAgain)
            }

            needsPassword && offer == null -> {
                PasswordPrompt(
                    error = error,
                    onSubmit = viewModel::submitReceivePassword,
                    onBack = onScanAgain
                )
            }

            offer == null -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Rounded.QrCodeScanner,
                        title = if (error != null) tr("Could not open this link") else tr("No incoming files"),
                        message = error?.let { tr(it) } ?: tr("Scan the sender's Sendit QR code to see their files here."),
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        if (error != null) {
                            BeamButton(
                                text = tr("Try again"),
                                onClick = viewModel::retryOpenReceive,
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Rounded.Refresh,
                                style = BeamButtonStyle.Primary
                            )
                        }
                        BeamButton(
                            text = tr("Go back"),
                            onClick = onScanAgain,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.QrCodeScanner,
                            style = BeamButtonStyle.Tonal
                        )
                    }
                }
            }

            else -> {
                val current = offer ?: return@Column
                val manifest = current.manifest
                var selected by androidx.compose.runtime.remember(manifest) {
                    androidx.compose.runtime.mutableStateOf(manifest.files.map { it.id }.toSet())
                }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(SurfaceLow)
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Secondary.copy(alpha = 0.14f))
                                    .border(1.dp, Secondary.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = Secondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("From %s", manifest.sender),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = (if (manifest.files.size == 1) tr("1 file") else tr("%d files", manifest.files.size)) + " · ${Files.formatBytes(manifest.totalBytes)}" +
                                        when {
                                            manifest.destructSeconds > 0 -> " · " + tr("💣 deletes itself after opening")
                                            manifest.viewOnce -> " · " + tr("👁 view once")
                                            manifest.oneTime -> " · " + tr("one-time download")
                                            else -> ""
                                        } + if (current.key != null) " · " + tr("🔐 encrypted") else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant
                                )
                            }
                        }
                    }

                    if (manifest.message.isNotBlank()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Secondary.copy(alpha = 0.10f))
                                    .padding(14.dp)
                            ) {
                                Text(manifest.message, style = MaterialTheme.typography.bodyLarge, color = OnSurface)
                                Text(
                                    text = if (copied) tr("Copied") else tr("Copy"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Secondary,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(manifest.message))
                                            copied = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (manifest.files.size > 1) {
                        item {
                            val all = selected.size == manifest.files.size
                            Text(
                                text = if (all) tr("Choose files") else tr("Select all"),
                                style = MaterialTheme.typography.labelLarge,
                                color = Secondary,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { selected = if (all) emptySet() else manifest.files.map { it.id }.toSet() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    items(manifest.files.sortedBy { it.id }, key = { it.id }) { file ->
                        val isOn = file.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(SurfaceLow)
                                .then(
                                    if (manifest.files.size > 1) Modifier.clickable {
                                        selected = if (isOn) selected - file.id else selected + file.id
                                    } else Modifier
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileKindBadge(
                                name = file.name,
                                mimeType = file.mimeType,
                                size = 44.dp
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${Files.formatBytes(file.size)} · ${Files.shortTypeLabel(file.name, file.mimeType)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant
                                )
                            }
                            if (manifest.files.size > 1) {
                                androidx.compose.material3.Checkbox(
                                    checked = isOn,
                                    onCheckedChange = { on -> selected = if (on) selected + file.id else selected - file.id },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = com.beamdrop.app.ui.theme.PrimaryContainer,
                                        checkmarkColor = androidx.compose.ui.graphics.Color.White,
                                        uncheckedColor = OnSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BeamButton(
                        text = if (selected.size == manifest.files.size) tr("Download")
                            else tr("Download %d of %d", selected.size, manifest.files.size),
                        onClick = { if (selected.isNotEmpty()) onDownload(selected) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Download,
                        style = BeamButtonStyle.Receive,
                        height = 54.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    var reporting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    if (reporting) ReportDialog(viewModel, manifest.code) { reporting = false }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tr("Saves to your phone"),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (manifest.cloud && manifest.code.isNotBlank()) {
                            Text(
                                text = tr("🚩 Report"),
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { reporting = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .padding(horizontal = 10.dp, vertical = 11.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.8.sp,
            fontSize = 9.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PasswordPrompt(error: String?, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var password by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(SurfaceLow),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Lock, null, tint = Secondary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(tr("This share has a password"), style = MaterialTheme.typography.headlineSmall, color = OnSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(tr("Ask the sender for it."), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        com.beamdrop.app.ui.components.BeamTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = tr("Password"),
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            onImeAction = { if (password.isNotEmpty()) onSubmit(password) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(tr(error), style = MaterialTheme.typography.bodySmall, color = com.beamdrop.app.ui.theme.Error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(18.dp))
        BeamButton(
            text = tr("Open"),
            onClick = { if (password.isNotEmpty()) onSubmit(password) },
            modifier = Modifier.fillMaxWidth(),
            style = BeamButtonStyle.Primary,
            height = 56.dp
        )
        Spacer(Modifier.height(10.dp))
        BeamButton(text = tr("Go back"), onClick = onBack, modifier = Modifier.fillMaxWidth(), style = BeamButtonStyle.Ghost)
    }
}


/** "These files open only near a place": check where the phone is and try again. */
@Composable
private fun LocationPrompt(message: String, viewModel: BeamDropViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val check = { error = null; viewModel.retryWithLocation { error = it } }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.any { it }) check() else error = "Sendit needs your location for this." }
    Column(Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Rounded.QrCodeScanner,
            title = tr("📍 Opens only at a place"),
            message = tr(message) + "\n\n" + tr("Sendit checks where you are only to open these files. Your location is not saved."),
            modifier = Modifier.weight(1f)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            error?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = com.beamdrop.app.ui.theme.Error) }
            BeamButton(
                text = tr("Check my location"),
                onClick = {
                    val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
                    val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
                    val has = androidx.core.content.ContextCompat.checkSelfPermission(context, fine) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        androidx.core.content.ContextCompat.checkSelfPermission(context, coarse) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (has) check() else ask.launch(arrayOf(fine, coarse))
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Refresh,
                style = BeamButtonStyle.Primary
            )
            BeamButton(text = tr("Go back"), onClick = onBack, modifier = Modifier.fillMaxWidth(), style = BeamButtonStyle.Tonal)
        }
    }
}


/** "Something is wrong with these files": goes to the Sendit admins. */
@Composable
private fun ReportDialog(viewModel: BeamDropViewModel, code: String, onClose: () -> Unit) {
    val reasons = listOf("spam" to "Spam", "illegal" to "Illegal content", "violence" to "Violence", "adult" to "Adult content",
        "copyright" to "Copyright", "virus" to "Virus or harmful file", "other" to "Something else")
    var reason by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("spam") }
    var details by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var state by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) } // null, "sending", "done", error
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = com.beamdrop.app.ui.theme.SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(if (state == "done") tr("Thank you") else tr("Report these files")) },
        text = {
            if (state == "done") {
                Text(tr("The Sendit team will look at it."), color = OnSurfaceVariant)
            } else Column {
                reasons.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { reason = key }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = reason == key, onClick = { reason = key })
                        Text(tr(label), color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = details, onValueChange = { details = it.take(1000) },
                    placeholder = { Text(tr("What is wrong? (optional)"), color = OnSurfaceVariant) },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = OnSurface, unfocusedTextColor = OnSurface)
                )
                state?.takeIf { it != "sending" }?.let { Text(tr(it), color = com.beamdrop.app.ui.theme.Error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (state == "done") androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("Done"), color = com.beamdrop.app.ui.theme.Secondary) }
            else androidx.compose.material3.TextButton(
                enabled = state != "sending",
                onClick = {
                    state = "sending"
                    viewModel.reportShare(code, reason, details.trim()) { err -> state = err ?: "done" }
                }
            ) { Text(if (state == "sending") tr("Sending…") else tr("Send report"), color = com.beamdrop.app.ui.theme.Error) }
        },
        dismissButton = {
            if (state != "done") androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("Cancel"), color = OnSurfaceVariant) }
        }
    )
}
