package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.SelectedFile
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.util.Files

@Composable
fun SendFilesScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val files by viewModel.selectedFiles.collectAsStateWithLifecycle()
    val connectError by viewModel.connectError.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }
    var writing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (writing) WriteTextDialog(onDismiss = { writing = false }) { text -> viewModel.addTextNote(text); writing = false }

    // Camera: take a photo or record a video right here and send it.
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.let { if (ok) viewModel.addCameraFile(android.net.Uri.parse(it), video = false) }
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        cameraUri?.let { if (ok) viewModel.addCameraFile(android.net.Uri.parse(it), video = true) }
    }
    val launchCamera: (Boolean) -> Unit = { video ->
        val uri = viewModel.newCameraFile(video)
        cameraUri = uri.toString()
        runCatching { if (video) takeVideo.launch(uri) else takePhoto.launch(uri) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var wantVideo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(wantVideo)
    }
    val openCamera: (Boolean) -> Unit = { video ->
        wantVideo = video
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera(video)
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SelectedFile?>(null) }
    renaming?.let { f ->
        RenameDialog(f.name.substringAfterLast('/'), onDismiss = { renaming = null }) { name ->
            viewModel.renameFile(f.uri, name)
            renaming = null
        }
    }

    // Leaving this screen means "I changed my mind": start fresh next time.
    val leave = {
        viewModel.clearFiles()
        onBack()
    }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = tr("Send"), onBack = leave)

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.FolderOpen,
                title = tr("No files chosen"),
                message = tr("Choose the photos, videos or files you want to send."),
                actionText = tr("Choose files"),
                onAction = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            )
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton(Icons.Rounded.PhotoCamera, tr("Take a photo"), { openCamera(false) }, Modifier.weight(1f))
                    AddButton(Icons.Rounded.Videocam, tr("Record a video"), { openCamera(true) }, Modifier.weight(1f))
                }
                AddButton(Icons.Rounded.CreateNewFolder, tr("Send a whole folder"), { folderPicker.launch(null) })
                AddButton(Icons.Rounded.EditNote, tr("Write a text"), { writing = true })
            }
            return@Column
        }

        Text(
            text = "${files.size} ${if (files.size == 1) tr("file") else tr("files")} · ${Files.formatBytes(files.sumOf { it.size })}",
            style = MaterialTheme.typography.titleMedium,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(files, key = { it.uri.toString() }) { file ->
                FileRow(file = file, onRemove = { viewModel.removeFile(file.uri) }, onRename = { renaming = file })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton(Icons.Rounded.Add, tr("Add files"), { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                    AddButton(Icons.Rounded.CreateNewFolder, tr("Add a folder"), { folderPicker.launch(null) }, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton(Icons.Rounded.PhotoCamera, tr("Photo"), { openCamera(false) }, Modifier.weight(1f))
                    AddButton(Icons.Rounded.Videocam, tr("Video"), { openCamera(true) }, Modifier.weight(1f))
                    AddButton(Icons.Rounded.EditNote, tr("Text"), { writing = true }, Modifier.weight(1f))
                }
            }
            item {
                Text(
                    tr("Tap a file to rename it"),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            connectError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            BeamButton(
                text = tr("Next"),
                onClick = { if (viewModel.startSend()) onContinue() },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                style = BeamButtonStyle.Primary,
                height = 58.dp
            )
        }
    }
}

@Composable
private fun WriteTextDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.beamdrop.app.ui.theme.SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(tr("Write a text")) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(20000) },
                placeholder = { Text(tr("A note, a link, an address…"), color = OnSurfaceVariant) },
                minLines = 4,
                maxLines = 10,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
                    focusedBorderColor = Secondary, unfocusedBorderColor = OnSurfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) {
                Text(tr("Add"), color = Secondary)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val dot = current.lastIndexOf('.').takeIf { it > 0 } ?: current.length
    var value by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, dot))
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.beamdrop.app.ui.theme.SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(tr("Rename file")) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
                    focusedBorderColor = Secondary, unfocusedBorderColor = OnSurfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDone(value.text) }, enabled = value.text.isNotBlank()) {
                Text(tr("Save"), color = Secondary)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
    )
}

@Composable
private fun AddButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Secondary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Secondary, maxLines = 1)
    }
}

@Composable
private fun FileRow(file: SelectedFile, onRemove: () -> Unit, onRename: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .clickable(onClick = onRename)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKindBadge(name = file.name, mimeType = file.mimeType, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Files.formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = tr("Remove %s", file.name),
            tint = OnSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(11.dp)
        )
    }
}
