package com.devanandsb.ccmanager.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devanandsb.ccmanager.data.Card as CardEntity
import com.devanandsb.ccmanager.data.ImportLog
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.MailCrypt
import com.devanandsb.ccmanager.data.MailSource
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Issuers
import com.devanandsb.ccmanager.ingest.StatementSync
import com.devanandsb.ccmanager.ingest.SyncState
import com.devanandsb.ccmanager.ingest.SyncWorker
import com.devanandsb.ccmanager.security.Auth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MailScreen(
    ledger: Ledger,
    log: List<ImportLog>,
    cards: List<CardEntity>,
    statements: List<Statement> = emptyList(),
    needsYou: List<ImportLog>,
    fixKey: String?,
    onFixKeyConsumed: () -> Unit,
    sync: SyncState,
    onSync: () -> Unit,
    onImport: () -> Unit,
    onImported: (StatementSync.Outcome) -> Unit,
    onOpenStatement: (Long) -> Unit,
    onPasswords: () -> Unit,
    onPickGoogleAccount: ((String) -> Unit) -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accounts by ledger.mailSources.collectAsStateWithLifecycle(initialValue = emptyList())
    val connectedAccounts = remember(accounts) { accounts.filter { it.passwordEnc != null && it.user.isNotBlank() } }
    val connected = connectedAccounts.isNotEmpty()
    var showAddAccount by rememberSaveable { mutableStateOf(false) }
    var fixing by remember { mutableStateOf<ImportLog?>(null) }

    LaunchedEffect(fixKey, log) {
        val k = fixKey ?: return@LaunchedEffect
        log.firstOrNull { it.key == k }?.let { fixing = it; onFixKeyConsumed() }
    }

    Scaffold(
        topBar = {
            VaultTopAppBar(
                title = "Email statements",
                onBack = onBack,
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter)
                .padding(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connected) {
                StatusCard(connectedAccounts, sync, onSync, onImport)
                ConnectedAccountsSection(
                    ledger = ledger,
                    accounts = connectedAccounts,
                    cards = cards,
                    onPasswords = onPasswords,
                    onAddClick = { showAddAccount = !showAddAccount },
                    addOpen = showAddAccount,
                )
            }
            NeedsYouSection(needsYou) { fixing = it }
            if (!connected || showAddAccount) {
                Setup(
                    ledger = ledger,
                    cards = cards,
                    isAdditional = connected,
                    onConnected = {
                        showAddAccount = false
                        onSync()
                    },
                    onCancelAdditional = if (connected) ({ showAddAccount = false }) else null,
                    onImport = onImport,
                    onPasswords = onPasswords,
                    onPickGoogleAccount = onPickGoogleAccount,
                )
            }
            if (connected || log.isNotEmpty()) {
                RecentImports(log, cards, statements, onOpenStatement, onRetry = { scope.launch { ledger.retryFailed(); onSync() } })
            }
        }
    }

    fixing?.let { e ->
        ImportFixDialog(
            entry = e, cards = cards,
            onDismiss = { fixing = null },
            onSkip = { fixing = null; scope.launch { ledger.dismiss(e.key) } },
            onResult = { r -> fixing = null; onImported(r) },
        )
    }
}

@Composable
private fun SoftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusCard(
    accounts: List<MailSource>,
    sync: SyncState,
    onSync: () -> Unit,
    onImport: () -> Unit,
) {
    val latest = accounts.maxByOrNull { it.lastSyncAt }
    val countLabel = if (accounts.size == 1) "1 Gmail connected" else "${accounts.size} Gmail accounts connected"
    val primarySummary = if (accounts.size == 1) accounts.first().user
    else "${accounts.first().user} + ${accounts.size - 1} more"
    VaultCardContainer(
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Good.copy(alpha = 0.12f), Color.Transparent)))
                .padding(18.dp),
        ) {
            UrgencyBadge(countLabel, UrgencyLevel.Good, showDot = true)
            Spacer(Modifier.height(10.dp))
            Text(primarySummary, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(6.dp))
            if (sync.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(sync.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                val lastSyncAt = latest?.lastSyncAt ?: 0L
                val lastResult = latest?.lastResult.orEmpty()
                Text(
                    (if (lastSyncAt == 0L) "Not synced yet" else "Synced ${android.text.format.DateUtils.getRelativeTimeSpanString(lastSyncAt)}") +
                        (lastResult.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Searches Inbox, All Mail, Spam & Bin once a day. Only parses cards in your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultPrimaryButton(
                    text = "Sync now",
                    icon = Icons.Outlined.Sync,
                    onClick = onSync,
                    enabled = !sync.running,
                    modifier = Modifier.weight(1f),
                )
                VaultTonalButton(
                    text = "Import PDF",
                    icon = Icons.Outlined.UploadFile,
                    onClick = onImport,
                    enabled = !sync.running,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Setup(
    ledger: Ledger,
    cards: List<CardEntity>,
    isAdditional: Boolean = false,
    onConnected: () -> Unit,
    onCancelAdditional: (() -> Unit)? = null,
    onImport: () -> Unit,
    onPasswords: () -> Unit,
    onPickGoogleAccount: ((String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val banks = cards.map { it.issuer }.distinct().map(Issuers::byName)
    val known = banks.filter { it.senders.isNotEmpty() }
    val unknown = banks.filter { it.senders.isEmpty() }

    var openedGoogle by rememberSaveable { mutableStateOf(false) }
    val canConnect = user.contains('@') && password.isNotBlank()

    // Hero privacy banner
    val primary = MaterialTheme.colorScheme.primary
    Panel(padded = false) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(primary.copy(alpha = 0.12f), Color.Transparent)))
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoogleGMark(Modifier.size(46.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    if (isAdditional) "Add another Gmail ID" else "Sign in with Google",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "Read-only connection · Searches Inbox, All Mail, Spam & Bin.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Step(1, "Create an app password", "Google account → Security → App passwords", reached = true) {
                InlineAction("Open Google App Passwords") {
                    openedGoogle = true
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://myaccount.google.com/apppasswords".toUri())) }
                }
            }
            Step(2, "Sign in with Gmail & App Password", null, reached = openedGoogle || user.isNotBlank() || password.isNotBlank()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { onPickGoogleAccount { picked -> user = picked } },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) {
                        GoogleGMark(Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (user.isBlank()) "Choose Google account on phone" else "Switch Google account")
                    }
                    SoftField(
                        user, { user = it.trim() }, "Gmail address",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    )
                    SoftField(
                        password, { password = it }, "App password", placeholder = "16 letters",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                }
            }
            Step(3, "Add statement passwords", "Name and birth date, to unlock PDFs", reached = canConnect) {
                InlineAction("Statement passwords", onPasswords)
            }
        }
    }

    Panel {
        Text("Banks to look for", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        if (banks.isEmpty()) {
            Text(
                "Add a card first. Senders come from your cards' banks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                known.forEach { b ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                    ) {
                        Row(
                            Modifier.padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BankBadge(b.name, size = 20.dp)
                            Text(b.name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                unknown.forEach { b ->
                    Surface(
                        shape = CircleShape,
                        color = Amber.copy(alpha = 0.14f),
                        modifier = Modifier.border(0.6.dp, Amber.copy(alpha = 0.30f), CircleShape),
                    ) {
                        Row(
                            Modifier.padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BankBadge(b.name, size = 20.dp)
                            Text("${b.name} · needs sender", style = MaterialTheme.typography.labelMedium, color = Amber)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SoftField(
            extra, { extra = it }, "Extra sender addresses (optional)",
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
    }

    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Connected")) Good else Danger) }
    VaultPrimaryButton(
        text = if (busy) "Connecting…" else if (isAdditional) "Add Gmail account & sync" else "Connect and sync",
        enabled = !busy && canConnect,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                busy = true
                status = "Connecting…"
                val r = StatementSync.test("imap.gmail.com", 993, user, password)
                if (r.startsWith("Connected")) {
                    ledger.saveMailAccount(0, user, password, "imap.gmail.com", 993, extra, 120, null)
                    SyncWorker.enable(context)
                    password = ""
                    onConnected()
                }
                status = r
                busy = false
            }
        },
    )

    if (onCancelAdditional != null) {
        TextButton(onClick = onCancelAdditional, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    } else {
        TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Text("  Or import a statement PDF")
        }
    }
}

@Composable
private fun Step(n: Int, title: String, hint: String?, reached: Boolean, content: @Composable () -> Unit) {
    Row {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                n.toString(), style = MaterialTheme.typography.labelLarge.tabular,
                color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp))
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun RecentImports(
    log: List<ImportLog>,
    cards: List<CardEntity>,
    statements: List<Statement>,
    onOpenStatement: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cardById = remember(cards) { cards.associateBy { it.id } }
    val stmtById = remember(statements) { statements.associateBy { it.id } }
    val quiet = setOf("duplicate", "dismissed", "not_in_wallet")
    val relevant = (if (showAll) log else log.filter { it.outcome !in quiet && it.outcome !in FIXABLE }).take(60)
    val shown = if (expanded) relevant else relevant.take(RECENT_PREVIEW)
    val hidden = log.count { it.outcome in quiet }
    if (log.isEmpty()) return

    Row(verticalAlignment = Alignment.Bottom) {
        SectionHeader("Recent imports", Modifier.weight(1f))
        if (log.any { it.outcome in setOf("unparsed", "error") }) TextButton(onClick = onRetry) { Text("Retry failed") }
    }
    if (shown.isEmpty()) {
        Text("Nothing new imported yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Panel(padded = false) {
            Column {
                val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
                shown.groupBy { YearMonth.from(Instant.ofEpochMilli(it.receivedAt).atZone(ZoneId.systemDefault())) }.forEach { (ym, list) ->
                    Text(
                        ym.format(fmt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    list.forEachIndexed { i, e ->
                        val sid = e.statementId
                        val st = sid?.let { stmtById[it] }
                        val matchedCard = st?.let { cardById[it.cardId] }
                        if (i > 0) RowDivider(inset = false)
                        ListRow(
                            icon = null,
                            leading = matchedCard?.let { c -> { MiniCardThumb(c, width = 36.dp, cornerRadius = 5.dp) } },
                            title = e.subject.ifBlank { matchedCard?.let { "${it.nickname} statement" } ?: "(no subject)" },
                            subtitle = listOfNotNull(
                                matchedCard?.let { "${it.nickname} · ${it.last4}" },
                                Dates.short(Instant.ofEpochMilli(e.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()),
                                e.detail,
                            ).joinToString(" · "),
                            onClick = sid?.let { { onOpenStatement(it) } },
                            action = {
                                UrgencyBadge(
                                    outcomeLabel(e.outcome),
                                    when (e.outcome) {
                                        "imported" -> UrgencyLevel.Good
                                        "needs_review" -> UrgencyLevel.Warning
                                        "duplicate", "dismissed" -> UrgencyLevel.Neutral
                                        else -> UrgencyLevel.Urgent
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    Row {
        if (relevant.size > RECENT_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${relevant.size}") }
        }
        if (hidden > 0 && (expanded || relevant.size <= RECENT_PREVIEW)) {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide duplicates and skipped" else "Show $hidden duplicates and skipped") }
        }
    }
}

private const val RECENT_PREVIEW = 6

@Composable
private fun ConnectedAccountsSection(
    ledger: Ledger,
    accounts: List<MailSource>,
    cards: List<CardEntity>,
    onPasswords: () -> Unit,
    onAddClick: () -> Unit,
    addOpen: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("Connected Gmail accounts (${accounts.size})", Modifier.weight(1f))
        TextButton(onClick = onAddClick) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (addOpen) "Hide form" else "Add Gmail")
        }
    }
    Panel(padded = false) {
        accounts.forEachIndexed { index, account ->
            if (index > 0) RowDivider()
            AccountEditorRow(ledger = ledger, account = account, totalAccounts = accounts.size, cards = cards)
        }
    }
    SectionHeader("Passwords")
    Panel(padded = false) {
        ListRow(
            Icons.Outlined.Key,
            "Statement passwords",
            "Locked · Name, birth date, PAN & remembered PDF passwords",
            iconTint = Amber,
            chevron = true,
            onClick = onPasswords,
        )
    }
}

@Composable
private fun AccountEditorRow(
    ledger: Ledger,
    account: MailSource,
    totalAccounts: Int,
    cards: List<CardEntity>,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val deviceSecure = remember(context) { Auth.deviceSecure(context) }

    var open by remember { mutableStateOf(false) }
    var user by remember(account.id, account.user) { mutableStateOf(account.user) }
    var password by remember { mutableStateOf("") }
    var hasPassword by remember(account.id, account.passwordEnc) { mutableStateOf(account.passwordEnc != null) }
    var changingPassword by remember { mutableStateOf(false) }
    var host by remember(account.id, account.host) { mutableStateOf(account.host) }
    var extra by remember(account.id, account.extraSenders) { mutableStateOf(account.extraSenders) }
    var lookback by remember(account.id, account.lookbackDays) { mutableStateOf(account.lookbackDays.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun lockAndWipe() {
        open = false
        password = ""
        changingPassword = false
        status = null
        confirmRemove = false
    }

    DisposableEffect(lifecycleOwner, deviceSecure) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && deviceSecure && !Auth.inProgress) {
                lockAndWipe()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lockAndWipe()
        }
    }

    val sub = if (open) {
        "Edit email, server, senders, lookback"
    } else if (account.lastSyncAt == 0L) {
        "Not synced yet · tap to manage"
    } else {
        "Synced ${android.text.format.DateUtils.getRelativeTimeSpanString(account.lastSyncAt)}" +
            (account.lastResult.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }

    ListRow(
        Icons.Outlined.Mail,
        account.user,
        sub,
        iconTint = MaterialTheme.colorScheme.primary,
        onClick = {
            if (open) {
                lockAndWipe()
            } else {
                scope.launch {
                    if (deviceSecure && activity != null) {
                        Auth.withVault(activity, "Unlock settings for ${account.user}") {
                            user = account.user
                            host = account.host
                            extra = account.extraSenders
                            lookback = account.lookbackDays.toString()
                            hasPassword = account.passwordEnc != null
                            open = true
                            true
                        }
                    } else {
                        user = account.user
                        host = account.host
                        extra = account.extraSenders
                        lookback = account.lookbackDays.toString()
                        hasPassword = account.passwordEnc != null
                        open = true
                    }
                }
            }
        },
        action = {
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    AnimatedVisibility(open) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftField(
                user, { user = it.trim() }, "Email address",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            )
            if (hasPassword && !changingPassword) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App password", style = MaterialTheme.typography.bodyMedium)
                        Text("Saved  ••••••••••••••••", style = MaterialTheme.typography.bodySmall, color = Good)
                    }
                    TextButton(onClick = { changingPassword = true }) { Text("Change") }
                }
            } else {
                SoftField(
                    password, { password = it },
                    if (hasPassword) "New app password" else "App password",
                    placeholder = "16 letters",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = if (hasPassword) {
                        { TextButton(onClick = { password = ""; changingPassword = false }) { Text("Cancel") } }
                    } else null,
                )
            }
            SoftField(host, { host = it.trim() }, "Email server")
            SoftField(
                extra, { extra = it }, "Extra sender addresses (comma-separated)",
                supportingText = {
                    val known = cards.map { it.issuer }.distinct().map(Issuers::byName).filter { it.senders.isNotEmpty() }
                    Text(if (known.isEmpty()) "No built-in senders for your banks" else "Built in: " + known.joinToString(", ") { it.name })
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            SoftField(
                lookback, { lookback = it.filter(Char::isDigit).take(3) }, "Look back (days)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && user.contains('@'), shape = CircleShape, onClick = {
                    scope.launch {
                        ledger.saveMailAccount(
                            id = account.id,
                            user = user,
                            password = password.ifBlank { null },
                            host = host,
                            port = account.port,
                            extraSenders = extra,
                            lookbackDays = lookback.toIntOrNull()?.coerceIn(7, 730) ?: 120,
                            profile = null,
                        )
                        if (password.isNotBlank()) hasPassword = true
                        password = ""; changingPassword = false; status = "Saved."
                    }
                }) { Text("Save") }
                OutlinedButton(enabled = !busy, shape = CircleShape, onClick = {
                    scope.launch {
                        busy = true; status = "Connecting…"
                        val pw = password.ifBlank { MailCrypt.dec(account.passwordEnc).orEmpty() }
                        status = StatementSync.test(host, account.port, user, pw)
                        busy = false
                    }
                }) { Text("Test") }
                TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Danger) }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${account.user}?",
            body = "Deletes its saved app password from this phone and stops syncing this Gmail ID. Imported statements and your PDF password details stay.",
            confirmLabel = "Remove",
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                scope.launch {
                    ledger.disconnectMailAccount(account.id)
                    if (totalAccounts <= 1) SyncWorker.disable(context)
                }
            },
        )
    }
}

private fun outcomeLabel(o: String) = when (o) {
    "imported" -> "Imported"
    "needs_review" -> "Review"
    "duplicate" -> "Duplicate"
    "not_in_wallet" -> "Not in wallet"
    "dismissed" -> "Skipped"
    "locked" -> "Locked"
    "unmatched" -> "No card"
    "unparsed" -> "Unreadable"
    else -> "Error"
}

@Preview(showBackground = true)
@Composable
fun MailScreenPreview() {
    CardsTheme {
        val ledgerDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            com.devanandsb.ccmanager.data.LedgerDao::class.java.classLoader,
            arrayOf(com.devanandsb.ccmanager.data.LedgerDao::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                kotlinx.coroutines.flow.Flow::class.java -> kotlinx.coroutines.flow.flowOf(emptyList<Any>())
                List::class.java -> emptyList<Any>()
                else -> null
            }
        } as com.devanandsb.ccmanager.data.LedgerDao
        val dummyLedger = Ledger(ledgerDaoProxy) { _ -> }

        MailScreen(
            ledger = dummyLedger,
            log = emptyList(),
            cards = emptyList(),
            statements = emptyList(),
            needsYou = emptyList(),
            fixKey = null,
            onFixKeyConsumed = {},
            sync = SyncState(false),
            onSync = {},
            onImport = {},
            onImported = {},
            onOpenStatement = {},
            onPasswords = {},
            onBack = {},
        )
    }
}
