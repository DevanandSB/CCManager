package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import java.time.Instant
import java.time.ZoneId

@Composable
fun ArchivedScreen(
    cards: List<CardWithDue>,
    onOpen: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf<CardWithDue?>(null) }
    Scaffold(
        topBar = {
            VaultTopAppBar(
                title = "Archived cards",
                onBack = onBack,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = 32.dp),
        ) {
            if (cards.isEmpty()) item {
                VaultCardContainer(
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Text(
                            "No archived cards",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Cards you archive from card details will stay safely stored here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else item {
                SectionHeader("${cards.size} archived")
                Panel(padded = false) {
                    cards.forEachIndexed { i, cw ->
                        val c = cw.card
                        if (i > 0) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(c.id) }
                                .heightIn(min = 62.dp)
                                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BankBadge(c.issuer, size = 36.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(c.nickname, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                val on = c.archivedAt?.let { Dates.short(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) }
                                Text(
                                    "${c.issuer} · •• ${c.last4}" + (on?.let { " · archived $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall.tabular,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRestore(c.id) }) { Icon(Icons.Outlined.Unarchive, "Restore ${c.nickname}") }
                            IconButton(onClick = { confirm = cw }) { Icon(Icons.Outlined.Delete, "Delete ${c.nickname}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { cw ->
        ConfirmDialog(
            title = "Delete ${cw.card.nickname}?",
            body = "The card, its stored number and CVV, statements and payment history are removed from this phone. This can't be undone.",
            onDismiss = { confirm = null },
            onConfirm = { confirm = null; onDelete(cw.card.id) },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ArchivedScreenPreview() {
    CardsTheme {
        ArchivedScreen(
            cards = emptyList(),
            onOpen = {},
            onRestore = {},
            onDelete = {},
            onBack = {},
        )
    }
}
