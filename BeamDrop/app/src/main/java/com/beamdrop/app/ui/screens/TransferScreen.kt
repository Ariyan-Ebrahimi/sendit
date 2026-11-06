package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.beamdrop.app.ui.theme.SendGradient
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.FileStatus
import com.beamdrop.app.data.TransferFileProgress
import com.beamdrop.app.data.TransferStatus
import com.beamdrop.app.data.TransferUi
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.GradientProgressBar
import com.beamdrop.app.ui.components.InfoBanner
import com.beamdrop.app.ui.components.Pill
import com.beamdrop.app.ui.components.SectionHeader
import com.beamdrop.app.ui.components.StatusDot
import com.beamdrop.app.ui.components.etaText
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.InkDim
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceHigh
import com.beamdrop.app.ui.theme.SurfaceHighest
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files

@Composable
fun TransferScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onShowQr: () -> Unit,
    onDone: () -> Unit
) {
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val keepAwake by viewModel.keepAwake.collectAsStateWithLifecycle()
    val receiveOffer by viewModel.receiveOffer.collectAsStateWithLifecycle()
    val view = LocalView.current
    var showCancel by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }

    val current = transfer

    DisposableEffect(keepAwake, current != null) {
        view.keepScreenOn = keepAwake && current != null
        onDispose { view.keepScreenOn = false }
    }

    if (current == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailHeader(title = tr("Transfer"), onBack = onBack)
            EmptyState(
                icon = Icons.Rounded.Wifi,
                title = tr("No active transfer"),
                message = tr("Start a send or scan a Sendit QR code to see live progress here."),
                actionText = tr("Back"),
                onAction = onBack,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }

    val terminal = current.status in setOf(
        TransferStatus.COMPLETE,
        TransferStatus.FAILED,
        TransferStatus.CANCELLED
    )
    BackHandler {
        if (terminal) onDone() else onBack()
    }

    val title = when (current.status) {
        TransferStatus.WAITING -> tr("Waiting")
        TransferStatus.COMPLETE -> ""
        TransferStatus.FAILED -> tr("Failed")
        TransferStatus.CANCELLED -> tr("Cancelled")
        TransferStatus.PAUSED -> tr("Paused")
        else -> if (current.direction == Direction.SEND) tr("Sending") else tr("Receiving")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailHeader(
            title = title,
            onBack = if (terminal) onDone else onBack
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                when (current.status) {
                    TransferStatus.WAITING -> WaitingHero(transfer = current)
                    TransferStatus.COMPLETE -> CompletionHero(
                        transfer = current,
                        icon = Icons.Rounded.CheckCircle,
                        tint = Tertiary,
                        title = when {
                            current.viewOnce && current.destructSeconds > 0 -> tr("Self-destructing files")
                            current.viewOnce -> tr("View-once files")
                            current.direction == Direction.RECEIVE -> tr("Files received")
                            else -> tr("Files sent")
                        },
                        subtitle = when {
                            current.viewOnce && current.destructSeconds > 0 && current.files.any { it.savedUri != null } ->
                                tr("A %d-second countdown starts when you open them. Then they're gone.", current.destructSeconds)
                            current.viewOnce && current.files.any { it.savedUri != null } -> tr("You can look at it one time. It's deleted when you close it.")
                            current.viewOnce -> tr("Seen. It's gone now.")
                            current.direction == Direction.RECEIVE -> tr("Saved to your phone · tap a file to open it")
                            else -> tr("Delivered to %s", current.peerName)
                        }
                    )
                    TransferStatus.FAILED -> CompletionHero(
                        transfer = current,
                        icon = Icons.Rounded.ErrorOutline,
                        tint = Error,
                        title = tr("Something went wrong"),
                        subtitle = tr(current.error ?: "The connection was interrupted.") + " " +
                            if (current.viaCloud) tr("Check your internet and try again.") else tr("Make sure both phones are on the same Wi-Fi.")
                    )
                    TransferStatus.CANCELLED -> CompletionHero(
                        transfer = current,
                        icon = Icons.Rounded.Close,
                        tint = OnSurfaceVariant,
                        title = tr("Cancelled"),
                        subtitle = tr("The transfer was stopped.")
                    )
                    else -> ActiveHero(transfer = current)
                }
            }
            if (!current.viewOnce && current.direction == Direction.RECEIVE && current.status == TransferStatus.COMPLETE &&
                current.files.any { it.savedUri != null }) {
                item {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var moved by remember { mutableStateOf(false) }
                    val confirm = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
                    ) { }
                    if (!moved) {
                        com.beamdrop.app.ui.components.BeamButton(
                            text = tr("Hide in vault"),
                            onClick = {
                                val files = current.files.mapNotNull { f -> f.savedUri?.let { com.beamdrop.app.data.SavedFile(f.name, it, f.mimeType, f.size) } }
                                viewModel.moveToVault(files) { n, ask ->
                                    moved = n > 0
                                    android.widget.Toast.makeText(context, tr("%d file(s) moved to the vault", n), android.widget.Toast.LENGTH_SHORT).show()
                                    ask?.let { runCatching { confirm.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) } }
                                }
                            },
                            icon = Icons.Rounded.Lock,
                            style = com.beamdrop.app.ui.components.BeamButtonStyle.Tonal,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (current.viewOnce && current.status == TransferStatus.COMPLETE && current.files.any { it.savedUri != null }) {
                item {
                    com.beamdrop.app.ui.components.BeamButton(
                        text = tr("View now"),
                        onClick = { viewing = true },
                        icon = Icons.Rounded.CheckCircle,
                        modifier = Modifier.fillMaxWidth(),
                        height = 58.dp
                    )
                }
            }
            item {
                Text(
                    text = tr("Files"),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                )
            }
            items(current.files, key = { it.id }) { file ->
                TransferFileRow(file = file, allowOpen = !current.viewOnce)
            }
        }

        TransferBottomBar(
            transfer = current,
            hasRetry = receiveOffer != null && current.direction == Direction.RECEIVE,
            onPauseToggle = viewModel::togglePause,
            onCancel = { showCancel = true },
            onShowQr = onShowQr,
            onRetry = viewModel::retryReceive,
            onDone = onDone
        )
    }

    if (viewing) {
        val shown = current.files.filter { it.savedUri != null }
        ViewOnceViewer(
            uris = shown.map { it.savedUri!! },
            mimes = shown.map { it.mimeType },
            destructSeconds = current.destructSeconds,
            onClose = {
                viewing = false
                viewModel.closeViewOnce()
            }
        )
    }

    if (showCancel) {
        AlertDialog(
            onDismissRequest = { showCancel = false },
            containerColor = SurfaceContainer,
            titleContentColor = OnSurface,
            textContentColor = OnSurfaceVariant,
            title = { Text(tr("Cancel this transfer?")) },
            text = {
                Text(
                    if (current.direction == Direction.SEND) {
                        tr("The QR code and download link will stop working immediately.")
                    } else {
                        tr("The current download will stop and partially saved files will be discarded.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancel = false
                        viewModel.cancelTransfer()
                    }
                ) {
                    Text(tr("Cancel transfer"), color = Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancel = false }) {
                    Text(tr("Keep going"), color = OnSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun ActiveHero(transfer: TransferUi) {
    val paused = transfer.status == TransferStatus.PAUSED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BeamProgressRing(
            progress = transfer.progress,
            paused = paused,
            modifier = Modifier.size(194.dp)
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (paused) OnSurfaceVariant else Secondary,
                size = 8.dp,
                pulsing = !paused
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (transfer.waiting) {
                    tr("Waiting for internet… continues by itself")
                } else if (paused) {
                    tr("Paused · resume when ready")
                } else {
                    if (transfer.direction == Direction.SEND) tr("Sending at %s", Files.formatSpeed(transfer.speedBps))
                    else tr("Receiving at %s", Files.formatSpeed(transfer.speedBps))
                },
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (transfer.direction == Direction.SEND) {
                tr("To %s", transfer.peerName)
            } else {
                tr("From %s", transfer.peerName)
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = tr("%d of %d files", transfer.completedFiles, transfer.files.size) +
                if (transfer.speedBps > 0 && transfer.bytesDone < transfer.totalBytes) {
                    " · " + tr("about %s left", etaText(transfer.totalBytes - transfer.bytesDone, transfer.speedBps))
                } else "",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant
        )
    }
}

@Composable
private fun WaitingHero(transfer: TransferUi) {
    val transition = rememberInfiniteTransition(label = "waiting")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ping"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(194.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2f
                for (index in 0..2) {
                    val phase = (pulse + index / 3f) % 1f
                    drawCircle(
                        color = Secondary.copy(alpha = ((1f - phase) * 0.30f)),
                        radius = maxRadius * phase,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                drawCircle(
                    color = Secondary.copy(alpha = 0.10f),
                    radius = maxRadius * 0.62f
                )
                drawCircle(
                    color = Secondary.copy(alpha = 0.22f),
                    radius = maxRadius * 0.62f,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PrimaryContainer, Primary)))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr("Waiting for receiver"),
            style = MaterialTheme.typography.headlineSmall,
            color = OnSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tr("Show your QR code or open the link on the other device."),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Pill(
                text = "${transfer.files.size} ${if (transfer.files.size == 1) tr("file") else tr("files")}",
                color = Primary,
                background = SurfaceHigh
            )
            Pill(
                text = Files.formatBytes(transfer.totalBytes),
                color = Secondary,
                background = SurfaceHigh
            )
            Pill(
                text = tr("Ready"),
                color = Tertiary,
                background = SurfaceHigh,
                dotColor = Tertiary
            )
        }
    }
}

@Composable
private fun BeamProgressRing(
    progress: Float,
    paused: Boolean,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(650),
        label = "ringProgress"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2 + 3.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawCircle(
                color = PrimaryContainer.copy(alpha = 0.10f),
                radius = size.minDimension / 2f - inset
            )
            drawArc(
                color = SurfaceHighest,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (animated > 0.001f) {
                rotate(degrees = -90f, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Secondary,
                                Primary,
                                PrimaryContainer,
                                Secondary
                            ),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = OnSurface,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (paused) tr("PAUSED") else tr("TRANSFERRED"),
                style = MaterialTheme.typography.labelSmall,
                color = if (paused) OnSurfaceVariant else Secondary,
                letterSpacing = 1.2.sp,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun TorchSummary(transfer: TransferUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                tint = Secondary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tr("Session broadcast active"),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = tr("Nearby Sendit devices can also discover this send."),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant
            )
        }
        StatusDot(color = Secondary, size = 8.dp)
    }
}

@Composable
private fun StatStrip(transfer: TransferUi) {
    val remaining = (transfer.totalBytes - transfer.bytesDone).coerceAtLeast(0L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCell(
            modifier = Modifier.weight(1f),
            label = "TRANSFERRED",
            value = Files.formatBytes(transfer.bytesDone),
            tint = Secondary
        )
        Box(
            Modifier
                .width(1.dp)
                .height(30.dp)
                .background(Color.White.copy(alpha = 0.07f))
        )
        StatCell(
            modifier = Modifier.weight(1f),
            label = "REMAINING",
            value = Files.formatBytes(remaining),
            tint = OnSurface
        )
        Box(
            Modifier
                .width(1.dp)
                .height(30.dp)
                .background(Color.White.copy(alpha = 0.07f))
        )
        StatCell(
            modifier = Modifier.weight(1f),
            label = "EST. TIME",
            value = etaText(remaining, transfer.speedBps),
            tint = Primary
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.7.sp,
            fontSize = 8.5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QueueHeader(transfer: TransferUi) {
    Column {
        SectionHeader(
            title = tr("Queue Manifest"),
            actionText = "${transfer.completedFiles}/${transfer.files.size} done"
        )
        Spacer(Modifier.height(7.dp))
        GradientProgressBar(progress = transfer.progress, height = 5.dp)
        if (transfer.peakBps > 0L) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("Peak %s", Files.formatSpeed(transfer.peakBps)),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${Files.formatBytes(transfer.totalBytes)} total",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TransferFileRow(file: TransferFileProgress, allowOpen: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canOpen = allowOpen && file.savedUri != null
    val statusText = when {
        canOpen -> com.beamdrop.app.i18n.tr("Open")
        file.status == FileStatus.QUEUED -> com.beamdrop.app.i18n.tr("Waiting")
        file.status == FileStatus.ACTIVE -> "${if (file.size > 0) (file.bytesDone * 100 / file.size) else 0}%"
        file.status == FileStatus.DONE -> com.beamdrop.app.i18n.tr("Done")
        else -> com.beamdrop.app.i18n.tr("Failed")
    }
    val statusColor = when (file.status) {
        FileStatus.QUEUED -> OnSurfaceVariant
        FileStatus.ACTIVE -> Secondary
        FileStatus.DONE -> Tertiary
        FileStatus.FAILED -> Error
    }
    val progress = if (file.size > 0L) {
        (file.bytesDone.toFloat() / file.size).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (file.status == FileStatus.ACTIVE) SurfaceHigh else SurfaceLow)
            .then(
                if (canOpen) Modifier.clickable { com.beamdrop.app.util.openFile(context, file.savedUri!!, file.mimeType) }
                else Modifier
            )
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileKindBadge(
                name = file.name,
                mimeType = file.mimeType,
                size = 43.dp
            )
            Spacer(Modifier.width(10.dp))
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
                    text = if (file.status == FileStatus.ACTIVE && file.size > 0L) {
                        "${Files.formatBytes(file.bytesDone)} of ${Files.formatBytes(file.size)}"
                    } else {
                        Files.formatBytes(file.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            if (canOpen) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Brush.linearGradient(SendGradient))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                if (file.status == FileStatus.DONE) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
        }
        if (file.status == FileStatus.ACTIVE) {
            Spacer(Modifier.height(9.dp))
            GradientProgressBar(progress = progress, height = 4.dp)
        }
    }
}

@Composable
private fun KeepAwakeCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceLow)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SurfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Visibility,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tr("Keep screen awake"),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = tr("Stops the display sleeping during a transfer."),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryContainer,
                checkedBorderColor = Primary.copy(alpha = 0.45f),
                uncheckedThumbColor = OnSurfaceVariant,
                uncheckedTrackColor = SurfaceHighest,
                uncheckedBorderColor = Color.White.copy(alpha = 0.10f)
            )
        )
    }
}

@Composable
private fun CompletionHero(
    transfer: TransferUi,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.13f))
                .border(1.dp, tint.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = OnSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Pill(
                text = "${transfer.files.size} ${if (transfer.files.size == 1) tr("file") else tr("files")}",
                color = Primary,
                background = SurfaceHigh
            )
            Pill(
                text = Files.formatBytes(transfer.totalBytes),
                color = tint,
                background = SurfaceHigh
            )
            if (transfer.peakBps > 0L) {
                Pill(
                    text = tr("Peak %s", Files.formatSpeed(transfer.peakBps)),
                    color = Secondary,
                    background = SurfaceHigh
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: TransferStatus) {
    val (text, color) = when (status) {
        TransferStatus.WAITING -> "Waiting" to Secondary
        TransferStatus.ACTIVE -> "Live" to Tertiary
        TransferStatus.PAUSED -> "Paused" to OnSurfaceVariant
        TransferStatus.COMPLETE -> "Complete" to Tertiary
        TransferStatus.FAILED -> "Failed" to Error
        TransferStatus.CANCELLED -> "Cancelled" to OnSurfaceVariant
        TransferStatus.IDLE -> "Idle" to OnSurfaceVariant
    }
    Pill(
        text = text,
        color = color,
        background = SurfaceContainer,
        dotColor = color
    )
}

@Composable
private fun TransferBottomBar(
    transfer: TransferUi,
    hasRetry: Boolean,
    onPauseToggle: () -> Unit,
    onCancel: () -> Unit,
    onShowQr: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        when (transfer.status) {
            TransferStatus.WAITING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BeamButton(
                        text = tr("Show QR code"),
                        onClick = onShowQr,
                        modifier = Modifier.weight(1.3f),
                        icon = Icons.Rounded.QrCode2,
                        style = BeamButtonStyle.Primary,
                        height = 52.dp
                    )
                    BeamButton(
                        text = tr("Cancel"),
                        onClick = onCancel,
                        modifier = Modifier.weight(0.7f),
                        icon = Icons.Rounded.Close,
                        style = BeamButtonStyle.Danger,
                        height = 52.dp
                    )
                }
            }

            TransferStatus.ACTIVE, TransferStatus.PAUSED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BeamButton(
                        text = if (transfer.status == TransferStatus.PAUSED) tr("Resume") else tr("Pause"),
                        onClick = onPauseToggle,
                        modifier = Modifier.weight(1.2f),
                        icon = if (transfer.status == TransferStatus.PAUSED) {
                            Icons.Rounded.PlayCircle
                        } else {
                            Icons.Rounded.PauseCircle
                        },
                        style = BeamButtonStyle.Tonal,
                        height = 52.dp
                    )
                    BeamButton(
                        text = tr("Cancel"),
                        onClick = onCancel,
                        modifier = Modifier.weight(0.8f),
                        icon = Icons.Rounded.Close,
                        style = BeamButtonStyle.Danger,
                        height = 52.dp
                    )
                }
            }

            TransferStatus.FAILED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (hasRetry) {
                        BeamButton(
                            text = tr("Retry"),
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Refresh,
                            style = BeamButtonStyle.Primary,
                            height = 52.dp
                        )
                    }
                    BeamButton(
                        text = tr("Done"),
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        style = BeamButtonStyle.Tonal,
                        height = 52.dp
                    )
                }
            }

            else -> {
                BeamButton(
                    text = tr("Done"),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Check,
                    style = BeamButtonStyle.Primary,
                    height = 54.dp
                )
            }
        }
    }
}
