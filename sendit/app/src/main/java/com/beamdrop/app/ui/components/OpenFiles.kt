package com.beamdrop.app.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beamdrop.app.data.HistoryEntry
import com.beamdrop.app.data.SavedFile
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.util.openFile

/** Opens a received file if it is still on the phone; otherwise says so. */
fun openSavedFile(context: Context, file: SavedFile) {
    val exists = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r")?.use { true } ?: false
    }.getOrDefault(false)
    if (exists) {
        openFile(context, file.uri, file.mimeType)
    } else {
        Toast.makeText(context, tr("This file is no longer on your phone."), Toast.LENGTH_SHORT).show()
    }
}

/** Tapping a History/Recent row: one file opens straight away; several files show a list ([onPick]). */
fun openHistoryEntry(context: Context, entry: HistoryEntry, onPick: (HistoryEntry) -> Unit) {
    when (entry.files.size) {
        0 -> Unit
        1 -> openSavedFile(context, entry.files.first())
        else -> onPick(entry)
    }
}

@Composable
fun OpenFilesDialog(entry: HistoryEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(tr("Open a file")) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entry.files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceLow)
                            .clickable { openSavedFile(context, file) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileKindBadge(name = file.name, mimeType = file.mimeType, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (file.name.contains('/')) {
                                Text(file.name.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(tr("Open"), style = MaterialTheme.typography.labelLarge, color = Secondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close"), color = OnSurfaceVariant) } }
    )
}
