package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.LedgerDao
import com.devanandsb.ccmanager.ingest.PdfProfile
import com.devanandsb.ccmanager.ingest.RememberedPdfPassword
import com.devanandsb.ccmanager.security.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PasswordsScreen(
    ledger: Ledger,
    cards: List<Card> = emptyList(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val activity = context as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val deviceSecure = remember(context, inspection) { inspection || Auth.deviceSecure(context) }

    var unlocked by remember { mutableStateOf(!deviceSecure || inspection) }
    var loaded by remember { mutableStateOf(inspection) }
    var authError by remember { mutableStateOf<String?>(null) }
    var unlockAttempt by remember { mutableIntStateOf(0) }

    var baseProfile by remember { mutableStateOf(PdfProfile()) }
    var entries by remember {
        mutableStateOf(
            if (inspection) {
                listOf(
                    RememberedPdfPassword("USER0101", cardId = 1L, cardLabel = "HDFC Infinia · •• 1234"),
                    RememberedPdfPassword("user0101", cardId = 2L, cardLabel = "Amazon Pay ICICI · •• 5678"),
                )
            } else {
                emptyList()
            },
        )
    }
    var revealedKeys by remember { mutableStateOf(setOf<String>()) }
    var assignTargetIndex by remember { mutableStateOf<Int?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    val activeCards = remember(cards) {
        cards.filter { it.archivedAt == null }.sortedBy { it.nickname.lowercase() }
    }
    val cardsById = remember(cards) { cards.associateBy { it.id } }

    fun extractEntries(p: PdfProfile): Pair<PdfProfile, List<RememberedPdfPassword>> {
        val patternTemplates = p.templates.filter { it.contains('{') }
        val linkedPws = p.cardPasswords.map { it.password }.toSet()
        val legacyUnlinked = p.templates
            .filter { it.isNotBlank() && !it.contains('{') && it !in linkedPws }
            .distinct()
            .map { RememberedPdfPassword(password = it) }
        val combined = (p.cardPasswords + legacyUnlinked)
            .distinctBy { it.password to it.cardId }
        return p.copy(templates = patternTemplates) to combined
    }

    fun wipeState() {
        baseProfile = PdfProfile()
        entries = emptyList()
        revealedKeys = emptySet()
        assignTargetIndex = null
        showAddSheet = false
        loaded = false
        if (deviceSecure) unlocked = false
    }

    if (!inspection) {
        DisposableEffect(lifecycleOwner, deviceSecure) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && deviceSecure && !Auth.inProgress) {
                    wipeState()
                    authError = null
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                wipeState()
            }
        }

        LaunchedEffect(unlockAttempt) {
            if (loaded) return@LaunchedEffect
            if (deviceSecure && activity != null) {
                authError = null
                val profileOrNull = Auth.withVault(activity, "Unlock statement passwords") {
                    withContext(Dispatchers.IO) {
                        val m = ledger.profileSourceOnce()
                        val p = ledger.profileOrThrow(m)
                        if (ledger.hasSavedProfile(m) && !ledger.profileRequiresAuth(m)) {
                            ledger.saveProfile(p, requireAuth = true)
                        }
                        p
                    }
                }
                if (profileOrNull == null) {
                    unlocked = false
                    loaded = false
                    authError = "Verify fingerprint, face, or device PIN to view saved statement passwords."
                    return@LaunchedEffect
                }
                val (cleanBase, combined) = extractEntries(profileOrNull)
                baseProfile = cleanBase
                entries = combined
                unlocked = true
                loaded = true
            } else {
                val p = withContext(Dispatchers.IO) { ledger.profile(ledger.profileSourceOnce()) }
                val (cleanBase, combined) = extractEntries(p)
                baseProfile = cleanBase
                entries = combined
                unlocked = true
                loaded = true
            }
        }
    }

    fun persistEntries(next: List<RememberedPdfPassword>, toastText: String) = scope.launch {
        if (inspection) {
            entries = next
            return@launch
        }
        val updated = baseProfile.copy(cardPasswords = next)
        val ok = runCatching {
            withContext(Dispatchers.IO) { ledger.saveProfile(updated, requireAuth = deviceSecure) }
            true
        }.getOrElse {
            if (deviceSecure && activity != null) {
                Auth.withVault(activity, "Confirm updating saved passwords") {
                    withContext(Dispatchers.IO) { ledger.saveProfile(updated, requireAuth = true) }
                    true
                } ?: false
            } else {
                false
            }
        }
        if (ok) {
            entries = next
            android.widget.Toast.makeText(context, toastText, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Authorization needed to update", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopAppBar(
                title = "PDF Passwords",
                onBack = onBack,
                actions = {
                    if (unlocked && loaded && deviceSecure) {
                        val isDark = LocalIsDark.current
                        Surface(
                            onClick = { wipeState(); authError = null },
                            shape = CircleShape,
                            color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                Text("Lock view", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (!unlocked || !loaded) {
            Box(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = Gutter),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Protected by device security",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            authError ?: "Use fingerprint or face unlock to view saved statement passwords.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VaultTonalButton(
                            text = "Back",
                            onClick = onBack,
                            modifier = Modifier.weight(1f),
                        )
                        VaultPrimaryButton(
                            text = "Unlock",
                            onClick = { unlockAttempt++ },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter)
                .padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Privacy status banner matching Stitch pdf_passwords_security/code.html
            val status = VaultStatus
            VaultCardContainer(
                containerColor = if (deviceSecure) status.goodContainer else status.warnContainer,
                borderColor = (if (deviceSecure) status.good else status.warn).copy(alpha = 0.25f),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = if (deviceSecure) status.onGoodContainer else status.onWarnContainer,
                        modifier = Modifier.size(22.dp).padding(top = 1.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (deviceSecure) "Locked safely on your phone" else "Device lock is off",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (deviceSecure) status.onGoodContainer else status.onWarnContainer,
                        )
                        Text(
                            if (deviceSecure) {
                                "Only you can open these details with your fingerprint or phone PIN."
                            } else {
                                "Set a PIN, pattern, or fingerprint lock in Android Settings to protect these passwords."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (deviceSecure) status.onGoodContainer.copy(alpha = 0.88f) else status.onWarnContainer.copy(alpha = 0.88f),
                        )
                    }
                }
            }

            // Remembered passwords grouped clearly with the card they belong to
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Remembered passwords",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "Passwords saved safely for each card's statement PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        onClick = { showAddSheet = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                        modifier = Modifier.border(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text("Add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (entries.isEmpty()) {
                    VaultCardContainer {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
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
                                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Text(
                                "No saved statement passwords yet",
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "When you unlock a card's PDF statement, its password is stored here linked to that card.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            VaultTonalButton(
                                text = "Add card password",
                                icon = Icons.Filled.Add,
                                onClick = { showAddSheet = true },
                            )
                        }
                    }
                } else {
                    Panel(padded = false) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) RowDivider()
                            val card = entry.cardId?.let { cardsById[it] }
                            val key = "${entry.cardId ?: "none"}:${entry.password}:$index"
                            val shown = key in revealedKeys
                            val cardTitle = when {
                                card != null -> "${card.nickname} · •• ${card.last4}"
                                entry.cardLabel.isNotBlank() -> entry.cardLabel
                                else -> "Unlinked PDF password"
                            }
                            val cardSub = when {
                                card != null -> card.issuer
                                entry.cardLabel.isNotBlank() -> "Saved card"
                                activeCards.isNotEmpty() -> "Tap to link a card"
                                else -> "Saved from statement unlock"
                            }

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (activeCards.isNotEmpty() || cards.isNotEmpty()) {
                                            Modifier.clickable { assignTargetIndex = index }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .heightIn(min = 68.dp)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (card != null) {
                                    BankBadge(card.issuer, size = 38.dp)
                                } else {
                                    Box(
                                        Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Key,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                }

                                Column(
                                    Modifier.weight(1f).padding(start = 14.dp, end = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        cardTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        cardSub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (card == null && entry.cardLabel.isBlank() && activeCards.isNotEmpty()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (shown) entry.password else "•".repeat(entry.password.length.coerceIn(6, 12)),
                                        style = MonoSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            revealedKeys = if (shown) revealedKeys - key else revealedKeys + key
                                        },
                                    ) {
                                        Icon(
                                            if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            if (shown) "Hide" else "Reveal",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val next = entries.toMutableList().apply { removeAt(index) }
                                            revealedKeys -= key
                                            persistEntries(next, "Password removed")
                                        },
                                    ) {
                                        Icon(Icons.Outlined.Delete, "Forget", tint = Danger)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    assignTargetIndex?.let { idx ->
        val current = entries.getOrNull(idx)
        if (current != null) {
            val allSelectable = cards.sortedWith(compareBy<Card> { it.archivedAt != null }.thenBy { it.nickname.lowercase() })
            EditSheet(
                title = "Which card is this for?",
                note = "Link this remembered PDF password to the card whose statement it unlocks.",
                onDismiss = { assignTargetIndex = null },
                confirmLabel = "Done",
                onConfirm = { assignTargetIndex = null },
            ) {
                if (allSelectable.isEmpty()) {
                    Text(
                        "Add a card to your wallet first to link this password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Panel(padded = false) {
                        allSelectable.forEachIndexed { i, c ->
                            if (i > 0) RowDivider()
                            val selected = current.cardId == c.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        assignTargetIndex = null
                                        val next = entries.toMutableList()
                                        next[idx] = current.copy(
                                            cardId = c.id,
                                            cardLabel = "${c.nickname} · •• ${c.last4}",
                                        )
                                        persistEntries(next.distinctBy { it.password to it.cardId }, "Linked to ${c.nickname}")
                                    }
                                    .heightIn(min = 58.dp)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BankBadge(c.issuer, size = 34.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("${c.nickname} · •• ${c.last4}", style = MaterialTheme.typography.titleSmall)
                                    Text(c.issuer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) {
                                    Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        val allSelectable = cards.sortedWith(compareBy<Card> { it.archivedAt != null }.thenBy { it.nickname.lowercase() })
        var selectedCardId by remember { mutableStateOf(allSelectable.firstOrNull()?.id) }
        var passwordInput by remember { mutableStateOf("") }
        var showNewPw by remember { mutableStateOf(false) }

        EditSheet(
            title = "Add statement password",
            note = "Save a PDF password linked to a specific card so statements unlock automatically.",
            onDismiss = { showAddSheet = false },
            confirmLabel = "Save password",
            confirmEnabled = passwordInput.isNotBlank() && (allSelectable.isEmpty() || selectedCardId != null),
            onConfirm = {
                val chosen = selectedCardId?.let { cardsById[it] }
                val pw = passwordInput.trim()
                showAddSheet = false
                if (pw.isNotBlank()) {
                    val newEntry = RememberedPdfPassword(
                        password = pw,
                        cardId = chosen?.id,
                        cardLabel = chosen?.let { "${it.nickname} · •• ${it.last4}" }.orEmpty(),
                    )
                    val filtered = entries.filterNot { it.password == pw && (it.cardId == chosen?.id || it.cardId == null) }
                    persistEntries(listOf(newEntry) + filtered, "Saved safely")
                }
            },
        ) {
            if (allSelectable.isNotEmpty()) {
                Text("Select card", style = MaterialTheme.typography.labelLarge)
                Panel(padded = false) {
                    allSelectable.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        val sel = c.id == selectedCardId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedCardId = c.id }
                                .heightIn(min = 54.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BankBadge(c.issuer, size = 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${c.nickname} · •• ${c.last4}", style = MaterialTheme.typography.titleSmall)
                                Text(c.issuer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (sel) {
                                Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it },
                label = { Text("Statement PDF password") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = MonoMedium,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showNewPw) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showNewPw = !showNewPw }) {
                        Icon(if (showNewPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showNewPw) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PasswordsScreenPreview() {
    CardsTheme {
        val ledgerDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            LedgerDao::class.java.classLoader,
            arrayOf(LedgerDao::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Flow::class.java -> flowOf(emptyList<Any>())
                List::class.java -> emptyList<Any>()
                else -> null
            }
        } as LedgerDao
        val dummyLedger = Ledger(ledgerDaoProxy) { _ -> }

        PasswordsScreen(
            ledger = dummyLedger,
            cards = listOf(
                Card(id = 1L, nickname = "HDFC Infinia", issuer = "HDFC Bank", network = "Visa", last4 = "1234", limitPaise = 50000000L, outstandingPaise = 0L, color = 0xFF004C8F),
                Card(id = 2L, nickname = "Amazon Pay", issuer = "ICICI Bank", network = "Visa", last4 = "5678", limitPaise = 25000000L, outstandingPaise = 0L, color = 0xFFB02A30),
            ),
            onBack = {},
        )
    }
}
