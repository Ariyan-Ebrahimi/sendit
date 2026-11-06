package com.beamdrop.app.ui.components

import com.beamdrop.app.i18n.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beamdrop.app.ui.theme.BeamGradient
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.OutlineVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.ReceiveGradient
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceHigh
import com.beamdrop.app.ui.theme.SurfaceHighest
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.util.FileKind
import com.beamdrop.app.util.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun BeamBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        content()
    }
}

/** The Sendit mark: an "S" that finishes as a send arrow, starting from a cyan dot. Same shape as the launcher icon. */
val SenditMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "SenditMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 54f,
        viewportHeight = 54f
    ).apply {
        // Launcher icon content sits in 108-unit space around (54,54); show its middle 54x54 square.
        group(translationX = -27f, translationY = -27f) {
            addPath(
                pathData = addPathNodes("M43,75 H65 A9,9 0 0 0 65,57 H53 A9,9 0 0 1 53,39 H72 M66,33 L72,39 L66,45"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
            addPath(
                pathData = addPathNodes("M35,75 m-4,0 a4,4 0 1,0 8,0 a4,4 0 1,0 -8,0"),
                fill = SolidColor(Secondary)
            )
        }
    }.build()
}

@Composable
fun BeamLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF6D4BEA), Color(0xFF7C3AED))))
            .background(
                Brush.radialGradient(
                    colors = listOf(Secondary.copy(alpha = 0.40f), Color.Transparent),
                    center = Offset(0f, Float.POSITIVE_INFINITY),
                    radius = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() * 0.9f }
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            imageVector = SenditMark,
            contentDescription = "Sendit",
            modifier = Modifier.size(size * 0.86f)
        )
    }
}

@Composable
fun DeviceAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "B"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(SurfaceHigh, SurfaceContainer)))
            .border(1.dp, Primary.copy(alpha = 0.35f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Primary,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp
            )
        )
    }
}

@Composable
fun StatusDot(
    color: Color = Tertiary,
    size: Dp = 8.dp,
    pulsing: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val transition = rememberInfiniteTransition(label = "dot")
        val alpha by transition.animateFloat(
            initialValue = if (pulsing) 0.15f else 0f,
            targetValue = if (pulsing) 0.65f else 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        if (pulsing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
        Box(
            Modifier
                .fillMaxSize(0.58f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun BrandHeader(
    name: String,
    onAvatarClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BeamLogo(36.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Sendit",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        DeviceAvatar(name = name, size = 40.dp, onClick = onAvatarClick)
    }
}

@Composable
fun DetailHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            BeamIconButton(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = tr("Back"),
                onClick = onBack,
                size = 44.dp,
                background = Color.Transparent
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = OnSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null) {
            Text(
                text = actionText,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = Primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Secondary,
    background: Color = SurfaceHigh,
    icon: ImageVector? = null,
    dotColor: Color? = null
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(5.dp))
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = color,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

enum class BeamButtonStyle { Primary, Receive, Tonal, Ghost, Danger }

@Composable
fun BeamButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BeamButtonStyle = BeamButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
        label = "buttonScale"
    )
    val background: Brush? = when (style) {
        BeamButtonStyle.Primary -> Brush.linearGradient(SendGradient)
        BeamButtonStyle.Receive -> Brush.linearGradient(ReceiveGradient)
        BeamButtonStyle.Tonal -> null
        BeamButtonStyle.Ghost -> null
        BeamButtonStyle.Danger -> null
    }
    val backgroundColor = when (style) {
        BeamButtonStyle.Tonal -> SurfaceHigh
        BeamButtonStyle.Ghost -> Color.Transparent
        BeamButtonStyle.Danger -> Error.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val contentColor = when (style) {
        BeamButtonStyle.Primary -> Color.White
        BeamButtonStyle.Receive -> Ink
        BeamButtonStyle.Tonal -> OnSurface
        BeamButtonStyle.Ghost -> OnSurfaceVariant
        BeamButtonStyle.Danger -> Error
    }
    val shape = CircleShape

    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (background != null) Modifier.background(background) else Modifier
            )
            .then(
                if (style == BeamButtonStyle.Ghost || style == BeamButtonStyle.Tonal) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun BeamIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = SurfaceHigh,
    tint: Color = OnSurface
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}

@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    brush: Brush = Brush.horizontalGradient(BeamGradient)
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(SurfaceHighest)
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(height)
                    .clip(CircleShape)
                    .background(brush)
            )
        }
    }
}

@Composable
fun FileKindBadge(
    name: String,
    mimeType: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val kind = Files.fileKind(name, mimeType)
    FileKindBadge(kind = kind, modifier = modifier, size = size)
}

@Composable
fun FileKindBadge(kind: FileKind, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val color = when (kind) {
        FileKind.IMAGE -> Primary
        FileKind.VIDEO -> Secondary
        FileKind.AUDIO -> Secondary
        FileKind.PDF -> Error
        FileKind.APK -> Tertiary
        FileKind.ARCHIVE -> Primary
        FileKind.DOCUMENT -> Secondary
        FileKind.GENERIC -> OnSurfaceVariant
    }
    val imageVector = when (kind) {
        FileKind.IMAGE -> Icons.Rounded.Image
        FileKind.VIDEO -> Icons.Rounded.Movie
        FileKind.AUDIO -> Icons.Rounded.Audiotrack
        FileKind.PDF -> Icons.Rounded.PictureAsPdf
        FileKind.APK -> Icons.Rounded.Android
        FileKind.ARCHIVE -> Icons.Rounded.FolderZip
        FileKind.DOCUMENT -> Icons.Rounded.Description
        FileKind.GENERIC -> Icons.Rounded.InsertDriveFile
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(SurfaceContainer)
                .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = OnSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            BeamButton(
                text = actionText,
                onClick = onAction,
                style = BeamButtonStyle.Tonal,
                icon = Icons.Rounded.Add,
                height = 46.dp
            )
        }
    }
}

@Composable
fun RowScope.BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = if (selected) Primary else OnSurfaceVariant,
        animationSpec = tween(200),
        label = "navTint"
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(27.dp)
                .clip(CircleShape)
                .background(if (selected) Primary.copy(alpha = 0.20f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint,
            fontSize = 10.sp
        )
    }
}

@Composable
fun BeamBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp),
            shape = CircleShape,
            color = SurfaceContainer.copy(alpha = 0.94f),
            shadowElevation = 24.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    icon = Icons.Rounded.GridView,
                    label = tr("Home"),
                    selected = currentRoute == "home",
                    onClick = { onNavigate("home") }
                )
                BottomNavItem(
                    icon = Icons.Rounded.Schedule,
                    label = tr("History"),
                    selected = currentRoute == "history",
                    onClick = { onNavigate("history") }
                )
                BottomNavItem(
                    icon = Icons.Rounded.Settings,
                    label = tr("Settings"),
                    selected = currentRoute == "profile",
                    onClick = { onNavigate("profile") }
                )
            }
        }
    }
}

@Composable
fun RadarGraphic(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    peerCount: Int = 0
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarSweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f - 2.dp.toPx()
        for (i in 1..4) {
            drawCircle(
                color = Secondary.copy(alpha = 0.08f + 0.02f * i),
                radius = maxRadius * i / 4f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        drawCircle(
            color = Secondary.copy(alpha = if (active) 0.14f else 0.05f),
            radius = maxRadius,
            center = center
        )
        if (active) {
            rotate(degrees = sweep, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Secondary.copy(alpha = 0.32f),
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = -26f,
                    sweepAngle = 52f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = Size(maxRadius * 2, maxRadius * 2)
                )
            }
        }
        drawCircle(
            color = Secondary.copy(alpha = pulse),
            radius = 3.5.dp.toPx(),
            center = center
        )
        val angles = listOf(42f, 150f, 265f)
        repeat(peerCount.coerceAtMost(3)) { index ->
            val angle = Math.toRadians(angles[index].toDouble())
            val distance = maxRadius * (0.42f + 0.14f * index)
            drawCircle(
                color = if (index == 0) Tertiary else Secondary,
                radius = 3.5.dp.toPx(),
                center = Offset(
                    center.x + (Math.cos(angle) * distance).toFloat(),
                    center.y + (Math.sin(angle) * distance).toFloat()
                )
            )
        }
    }
}

@Composable
fun BeamTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(SurfaceLow)
            .border(1.dp, OutlineVariant.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 16.dp),
        textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
            color = OnSurface
        ),
        singleLine = singleLine,
        cursorBrush = SolidColor(Primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
            onSearch = { onImeAction?.invoke() }
        ),
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        }
    )
}

@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Secondary,
    background: Color = SurfaceContainer
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ColumnScope.DividerLine() {
    Spacer(Modifier.height(1.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.055f))
    )
    Spacer(Modifier.height(1.dp))
}

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    if (diff < TimeUnit.MINUTES.toMillis(1)) return tr("Just now")
    if (diff < TimeUnit.HOURS.toMillis(1)) return tr("%dm ago", TimeUnit.MILLISECONDS.toMinutes(diff))
    if (diff < TimeUnit.DAYS.toMillis(1)) return tr("%dh ago", TimeUnit.MILLISECONDS.toHours(diff))
    if (diff < TimeUnit.DAYS.toMillis(7)) return tr("%dd ago", TimeUnit.MILLISECONDS.toDays(diff))
    return SimpleDateFormat("MMM d", Locale.US).format(Date(timestamp))
}

fun etaText(remainingBytes: Long, speedBps: Long): String {
    if (remainingBytes <= 0L) return tr("Done")
    if (speedBps <= 0L) return "—"
    val seconds = (remainingBytes / speedBps).coerceAtLeast(1L)
    return when {
        seconds < 60 -> tr("%ds", seconds)
        seconds < 3600 -> tr("%dm %ds", seconds / 60, seconds % 60)
        else -> tr("%dh %dm", seconds / 3600, (seconds % 3600) / 60)
    }
}

@Composable
fun CompactTransferRow(
    title: String,
    subtitle: String,
    trailing: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileKindBadge(
            kind = FileKind.GENERIC,
            size = 42.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = trailing,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
