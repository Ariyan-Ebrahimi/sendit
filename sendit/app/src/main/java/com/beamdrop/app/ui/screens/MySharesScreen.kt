package com.beamdrop.app.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.api.ShareSummary
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files

/** Everything I've shared that is still online: downloads, time left, share again or delete early. */
@Composable
fun MySharesScreen(viewModel: BeamDropViewModel, onBack: () -> Unit, onOpenPlans: () -> Unit = {}) {
    val shares by viewModel.myShares.collectAsStateWithLifecycle()
    val loading by viewModel.mySharesLoading.collectAsStateWithLifecycle()
    val error by viewModel.mySharesError.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ShareSummary?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshMyShares() }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = tr("My shares"), onBack = onBack)
        storage?.let { (used, quota) -> StorageBar(used, quota, onOpenPlans) }

        when {
            loading && shares.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator(color = Secondary) }

            shares.isEmpty() -> EmptyState(
                icon = Icons.Rounded.CloudUpload,
                title = if (error != null) tr("Couldn't load your shares") else tr("Nothing shared right now"),
                message = error?.let { tr(it) } ?: tr("Files you send with Anywhere show up here until they expire."),
                actionText = tr("Refresh"),
                onAction = viewModel::refreshMyShares,
                modifier = Modifier.weight(1f)
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shares, key = { it.code }) { share ->
                    ShareCard(share, onDelete = { toDelete = share }, onExtend = { viewModel.extendShare(share.code) })
                }
            }
        }
    }

    toDelete?.let { share ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(tr("Delete this share?")) },
            text = { Text(tr("The code %s will stop working and the files are removed from the server.", share.code)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMyShare(share.code)
                    toDelete = null
                }) { Text(tr("Delete"), color = Error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
        )
    }
}

@Composable
private fun ShareCard(share: ShareSummary, onDelete: () -> Unit, onExtend: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileKindBadge(name = share.firstName, mimeType = "", size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = (if (share.e2e) tr("🔐 Encrypted files") else share.firstName) + if (share.fileCount > 1) " +${share.fileCount - 1}" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = Files.formatBytes(share.totalSize) + " · " +
                        (share.recipientName?.let { tr("To %s", it) } ?: tr("Anyone with the code")),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (copied) tr("Copied") else share.code,
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(share.code))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        val facts = buildList {
            add(
                when (share.downloads) {
                    0 -> tr("Not downloaded yet")
                    1 -> tr("Downloaded once")
                    else -> tr("Downloaded %d times", share.downloads)
                }
            )
            share.releaseAt?.let { if (it > System.currentTimeMillis()) add(tr("opens %s", java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(it)))) }
            add(expiresText(share.expiresAt))
            if (share.viewOnce) add(tr("view once")) else if (share.oneTime) add(tr("one-time"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (share.hasPassword) {
                Icon(Icons.Rounded.Lock, contentDescription = tr("password protected"), tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (share.downloads > 0) Tertiary else OnSurfaceVariant
            )
        }
        if (share.downloaders.isNotEmpty()) {
            Text(
                text = tr("Downloaded by %s", share.downloaders.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = Tertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardAction(Icons.Rounded.Share, tr("Share"), Secondary, Modifier.weight(1f)) {
                val text = tr("I sent you files with Sendit.\n\nDownload: %s\n\nOr open the Sendit app and enter the code: %s", share.url, share.code)
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Share Sendit link"))
                )
            }
            CardAction(Icons.Rounded.MoreTime, tr("+1 day"), Primary, Modifier.weight(1f), onClick = onExtend)
            CardAction(Icons.Rounded.Delete, tr("Delete"), Error, Modifier.weight(1f), onClick = onDelete)
        }
    }
}

@Composable
private fun StorageBar(used: Long, quota: Long, onOpenPlans: () -> Unit) {
    if (quota <= 0) return
    val fraction = (used.toFloat() / quota).coerceIn(0f, 1f)
    val full = fraction > 0.9f
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr("%s of %s used", Files.formatBytes(used), Files.formatBytes(quota)),
                style = MaterialTheme.typography.bodySmall,
                color = if (full) Error else OnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = tr("★ Get more space"),
                style = MaterialTheme.typography.labelMedium,
                color = Secondary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenPlans).padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(SurfaceContainer)
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (full) Error else Secondary)
            )
        }
        if (full) {
            Text(
                text = tr("Almost full. Delete shares you don't need, or wait for them to expire."),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

private fun expiresText(expiresAt: Long): String {
    val left = expiresAt - System.currentTimeMillis()
    if (left <= 0) return tr("expired")
    val minutes = left / 60_000
    return when {
        minutes < 60 -> tr("expires in %d min", minutes.coerceAtLeast(1))
        minutes < 48 * 60 -> tr("expires in %d h", minutes / 60)
        else -> tr("expires in %d days", minutes / (24 * 60))
    }
}
