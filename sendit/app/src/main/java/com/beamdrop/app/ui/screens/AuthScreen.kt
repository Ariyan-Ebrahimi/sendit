package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.ui.components.BeamLogo
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.OutlineVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SendGradient
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import com.beamdrop.app.ui.theme.Violet

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun AuthScreen(
    viewModel: BeamDropViewModel,
    onSuccess: () -> Unit
) {
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    var forgot by rememberSaveable { mutableStateOf(false) }

    if (forgot) {
        ForgotPasswordFlow(viewModel = viewModel, initialEmail = email) { signInEmail ->
            forgot = false
            if (signInEmail != null) {
                email = signInEmail
                password = ""
                isSignUp = false
                errorMessage = null
            }
        }
        return
    }

    fun submit() {
        focus.clearFocus()
        errorMessage = when {
            isSignUp && name.isBlank() -> tr("Please tell us your name.")
            !EMAIL_REGEX.matches(email.trim()) -> tr("Please enter a valid email address.")
            password.length < 6 -> tr("Password must be at least 6 characters.")
            else -> null
        }
        if (errorMessage != null) return
        isLoading = true
        val done: (Boolean, String?) -> Unit = { ok, err ->
            isLoading = false
            if (ok) onSuccess() else errorMessage = tr(err ?: "Something went wrong. Please try again.")
        }
        if (isSignUp) viewModel.signUp(name.trim(), email.trim(), password, done)
        else viewModel.login(email.trim(), password, done)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                // Two soft glows give the page depth without an image asset.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(PrimaryContainer.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.08f),
                        radius = size.width * 0.85f
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(size.width * 0.15f, size.height * 0.08f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Secondary.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.92f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.95f, size.height * 0.92f)
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(44.dp))

            // ---------- Brand ----------
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(118.dp)
                        .background(
                            Brush.radialGradient(listOf(Violet.copy(alpha = 0.45f), Color.Transparent)),
                            CircleShape
                        )
                )
                BeamLogo(size = 76.dp)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Sendit",
                style = MaterialTheme.typography.displayMedium,
                color = OnSurface
            )
            Text(
                text = tr("Send anything, anywhere."),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.height(30.dp))

            // ---------- Mode switch ----------
            ModeSwitch(
                isSignUp = isSignUp,
                onChange = {
                    if (it != isSignUp) {
                        isSignUp = it
                        errorMessage = null
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ---------- Form card ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(SurfaceLow.copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                AnimatedContent(
                    targetState = isSignUp,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "authTitle"
                ) { signUp ->
                    Column {
                        Text(
                            text = if (signUp) tr("Create your account") else tr("Welcome back"),
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface
                        )
                        Text(
                            text = if (signUp) tr("It takes less than a minute.") else tr("Sign in to send and receive from anywhere."),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                AnimatedVisibility(
                    visible = isSignUp,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        AuthField(
                            label = tr("Name"),
                            value = name,
                            onValueChange = { name = it; errorMessage = null },
                            placeholder = tr("Your name"),
                            icon = Icons.Rounded.Person,
                            imeAction = ImeAction.Next,
                            onIme = { focus.moveFocus(FocusDirection.Down) }
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }

                AuthField(
                    label = tr("Email"),
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    placeholder = tr("you@example.com"),
                    icon = Icons.Rounded.AlternateEmail,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    onIme = { focus.moveFocus(FocusDirection.Down) }
                )

                Spacer(Modifier.height(14.dp))

                AuthField(
                    label = tr("Password"),
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = if (isSignUp) tr("At least 6 characters") else tr("Your password"),
                    icon = Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onIme = { submit() },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassword) tr("Hide password") else tr("Show password"),
                            tint = OnSurfaceVariant,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { showPassword = !showPassword }
                                .padding(8.dp)
                        )
                    }
                )

                AnimatedVisibility(visible = isSignUp && password.isNotEmpty()) {
                    PasswordStrength(password, Modifier.padding(top = 10.dp))
                }

                AnimatedVisibility(visible = errorMessage != null) {
                    Row(
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Error.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Error
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (!isSignUp) {
                    Text(
                        text = tr("Forgot password?"),
                        style = MaterialTheme.typography.labelLarge,
                        color = Secondary,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(CircleShape)
                            .clickable { forgot = true; errorMessage = null }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }

                SubmitButton(
                    text = if (isSignUp) tr("Create account") else tr("Sign in"),
                    loading = isLoading,
                    onClick = { if (!isLoading) submit() }
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { isSignUp = !isSignUp; errorMessage = null }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isSignUp) tr("Already have an account? ") else tr("New to Sendit? "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceVariant
                )
                Text(
                    text = if (isSignUp) tr("Sign in") else tr("Create one"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Secondary
                )
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ModeSwitch(isSignUp: Boolean, onChange: (Boolean) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(SurfaceContainer)
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
            .padding(4.dp)
    ) {
        val half = maxWidth / 2
        val offset by animateDpAsState(
            targetValue = if (isSignUp) half else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "modeIndicator"
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
            listOf(false to tr("Sign in"), true to tr("Sign up")).forEach { (mode, label) ->
                val color by animateColorAsState(
                    if (mode == isSignUp) Color.White else OnSurfaceVariant,
                    label = "modeText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onChange(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

@Composable
private fun AuthField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onIme: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (focused) Secondary.copy(alpha = 0.85f) else OutlineVariant.copy(alpha = 0.5f),
        label = "fieldBorder"
    )
    val iconTint by animateColorAsState(if (focused) Secondary else OnSurfaceVariant, label = "fieldIcon")

    Column(Modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnSurface),
            cursorBrush = SolidColor(Secondary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Ink.copy(alpha = 0.55f))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 14.dp, end = if (trailing != null) 6.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            }
        )
    }
}

@Composable
private fun PasswordStrength(password: String, modifier: Modifier = Modifier) {
    var score = 0
    if (password.length >= 6) score++
    if (password.length >= 10) score++
    if (password.any { it.isDigit() } && password.any { it.isLetter() }) score++
    if (password.any { !it.isLetterOrDigit() } || (password.any { it.isUpperCase() } && password.any { it.isLowerCase() })) score++
    val (label, color) = when (score) {
        0, 1 -> tr("Weak") to Error
        2 -> tr("Okay") to Color(0xFFFBBF24)
        3 -> tr("Good") to Secondary
        else -> tr("Strong") to Tertiary
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                val active = i < score.coerceAtLeast(1)
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (active) color else Color.White.copy(alpha = 0.08f))
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun SubmitButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(SendGradient))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(targetState = loading, label = "submitContent") { busy ->
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
