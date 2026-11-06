package com.beamdrop.app.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.LooksOne
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Schedule
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.api.ShareOptions
import com.beamdrop.app.data.CloudPhase
import com.beamdrop.app.data.CloudShareUi
import com.beamdrop.app.data.ConnectSession
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.FileStatus
import com.beamdrop.app.data.TransferFileProgress
import com.beamdrop.app.data.TransferStatus
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.net.QrCode
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.BeamLogo
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.FileKindBadge
import com.beamdrop.app.ui.components.InfoBanner
import com.beamdrop.app.ui.components.StatusDot
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.util.Files
import com.beamdrop.app.util.MediaShrinker
import kotlinx.coroutines.delay

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun ConnectScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onViewTransfer: () -> Unit,
    onDone: () -> Unit,
    onOpenContacts: () -> Unit = {},
    onOpenPlans: () -> Unit = {}
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val recent by viewModel.recentPeople.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshContacts()
        viewModel.refreshGroups()
    }
    // People to pick from: contacts first, then others I sent to recently.
    val people = remember(contacts, recent) {
        (contacts.map { it.email to it.name } + recent).distinctBy { it.first.lowercase() }
    }
    val session by viewModel.connectSession.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val cloud by viewModel.cloudShare.collectAsStateWithLifecycle()
    val anywhere by viewModel.preferAnywhere.collectAsStateWithLifecycle()
    val options by viewModel.shareOptions.collectAsStateWithLifecycle()
    val sendSmaller by viewModel.sendSmaller.collectAsStateWithLifecycle()
    val hotspot by viewModel.hotspot.collectAsStateWithLifecycle()
    val currentTransfer by rememberUpdatedState(transfer)
    val context = LocalContext.current

    var secondsLeft by remember(session?.token) { mutableIntStateOf(300) }
    var openedTransfer by rememberSaveable { mutableStateOf(false) }
    var recipientName by remember { mutableStateOf<String?>(null) }
    var recipientError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        viewModel.acquireDiscovery()
        onDispose { viewModel.releaseDiscovery() }
    }

    // Check the recipient's email shortly after typing stops.
    LaunchedEffect(options.toEmail) {
        recipientName = null
        recipientError = null
        val email = options.toEmail.trim()
        if (!EMAIL.matches(email)) return@LaunchedEffect
        delay(700)
        viewModel.lookupRecipient(email)
            .onSuccess { recipientName = it }
            .onFailure { recipientError = it.message }
    }

    // While a share is live, keep the download counter fresh.
    LaunchedEffect(cloud?.phase) {
        while (cloud?.phase == CloudPhase.READY) {
            viewModel.refreshCloudStatus()
            delay(5000)
        }
    }

    // Nearby QR quietly refreshes every 5 minutes while nobody has scanned it.
    LaunchedEffect(session?.token) {
        secondsLeft = 300
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        if (currentTransfer?.direction == Direction.SEND && currentTransfer?.status == TransferStatus.WAITING) {
            viewModel.regenerateToken()
        }
    }

    // A nearby phone started downloading: show live progress.
    LaunchedEffect(transfer?.status, transfer?.direction) {
        val active = transfer
        if (!openedTransfer && active?.direction == Direction.SEND && active.status == TransferStatus.ACTIVE) {
            openedTransfer = true
            onViewTransfer()
        }
    }

    val current = session
    val leave = {
        viewModel.finishSendFlow()
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        DetailHeader(title = tr("Send"), onBack = onBack)

        if (current == null) {
            EmptyState(
                icon = Icons.Rounded.QrCode2,
                title = tr("Session ended"),
                message = tr("Pick your files again to start a new send."),
                actionText = tr("Back to files"),
                onAction = onBack,
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        val locked = cloud != null && cloud?.phase != CloudPhase.FAILED
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            SendModeSwitch(anywhere = anywhere, onChange = { if (!locked) viewModel.setPreferAnywhere(it) })
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (anywhere) {
                val c = cloud
                when (c?.phase) {
                    null -> {
                        item {
                            OptionsCard(
                                options = options,
                                onChange = { change -> formError = null; viewModel.updateShareOptions(change) },
                                recipientName = recipientName,
                                recipientError = recipientError,
                                people = people,
                                groups = groups,
                                onOpenContacts = onOpenContacts,
                                secretOk = current.files.all {
                                    it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") || it.mimeType == "text/plain"
                                },
                                viewModel = viewModel,
                                onOpenPlans = onOpenPlans
                            )
                        }
                        if (sendSmaller && current.files.any { MediaShrinker.canShrink(it) }) {
                            item {
                                Text(
                                    text = tr("Photos and videos will be made smaller to save data. You can turn this off in Settings."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }
                        }
                        formError?.let { msg ->
                            item { Text(msg, style = MaterialTheme.typography.bodyMedium, color = Error, modifier = Modifier.padding(horizontal = 6.dp)) }
                        }
                    }
                    CloudPhase.PREPARING, CloudPhase.UPLOADING -> {
                        item { UploadCard(c) }
                        if (c.files.size > 1) items(c.files, key = { "up-${it.id}" }) { CloudFileRow(it) }
                    }
                    CloudPhase.READY -> item { ReadyCard(c) }
                    CloudPhase.FAILED -> item { FailedCard(c.error, onRetry = viewModel::retryCloudShare) }
                }
            } else {
                nearbyContent(current = current, hotspot = hotspot, viewModel = viewModel)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (!anywhere) {
                BeamButton(tr("Cancel"), onClick = leave, modifier = Modifier.weight(1f), style = BeamButtonStyle.Danger, icon = Icons.Rounded.Close, height = 54.dp)
                return@Row
            }
            when (cloud?.phase) {
                null -> BeamButton(
                    text = when {
                        options.sendAt != null -> tr("Upload & schedule")
                        options.toEmail.isNotBlank() || options.groupId != null || options.toSelf -> tr("Upload & send")
                        else -> tr("Upload & get code")
                    },
                    onClick = {
                        formError = when {
                            options.toEmail.isNotBlank() && !EMAIL.matches(options.toEmail.trim()) -> tr("Please enter a valid email address.")
                            options.toEmail.isNotBlank() && recipientError != null -> recipientError
                            options.password.isNotEmpty() && options.password.length < 4 -> tr("The password must be at least 4 characters.")
                            else -> null
                        }
                        if (formError == null) viewModel.startCloudShare()
                    },
                    modifier = Modifier.weight(1f),
                    style = BeamButtonStyle.Primary,
                    icon = Icons.Rounded.CloudUpload,
                    height = 58.dp
                )
                CloudPhase.READY -> {
                    BeamButton(
                        text = tr("Done"),
                        onClick = {
                            viewModel.finishSendFlow()
                            onDone()
                        },
                        modifier = Modifier.weight(1f),
                        style = BeamButtonStyle.Tonal,
                        icon = Icons.Rounded.Check,
                        height = 54.dp
                    )
                    BeamButton(
                        text = tr("Share link"),
                        onClick = {
                            val c = cloud ?: return@BeamButton
                            val text = tr("I sent you files with Sendit.\n\nDownload: %s\n\nOr open the Sendit app and enter the code: %s", c.url ?: "", c.code ?: "") +
                                if (c.hasPassword) tr("\n\nThe password is separate, I'll tell you.") else ""
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, tr("Share Sendit link")))
                        },
                        modifier = Modifier.weight(1.4f),
                        style = BeamButtonStyle.Primary,
                        icon = Icons.Rounded.Share,
                        height = 54.dp
                    )
                }
                CloudPhase.FAILED -> {
                    BeamButton(tr("Cancel"), onClick = leave, modifier = Modifier.weight(1f), style = BeamButtonStyle.Tonal, icon = Icons.Rounded.Close, height = 54.dp)
                    BeamButton(tr("Try again"), onClick = viewModel::retryCloudShare, modifier = Modifier.weight(1.4f), style = BeamButtonStyle.Primary, icon = Icons.Rounded.Refresh, height = 54.dp)
                }
                else -> {
                    if (cloud?.phase == CloudPhase.UPLOADING) {
                        val paused = cloud?.paused == true
                        BeamButton(
                            text = if (paused) tr("Resume") else tr("Pause"),
                            onClick = { if (paused) viewModel.resumeCloudShare() else viewModel.pauseCloudShare() },
                            modifier = Modifier.weight(1f),
                            style = if (paused) BeamButtonStyle.Primary else BeamButtonStyle.Tonal,
                            icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            height = 54.dp
                        )
                    }
                    BeamButton(tr("Cancel"), onClick = leave, modifier = Modifier.weight(1f), style = BeamButtonStyle.Danger, icon = Icons.Rounded.Close, height = 54.dp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mode switch
// ---------------------------------------------------------------------------

@Composable
private fun SendModeSwitch(anywhere: Boolean, onChange: (Boolean) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(SurfaceLow)
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
            .padding(4.dp)
    ) {
        val half = maxWidth / 2
        val offset by animateDpAsState(
            targetValue = if (anywhere) 0.dp else half,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "sendMode"
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(half)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.linearGradient(SendGradient))
        )
        Row(Modifier.fillMaxSize()) {
            listOf(
                Triple(true, tr("Anywhere"), Icons.Rounded.Public),
                Triple(false, tr("Nearby Wi-Fi"), Icons.Rounded.Wifi)
            ).forEach { (mode, label, icon) ->
                val color by animateColorAsState(if (mode == anywhere) Color.White else OnSurfaceVariant, label = "modeColor")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Anywhere: options before uploading
// ---------------------------------------------------------------------------

@Composable
private fun OptionsCard(
    options: ShareOptions,
    onChange: ((ShareOptions) -> ShareOptions) -> Unit,
    recipientName: String?,
    recipientError: String?,
    people: List<Pair<String, String>>,
    groups: List<com.beamdrop.app.api.ContactGroup>,
    onOpenContacts: () -> Unit,
    secretOk: Boolean,
    viewModel: BeamDropViewModel,
    onOpenPlans: () -> Unit
) {
    var toPerson by rememberSaveable { mutableStateOf(options.toEmail.isNotBlank()) }
    // Secret files only work for photos, videos and text.
    LaunchedEffect(secretOk) { if (!secretOk && (options.viewOnce || options.destructSeconds > 0)) onChange { it.copy(viewOnce = false, destructSeconds = 0) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceLow)
            .padding(16.dp)
    ) {
        OptionLabel(tr("Message (optional)"))
        BeamTextField(
            value = options.message,
            onValueChange = { v -> onChange { it.copy(message = v.take(1000)) } },
            placeholder = tr("e.g. Photos from Saturday"),
            leadingIcon = Icons.Rounded.ChatBubbleOutline,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
        OptionLabel(tr("Who can get it?"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(tr("Anyone"), selected = !toPerson && !options.toSelf, modifier = Modifier.weight(1f)) {
                toPerson = false
                onChange { it.copy(toEmail = "", groupId = null, groupName = null, toSelf = false, encrypt = false) }
            }
            ChoiceChip(tr("People"), selected = toPerson, modifier = Modifier.weight(1f)) {
                toPerson = true
                onChange { it.copy(toSelf = false, encrypt = viewModel.encryptByDefault) }
            }
            ChoiceChip(tr("My devices"), selected = options.toSelf, modifier = Modifier.weight(1f)) {
                toPerson = false
                onChange { it.copy(toEmail = "", groupId = null, groupName = null, toSelf = true, encrypt = viewModel.encryptByDefault) }
            }
        }
        if (options.toSelf) HintLine(tr("✓ Shows up on your other phones and tablets signed in to this account"), Tertiary)
        AnimatedVisibility(visible = toPerson) {
            Column(Modifier.padding(top = 10.dp)) {
                BeamTextField(
                    value = options.toEmail,
                    onValueChange = { v -> onChange { it.copy(toEmail = v.trim(), groupId = null, groupName = null) } },
                    placeholder = tr("Their email address"),
                    leadingIcon = Icons.Rounded.Mail,
                    keyboardType = KeyboardType.Email,
                    modifier = Modifier.fillMaxWidth()
                )
                if (people.isNotEmpty() || groups.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        items(groups, key = { "g" + it.id }) { g ->
                            val picked = options.groupId == g.id
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (picked) PrimaryContainer else SurfaceContainer)
                                    .clickable { onChange { it.copy(groupId = g.id, groupName = g.name, toEmail = "") } }
                                    .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Groups, null, tint = if (picked) Color.White else Secondary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("${g.name} (${g.members.size})", style = MaterialTheme.typography.labelLarge, color = if (picked) Color.White else OnSurface, maxLines = 1)
                            }
                        }
                        items(people, key = { it.first }) { (email, name) ->
                            val picked = options.toEmail.equals(email, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (picked) PrimaryContainer else SurfaceContainer)
                                    .clickable { onChange { it.copy(toEmail = email, groupId = null, groupName = null) } }
                                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.beamdrop.app.ui.components.DeviceAvatar(name = name.ifBlank { email }, size = 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = name.ifBlank { email }.substringBefore(' '),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (picked) Color.White else OnSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Text(
                    text = tr("Manage contacts"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Secondary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenContacts)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
                when {
                    options.groupId != null -> HintLine(tr("✓ Everyone in %s will get a notification", options.groupName ?: ""), Tertiary)
                    recipientName != null -> HintLine(tr("✓ %s will get a notification", recipientName), Tertiary)
                    recipientError != null -> HintLine(tr(recipientError), Error)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ExtrasPicker(options, onChange, secretOk, toSomeone = toPerson || options.toSelf, viewModel = viewModel, onOpenPlans = onOpenPlans)
    }
}

/** The extras, as one row of pills. Tap a pill to turn it on and see its few choices; nothing else is in the way. */
private enum class Extra { ENCRYPT, LATER, TIME, PASSWORD, SECRET, PLACE, ONCE }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ExtrasPicker(
    options: ShareOptions,
    onChange: ((ShareOptions) -> ShareOptions) -> Unit,
    secretOk: Boolean,
    toSomeone: Boolean,
    viewModel: BeamDropViewModel,
    onOpenPlans: () -> Unit
) {
    var open by rememberSaveable { mutableStateOf<Extra?>(null) }
    var withPassword by rememberSaveable { mutableStateOf(options.password.isNotEmpty()) }
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshPlans() }
    val secret = options.viewOnce || options.destructSeconds > 0
    val placeOn = options.geoLat != null

    OptionLabel(tr("Extras"))
    Text(tr("Tap one to turn it on. Everything is optional."), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExtraPill(Icons.Rounded.Lock, tr("Encrypted"), options.encrypt, open == Extra.ENCRYPT) {
            onChange { it.copy(encrypt = !it.encrypt) }
            open = Extra.ENCRYPT
        }
        ExtraPill(Icons.Rounded.Schedule, if (options.sendAt != null) formatWhen(options.sendAt) else tr("Send later"), options.sendAt != null, open == Extra.LATER) {
            open = if (open == Extra.LATER) null else Extra.LATER
        }
        ExtraPill(Icons.Rounded.HourglassBottom, ttlLabel(options.ttlHours), options.ttlHours != 24, open == Extra.TIME) {
            open = if (open == Extra.TIME) null else Extra.TIME
        }
        ExtraPill(Icons.Rounded.Key, tr("Password"), options.password.isNotEmpty(), open == Extra.PASSWORD) {
            withPassword = true
            open = if (open == Extra.PASSWORD) null else Extra.PASSWORD
        }
        if (secretOk) {
            ExtraPill(Icons.Rounded.Visibility, when {
                options.destructSeconds > 0 -> tr("Self-destruct %s", secondsLabel(options.destructSeconds))
                options.viewOnce -> tr("View once")
                else -> tr("Secret")
            }, secret, open == Extra.SECRET) {
                if (!secret) onChange { it.copy(viewOnce = true, destructSeconds = 0) }
                open = if (open == Extra.SECRET) null else Extra.SECRET
            }
        }
        ExtraPill(Icons.Rounded.Place, if (placeOn) tr("Only here · %s", metres(options.geoRadius)) else tr("Only here"), placeOn, open == Extra.PLACE) {
            open = if (open == Extra.PLACE) null else Extra.PLACE
        }
        if (!secret) {
            ExtraPill(Icons.Rounded.LooksOne, tr("One download"), options.oneTime, open == Extra.ONCE) {
                onChange { it.copy(oneTime = !it.oneTime) }
                open = Extra.ONCE
            }
        }
    }

    AnimatedVisibility(visible = open != null) {
        Column(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceContainer)
                .padding(14.dp)
        ) {
            when (open) {
                Extra.ENCRYPT -> {
                    PanelTitle(if (options.encrypt) tr("🔐 Encrypted end to end") else tr("Not encrypted"))
                    PanelText(
                        if (!options.encrypt) tr("Turn on so only the receiver can open the files. Not even the Sendit server can see them.")
                        else if (toSomeone) tr("Only their phone can open the files. Not even the Sendit server or the admin can see them.")
                        else tr("Only people with the link or QR code can open them. The 6-letter code alone won't work.")
                    )
                }
                Extra.LATER -> SendLaterPicker(options.sendAt) { at -> onChange { it.copy(sendAt = at) } }
                Extra.TIME -> {
                    PanelTitle(tr("How long the code works"))
                    val canMonth = (plans?.current?.maxTtlHours ?: 168) >= 720
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 24, 168, 720).forEach { hours ->
                            ChoiceChip(ttlLabel(hours) + if (hours == 720 && !canMonth) " ★" else "", selected = options.ttlHours == hours, modifier = Modifier.weight(1f)) {
                                if (hours == 720 && !canMonth) onOpenPlans() else onChange { it.copy(ttlHours = hours) }
                            }
                        }
                    }
                    if ((plans?.current?.maxTtlHours ?: 168) < 720) PanelText(tr("★ 30 days comes with Sendit Plus."))
                }
                Extra.PASSWORD -> {
                    PanelTitle(tr("They need the code and this password"))
                    BeamTextField(
                        value = options.password,
                        onValueChange = { v -> onChange { it.copy(password = v.take(64)) } },
                        placeholder = tr("Password (at least 4 characters)"),
                        leadingIcon = Icons.Rounded.Lock,
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (options.password.isNotEmpty()) TurnOff { onChange { it.copy(password = "") }; open = null }
                }
                Extra.SECRET -> {
                    PanelTitle(tr("Secret: opened only inside Sendit, no screenshots"))
                    PanelText(tr("Gone after they look, or after a countdown starts when they open it."))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip(tr("Once"), selected = options.viewOnce && options.destructSeconds == 0, modifier = Modifier.weight(1f)) {
                            onChange { it.copy(viewOnce = true, destructSeconds = 0) }
                        }
                        listOf(10, 30, 60, 300).forEach { sec ->
                            ChoiceChip(secondsLabel(sec), selected = options.destructSeconds == sec, modifier = Modifier.weight(1f)) {
                                onChange { it.copy(viewOnce = true, destructSeconds = sec) }
                            }
                        }
                    }
                    TurnOff { onChange { it.copy(viewOnce = false, destructSeconds = 0) }; open = null }
                }
                Extra.PLACE -> PlacePanel(options, onChange, viewModel) { open = null }
                Extra.ONCE -> {
                    PanelTitle(if (options.oneTime) tr("① Deleted after the first download") else tr("Anyone with the code can download it until it expires"))
                    PanelText(tr("Tap the pill again to switch."))
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun PlacePanel(options: ShareOptions, onChange: ((ShareOptions) -> ShareOptions) -> Unit, viewModel: BeamDropViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val find = {
        finding = true
        error = null
        scope.launch {
            val loc = viewModel.currentLocation()
            finding = false
            if (loc == null) error = if (!com.beamdrop.app.util.Locator.isOn(context)) tr("Location is off on this phone.")
                else tr("Couldn't find where you are. Turn on Location and try again.")
            else onChange { it.copy(geoLat = loc.latitude, geoLng = loc.longitude) }
        }
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.any { it }) find() else error = tr("Sendit needs your location for this.") }

    PanelTitle(tr("📍 Opens only near where you are now"))
    PanelText(tr("Leave files at a place: the receiver must come here to open them. Great for surprises, meetings or a treasure hunt."))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(100, 500, 2000).forEach { r ->
            ChoiceChip(metres(r), selected = options.geoRadius == r, modifier = Modifier.weight(1f)) { onChange { it.copy(geoRadius = r) } }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (options.geoLat != null) {
        HintLine(tr("✓ Place saved (%s, %s)", "%.4f".format(options.geoLat), "%.4f".format(options.geoLng ?: 0.0)), Tertiary)
        TurnOff { onChange { it.copy(geoLat = null, geoLng = null) }; onClose() }
    } else {
        BeamButton(
            text = if (finding) tr("Finding you…") else tr("Use my location"),
            onClick = {
                if (finding) return@BeamButton
                val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
                val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
                val has = androidx.core.content.ContextCompat.checkSelfPermission(context, fine) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(context, coarse) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (has) find() else ask.launch(arrayOf(fine, coarse))
            },
            icon = Icons.Rounded.Place,
            style = BeamButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth()
        )
    }
    error?.let {
        HintLine(it, Error)
        if (!com.beamdrop.app.util.Locator.isOn(context)) {
            Text(
                tr("Turn on location"),
                style = MaterialTheme.typography.labelLarge,
                color = Secondary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .clickable { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ExtraPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, on: Boolean, focused: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) PrimaryContainer else SurfaceContainer)
            .border(1.5.dp, if (focused) Secondary else Color.White.copy(alpha = 0.06f), CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (on) Color.White else Secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else OnSurface, maxLines = 1)
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun PanelText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun TurnOff(onClick: () -> Unit) {
    Text(
        tr("Turn off"),
        style = MaterialTheme.typography.labelLarge,
        color = Error,
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

private fun ttlLabel(hours: Int) = when (hours) {
    1 -> tr("1 hour")
    168 -> tr("7 days")
    720 -> tr("30 days")
    else -> tr("1 day")
}

private fun secondsLabel(sec: Int) = if (sec < 60) tr("%d sec", sec) else tr("%d min", sec / 60)

private fun metres(m: Int) = if (m < 1000) tr("%d m", m) else tr("%d km", m / 1000)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SendLaterPicker(sendAt: Long?, onPick: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val inHour = remember { 60 * 60 * 1000L }
    OptionLabel(tr("When?"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChip(tr("Now"), selected = sendAt == null, modifier = Modifier.weight(1f)) { onPick(null) }
        ChoiceChip(tr("In 1 hour"), selected = false, modifier = Modifier.weight(1f)) { onPick(System.currentTimeMillis() + inHour) }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChip(tr("Tomorrow"), selected = false, modifier = Modifier.weight(1f)) {
            val c = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }
            onPick(c.timeInMillis)
        }
        ChoiceChip(tr("Pick time"), selected = false, modifier = Modifier.weight(1f)) { picking = true }
    }
    if (sendAt != null) HintLine(tr("⏰ Receivers can open it from %s", formatWhen(sendAt)), Secondary)
    if (picking) {
        val state = androidx.compose.material3.rememberTimePickerState(is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = SurfaceContainer,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val c = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, state.hour); set(java.util.Calendar.MINUTE, state.minute); set(java.util.Calendar.SECOND, 0)
                    }
                    if (c.timeInMillis <= System.currentTimeMillis() + 60_000) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    onPick(c.timeInMillis)
                    picking = false
                }) { Text(tr("OK"), color = Secondary) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text(tr("Cancel"), color = OnSurfaceVariant) } },
            text = { androidx.compose.material3.TimePicker(state = state) }
        )
    }
}

/** "20:30" today, "tomorrow 09:00", or "Nov 6, 09:00". */
private fun formatWhen(at: Long): String {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at))
    val today = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val days = then.get(java.util.Calendar.DAY_OF_YEAR) - today.get(java.util.Calendar.DAY_OF_YEAR) +
        365 * (then.get(java.util.Calendar.YEAR) - today.get(java.util.Calendar.YEAR))
    return when (days) {
        0 -> tr("today %s", time)
        1 -> tr("tomorrow %s", time)
        else -> java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(at))
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun HintLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (selected) PrimaryContainer else SurfaceContainer)
            .border(1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.06f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else OnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryContainer,
                uncheckedThumbColor = OnSurfaceVariant,
                uncheckedTrackColor = SurfaceContainer,
                uncheckedBorderColor = OnSurfaceVariant.copy(alpha = 0.5f)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Anywhere: uploading / ready / failed
// ---------------------------------------------------------------------------

@Composable
private fun UploadCard(cloud: CloudShareUi) {
    val progress by animateFloatAsState(cloud.progress, label = "uploadProgress")
    val preparing = cloud.phase == CloudPhase.PREPARING
    val activeIndex = cloud.files.indexOfFirst { it.status == FileStatus.ACTIVE }.takeIf { it >= 0 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color.White.copy(alpha = 0.07f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke)
                )
                if (!preparing) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Secondary, PrimaryContainer, Color(0xFF7C3AED), Secondary)),
                        startAngle = -90f, sweepAngle = 360f * progress.coerceAtLeast(0.01f), useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (preparing) {
                    androidx.compose.material3.CircularProgressIndicator(color = Secondary, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                } else {
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge, color = OnSurface)
                    Icon(
                        if (cloud.waiting) Icons.Rounded.WifiOff else Icons.Rounded.CloudUpload, null,
                        tint = if (cloud.waiting) Error else Secondary, modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                preparing -> tr("Making files smaller…")
                cloud.paused -> tr("Paused")
                cloud.waiting -> tr("Waiting for internet…")
                else -> tr("Uploading")
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (cloud.waiting) Error else OnSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                preparing -> tr("This can take a moment for videos.")
                cloud.paused -> tr("Tap Resume to continue from where it stopped.")
                cloud.waiting -> tr("The upload will continue by itself.")
                else -> {
                    val fileLabel = activeIndex?.let { tr("File %d of %d", it + 1, cloud.files.size) } ?: tr("%d files", cloud.files.size)
                    "$fileLabel · ${Files.formatBytes(cloud.bytesDone)} / ${Files.formatBytes(cloud.totalBytes)}" +
                        if (cloud.speedBps > 0) " · ${Files.formatSpeed(cloud.speedBps)}" else ""
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr("You can leave the app. It keeps going."),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun CloudFileRow(file: TransferFileProgress) {
    val fraction = if (file.size > 0) (file.bytesDone.toFloat() / file.size).coerceIn(0f, 1f) else if (file.status == FileStatus.DONE) 1f else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKindBadge(name = file.name, mimeType = file.mimeType, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Secondary, PrimaryContainer)))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when (file.status) {
            FileStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = Tertiary, modifier = Modifier.size(20.dp))
            FileStatus.ACTIVE -> Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Secondary)
            else -> Text(Files.formatBytes(file.size), style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
        }
    }
}

@Composable
private fun ReadyCard(cloud: CloudShareUi) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    val qr = remember(cloud.url) { cloud.url?.let { QrCode.generate(it, 720) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Tertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = cloud.recipientName?.let { tr("Sent to %s!", it) } ?: tr("Ready! Send this code to anyone"),
                style = MaterialTheme.typography.titleSmall,
                color = Tertiary
            )
        }
        if (cloud.recipientName != null) {
            Text(
                text = tr("They'll get a notification. You can also share the code."),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PrimaryContainer.copy(alpha = 0.22f))
                .border(1.dp, Primary.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .clickable {
                    clipboard.setText(AnnotatedString(cloud.code.orEmpty()))
                    copied = true
                }
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(cloud.code.orEmpty(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = OnSurface)
            Spacer(Modifier.width(12.dp))
            Icon(
                if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = tr("Copy code"),
                tint = if (copied) Tertiary else Primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(tr("or let them scan"), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFF4F7FF))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = tr("Sendit download QR code"), modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.QrCode2, null, tint = Ink.copy(alpha = 0.4f), modifier = Modifier.size(100.dp))
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0xFFF4F7FF))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BeamLogo(size = 34.dp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val facts = buildList {
            add(
                when (cloud.downloads) {
                    0 -> tr("Not downloaded yet")
                    1 -> tr("Downloaded once")
                    else -> tr("Downloaded %d times", cloud.downloads)
                }
            )
            add(
                when (cloud.ttlHours) {
                    1 -> tr("works for 1 hour")
                    168 -> tr("works for 7 days")
                    else -> tr("works for 24 hours")
                }
            )
            cloud.releaseAt?.let { if (it > System.currentTimeMillis()) add(tr("opens %s", formatWhen(it))) }
            if (cloud.encrypted) add(tr("🔐 end-to-end encrypted"))
            if (cloud.oneTime) add(tr("deleted after first download"))
            if (cloud.hasPassword) add(tr("password protected"))
        }
        Text(
            text = facts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (cloud.downloads > 0) Tertiary else OnSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FailedCard(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .border(1.dp, Error.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Error.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Error, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("Upload failed"), style = MaterialTheme.typography.titleLarge, color = OnSurface)
        Spacer(Modifier.height(6.dp))
        Text(tr(error ?: "Something went wrong."), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = tr("Tip: on the same Wi-Fi? Use Nearby Wi-Fi. It doesn't need the internet."),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// Nearby Wi-Fi (phone to phone, nothing uploaded)
// ---------------------------------------------------------------------------

private fun LazyListScope.nearbyContent(current: ConnectSession, hotspot: com.beamdrop.app.net.HotspotInfo?, viewModel: BeamDropViewModel) {
    item { NearbyQrCard(current, hotspot) }
    item { HotspotCard(hotspot, viewModel) }
    if (current.localIp == null && hotspot == null) {
        item {
            InfoBanner(
                text = tr("This phone isn't on Wi-Fi. Connect both phones to the same Wi-Fi, or switch to Anywhere."),
                icon = Icons.Rounded.Wifi,
                tint = Error,
                background = Error.copy(alpha = 0.12f)
            )
        }
    }
    item {
        Text(
            text = tr("On the other phone: Receive → Scan QR code. Both phones must be on the same Wi-Fi."),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun NearbyQrCard(current: ConnectSession, hotspot: com.beamdrop.app.net.HotspotInfo?) {
    val content = if (hotspot != null) com.beamdrop.app.net.Hotspot.qrText(hotspot, current.url) else current.url
    val qr = remember(content) { QrCode.generate(content, 720) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceLow)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFF4F7FF))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = tr("Sendit Wi-Fi QR code"), modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF4F7FF))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BeamLogo(size = 40.dp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = Secondary, size = 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(tr("Waiting for a nearby phone to scan…"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
        }
    }
}

/** "No Wi-Fi around?" This phone makes its own small Wi-Fi; the QR code also tells the other phone how to join. */
@Composable
private fun HotspotCard(hotspot: com.beamdrop.app.net.HotspotInfo?, viewModel: BeamDropViewModel) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES
        else android.Manifest.permission.ACCESS_FINE_LOCATION
    val start = {
        error = null
        starting = true
        viewModel.startHotspot { msg -> starting = false; error = msg }
    }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) start() else error = tr("Sendit needs this permission to make a hotspot.") }
    LaunchedEffect(hotspot) { if (hotspot != null) starting = false }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceLow)
            .padding(16.dp)
    ) {
        if (hotspot == null) {
            Text(tr("No Wi-Fi here?"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
            Text(
                tr("This phone can make its own small Wi-Fi. No internet or router needed."),
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            BeamButton(
                text = if (starting) tr("Starting…") else tr("Make a hotspot"),
                onClick = {
                    if (starting) return@BeamButton
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) start() else askPermission.launch(permission)
                },
                icon = Icons.Rounded.Wifi,
                style = BeamButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(tr("Hotspot is on"), style = MaterialTheme.typography.titleSmall, color = Tertiary)
            Text(
                tr("The other phone scans the QR code and joins automatically (Android 10+). Or join this Wi-Fi by hand:"),
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(tr("Wi-Fi: %s", hotspot.ssid), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            if (hotspot.password.isNotEmpty()) Text(tr("Password: %s", hotspot.password), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Spacer(Modifier.height(10.dp))
            BeamButton(
                text = tr("Turn off hotspot"),
                onClick = { viewModel.stopHotspot() },
                style = BeamButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = Error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
