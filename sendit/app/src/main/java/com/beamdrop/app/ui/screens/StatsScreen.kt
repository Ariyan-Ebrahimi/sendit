package com.beamdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.Direction
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.DeviceAvatar
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files
import java.util.Calendar

/** "Your stats": what you sent and received, from this phone's history. */
@Composable
fun StatsScreen(viewModel: BeamDropViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val ok = remember(history) { history.filter { it.success } }
    val sent = ok.filter { it.direction == Direction.SEND }
    val received = ok.filter { it.direction == Direction.RECEIVE }
    val monthStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
    }
    val thisMonth = ok.filter { it.timestamp >= monthStart }
    // "Ali · code AB12CD" -> "Ali"; codes for anyone don't count as a person.
    val people = remember(ok) {
        ok.map { it.peerName.substringBefore(" · ").trim() }
            .filter { it.isNotBlank() && it != "Anywhere" && it != "Nearby device" && it != "Waiting for receiver" }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5)
    }
    // Transfers on each of the last 7 days.
    val week = remember(ok) {
        val day = 24L * 60 * 60 * 1000
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        (6 downTo 0).map { back ->
            val from = today - back * day
            val label = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(from))
            label to ok.count { it.timestamp in from until from + day }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = tr("Your stats"), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(tr("Sent"), Files.formatBytes(sent.sumOf { it.totalBytes }), tr("%d files", sent.sumOf { it.fileCount }), Primary, Modifier.weight(1f))
                    StatTile(tr("Received"), Files.formatBytes(received.sumOf { it.totalBytes }), tr("%d files", received.sumOf { it.fileCount }), Tertiary, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(tr("This month"), thisMonth.size.toString(), tr("transfers"), Secondary, Modifier.weight(1f))
                    StatTile(tr("All time"), ok.size.toString(), tr("transfers"), Secondary, Modifier.weight(1f))
                }
            }
            item {
                Card {
                    Text(tr("Last 7 days"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    Spacer(Modifier.height(12.dp))
                    val max = (week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                    Row(
                        Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        week.forEach { (label, n) ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                if (n > 0) Text(n.toString(), style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height((6 + 80 * n / max).dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (n > 0) Primary else SurfaceContainer)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    Text(tr("People you share with most"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    if (people.isEmpty()) {
                        Text(tr("Send to someone by email or nearby to see them here."), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    people.forEach { (name, n) ->
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeviceAvatar(name = name, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (n == 1) tr("1 time") else tr("%d times", n), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text(
                    tr("Counted from this phone's history. Clearing history resets them."),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = tint, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
    }
}
