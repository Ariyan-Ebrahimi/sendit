package com.beamdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beamdrop.app.api.Contact
import com.beamdrop.app.api.ContactGroup
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.theme.Error
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.PrimaryContainer
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceLow

@Composable
fun GroupRow(group: ContactGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Groups, null, tint = Secondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            Text(
                text = if (group.members.isEmpty()) tr("Nobody yet") else group.members.joinToString(", ") { it.name.substringBefore(' ') },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(if (group.members.size == 1) tr("1 person") else tr("%d people", group.members.size), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
    }
}

/** Create or edit a group: a name plus contacts ticked from the list. */
@Composable
fun GroupEditorDialog(
    group: ContactGroup?,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onSave: (name: String, emails: List<String>, done: (String?) -> Unit) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(group?.name ?: "") }
    var picked by remember { mutableStateOf(group?.members?.map { it.email.lowercase() }?.toSet() ?: emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SurfaceContainer,
        titleContentColor = OnSurface,
        title = { Text(if (group == null) tr("New group") else tr("Edit group")) },
        text = {
            Column {
                BeamTextField(value = name, onValueChange = { name = it.take(80); error = null }, placeholder = tr("Group name, e.g. Family"), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(tr("Who's in it?"), style = MaterialTheme.typography.titleSmall, color = OnSurface)
                Spacer(Modifier.height(6.dp))
                if (contacts.isEmpty()) {
                    Text(tr("Add contacts first, then put them in groups."), style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(contacts, key = { it.id }) { c ->
                        val on = c.email.lowercase() in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { picked = if (on) picked - c.email.lowercase() else picked + c.email.lowercase() }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { picked = if (it) picked + c.email.lowercase() else picked - c.email.lowercase() },
                                colors = CheckboxDefaults.colors(checkedColor = PrimaryContainer, uncheckedColor = OnSurfaceVariant, checkmarkColor = androidx.compose.ui.graphics.Color.White)
                            )
                            Column {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                Text(c.email, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (onDelete != null) {
                    Text(
                        text = tr("Delete group"),
                        style = MaterialTheme.typography.labelLarge,
                        color = Error,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (name.isBlank()) { error = tr("Give the group a name."); return@TextButton }
                busy = true
                onSave(name, picked.toList()) { err -> busy = false; error = err?.let { tr(it) } }
            }) { Text(tr("Save"), color = Secondary) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel"), color = OnSurfaceVariant) } }
    )
}
