package com.beamdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamLogo
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant

/** Shown instead of the app while App lock is on and Sendit hasn't been unlocked yet. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BeamLogo(size = 84.dp)
        Spacer(Modifier.height(20.dp))
        Text(tr("Sendit is locked"), style = MaterialTheme.typography.headlineSmall, color = OnSurface)
        Spacer(Modifier.height(6.dp))
        Text(tr("Unlock with your fingerprint, face or screen lock."), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        BeamButton(tr("Unlock"), onClick = onUnlock, icon = Icons.Rounded.Fingerprint, modifier = Modifier.fillMaxWidth(), height = 56.dp)
    }
}
