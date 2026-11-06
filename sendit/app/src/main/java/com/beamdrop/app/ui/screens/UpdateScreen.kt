package com.beamdrop.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beamdrop.app.api.UpdateInfo
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceLow

/**
 * A new Sendit version. Required → full screen, nothing else can be used; optional → a card with "Later".
 */
@Composable
fun UpdateScreen(info: UpdateInfo, onLater: () -> Unit) {
    val context = LocalContext.current
    val open = {
        if (info.url.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (info.required) 0.92f else 0.6f))
            // Swallow taps so the app behind can't be used.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!info.required) onLater() },
        contentAlignment = if (info.required) Alignment.Center else Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(SurfaceLow)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Secondary, modifier = Modifier.size(52.dp))
            Text(if (info.required) tr("Please update Sendit") else tr("A new Sendit is ready"), style = MaterialTheme.typography.titleLarge, color = OnSurface)
            Text(
                if (info.required) tr("This version is too old to keep working. The update takes a minute.") else tr("Get the newest features and fixes."),
                style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, textAlign = TextAlign.Center
            )
            if (info.note.isNotBlank()) {
                Text(info.note, style = MaterialTheme.typography.bodyMedium, color = OnSurface, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Secondary.copy(alpha = 0.1f)).padding(12.dp).fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            BeamButton(text = tr("Update now"), onClick = open, enabled = info.url.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (info.url.isBlank()) Text(tr("Ask the Sendit team for the new version."), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
            if (!info.required) BeamButton(text = tr("Later"), onClick = onLater, style = BeamButtonStyle.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}
