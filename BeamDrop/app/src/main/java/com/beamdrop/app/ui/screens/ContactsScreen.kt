package com.beamdrop.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.People
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.api.Contact
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.DeviceAvatar
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow

@Composable
fun ContactsScreen(viewModel: BeamDropViewModel, onBack: () -> Unit, onScan: () -> Unit = {}) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val myEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val myName by viewModel.userDisplayName.collectAsStateWithLifecycle()
    var showMyQr by remember { mutableStateOf(false) }
    if (showMyQr && myEmail != null) MyQrDialog(myName ?: "", myEmail!!) { showMyQr = false }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<Contact?>(null) }
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<com.beamdrop.app.api.ContactGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshContacts()
        viewModel.refreshGroups()
    }
    if (creatingGroup || editing != null) {
        val g = editing
        GroupEditorDialog(
            group = g,
            contacts = contacts,
            onDismiss = { creatingGroup = false; editing = null },
            onSave = { name, emails, done ->
                viewModel.saveGroup(g?.id, name, emails) { err ->
                    done(err)
                    if (err == null) { creatingGroup = false; editing = null }
                }
            },
            onDelete = g?.let { { viewModel.deleteGroup(it.id); editing = null } }
        )
    }

    fun add() {
        if (email.isBlank() || busy) return
        busy = true
        viewModel.addContact(email.trim()) { err ->
            busy = false
            if (err == null) email = "" else error = tr(err)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        DetailHeader(title = tr("Contacts"), onBack = onBack)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BeamTextField(
                value = email,
                onValueChange = { email = it; error = null },
                placeholder = tr("Add by email"),
                leadingIcon = Icons.Rounded.Mail,
                keyboardType = KeyboardType.Email,
                onImeAction = ::add,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (busy) "…" else tr("Add"),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PrimaryContainer)
                    .clickable(onClick = ::add)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            )
        }
        error?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 24.dp, top = 6.dp)) }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QrAction(Icons.Rounded.QrCode2, tr("My Sendit QR"), Modifier.weight(1f)) { showMyQr = true }
            QrAction(Icons.Rounded.QrCodeScanner, tr("Scan to add"), Modifier.weight(1f), onClick = onScan)
        }
        Text(
            text = tr("Turn on \"Receive automatically\" for people you trust: their files download without asking (unless they have a password)."),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )

        if (contacts.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.People,
                title = tr("No contacts yet"),
                message = tr("Add the people you send files to most, so you can pick them with one tap."),
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Groups"), style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.weight(1f))
                    Text(
                        text = tr("New group"),
                        style = MaterialTheme.typography.labelLarge,
                        color = Secondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { creatingGroup = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        tr("Make a group (like Family) to send to everyone in it at once."),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant
                    )
                }
            }
            items(groups, key = { "g" + it.id }) { g -> GroupRow(g) { editing = g } }
            item {
                Text(tr("People"), style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.padding(top = 14.dp))
            }
            items(contacts, key = { it.id }) { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(SurfaceLow)
                        .padding(start = 14.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(name = c.name, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.email, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(
                            Icons.Rounded.Close, contentDescription = tr("Remove"), tint = OnSurfaceVariant,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { toRemove = c }
                                .padding(11.dp)
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setAutoAccept(c, !c.autoAccept) }
                            .padding(start = 54.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("Receive automatically"), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, modifier = Modifier.weight(1f))
                        Switch(
                            checked = c.autoAccept,
                            onCheckedChange = { viewModel.setAutoAccept(c, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = PrimaryContainer,
                                uncheckedThumbColor = OnSurfaceVariant, uncheckedTrackColor = SurfaceContainer,
                                uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
    }

    toRemove?.let { c ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(tr("Remove %s?", c.name)) },
            text = { Text(tr("They stay a Sendit user; they're just removed from your contacts.")) },
            confirmButton = {
                TextButton(onClick = { viewModel.removeContact(c); toRemove = null }) {
                    Text(tr("Remove"), color = Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
        )
    }
}


@Composable
private fun QrAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(com.beamdrop.app.ui.theme.SurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = com.beamdrop.app.ui.theme.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = com.beamdrop.app.ui.theme.Secondary, maxLines = 1)
    }
}

/** A QR code friends scan in Sendit (Contacts → Scan to add) to add me. */
@Composable
private fun MyQrDialog(name: String, email: String, onDismiss: () -> Unit) {
    val content = remember(email) {
        android.net.Uri.Builder().scheme("sendit").authority("add").appendQueryParameter("e", email).build().toString()
    }
    val qr = remember(content) { com.beamdrop.app.net.QrCode.generate(content, 640) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.beamdrop.app.ui.theme.SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(tr("My Sendit QR")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF4F7FF))
                        .padding(14.dp)
                ) {
                    if (qr != null) androidx.compose.foundation.Image(qr, contentDescription = null, modifier = Modifier.size(220.dp))
                }
                Spacer(Modifier.height(12.dp))
                if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                Text(email, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Friends scan this in Sendit (Contacts → Scan to add) to add you."),
                    style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Done"), color = com.beamdrop.app.ui.theme.Secondary) } }
    )
}
