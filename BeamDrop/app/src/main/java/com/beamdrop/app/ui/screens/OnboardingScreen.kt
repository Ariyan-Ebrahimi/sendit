package com.beamdrop.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.ReceiveGradient
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceHigh
import kotlinx.coroutines.launch

private class TourPage(val icon: ImageVector, val gradient: List<Color>, val title: String, val text: String)

/** Four quick screens the first time someone signs in. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        TourPage(Icons.Rounded.ArrowUpward, SendGradient, tr("Send anything"), tr("Tap Send, pick photos, videos or any file, and you get a 6-character code to share.")),
        TourPage(Icons.Rounded.ArrowDownward, ReceiveGradient, tr("Receive with a code"), tr("Tap Receive and type the code, or scan the QR. The files are saved in Downloads/Sendit.")),
        TourPage(Icons.Rounded.Public, SendGradient, tr("Anywhere or nearby"), tr("Anywhere works over the internet. Nearby Wi-Fi sends phone to phone with no upload, when you're on the same Wi-Fi.")),
        TourPage(Icons.Rounded.MoveToInbox, ReceiveGradient, tr("Send to people directly"), tr("Add contacts and send to them by name. They get a notification, no code needed."))
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // Swallow taps so nothing underneath the tour reacts.
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) BeamButton(tr("Skip"), onClick = onDone, style = BeamButtonStyle.Ghost, height = 44.dp)
            else Spacer(Modifier.height(44.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(p.gradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(p.icon, null, tint = Color.White, modifier = Modifier.size(70.dp))
                }
                Spacer(Modifier.height(36.dp))
                Text(p.title, style = MaterialTheme.typography.headlineMedium, color = OnSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.text, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { i ->
                val on = i == pager.currentPage
                val w by animateDpAsState(if (on) 24.dp else 8.dp, label = "dot")
                val c by animateColorAsState(if (on) OnSurface else SurfaceHigh, label = "dotColor")
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(c)
                )
            }
        }
        BeamButton(
            text = if (last) tr("Get started") else tr("Next"),
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth(),
            height = 58.dp
        )
    }
}
