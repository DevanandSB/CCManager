package com.devanandsb.ccmanager.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.reminders.Notifications
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BillRemindersScreen(
    sampleCard: CardWithDue?,
    sampleMinDuePaise: Long? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = LocalIsDark.current

    val card = sampleCard?.card
    val due = sampleCard?.due
    val issuer = card?.issuer ?: "Sample Bank"
    val nickname = card?.nickname ?: "Demo Card"
    val last4 = card?.last4 ?: "4321"
    val dueDate = due?.dueDate ?: LocalDate.of(2026, 12, 15)
    val daysLeft = due?.daysLeft ?: 2L
    val amountPaise = card?.outstandingPaise?.takeIf { it > 0L } ?: 339_662L
    val minPaise = sampleMinDuePaise?.takeIf { it > 0L }
        ?: if (card != null && card.outstandingPaise > 0L) (card.outstandingPaise / 20L).coerceAtLeast(10_000L) else 17_000L

    val totalText = Money.inr(amountPaise, true)
    val minText = Money.inr(minPaise, true)
    val dueLongText = Dates.long(dueDate)
    val badgeText = when {
        daysLeft < 0 -> "${-daysLeft}d overdue"
        daysLeft == 0L -> "Due today"
        else -> "${daysLeft}d left"
    }
    val headlineText = when {
        daysLeft < 0 -> "Bill overdue by ${-daysLeft} day${if (daysLeft == -1L) "" else "s"}"
        daysLeft == 0L -> "Bill due today"
        daysLeft == 1L -> "Bill due tomorrow"
        else -> "Bill due in $daysLeft days"
    }
    val urgent = daysLeft <= 3L

    val statusTime = remember {
        val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        "$nowTime · ${Dates.weekday(LocalDate.now())}"
    }

    var markedPaidPreview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            VaultTopAppBar(
                title = "Bill Reminders",
                onBack = onBack,
                actions = {
                    FilledTonalButton(
                        onClick = {
                            val sent = Notifications.sendTestPreview(context, sampleCard)
                            Toast.makeText(
                                context,
                                if (sent) "Test bill reminder sent to shade" else "Allow notifications in Settings first",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text("Send test", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter)
                .padding(top = 14.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Section 1: Android Notification Shade Preview
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android notification shade",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (isDark) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            0.75.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.40f else 0.50f),
                            MaterialTheme.shapes.extraLarge,
                        ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Android status strip
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                statusTime,
                                style = MaterialTheme.typography.labelMedium.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Icon(Icons.Filled.BatteryFull, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }

                        // Expanded Android BigPictureStyle Notification
                        VaultCardContainer(
                            shape = MaterialTheme.shapes.extraLarge,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Notification header
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.Lock,
                                                null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                        Text(
                                            "Private Vault · now",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                // Headline + description
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        headlineText,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "Your $nickname statement is ready for payment.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                // BigPicture Card Image inside Notification
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.large)
                                        .background(Brush.linearGradient(listOf(Color(0xFF282B33), Color(0xFF15171D))))
                                        .border(
                                            0.75.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                            MaterialTheme.shapes.large,
                                        )
                                        .padding(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    Modifier
                                                        .size(30.dp)
                                                        .clip(CircleShape)
                                                        .background(Color.White.copy(alpha = 0.14f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.AccountBalance,
                                                        null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        issuer,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.White.copy(alpha = 0.78f),
                                                    )
                                                    Text(
                                                        "$nickname •• $last4",
                                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = Color.White,
                                                    )
                                                }
                                            }
                                            UrgencyBadge(
                                                text = badgeText,
                                                level = if (urgent) UrgencyLevel.Urgent else UrgencyLevel.Good,
                                            )
                                        }

                                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f), thickness = 0.75.dp)

                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Bottom,
                                        ) {
                                            Column {
                                                Text(
                                                    "Due date",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White.copy(alpha = 0.72f),
                                                )
                                                Text(
                                                    dueLongText,
                                                    style = MaterialTheme.typography.titleSmall.tabular,
                                                    color = Color.White,
                                                )
                                            }
                                            Column {
                                                Text(
                                                    "Min due",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White.copy(alpha = 0.72f),
                                                )
                                                Text(
                                                    minText,
                                                    style = MaterialTheme.typography.titleSmall.tabular,
                                                    color = Color.White,
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    "Due amount",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White.copy(alpha = 0.72f),
                                                )
                                                Text(
                                                    totalText,
                                                    style = MaterialTheme.typography.headlineMedium.tabular,
                                                    color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                }

                                // Android Notification Action Buttons
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (markedPaidPreview) {
                                        Surface(
                                            shape = CircleShape,
                                            color = VaultStatus.goodContainer,
                                            modifier = Modifier.clickable { markedPaidPreview = false },
                                        ) {
                                            Text(
                                                "Marked paid",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = VaultStatus.onGoodContainer,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            )
                                        }
                                    } else {
                                        TextButton(
                                            onClick = { markedPaidPreview = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                "Mark as paid",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            Toast.makeText(context, "Snoozes reminder until evening or tomorrow morning", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            "Remind later",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Collapsed Notification Preview
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Collapsed view",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                VaultCardContainer(
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$nickname · $totalText",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold).tabular,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$issuer · Due $dueLongText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BillRemindersScreenPreview() {
    CardsTheme {
        BillRemindersScreen(sampleCard = null, onBack = {})
    }
}
