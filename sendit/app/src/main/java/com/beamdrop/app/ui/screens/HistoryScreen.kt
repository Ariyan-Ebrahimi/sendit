package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.HistoryEntry
import com.beamdrop.app.ui.components.BrandHeader
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.Pill
import com.beamdrop.app.ui.components.SectionHeader
import com.beamdrop.app.ui.components.relativeTime
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceHigh
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    ALL("All"),
    SENT("Sent"),
    RECEIVED("Received")
}

@Composable
fun HistoryScreen(
    viewModel: BeamDropViewModel,
    onOpenDevices: () -> Unit,
    onOpenMyShares: () -> Unit,
    onOpenVault: () -> Unit = {}
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = activity, 1 = my files
    androidx.compose.runtime.LaunchedEffect(Unit) { if (viewModel.consumeFilesTab()) tab = 1 }
    var query by rememberSaveable { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf<HistoryEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    pickFrom?.let { entry -> com.beamdrop.app.ui.components.OpenFilesDialog(entry) { pickFrom = null } }

    val filtered = remember(history, filter, query) {
        val q = query.trim().lowercase()
        history.filter { entry ->
            val typeOk = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SENT -> entry.direction == Direction.SEND
                HistoryFilter.RECEIVED -> entry.direction == Direction.RECEIVE
            }
            typeOk && (q.isEmpty() || entry.fileName.lowercase().contains(q) || entry.peerName.lowercase().contains(q) ||
                entry.files.any { it.name.lowercase().contains(q) })
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { dateLabel(it.timestamp) }
    }

    Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = com.beamdrop.app.i18n.tr("History"),
                onBack = null,
                trailing = {
                    Text(
                        text = com.beamdrop.app.i18n.tr("My shares"),
                        style = MaterialTheme.typography.labelLarge,
                        color = Secondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Secondary.copy(alpha = 0.12f))
                            .clickable(onClick = onOpenMyShares)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            )
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(SurfaceLow)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(modifier = Modifier.weight(1f), label = tr("Activity"), selected = tab == 0, onClick = { tab = 0 })
                FilterChip(modifier = Modifier.weight(1f), label = tr("My files"), selected = tab == 1, onClick = { tab = 1 })
            }
            if (tab == 1) {
                FilesGallery(history = history, onEmptyAction = onOpenDevices, onDelete = viewModel::deleteReceivedFiles, onForget = viewModel::forgetFiles,
                    onVault = { files, done -> viewModel.moveToVault(files) { n, ask -> done(n, ask) } }, onOpenVault = onOpenVault)
                return@Column
            }

    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            com.beamdrop.app.ui.components.BeamTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("Search files or people"),
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(SurfaceLow)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HistoryFilter.entries.forEach { item ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            label = tr(item.label),
                            selected = filter == item,
                            onClick = { filter = item }
                        )
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Schedule,
                    title = tr("No transfers yet"),
                    message = tr("Files you send and receive will show up here."),
                    actionText = tr("Receive files"),
                    onAction = onOpenDevices
                )
            }
        } else if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Radar,
                    title = if (query.isNotBlank()) tr("No matches") else tr("Nothing in this filter"),
                    message = if (query.isNotBlank()) tr("Nothing matches \"%s\".", query.trim()) else tr("Switch back to all activity to see the rest of your transfer history."),
                    actionText = tr("Show all"),
                    onAction = { filter = HistoryFilter.ALL; query = "" }
                )
            }
        } else {
            groups.forEach { (label, entries) ->
                item(key = "header-$label") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.06f))
                        )
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        onOpen = { com.beamdrop.app.ui.components.openHistoryEntry(context, entry) { pickFrom = it } }
                    )
                }
            }

            item {
                Text(
                    text = tr("Clear history"),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clickable { showClear = true }
                        .padding(14.dp)
                )
            }
        }
    }

    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(tr("Clear transfer history?")) },
            text = { Text(tr("This removes every entry from this device. Downloaded files are not affected.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClear = false
                        viewModel.clearHistory()
                    }
                ) {
                    Text(tr("Clear history"), color = Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text(tr("Keep it"), color = OnSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun HistoryStat(
    label: String,
    count: Int,
    bytes: Long,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.75f),
                letterSpacing = 0.8.sp,
                fontSize = 9.sp
            )
            Text(
                text = "$count · ${Files.formatBytes(bytes)}",
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(CircleShape)
            .background(if (selected) PrimaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else OnSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val isSend = entry.direction == Direction.SEND
    val canOpen = entry.files.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKindBadge(
            name = entry.fileName,
            mimeType = "",
            size = 44.dp
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.fileCount > 1) {
                    Spacer(Modifier.width(5.dp))
                    Pill(
                        text = "+${entry.fileCount - 1}",
                        color = Secondary,
                        background = SurfaceHigh
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isSend) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = if (isSend) Primary else Secondary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = if (isSend) tr("To %s", entry.peerName) else tr("From %s", entry.peerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Files.formatBytes(entry.totalBytes)} · ${relativeTime(entry.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.width(8.dp))
        if (canOpen) {
            Text(
                text = tr("Open"),
                style = MaterialTheme.typography.labelLarge,
                color = Secondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Secondary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        } else {
            Icon(
                imageVector = if (entry.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = if (entry.success) tr("Completed") else tr("Failed"),
                tint = if (entry.success) Tertiary else Error,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

private fun dateLabel(timestamp: Long): String {
    if (timestamp <= 0L) return tr("Earlier")
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> tr("Today")
        sameYear && dayDiff == 1 -> tr("Yesterday")
        sameYear -> SimpleDateFormat("MMMM d", Locale.US).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestamp))
    }
}
