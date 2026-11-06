package com.beamdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.api.Plan
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.DetailHeader
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Primary
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceLow
import com.beamdrop.app.ui.theme.Tertiary

/** Sendit Plus: my plan, what the plans give, how to buy, and "enter your code". */
@Composable
fun PlansScreen(viewModel: BeamDropViewModel, onBack: () -> Unit) {
    val info by viewModel.plans.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshPlans() }

    Column(Modifier.fillMaxSize().imePadding()) {
        DetailHeader(title = tr("Sendit Plus"), onBack = onBack)
        LazyColumn(
            modifier = Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val i = info
            if (i == null) {
                item { Text(tr("Loading…"), color = OnSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Primary.copy(alpha = 0.16f)).padding(18.dp)
                ) {
                    Text(tr("Your plan"), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                    Text(tr(i.current.name), style = MaterialTheme.typography.headlineSmall, color = OnSurface)
                    i.until?.let {
                        Text(tr("until %s", java.text.DateFormat.getDateInstance().format(java.util.Date(it))), style = MaterialTheme.typography.bodySmall, color = Tertiary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Limits(i.current)
                }
            }
            if (i.plans.isNotEmpty()) {
                item { Text(tr("Plans"), style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.padding(top = 6.dp)) }
                items(i.plans, key = { it.id }) { p ->
                    val mine = p.name == i.current.name
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(SurfaceLow)
                            .border(1.5.dp, if (mine) Secondary else Color.Transparent, RoundedCornerShape(22.dp)).padding(16.dp)
                    ) {
                        Row {
                            androidx.compose.material3.Icon(Icons.Rounded.Star, null, tint = Secondary)
                            Text(" " + p.name, style = MaterialTheme.typography.titleMedium, color = OnSurface, modifier = Modifier.weight(1f))
                            if (p.price.isNotBlank()) Text(p.price, style = MaterialTheme.typography.labelLarge, color = Secondary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Limits(p)
                        if (mine) Text(tr("✓ You have this plan"), style = MaterialTheme.typography.labelMedium, color = Tertiary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(SurfaceLow).padding(16.dp)) {
                    Text(tr("How to get it"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    Text(i.buyInfo.ifBlank { tr("Ask the Sendit team for a code.") }, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                    Text(tr("Have a code?"), style = MaterialTheme.typography.titleSmall, color = OnSurface, modifier = Modifier.padding(bottom = 8.dp))
                    BeamTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(20); result = null },
                        placeholder = "XXXX-XXXX-XXXX",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    BeamButton(
                        text = if (busy) tr("Checking…") else tr("Use code"),
                        onClick = {
                            if (busy || code.isBlank()) return@BeamButton
                            busy = true
                            viewModel.redeemCode(code) { plan, err ->
                                busy = false
                                result = if (plan != null) true to tr("🎉 Done! You now have %s.", plan) else false to tr(err ?: "Could not use the code")
                                if (plan != null) code = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    result?.let { (ok, msg) ->
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (ok) Tertiary else Error, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Limits(p: Plan) {
    val gb = { mb: Int -> if (mb >= 1024) tr("%s GB", "%.0f".format(mb / 1024.0)) else tr("%d MB", mb) }
    listOf(
        tr("💾 %s of storage", gb(p.quotaMb)),
        tr("📦 Files up to %s each", gb(p.maxFileMb)),
        tr("⏳ Links last up to %s", if (p.maxTtlHours >= 720) tr("30 days") else tr("7 days"))
    ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = OnSurface, modifier = Modifier.padding(top = 2.dp)) }
}
