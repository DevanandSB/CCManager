package com.devanandsb.ccmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.LedgerDao
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.Issuers
import com.devanandsb.ccmanager.ingest.Passwords
import com.devanandsb.ccmanager.ingest.StatementSync
import com.devanandsb.ccmanager.security.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UploadUnlockStatementScreen(
    pendingKey: String,
    preferredCardId: Long?,
    initialNeedsCardPick: Boolean = false,
    ledger: Ledger,
    cards: List<Card>,
    onBack: () -> Unit,
    onImported: (StatementSync.Outcome) -> Unit,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val loading = LocalStatementLoading.current

    var activeKey by rememberSaveable(pendingKey) { mutableStateOf(pendingKey) }
    var fileName by rememberSaveable(pendingKey) {
        mutableStateOf(if (inspection) "HDFC_Infinia_Nov2026.pdf" else "statement.pdf")
    }
    var fileSizeBytes by rememberSaveable(pendingKey) {
        mutableIntStateOf(if (inspection) 421_888 else 0)
    }
    var needsPassword by rememberSaveable(pendingKey) { mutableStateOf(true) }
    var needsCardPick by rememberSaveable(pendingKey) { mutableStateOf(initialNeedsCardPick) }

    val creditCards = remember(cards) { cards.filter { it.isCredit && it.archivedAt == null } }
    var selectedCardId by rememberSaveable(pendingKey, preferredCardId) {
        mutableStateOf(preferredCardId?.takeIf { it > 0L })
    }
    val activeCard = remember(creditCards, selectedCardId) {
        creditCards.firstOrNull { it.id == selectedCardId }
    }

    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberForCard by rememberSaveable { mutableStateOf(true) }
    var applyToBank by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var savedCandidates by remember {
        mutableStateOf(if (inspection) listOf("USER0101") else emptyList())
    }
    var candidateIndex by remember { mutableIntStateOf(0) }
    var profileLocked by remember { mutableStateOf(false) }

    LaunchedEffect(activeKey, inspection) {
        if (inspection) return@LaunchedEffect
        val pending = withContext(Dispatchers.IO) { ledger.pendingPdf(activeKey) }
        val logEntry = withContext(Dispatchers.IO) { ledger.logEntry(activeKey) }
        if (pending != null) {
            fileName = pending.fileName.ifBlank { logEntry?.subject.orEmpty().ifBlank { "statement.pdf" } }
            fileSizeBytes = pending.bytes.size
        } else if (logEntry != null) {
            fileName = logEntry.subject.ifBlank { "statement.pdf" }
        }
        if (logEntry?.outcome == "unmatched") {
            needsPassword = false
            needsCardPick = true
        }
    }

    LaunchedEffect(selectedCardId, inspection) {
        if (inspection) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val m = ledger.profileSourceOnce()
            val reqAuth = ledger.profileRequiresAuth(m)
            val profile = runCatching { ledger.profileOrThrow(m) }.getOrNull()
            if (profile != null) {
                val last4s = buildList {
                    activeCard?.last4?.let { add(it) }
                }.ifEmpty { creditCards.map { it.last4 }.distinct() }
                val cands = Passwords.candidates(profile, last4s).distinct()
                savedCandidates = cands
                profileLocked = false
            } else {
                savedCandidates = emptyList()
                profileLocked = reqAuth && ledger.hasSavedProfile(m)
            }
        }
    }

    val inferredIssuer = remember(activeCard, fileName) {
        activeCard?.issuer?.takeIf { it != "Other" } ?: Issuers.detect(fileName)
    }

    val changePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                error = null
                val out = importUri(context, uri, activity, scope, loading)
                busy = false
                when {
                    out.statementId != null -> onImported(out)
                    out.code == "locked" -> {
                        activeKey = out.key
                        password = ""
                        needsPassword = true
                        needsCardPick = false
                    }
                    out.code == "unmatched" -> {
                        activeKey = out.key
                        password = ""
                        needsPassword = false
                        needsCardPick = true
                    }
                    else -> error = out.message
                }
            }
        }
    }

    val submitUnlock: () -> Unit = {
        scope.launch {
            busy = true
            error = null
            val runFix: suspend () -> StatementSync.Outcome = {
                loading?.begin(scope, fileName)
                var res: StatementSync.Outcome? = null
                try {
                    res = StatementSync.fix(
                        context = context,
                        key = activeKey,
                        password = password.takeIf { needsPassword },
                        cardId = selectedCardId,
                        remember = rememberForCard,
                        applyToBank = applyToBank,
                        strictPreferredCard = true,
                    )
                    res
                } finally {
                    loading?.end(scope, success = res?.statementId != null)
                }
            }
            val outcome = if (activity != null && needsPassword && (rememberForCard || applyToBank) && Auth.deviceSecure(context)) {
                Auth.withVault(activity, "Save statement password") {
                    runFix()
                } ?: StatementSync.Outcome("cancelled", "Verification cancelled.")
            } else {
                runFix()
            }
            busy = false
            when {
                outcome.statementId != null -> onImported(outcome)
                outcome.code == "locked" -> {
                    error = "That password didn't work. Please check and try again."
                }
                outcome.code == "unmatched" -> {
                    needsPassword = false
                    needsCardPick = true
                    error = "Choose which card this statement belongs to."
                }
                outcome.code == "cancelled" -> {
                    error = "Confirm screen lock to save this password, or turn off Remember password."
                }
                else -> error = outcome.message
            }
        }
    }

    val isDark = LocalIsDark.current
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
    val cardShape = RoundedCornerShape(24.dp)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopAppBar(
                title = "Import statement",
                subtitle = activeCard?.let { "${it.nickname} · •• ${it.last4}" }
                    ?: inferredIssuer?.let { "$it · Statement PDF" }
                    ?: "PDF statement",
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = Gutter, vertical = 14.dp),
                ) {
                    val ctaEnabled = !busy && (if (needsPassword) password.isNotBlank() else selectedCardId != null)
                    VaultPrimaryButton(
                        text = when {
                            busy -> "Unlocking & importing…"
                            needsPassword -> "Unlock & import statement"
                            else -> "Import statement"
                        },
                        icon = Icons.Outlined.LockOpen,
                        enabled = ctaEnabled,
                        onClick = submitUnlock,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter)
                .padding(top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Step 1: Uploaded PDF File Card
            Surface(
                shape = cardShape,
                color = cardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.75.dp, borderColor, cardShape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(VaultStatus.goodContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PictureAsPdf,
                            contentDescription = null,
                            tint = VaultStatus.onGoodContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            fileName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (fileSizeBytes > 0) "Uploaded · ${formatStatementFileSize(fileSizeBytes)}" else "Uploaded PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = Good,
                        )
                    }
                    Surface(
                        onClick = { if (!busy) changePicker.launch(arrayOf("application/pdf")) },
                        enabled = !busy,
                        shape = CircleShape,
                        color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            "Change",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // Step 2: Statement Password Card
            if (needsPassword) {
                Surface(
                    shape = cardShape,
                    color = cardBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.75.dp, borderColor, cardShape),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(VaultStatus.warnContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = VaultStatus.onWarnContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Enter statement password",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    bankStatementHint(inferredIssuer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "PDF password",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    error = null
                                },
                                placeholder = { Text("Enter PDF password") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                textStyle = MonoMedium,
                                isError = error != null,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = if (showPassword) "Hide password" else "Show password",
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (savedCandidates.isNotEmpty() || profileLocked) {
                            val currentCandidate = savedCandidates.getOrNull(candidateIndex % savedCandidates.size.coerceAtLeast(1))
                            Surface(
                                onClick = {
                                    if (profileLocked && activity != null) {
                                        scope.launch {
                                            val loadedCands = Auth.withVault(activity, "Use saved passwords") {
                                                withContext(Dispatchers.IO) {
                                                    val p = ledger.profileOrThrow(ledger.profileSourceOnce())
                                                    val l4s = activeCard?.last4?.let(::listOf) ?: creditCards.map { it.last4 }.distinct()
                                                    Passwords.candidates(p, l4s).distinct()
                                                }
                                            }.orEmpty()
                                            if (loadedCands.isNotEmpty()) {
                                                savedCandidates = loadedCands
                                                profileLocked = false
                                                candidateIndex = 0
                                                password = loadedCands.first()
                                                error = null
                                            }
                                        }
                                    } else if (currentCandidate != null) {
                                        password = currentCandidate
                                        candidateIndex = (candidateIndex + 1) % savedCandidates.size
                                        error = null
                                    }
                                },
                                shape = CircleShape,
                                color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.AutoFixHigh,
                                        contentDescription = null,
                                        tint = if (isDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        if (profileLocked) "Try saved password"
                                        else "Try saved pattern: $currentCandidate",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Optional card picker if PDF couldn't be matched automatically
            if (needsCardPick && creditCards.isNotEmpty()) {
                Surface(
                    shape = cardShape,
                    color = cardBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.75.dp, borderColor, cardShape),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Select card for this statement",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                        creditCards.forEachIndexed { idx, c ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    thickness = 0.7.dp,
                                    color = borderColor,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedCardId = c.id; error = null }
                                    .heightIn(min = 58.dp)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BankBadge(c.issuer, size = 34.dp)
                                Column(Modifier.weight(1f)) {
                                    Text("${c.nickname} · •• ${c.last4}", style = MaterialTheme.typography.titleSmall)
                                    Text(c.issuer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RadioButton(selected = selectedCardId == c.id, onClick = null)
                            }
                        }
                    }
                }
            }

            error?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }

            // Step 3: Password Remember Confirmation Card
            if (needsPassword) {
                Surface(
                    shape = cardShape,
                    color = cardBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.75.dp, borderColor, cardShape),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { rememberForCard = !rememberForCard }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Remember password for this card",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (inferredIssuer != null) {
                                        "Save this password on your phone so future $inferredIssuer statements open automatically."
                                    } else {
                                        "Save this password on your phone so future statements open automatically."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = rememberForCard,
                                onCheckedChange = { rememberForCard = it },
                            )
                        }

                        HorizontalDivider(
                            thickness = 0.7.dp,
                            color = borderColor,
                        )

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { applyToBank = !applyToBank }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (inferredIssuer != null) "Apply to all $inferredIssuer statements"
                                    else "Apply to all statements from this bank",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Also use this format for Gmail-fetched PDFs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = applyToBank,
                                onCheckedChange = { applyToBank = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatStatementFileSize(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = (bytes / 1024.0).toInt().coerceAtLeast(1)
    if (kb < 1024) return "$kb KB"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}

private fun bankStatementHint(issuer: String?): String = when (issuer) {
    "HDFC" -> "This PDF is protected. HDFC usually uses the first 4 letters of your name + DDMM (e.g. USER0101)."
    "ICICI" -> "This PDF is protected. ICICI usually uses the first 4 letters of your name in lowercase + DDMM (e.g. user0101)."
    "SBI" -> "This PDF is protected. SBI usually uses DDMMYYYY + the last 4 digits of your card, or your birth date."
    "Axis" -> "This PDF is protected. Axis usually uses the first 4 letters of your name in UPPERCASE + DDMM."
    "Kotak" -> "This PDF is protected. Kotak usually uses your name + birth date or CRN."
    "Amex" -> "This PDF is protected. American Express usually uses your PIN or birth date details."
    else -> "This PDF is protected. Check the bank's email for the password format (often first 4 letters of your name + birth date DDMM)."
}

@Preview(showBackground = true)
@Composable
fun UploadUnlockStatementScreenPreview() {
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

        UploadUnlockStatementScreen(
            pendingKey = "preview",
            preferredCardId = 1L,
            ledger = Ledger(ledgerDaoProxy) {},
            cards = listOf(
                Card(
                    id = 1L,
                    nickname = "HDFC Infinia",
                    issuer = "HDFC",
                    network = "Visa",
                    last4 = "4321",
                    limitPaise = 500_000_00L,
                    outstandingPaise = 42_000_00L,
                    statementDay = 15,
                    dueOffsetDays = 20,
                    color = 0xFF1E2027,
                ),
            ),
            onBack = {},
            onImported = {},
        )
    }
}
