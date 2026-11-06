package com.beamdrop.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.HistoryEntry
import com.beamdrop.app.data.TransferStatus
import com.beamdrop.app.ui.components.BrandHeader
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.relativeTime
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.ReceiveGradient
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.util.Files
import com.beamdrop.app.api.ShareSummary
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.theme.Tertiary
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: BeamDropViewModel,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInboxItem: (ShareSummary) -> Unit
) {
    val inbox by viewModel.inbox.collectAsStateWithLifecycle()
    // Files sent to me: check now and then while Home is on screen.
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshInbox()
        viewModel.refreshAppConfig()
    }
    var pickFrom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<HistoryEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    pickFrom?.let { entry -> com.beamdrop.app.ui.components.OpenFilesDialog(entry) { pickFrom = null } }
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val userName by viewModel.userDisplayName.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val tipsSeen by viewModel.tipsSeen.collectAsStateWithLifecycle()

    // "Send" goes straight to the system file picker: one tap fewer.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
            onOpenSend()
        }
    }

    val name = userName?.takeIf { it.isNotBlank() } ?: deviceName
    val active = transfer?.takeIf {
        it.status == TransferStatus.WAITING || it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PAUSED
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { BrandHeader(name = name, onAvatarClick = onOpenSettings) }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = tr("Hi, %s", name.substringBefore(' ')),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurface
                )
                Text(
                    text = tr("What do you want to do?"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceVariant
                )
            }
        }

        if (announcement.isNotBlank()) {
            item {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Secondary.copy(alpha = 0.12f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Campaign, null, tint = Secondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(announcement, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                }
            }
        }

        items(inbox, key = { "inbox-" + it.code }) { item -> InboxCard(item) { onOpenInboxItem(item) } }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BigAction(
                    title = tr("Send"),
                    subtitle = tr("Photos, videos or any file"),
                    icon = Icons.Rounded.ArrowUpward,
                    gradient = SendGradient,
                    contentColor = Color.White,
                    onClick = { picker.launch(arrayOf("*/*")) }
                )
                BigAction(
                    title = tr("Receive"),
                    subtitle = tr("Enter a code or scan a QR"),
                    icon = Icons.Rounded.ArrowDownward,
                    gradient = ReceiveGradient,
                    contentColor = Color(0xFF052E2B),
                    onClick = onOpenReceive
                )
            }
        }

        // One tap to the extra places, instead of hunting through Settings.
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickTile("🔒", tr("Vault"), Modifier.weight(1f)) { viewModel.requestScreen("vault") }
                QuickTile("📁", tr("My files"), Modifier.weight(1f)) { viewModel.requestScreen("my_files") }
                QuickTile("★", tr("Plus"), Modifier.weight(1f)) { viewModel.requestScreen("plans") }
            }
        }

        if (!tipsSeen) {
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Secondary.copy(alpha = 0.10f))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("✨ New in Sendit"), style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.weight(1f))
                        Text(
                            tr("Got it"),
                            style = MaterialTheme.typography.labelLarge,
                            color = Secondary,
                            modifier = Modifier.clip(CircleShape).clickable { viewModel.hideTips() }.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    listOf(
                        tr("🔐 Files you send to people are encrypted: only their phone can open them."),
                        tr("💣 Secret files can delete themselves seconds after they're opened."),
                        tr("📍 Leave files at a place: they open only when someone comes there."),
                        tr("🔒 Hide received files in your vault, behind their own PIN.")
                    ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                    Text(tr("When sending, find them under Extras."), style = MaterialTheme.typography.bodySmall, color = Secondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        if (active != null) {
            item {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(PrimaryContainer.copy(alpha = 0.28f))
                        .clickable(onClick = onOpenTransfer)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = { active.progress },
                        modifier = Modifier.size(30.dp),
                        color = Secondary,
                        trackColor = Color.White.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (active.direction == Direction.SEND) tr("Sending…") else tr("Receiving…"),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface
                        )
                        Text(
                            text = tr("%d%% · tap to see", (active.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Secondary)
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("Recent"), style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.weight(1f))
                    Text(
                        text = tr("See all"),
                        style = MaterialTheme.typography.labelLarge,
                        color = Secondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onOpenHistory)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            items(history.take(3), key = { it.id }) { entry ->
                RecentRow(entry) { com.beamdrop.app.ui.components.openHistoryEntry(context, entry) { pickFrom = it } }
            }
        }
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = contentColor)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.8f))
        }
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun InboxCard(item: ShareSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Tertiary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Tertiary.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MoveToInbox, null, tint = Tertiary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("%s sent you files", item.sender), style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    if (item.fileCount == 1) tr("1 file") else tr("%d files", item.fileCount),
                    Files.formatBytes(item.totalSize),
                    if (item.e2e) "🔐" else null,
                    if (item.geoRadius > 0) "📍" else null,
                    if (item.destructSeconds > 0) "💣" else if (item.viewOnce) "👁" else null,
                    tr("tap to open")
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Tertiary)
    }
}

@Composable
private fun QuickTile(emoji: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = OnSurface, maxLines = 1)
    }
}

@Composable
private fun RecentRow(entry: HistoryEntry, onOpen: () -> Unit) {
    val canOpen = entry.files.isNotEmpty()
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKindBadge(name = entry.fileName, mimeType = "", size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (entry.fileCount > 1) "${entry.fileName} +${entry.fileCount - 1}" else entry.fileName,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${if (entry.direction == Direction.SEND) tr("Sent") else tr("Received")} · ${Files.formatBytes(entry.totalBytes)} · ${relativeTime(entry.timestamp)}" +
                    if (!entry.success) " · " + tr("failed") else "",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                maxLines = 1
            )
        }
        if (canOpen) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = tr("Open"),
                style = MaterialTheme.typography.labelLarge,
                color = Secondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Secondary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
