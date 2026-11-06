package com.beamdrop.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.api.FileRequest
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow

/** "Ask someone to send you files": button + your active upload links. Lives on the Receive screen. */
@Composable
fun RequestFilesSection(viewModel: BeamDropViewModel) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<FileRequest?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshRequests() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BeamButton(
            text = tr("Ask someone to send you files"),
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth(),
            style = BeamButtonStyle.Ghost,
            icon = Icons.Rounded.Link,
            height = 54.dp
        )
        if (requests.isNotEmpty()) {
            Text(tr("Your upload links"), style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.padding(top = 8.dp))
            requests.forEach { r -> RequestRow(r, onOpen = { created = r }, onDelete = { viewModel.deleteRequest(r.code) }) }
        }
    }

    if (creating) {
        CreateRequestDialog(
            onDismiss = { creating = false },
            onCreate = { title, done ->
                viewModel.createRequest(title) { r, err ->
                    done(err)
                    if (r != null) { creating = false; created = r }
                }
            }
        )
    }
    created?.let { r -> RequestLinkDialog(r) { created = null } }
}

@Composable
private fun RequestRow(r: FileRequest, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Link, null, tint = Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { tr("Upload link") }, style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when (r.uploads) {
                    0 -> tr("No uploads yet")
                    1 -> tr("1 upload")
                    else -> tr("%d uploads", r.uploads)
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant
            )
        }
        Icon(
            Icons.Rounded.Close, contentDescription = tr("Delete"), tint = OnSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(11.dp)
        )
    }
}

@Composable
private fun CreateRequestDialog(onDismiss: () -> Unit, onCreate: (String, (String?) -> Unit) -> Unit) {
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SurfaceContainer,
        titleContentColor = OnSurface,
        textContentColor = OnSurfaceVariant,
        title = { Text(tr("Ask for files")) },
        text = {
            Column {
                Text(tr("You'll get a link. Anyone who opens it can upload files to you, even without the app. They arrive in your Sendit inbox."))
                Spacer(Modifier.height(12.dp))
                BeamTextField(value = title, onValueChange = { title = it.take(120) }, placeholder = tr("What for? (optional)"), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onCreate(title) { err -> busy = false; error = err?.let { tr(it) } }
            }) { Text(if (busy) "…" else tr("Create link"), color = Secondary) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
    )
}

@Composable
private fun RequestLinkDialog(r: FileRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainer,
        titleContentColor = OnSurface,
        textContentColor = OnSurfaceVariant,
        title = { Text(tr("Your upload link")) },
        text = {
            Column {
                Text(tr("Send this link to the people who should send you files. It works for 7 days."))
                Spacer(Modifier.height(12.dp))
                Text(
                    r.url.removePrefix("http://").removePrefix("https://"),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceLow)
                        .padding(14.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = tr("Please send me your files with Sendit: %s", r.url) + if (r.title.isNotBlank()) "\n(${r.title})" else ""
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Share")))
            }) { Text(tr("Share"), color = Secondary) }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(r.url)); copied = true }) {
                Text(if (copied) tr("Copied") else tr("Copy link"), color = OnSurfaceVariant)
            }
        }
    )
}
