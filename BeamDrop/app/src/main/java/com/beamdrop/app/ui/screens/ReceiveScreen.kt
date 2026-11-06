package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.data.PeerDevice
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.OutlineVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary

private const val CODE_LENGTH = 6

/** One place to receive: type the sender's code, scan their QR, or pick a nearby phone. */
@Composable
fun ReceiveScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onReceiveUrl: (String) -> Unit
) {
    val peers by viewModel.nearbyDevices.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        viewModel.acquireDiscovery()
        onDispose { viewModel.releaseDiscovery() }
    }

    val clean = code.filter { it.isLetterOrDigit() }.uppercase()
    fun open() {
        if (clean.length == CODE_LENGTH) {
            code = ""
            onReceiveUrl(viewModel.shareUrlForCode(clean))
        }
    }

    // Opens by itself once all 6 characters are typed.
    LaunchedEffect(clean) { if (clean.length == CODE_LENGTH) open() }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        DetailHeader(title = tr("Receive"), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text(tr("Enter the code"), style = MaterialTheme.typography.headlineSmall, color = OnSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr("Ask the sender for their 6-character code."),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            CodeBoxes(
                value = code,
                onValueChange = { new ->
                    when {
                        // A pasted link opens straight away.
                        new.trim().startsWith("http", ignoreCase = true) -> {
                            code = ""
                            onReceiveUrl(new.trim())
                        }
                        new.filter { it.isLetterOrDigit() }.length <= CODE_LENGTH -> code = new
                    }
                },
                onDone = ::open,
                focusRequester = focus
            )

            Spacer(Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Text(tr("or"), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            }
            Spacer(Modifier.height(22.dp))

            BeamButton(
                text = tr("Scan QR code"),
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                style = BeamButtonStyle.Tonal,
                icon = Icons.Rounded.QrCodeScanner,
                height = 58.dp
            )
            Spacer(Modifier.height(12.dp))
            RequestFilesSection(viewModel)

            if (peers.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                Text(
                    text = tr("Nearby phones"),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { peer -> PeerRow(peer) { onReceiveUrl(peer.url) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (clean.length in 1 until CODE_LENGTH) {
            Text(
                text = tr("%d more to go", CODE_LENGTH - clean.length),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun CodeBoxes(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester
) {
    var focused by remember { mutableStateOf(false) }
    val chars = value.filter { it.isLetterOrDigit() }.uppercase()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go
        ),
        keyboardActions = KeyboardActions(onGo = { onDone() }, onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box {
                // The real text field sits underneath, invisible; the boxes show what was typed.
                Box(Modifier.size(1.dp)) { inner() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(CODE_LENGTH) { i ->
                        val isNext = focused && i == chars.length
                        val border by animateColorAsState(
                            when {
                                isNext -> Secondary
                                i < chars.length -> OutlineVariant
                                else -> OutlineVariant.copy(alpha = 0.45f)
                            },
                            label = "codeBox"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(0.82f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (i < chars.length) SurfaceContainer else Ink.copy(alpha = 0.5f))
                                .border(if (isNext) 2.dp else 1.dp, border, RoundedCornerShape(14.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { focusRequester.requestFocus() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chars.getOrNull(i)?.toString().orEmpty(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
                                color = OnSurface
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PeerRow(peer: PeerDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Tertiary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Smartphone, null, tint = Tertiary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            Text(tr("Tap to receive"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Secondary)
    }
}
