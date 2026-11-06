package com.beamdrop.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary
import kotlinx.coroutines.launch

/** Forgot password: 1) email → code is sent, 2) code + new password. [onDone] gets the email to sign in with. */
@Composable
fun ForgotPasswordFlow(
    viewModel: BeamDropViewModel,
    initialEmail: String,
    onDone: (email: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(1) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var emailed by rememberSaveable { mutableStateOf(true) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    BackHandler { if (step == 2) step = 1 else onDone(null) }

    fun sendCode() {
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())) {
            error = tr("Please enter a valid email address.")
            return
        }
        busy = true
        error = null
        scope.launch {
            viewModel.forgotPassword(email.trim())
                .onSuccess { emailed = it; step = 2 }
                .onFailure { error = tr(it.message ?: "Something went wrong.") }
            busy = false
        }
    }

    fun reset() {
        error = when {
            code.trim().length != 6 -> tr("The code has 6 digits.")
            password.length < 6 -> tr("Password must be at least 6 characters.")
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            viewModel.resetPassword(email.trim(), code.trim(), password)
                .onSuccess { step = 3 }
                .onFailure { error = tr(it.message ?: "Something went wrong.") }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(SurfaceLow),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.LockReset, null, tint = if (step == 3) Tertiary else Secondary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))

        when (step) {
            1 -> {
                Title(tr("Forgot your password?"), tr("Enter your email and we'll send you a 6-digit code."))
                BeamTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    placeholder = tr("you@example.com"),
                    leadingIcon = Icons.Rounded.AlternateEmail,
                    keyboardType = KeyboardType.Email,
                    onImeAction = ::sendCode,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            2 -> {
                Title(
                    tr("Enter the code"),
                    if (emailed) tr("We sent a 6-digit code to %s. It works for 15 minutes.", email.trim())
                    else tr("Email isn't set up on this server. Ask the Sendit admin for your 6-digit code.")
                )
                BeamTextField(
                    value = code,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) { code = v; error = null } },
                    placeholder = tr("6-digit code"),
                    leadingIcon = Icons.Rounded.Key,
                    keyboardType = KeyboardType.NumberPassword,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                BeamTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    placeholder = tr("New password (at least 6 characters)"),
                    leadingIcon = Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                    onImeAction = ::reset,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> Title(tr("Password changed"), tr("You can sign in with your new password now."))
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(22.dp))

        if (busy) {
            CircularProgressIndicator(color = Secondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    1 -> BeamButton(tr("Send code"), onClick = ::sendCode, modifier = Modifier.fillMaxWidth(), height = 56.dp)
                    2 -> {
                        BeamButton(tr("Set new password"), onClick = ::reset, modifier = Modifier.fillMaxWidth(), height = 56.dp)
                        BeamButton(tr("Send a new code"), onClick = ::sendCode, modifier = Modifier.fillMaxWidth(), style = BeamButtonStyle.Ghost)
                    }
                    else -> BeamButton(tr("Sign in"), onClick = { onDone(email.trim()) }, modifier = Modifier.fillMaxWidth(), height = 56.dp)
                }
                if (step != 3) {
                    BeamButton(tr("Back to sign in"), onClick = { onDone(null) }, modifier = Modifier.fillMaxWidth(), style = BeamButtonStyle.Ghost)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = OnSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(22.dp))
}
