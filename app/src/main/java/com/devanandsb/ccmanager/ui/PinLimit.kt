package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.data.Card

/** How many cards can be pinned to the home stack (the stack shows three). */
const val MAX_PINS = 3

@Composable
fun ReplacePinDialog(card: Card, pinned: List<Card>, onReplace: (Long) -> Unit, onDismiss: () -> Unit) {
    if (LocalLocked.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Replace a pinned card", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "Up to $MAX_PINS cards can be pinned to the stack. Pick one to unpin for ${card.nickname}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pinned.forEach { p ->
                    ListRow(Icons.Outlined.PushPin, p.nickname, "${p.issuer} · •• ${p.last4}", onClick = { onReplace(p.id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
