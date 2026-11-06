package com.beamdrop.app.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.relativeTime
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceLow
import kotlinx.coroutines.delay

/** Chat with the Sendit team (Settings → Contact support). Replies also arrive as notifications. */
@Composable
fun SupportScreen(viewModel: BeamDropViewModel, onBack: () -> Unit) {
    val messages by viewModel.support.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Check for replies while the chat is open.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshSupport()
            delay(15_000)
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    fun send() {
        if (text.isBlank() || sending) return
        sending = true
        viewModel.sendSupport(text) { err ->
            sending = false
            if (err == null) text = "" else error = tr(err)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        DetailHeader(title = tr("Contact support"), onBack = onBack)
        if (messages.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.SupportAgent,
                title = tr("How can we help?"),
                message = tr("Write your question or problem below. We'll reply here and you'll get a notification."),
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromAdmin) Alignment.CenterStart else Alignment.CenterEnd) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .then(
                                    if (m.fromAdmin) Modifier.background(SurfaceLow)
                                    else Modifier.background(Brush.linearGradient(SendGradient))
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            if (m.fromAdmin) Text(tr("Sendit support"), style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                            Text(m.body, style = MaterialTheme.typography.bodyMedium, color = if (m.fromAdmin) OnSurface else Color.White)
                            Text(
                                relativeTime(m.createdAt), style = MaterialTheme.typography.labelSmall,
                                color = if (m.fromAdmin) OnSurfaceVariant else Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BeamTextField(
                value = text,
                onValueChange = { text = it.take(2000); error = null },
                placeholder = tr("Write a message…"),
                onImeAction = ::send,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(SendGradient))
                    .clickable(onClick = ::send),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, tr("Send"), tint = Color.White)
            }
        }
    }
}
