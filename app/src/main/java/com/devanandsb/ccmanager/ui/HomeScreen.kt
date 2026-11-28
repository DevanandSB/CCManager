package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.data.Card as CardEntity
import com.devanandsb.ccmanager.data.CardKind
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import kotlinx.coroutines.launch

/** Bottom clearance for scrolling content. */
val FloatingBarClearance = 28.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    cards: List<CardWithDue>?,
    deviceSecure: Boolean,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onAddWith: (String?) -> Unit = { onAdd() },
    onEdit: (Long) -> Unit,
    onSettings: () -> Unit,
    onPin: suspend (CardEntity, Boolean, Long?) -> Boolean,
) {
    var sheetFor by remember { mutableStateOf<CardWithDue?>(null) }
    var replacing by remember { mutableStateOf<CardEntity?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val haptics = LocalHapticFeedback.current

    val locked = LocalLocked.current
    LaunchedEffect(locked) { if (locked) { sheetFor = null; replacing = null } }

    val replaySplash = LocalReplaySplash.current
    val personalizeOwner = LocalPersonalizeOwner.current
    val ownerName = LocalOwnerName.current
    val topBarEyebrow = timeOfDayGreeting().uppercase()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopAppBar(
                eyebrow = topBarEyebrow,
                title = ownerName,
                navigationContent = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(40.dp)
                            .then(
                                if (replaySplash != null) Modifier.clip(CircleShape).clickable(onClick = replaySplash)
                                else Modifier,
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = "Private Vault",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (personalizeOwner != null) {
                        VaultIconButton(
                            icon = Icons.Outlined.Person,
                            contentDescription = "Edit profile name",
                            onClick = personalizeOwner,
                        )
                    }
                    VaultIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        onClick = onSettings,
                    )
                    VaultIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "Add card",
                        onClick = onAdd,
                        filled = true,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            when {
                cards == null -> {}
                cards.isEmpty() -> EmptyVaultStage(
                    ownerName = ownerName,
                    deviceSecure = deviceSecure,
                    onAddWith = onAddWith,
                )
                else -> {
                    val credit = remember(cards) { cards.filter { it.card.isCredit } }
                    val kinds = remember(cards) { CardKind.all.filter { k -> cards.any { it.card.kind == k } } }
                    if (category != null && category !in kinds) category = null
                    val shown = remember(cards, category) { category?.let { k -> cards.filter { it.card.kind == k } } ?: cards }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 14.dp,
                            bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (!deviceSecure) {
                            item("lock_warning") {
                                Box(Modifier.padding(horizontal = Gutter)) { LockWarning() }
                            }
                        }

                        if (credit.isNotEmpty()) {
                            item("wealth_header") {
                                WealthEditorialHeader(credit, onOpen)
                            }
                        }

                        if (kinds.size > 1) {
                            item("kind_filter") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    VaultFilterChip(
                                        label = "All (${cards.size})",
                                        selected = category == null,
                                        onClick = { category = null },
                                    )
                                    kinds.forEach { k ->
                                        val count = cards.count { it.card.kind == k }
                                        VaultFilterChip(
                                            label = "${CardKind.label(k)} ($count)",
                                            selected = category == k,
                                            onClick = { category = k },
                                        )
                                    }
                                }
                            }
                        }

                        items(shown, key = { it.card.id }) { cw ->
                            val landing = FlipBack.pending?.takeIf { it.cardId == cw.card.id }
                            val mod = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Gutter)
                                .sharedCard(cw.card.id)
                                .combinedClickable(
                                    onClickLabel = "Open card",
                                    onLongClickLabel = "Card actions",
                                    onClick = { onOpen(cw.card.id) },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        sheetFor = cw
                                    },
                                )
                            if (landing != null) LandingFlip(cw, landing, mod)
                            else CardFace(cw, ownShadow = true, modifier = mod)
                        }

                        if (credit.isNotEmpty()) {
                            item("upcoming_section") {
                                UpcomingCarousel(credit, onOpen)
                            }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.takeIf { !locked }?.let { cw ->
        ModalBottomSheet(
            onDismissRequest = { sheetFor = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BankBadge(cw.card.issuer, size = 44.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(cw.card.nickname, style = MaterialTheme.typography.titleMedium)
                    Text("${cw.card.issuer} · •• ${cw.card.last4}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.padding(horizontal = Gutter), padded = false) {
                ListRow(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    "Open card",
                    onClick = { sheetFor = null; onOpen(cw.card.id) },
                )
                RowDivider()
                ListRow(
                    Icons.Outlined.PushPin,
                    if (cw.card.pinned) "Unpin from top" else "Pin to top",
                    if (cw.card.pinned) "Back to soonest-due order" else "Keep in your pinned $MAX_PINS cards",
                    onClick = {
                        val pin = !cw.card.pinned
                        sheetFor = null
                        val pinnedNow = cards.orEmpty().count { it.card.pinned }
                        if (pin && pinnedNow >= MAX_PINS) { haptic(Haptic.Reject); replacing = cw.card; return@ListRow }
                        scope.launch {
                            if (onPin(cw.card, pin, null)) {
                                haptic(Haptic.Confirm)
                                snackbar.showSnackbar(if (pin) "${cw.card.nickname} pinned to top" else "Unpinned")
                            } else {
                                haptic(Haptic.Reject)
                                replacing = cw.card
                            }
                        }
                    },
                )
                RowDivider()
                ListRow(
                    Icons.Outlined.Edit,
                    "Edit details",
                    onClick = { sheetFor = null; onEdit(cw.card.id) },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(20.dp))
        }
    }

    replacing?.let { card ->
        ReplacePinDialog(
            card, cards.orEmpty().map { it.card }.filter { it.pinned },
            onReplace = { id ->
                replacing = null
                scope.launch {
                    val ok = onPin(card, true, id)
                    haptic(if (ok) Haptic.Confirm else Haptic.Reject)
                    snackbar.showSnackbar(if (ok) "${card.nickname} pinned to top" else "Couldn't pin ${card.nickname}")
                }
            },
            onDismiss = { replacing = null },
        )
    }
}

@Composable
private fun WealthEditorialHeader(credit: List<CardWithDue>, onOpen: (Long) -> Unit) {
    val used = remember(credit) { credit.sumOf { it.totalUsedPaise } }
    val limit = remember(credit) { credit.sumOf { it.card.limitPaise.coerceAtLeast(0L) } }
    val usedOnLimited = remember(credit) { credit.filter { it.card.limitPaise > 0L }.sumOf { it.totalUsedPaise } }
    val next = remember(credit) {
        val dueCards = credit.filter { it.card.billingSet && !(it.due.lastCyclePaid && it.card.outstandingPaise <= 0L) }
        dueCards.filter { it.card.outstandingPaise > 0L }.minByOrNull { it.due.dueDate }
            ?: dueCards.minByOrNull { it.due.dueDate }
    }
    val frac = if (limit > 0L) (usedOnLimited.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    val utilPct = if (limit > 0L) ((usedOnLimited * 100L) / limit).toInt() else 0

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "TOTAL OUTSTANDING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Money.inr(used),
                style = MaterialTheme.typography.displayMedium.tabular,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (next != null) {
                val level = when {
                    next.due.overdue || next.due.daysLeft <= 2 -> UrgencyLevel.Urgent
                    next.due.daysLeft <= 5 -> UrgencyLevel.Warning
                    else -> UrgencyLevel.Neutral
                }
                Box(Modifier.padding(top = 4.dp).clip(CircleShape).clickable { onOpen(next.card.id) }) {
                    UrgencyBadge(
                        text = "Next: ${next.card.nickname} · Due ${Dates.short(next.due.dueDate)}",
                        level = level,
                        showDot = true,
                    )
                }
            }
        }
        if (limit > 0) {
            Spacer(Modifier.width(12.dp))
            CircularUtilizationGauge(
                fraction = frac,
                label = "$utilPct%",
                size = 64.dp,
            )
        }
    }
}

@Composable
private fun EmptyVaultStage(
    ownerName: String,
    deviceSecure: Boolean,
    onAddWith: (String?) -> Unit,
) {
    val hasCustomName = remember(ownerName) { isCustomOwnerName(ownerName) }
    val greetingName = remember(ownerName, hasCustomName) {
        if (!hasCustomName) DEFAULT_OWNER_NAME else ownerName.trim().substringBefore(' ')
    }
    val headline = if (hasCustomName) {
        "${timeOfDayGreeting()}, $greetingName.\nAdd your first card."
    } else {
        "Welcome, $greetingName.\nAdd your first card."
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Gutter,
                end = Gutter,
                top = 16.dp,
                bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!deviceSecure) {
            LockWarning()
            Spacer(Modifier.height(14.dp))
        }
        BlueprintCardStage(
            badgeText = "STORED ONLY ON YOUR PHONE",
            modifier = Modifier.height(190.dp),
            onClick = { onAddWith("scan") },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            headline,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "See due dates clearly, read monthly statements, and keep card numbers locked on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        CardLaunchpadDock(onAddWith)
        Spacer(Modifier.height(18.dp))
        val isDark = LocalIsDark.current
        val pillBg = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("Offline vault", "Bill reminders", "Statement reader", "One-tap reveal").forEach { tag ->
                Surface(
                    shape = CircleShape,
                    color = pillBg,
                    modifier = Modifier.border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape),
                ) {
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LockWarning() {
    Panel(padded = true, color = Amber.copy(alpha = 0.12f)) {
        Text(
            "No screen lock is set. Set a PIN, pattern or fingerprint in phone settings to lock CC Manager.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UpcomingCarousel(cards: List<CardWithDue>, onOpen: (Long) -> Unit) {
    val next = remember(cards) {
        cards.filter { it.card.billingSet && !(it.due.lastCyclePaid && it.card.outstandingPaise <= 0L) }.sortedBy { it.due.dueDate }.take(6)
    }
    if (next.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Upcoming bills",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${next.size} due soon",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(next, key = { it.card.id }) { cw ->
                val level = when {
                    cw.due.overdue || cw.due.daysLeft <= 2 -> UrgencyLevel.Urgent
                    cw.due.daysLeft <= 5 -> UrgencyLevel.Warning
                    else -> UrgencyLevel.Neutral
                }
                val accentColor = when (level) {
                    UrgencyLevel.Urgent -> Danger
                    UrgencyLevel.Warning -> Amber
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val dateColor = when (level) {
                    UrgencyLevel.Urgent -> Danger
                    UrgencyLevel.Warning -> Amber
                    else -> MaterialTheme.colorScheme.primary
                }
                val badgeText = when {
                    cw.due.overdue -> "Overdue"
                    cw.due.daysLeft == 0L -> "Due today"
                    else -> "${cw.due.daysLeft}d left"
                }

                VaultCardContainer(
                    modifier = Modifier.width(224.dp),
                    shape = MaterialTheme.shapes.large,
                    borderColor = accentColor.copy(alpha = 0.45f),
                    onClick = { onOpen(cw.card.id) },
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BankBadge(cw.card.issuer, size = 32.dp)
                        Spacer(Modifier.weight(1f))
                        UrgencyBadge(text = badgeText, level = level)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        Money.inr(cw.card.outstandingPaise),
                        style = MaterialTheme.typography.headlineMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cw.card.nickname,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Due ${Dates.short(cw.due.dueDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = dateColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    CardsTheme {
        val sampleCard = com.devanandsb.ccmanager.data.Card(
            id = 1L,
            nickname = "Regalia",
            issuer = "HDFC",
            network = "Visa",
            last4 = "4321",
            limitPaise = 10000000L,
            outstandingPaise = 150000L,
            color = 0xFF1E3A8A,
        )
        val sampleCardWithDue = CardWithDue(
            card = sampleCard,
            due = Billing.dueInfo(sampleCard, emptyList()),
        )
        HomeScreen(
            cards = listOf(sampleCardWithDue),
            deviceSecure = true,
            onOpen = {},
            onAdd = {},
            onEdit = {},
            onSettings = {},
            onPin = { _, _, _ -> true },
        )
    }
}
