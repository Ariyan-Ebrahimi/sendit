package com.beamdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.HistoryEntry
import com.beamdrop.app.data.SavedFile
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.openSavedFile
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.util.FileKind
import com.beamdrop.app.util.Files

private enum class GallerySort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), SIZE("Biggest") }

private enum class GalleryFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), DOCS("Documents"), OTHER("Other") }

/** Every file I received that is still saved on the phone, as a grid with previews. */
@Composable
fun FilesGallery(
    history: List<HistoryEntry>,
    onEmptyAction: () -> Unit,
    onDelete: (List<SavedFile>) -> android.content.IntentSender? = { null },
    onForget: (Set<String>) -> Unit = {},
    onVault: (List<SavedFile>, (Int, android.content.IntentSender?) -> Unit) -> Unit = { _, _ -> },
    onOpenVault: () -> Unit = {}
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(GalleryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(GallerySort.NEWEST) }
    // Long-press a file to start selecting.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingAsk by remember { mutableStateOf(setOf<String>()) }
    val askAndroid = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) onForget(pendingAsk)
        pendingAsk = emptySet()
    }
    val loader = remember {
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
    }
    val received = remember(history) {
        history.filter { it.direction == Direction.RECEIVE }
            .flatMap { e -> e.files.map { it to e.timestamp } }
            .distinctBy { it.first.uri }
    }
    val all = remember(received, sort) {
        when (sort) {
            GallerySort.NEWEST -> received.sortedByDescending { it.second }
            GallerySort.OLDEST -> received.sortedBy { it.second }
            GallerySort.NAME -> received.sortedBy { it.first.name.substringAfterLast('/').lowercase() }
            GallerySort.SIZE -> received.sortedByDescending { it.first.size }
        }.map { it.first }
    }
    val shown = all.filter { f ->
        val kind = Files.fileKind(f.name, f.mimeType)
        when (filter) {
            GalleryFilter.ALL -> true
            GalleryFilter.PHOTOS -> kind == FileKind.IMAGE
            GalleryFilter.VIDEOS -> kind == FileKind.VIDEO
            GalleryFilter.DOCS -> kind == FileKind.PDF || kind == FileKind.DOCUMENT
            GalleryFilter.OTHER -> kind !in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.PDF, FileKind.DOCUMENT)
        }
    }

    if (confirmDelete) {
        val count = selected.size
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(if (count == 1) tr("Delete 1 file?") else tr("Delete %d files?", count)) },
            text = { Text(tr("They are removed from your phone too.")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    val files = all.filter { it.uri in selected }
                    selected = emptySet()
                    onDelete(files)?.let { sender ->
                        pendingAsk = files.map { it.uri }.toSet()
                        runCatching { askAndroid.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
                    }
                }) { Text(tr("Delete"), color = com.beamdrop.app.ui.theme.Error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel"), color = OnSurfaceVariant) }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.TextButton(onClick = { selected = emptySet() }) { Text(tr("Cancel"), color = OnSurfaceVariant) }
                Text(tr("%d selected", selected.size), style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { selected = shown.map { it.uri }.toSet() }) { Text(tr("All"), color = com.beamdrop.app.ui.theme.Secondary) }
                androidx.compose.material3.TextButton(onClick = {
                    val files = all.filter { it.uri in selected }
                    selected = emptySet()
                    onVault(files) { n, ask ->
                        android.widget.Toast.makeText(context, tr("%d file(s) moved to the vault", n), android.widget.Toast.LENGTH_SHORT).show()
                        ask?.let { sender ->
                            pendingAsk = files.map { it.uri }.toSet()
                            runCatching { askAndroid.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
                        }
                    }
                }) { Text(tr("Vault"), color = com.beamdrop.app.ui.theme.Secondary) }
                androidx.compose.material3.TextButton(onClick = { confirmDelete = true }) { Text(tr("Delete"), color = com.beamdrop.app.ui.theme.Error) }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(GalleryFilter.entries) { f ->
                Text(
                    text = tr(f.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (f == filter) Color.White else OnSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (f == filter) PrimaryContainer else SurfaceContainer)
                        .clickable { filter = f }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { Text(tr("Sort:"), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant) }
            items(GallerySort.entries) { o ->
                Text(
                    text = tr(o.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (o == sort) com.beamdrop.app.ui.theme.Secondary else OnSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { sort = o }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            item {
                Text(
                    tr("🔒 Vault"),
                    style = MaterialTheme.typography.labelMedium,
                    color = com.beamdrop.app.ui.theme.Secondary,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenVault).padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            if (selected.isEmpty() && shown.isNotEmpty()) item {
                Text(tr("Hold a file to select"), style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PhotoLibrary,
                title = if (all.isEmpty()) tr("No received files yet") else tr("Nothing in this filter"),
                message = tr("Files you receive with this version of Sendit show up here."),
                actionText = tr("Receive files"),
                onAction = onEmptyAction,
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(shown, key = { it.uri }) { file ->
                val toggle = { selected = if (file.uri in selected) selected - file.uri else selected + file.uri }
                Tile(
                    file, loader,
                    picked = file.uri in selected,
                    onLongClick = toggle,
                    onClick = { if (selected.isNotEmpty()) toggle() else openSavedFile(context, file) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Tile(file: SavedFile, loader: ImageLoader, picked: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val kind = Files.fileKind(file.name, file.mimeType)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .then(if (picked) Modifier.border(3.dp, com.beamdrop.app.ui.theme.Secondary, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) {
            AsyncImage(
                model = file.uri,
                imageLoader = loader,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (kind == FileKind.VIDEO) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                FileKindBadge(name = file.name, mimeType = file.mimeType, size = 40.dp)
                Text(
                    text = file.name.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        if (picked) {
            Icon(
                Icons.Rounded.CheckCircle, null,
                tint = com.beamdrop.app.ui.theme.Secondary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
