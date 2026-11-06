package com.beamdrop.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.beamdrop.app.i18n.tr
import kotlinx.coroutines.delay

/**
 * Shows secret files (photos, videos, text) full screen. Screenshots and screen recording are blocked.
 * Tap for the next file. With [destructSeconds] > 0 a countdown runs and everything is deleted at zero;
 * otherwise it's deleted when closed. [onClose] does the deleting.
 */
@Composable
fun ViewOnceViewer(uris: List<String>, onClose: () -> Unit, mimes: List<String> = emptyList(), destructSeconds: Int = 0) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(destructSeconds) }
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (destructSeconds > 0) {
        LaunchedEffect(Unit) {
            while (left > 0) { delay(1000); left-- }
            onClose()
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The dialog has its own window: protect it too.
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        BackHandler(onBack = onClose)
        val i = index.coerceIn(0, uris.lastIndex)
        val uri = android.net.Uri.parse(uris[i])
        val mime = mimes.getOrNull(i).orEmpty()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { if (index < uris.lastIndex) index++ else if (destructSeconds == 0) onClose() }
        ) {
            when {
                mime.startsWith("video/") -> key(uri) {
                    AndroidView(
                        factory = { ctx -> VideoView(ctx).apply { setVideoURI(uri); setOnPreparedListener { it.isLooping = true; start() } } },
                        modifier = Modifier.fillMaxSize().padding(vertical = 60.dp)
                    )
                }
                mime == "text/plain" -> {
                    val text = remember(uri) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString().take(200_000) } }.getOrNull().orEmpty()
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 64.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                else -> AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (destructSeconds > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("💣 $left", style = MaterialTheme.typography.titleMedium, color = if (left <= 5) Color(0xFFFF8A80) else Color.White)
                }
                LinearProgressIndicator(
                    progress = { left / destructSeconds.toFloat() },
                    color = Color(0xFFFF8A80),
                    trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter).statusBarsPadding()
                )
            }
            Icon(
                Icons.Rounded.Close,
                contentDescription = tr("Close"),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onClose)
                    .padding(10.dp)
            )
            Text(
                text = when {
                    uris.size > 1 -> tr("%d of %d · tap for next", i + 1, uris.size)
                    destructSeconds > 0 -> tr("Deletes itself when the timer ends.")
                    else -> tr("Tap to close. It disappears after this.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }
