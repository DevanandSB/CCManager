package com.devanandsb.ccmanager.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.CardDao
import com.devanandsb.ccmanager.data.CardKind
import com.devanandsb.ccmanager.data.CardSecret
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.LedgerDao
import com.devanandsb.ccmanager.data.Payment
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.hasDues
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.security.Auth
import com.devanandsb.ccmanager.security.SecretDraft
import com.devanandsb.ccmanager.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

private const val REVEAL_SECONDS = 15
private const val CLIPBOARD_CLEAR_MS = 30_000L

@Composable
fun DetailScreen(
    cw: CardWithDue?,
    payments: List<Payment>,
    repo: Repo,
    statements: List<Statement> = emptyList(),
    openUpload: Boolean = false,
    onOpenStatement: (Long) -> Unit = {},
    onImport: (() -> Unit)? = null,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()

    var secret by remember { mutableStateOf<CardSecret?>(null) }
    var revealed by remember { mutableStateOf<SecretDraft?>(null) }
    var revealLeft by remember { mutableIntStateOf(0) }
    var revealDetectAfterMs by remember { mutableLongStateOf(0L) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var payDialog by remember { mutableStateOf(false) }
    var balanceDialog by remember { mutableStateOf(false) }
    var confirmDeletePayment by remember { mutableStateOf<Payment?>(null) }
    var pinFull by remember { mutableStateOf<List<Card>?>(null) }
    var tiltPromptDir by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    val locked = LocalLocked.current

    val cardId = cw?.card?.id
    val tiltDetector = remember(cardId) { TiltDetector() }
    var uploadDeepLinkConsumed by rememberSaveable(cardId, openUpload) { mutableStateOf(false) }
    LaunchedEffect(cardId, openUpload, locked, onImport) {
        if (cardId != null && openUpload && !uploadDeepLinkConsumed && !locked && onImport != null) {
            uploadDeepLinkConsumed = true
            onImport.invoke()
        }
    }
    LaunchedEffect(cardId) { if (cardId != null) secret = repo.secret(cardId) }
    LaunchedEffect(locked) { if (locked) tiltPromptDir = null }
    LaunchedEffect(revealed) {
        if (revealed != null) {
            // Wait 5 seconds after revealing card details before detecting flip-back tilts.
            revealDetectAfterMs = android.os.SystemClock.elapsedRealtime() + 5_000L
            revealLeft = REVEAL_SECONDS
            while (revealLeft > 0) { delay(1.seconds); revealLeft-- }
            revealed = null
        } else {
            revealDetectAfterMs = 0L
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false; tiltPromptDir = null }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { revealed = null }

    if (cw == null) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { Column(Modifier.padding(it)) {} }
        return
    }
    val c = cw.card
    val d = cw.due
    val s = secret
    val hasSecrets = s != null && (s.numberEnc != null || s.expiryEnc != null || s.cvvEnc != null)
    val stmts = remember(statements, c.id) {
        statements.filter { it.cardId == c.id }
            .sortedWith(compareByDescending<Statement> { it.statementEpochDay }.thenByDescending { it.id })
    }
    val latest = stmts.firstOrNull()
    val latestMatchesCycle = latest != null && !d.lastCyclePaid && (
        latest.dueEpochDay == d.dueDate.toEpochDay() ||
            (latest.dueEpochDay == null && latest.statementEpochDay == d.statementDate.toEpochDay())
        )
    val minDue = latest?.takeIf { latestMatchesCycle }?.minDuePaise?.takeIf { it > 0L }
    val promptOpen by rememberUpdatedState(tiltPromptDir != null)
    val modalOpen by rememberUpdatedState(unlocking || menu || confirmDelete || payDialog || balanceDialog || confirmDeletePayment != null || pinFull != null)
    val isRevealed by rememberUpdatedState(revealed != null)
    val revealDetectAfter by rememberUpdatedState(revealDetectAfterMs)
    DisposableEffect(c.id, hasSecrets, locked, resumed) {
        val sm = if (hasSecrets && !locked && resumed) context.getSystemService(SensorManager::class.java) else null
        val sensor = sm?.let { it.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
        if (sm == null || sensor == null) {
            onDispose {}
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val x = event.values.getOrNull(0) ?: return
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dir = tiltDetector.onSample(
                        x = x,
                        nowMs = now,
                        blocking = promptOpen || modalOpen,
                        revealed = isRevealed,
                        revealDetectAfterMs = revealDetectAfter,
                    )
                    if (dir != null) {
                        haptic(Haptic.Tick)
                        tiltPromptDir = dir
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm.unregisterListener(listener) }
        }
    }

    suspend fun unlock(title: String): SecretDraft? {
        val sec = secret ?: return null
        unlocking = true
        return try {
            if (!Auth.deviceSecure(context)) withContext(Dispatchers.IO) { Vault.decrypt(sec) }
            else if (activity != null) Auth.unlockVault(activity, title, c.nickname) { withContext(Dispatchers.IO) { Vault.decrypt(sec) } }
            else withContext(Dispatchers.IO) { Vault.decrypt(sec) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = if (e is Vault.KeyLost) e.message else "Couldn't open card details. Please try again."
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            null
        } finally {
            unlocking = false
        }
    }

    fun reveal() = scope.launch { unlock("Reveal card details")?.let { revealed = it } }
    fun back() {
        revealed?.let { r ->
            FlipBack.start(FlipBack.Pending(c.id, r, StoredFields(s?.numberEnc != null, s?.expiryEnc != null, s?.cvvEnc != null, s?.holderEnc != null)))
        }
        revealed = null
        onBack()
    }
    BackHandler(enabled = revealed != null) { back() }
    val archived = c.archivedAt != null

    val isDark = LocalIsDark.current
    val resolvedArt = remember(c.art, c.issuer, c.nickname) { CardArts.resolve(c) }
    val ambientColor = remember(resolvedArt, c.color) {
        Color(resolvedArt?.accent?.firstOrNull() ?: resolvedArt?.colors?.firstOrNull() ?: c.color)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        0f to ambientColor.copy(alpha = if (isDark) 0.18f else 0.08f),
                        0.6f to ambientColor.copy(alpha = if (isDark) 0.06f else 0.03f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                VaultTopAppBar(
                    title = c.nickname,
                    subtitle = "${c.network} · •• ${c.last4}",
                    onBack = ::back,
                    actions = {
                        VaultIconButton(
                            icon = Icons.Outlined.Edit,
                            contentDescription = "Edit card",
                            onClick = onEdit,
                        )
                        Box {
                            VaultIconButton(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = "More",
                                onClick = { menu = true },
                            )
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                if (!archived) DropdownMenuItem(
                                    text = { Text(if (c.pinned) "Unpin from stack" else "Pin to stack") },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            if (repo.setPinned(c, !c.pinned)) haptic(Haptic.Confirm)
                                            else { haptic(Haptic.Reject); pinFull = repo.activeCards(includeAll = true).filter { it.pinned } }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (archived) "Restore card" else "Archive card") },
                                    leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            repo.setArchived(c.id, !archived)
                                            Toast.makeText(context, if (archived) "${c.nickname} restored" else "${c.nickname} archived", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                                if (c.isCredit) DropdownMenuItem(
                                    text = { Text("Update balance") },
                                    leadingIcon = { Icon(Icons.Outlined.CurrencyRupee, null) },
                                    onClick = { menu = false; balanceDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete card", color = Danger) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Danger) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    },
                )
            },
        ) { pad ->
            pinFull?.let { pinned ->
                ReplacePinDialog(c, pinned, onReplace = { id ->
                    pinFull = null
                    scope.launch { haptic(if (repo.setPinned(c, true, id)) Haptic.Confirm else Haptic.Reject) }
                }, onDismiss = { pinFull = null })
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter)
                    .padding(top = 16.dp, bottom = 44.dp),
            ) {
                if (archived) {
                    VaultCardContainer(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Archived card",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { scope.launch { repo.setArchived(c.id, false) } }) { Text("Restore") }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 1. Physical Card Hero (Interactive 3D FlipCard) + inline Reveal/Action Dock
                val progress by animateFloatAsState(
                    if (revealed == null) 0f else revealLeft / REVEAL_SECONDS.toFloat(), tween(1000), label = "reveal",
                )
                FlipCard(
                    cw, revealed,
                    StoredFields(s?.numberEnc != null, s?.expiryEnc != null, s?.cvvEnc != null, s?.holderEnc != null),
                    progress,
                    onCopy = { label, value -> copySensitive(context, label, value) },
                    modifier = Modifier.sharedCard(c.id),
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (!hasSecrets) "Only last 4 digits stored"
                        else if (revealed == null) "Tilt slightly or tap Reveal to view CVV"
                        else "Auto-hides in ${revealLeft}s · slant steeply to hide",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasSecrets) {
                        Spacer(Modifier.width(8.dp))
                        VaultFilterChip(
                            label = if (revealed == null) "Reveal CC" else "Hide (${revealLeft}s)",
                            selected = revealed != null,
                            leadingIcon = if (revealed == null) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            onClick = { if (revealed == null) reveal() else revealed = null },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                // Quick Bento Action Dock
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionDockTile(
                        icon = if (revealed == null) Icons.Outlined.Fingerprint else Icons.Outlined.VisibilityOff,
                        label = if (revealed == null) "Reveal" else "Hide ${revealLeft}s",
                        sub = if (!hasSecrets) "Not saved" else if (revealed == null) "Number & CVV" else "Tap to hide",
                        enabled = hasSecrets,
                        active = revealed != null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { if (revealed == null) reveal() else revealed = null }

                    if (c.isCredit) {
                        val settled = d.lastCyclePaid && c.outstandingPaise <= 0L
                        if (!archived) ActionDockTile(
                            icon = Icons.Outlined.CheckCircle,
                            label = if (settled) "Paid" else "Mark paid",
                            sub = when {
                                settled -> "All settled"
                                c.billingSet -> Dates.short(d.dueDate)
                                c.outstandingPaise > 0L -> "Pending due"
                                else -> "No due date"
                            },
                            enabled = (!settled && c.billingSet) || c.outstandingPaise > 0L,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { payDialog = true }

                        ActionDockTile(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            label = "Statements",
                            sub = if (stmts.isEmpty()) "None yet" else "${stmts.size} saved",
                            enabled = latest != null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { latest?.let { onOpenStatement(it.id) } }
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (!hasSecrets) {
                    VaultCardContainer {
                        Text(
                            "Only the last four digits are saved. Add the full number, expiry and CVV (stored safely on this phone) to reveal and copy them from the card.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        VaultTonalButton(text = "Add card number", onClick = onEdit)
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 2. Due & Limit Bento Grid (Credit cards)
                if (c.isCredit) {
                    DueAndLimitBentoGrid(
                        cw = cw,
                        minDue = minDue,
                        archived = archived,
                        onPay = { payDialog = true },
                        onEdit = onEdit,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // 3. Compact Card Specs & Reminder Strip
                val leads = remember(c.reminderLeads) { Billing.parseLeads(c.reminderLeads) }
                VaultCardContainer(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (c.isCredit) "Billing cycle" else "Card type",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (c.isCredit) {
                                    if (c.hasDues) "${ordinal(c.statementDay)} · +${c.dueOffsetDays}d" else "Not set"
                                } else CardKind.label(c.kind),
                                style = MaterialTheme.typography.titleSmall.tabular,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (c.isCredit) "Synced" else "Network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (c.isCredit) "${stmts.size} ${if (stmts.size == 1) "statement" else "statements"}" else c.network,
                                style = MaterialTheme.typography.titleSmall.tabular,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(
                            Modifier
                                .weight(0.9f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onEdit),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(
                                "Reminders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val remOn = c.isCredit && c.billingSet && leads.isNotEmpty()
                            Text(
                                if (!c.isCredit) "N/A" else if (remOn) "On (${leads.size})" else "Off",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (remOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 4. Import Statement Action Card
                if (!archived && c.isCredit && onImport != null) {
                    Spacer(Modifier.height(12.dp))
                    VaultCardContainer(
                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        onClick = onImport,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.UploadFile,
                                    null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Import statement", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Upload PDF or fetch from Gmail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = onImport,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("Import", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // 5. Statements List (Email & Manual)
                if (c.isCredit && stmts.isNotEmpty()) {
                    SectionHeader("Statements", trailing = "Tap to inspect spend")
                    Panel(padded = false) {
                        stmts.take(12).forEachIndexed { i, st ->
                            if (i > 0) RowDivider(inset = false)
                            val ok = st.status == Ledger.STATUS_OK || (st.openingPaise == null && st.totalDuePaise != null && st.dueEpochDay != null)
                            val fromEmail = st.sourceMsgId.isNotBlank()
                            val stmtDate = LocalDate.ofEpochDay(st.statementEpochDay)
                            val monthTitle = stmtDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
                            val paidForStmt = st.dueEpochDay?.let { dueDay ->
                                payments.any { it.cardId == c.id && abs(it.dueEpochDay - dueDay) <= 10L }
                            } ?: (d.lastCyclePaid && i == 0)

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenStatement(st.id) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        monthTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            if (fromEmail) Icons.Outlined.MailOutline else Icons.Outlined.UploadFile,
                                            null,
                                            tint = if (!ok) Amber else if (fromEmail) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            when {
                                                !ok -> "Needs review"
                                                fromEmail -> "Fetched from email"
                                                else -> "Manually uploaded"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (!ok) Amber else if (fromEmail) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        st.totalDuePaise?.let { Money.inr(it, true) } ?: "—",
                                        style = MaterialTheme.typography.titleSmall.tabular,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        when {
                                            paidForStmt -> "Paid"
                                            st.dueEpochDay != null -> "Due ${Dates.short(LocalDate.ofEpochDay(st.dueEpochDay))}"
                                            else -> Dates.short(stmtDate)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            paidForStmt -> Good
                                            i == 0 && d.overdue -> Danger
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 7. Payments History
                val mine = remember(payments, c.id) { payments.filter { it.cardId == c.id } }
                if (mine.isNotEmpty()) {
                    SectionHeader("Payments")
                    Panel(padded = false) {
                        mine.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            ListRow(
                                icon = Icons.Outlined.CheckCircle,
                                iconTint = Good,
                                title = if (p.amountPaise > 0) Money.inr(p.amountPaise, true) else "Settled, nothing due",
                                subtitle = "Paid ${Dates.short(LocalDate.ofEpochDay(p.paidOnEpochDay))} · for due ${Dates.short(LocalDate.ofEpochDay(p.dueEpochDay))}",
                                trailing = null,
                                action = {
                                    IconButton(onClick = { confirmDeletePayment = p }) {
                                        Icon(Icons.Outlined.Delete, "Delete payment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                    }
                }

                if (c.notes.isNotBlank()) {
                    SectionHeader("Notes")
                    VaultCardContainer {
                        Text(c.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${c.nickname}?",
            body = "The card, its stored number and CVV, statements and payment history are removed from this phone. This can't be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; scope.launch { repo.delete(c.id); onBack() } },
        )
    }

    confirmDeletePayment?.let { p ->
        ConfirmDialog(
            title = "Delete this payment?",
            body = "The ${if (p.amountPaise > 0) Money.inr(p.amountPaise, true) + " " else ""}payment for the due on ${Dates.short(LocalDate.ofEpochDay(p.dueEpochDay))} is removed from this phone, and its reminders may come back. This can't be undone.",
            onDismiss = { confirmDeletePayment = null },
            onConfirm = { confirmDeletePayment = null; scope.launch { repo.deletePayment(p) } },
        )
    }

    if (payDialog) {
        val dueDay = d.dueDate.toEpochDay()
        val paidForDue = payments.filter { it.cardId == c.id && abs(it.dueEpochDay - dueDay) <= 10L }.sumOf { it.amountPaise }
        val stmtLeft = latest?.takeIf {
            val stDue = it.dueEpochDay ?: (it.statementEpochDay + c.dueOffsetDays.toLong())
            abs(stDue - dueDay) <= 10L
        }
            ?.let { Repo.effectiveStatementDuePaise(it, c.limitPaise) }
            ?.let { (it - paidForDue).coerceAtLeast(0L) }
        val effectiveTotal = (stmtLeft?.let { minOf(it, c.outstandingPaise) } ?: c.outstandingPaise).takeIf { it > 0L } ?: c.outstandingPaise
        MarkPaidSheet(
            dueLabel = if (c.billingSet) Dates.weekday(d.dueDate) else c.nickname,
            total = effectiveTotal,
            minimum = minDue,
            onDismiss = { payDialog = false },
            onConfirm = { paise ->
                payDialog = false
                scope.launch {
                    if (c.billingSet) repo.markPaid(c.id, dueDay, paise)
                    else repo.setOutstanding(c.id, (c.outstandingPaise - paise).coerceAtLeast(0L))
                }
            },
        )
    }

    if (balanceDialog) {
        AmountSheet(
            title = "Outstanding balance",
            note = "What the card owes right now. Statements update this automatically.",
            initialPaise = c.outstandingPaise,
            onDismiss = { balanceDialog = false },
            onConfirm = { paise ->
                balanceDialog = false
                scope.launch { repo.setOutstanding(c.id, paise) }
            },
        )
    }

    tiltPromptDir?.let { dir ->
        ConfirmDialog(
            title = "Do you want to flip this card?",
            body = if (revealed == null)
                "You tilted your phone $dir. Flip the card now to unlock and view your card details."
            else
                "You slanted your phone steeply $dir. Flip the card back to hide sensitive details.",
            confirmLabel = "Flip card",
            destructive = false,
            onDismiss = {
                tiltPromptDir = null
                tiltDetector.onDismissed(android.os.SystemClock.elapsedRealtime())
            },
            onConfirm = {
                tiltPromptDir = null
                if (revealed == null) reveal() else revealed = null
            },
        )
    }
}

@Composable
private fun DueAndLimitBentoGrid(
    cw: CardWithDue,
    minDue: Long?,
    archived: Boolean,
    onPay: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = cw.card
    val d = cw.due
    val settled = d.lastCyclePaid && c.outstandingPaise <= 0L
    val accent = when {
        d.overdue || (!settled && c.hasDues && d.daysLeft <= 2) -> Danger
        !settled && c.hasDues && d.daysLeft <= 5 -> Amber
        settled -> Good
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left Tile: Statement Due
        val dueEyebrow = when {
            !c.hasDues && c.outstandingPaise > 0L -> "PENDING DUE"
            !c.hasDues -> "BILLING DUE"
            d.overdue -> "OVERDUE"
            settled -> "ALL PAID"
            else -> "DUE ${Dates.short(d.dueDate).uppercase()}"
        }
        val dueValue = when {
            !c.hasDues && c.outstandingPaise <= 0L -> "No due date"
            else -> Money.inr(c.outstandingPaise, true)
        }
        val dueSub = when {
            !c.hasDues && c.outstandingPaise > 0L -> "Add due date for alerts"
            !c.hasDues -> "Set cycle or import PDF"
            settled -> "Next ${Dates.short(d.dueDate)}"
            else -> buildString {
                append(Dates.relative(d.daysLeft).replaceFirstChar { it.uppercase() })
                if (minDue != null && c.outstandingPaise > 0L && minDue <= c.outstandingPaise) {
                    append(" · Min ${Money.inr(minDue, true)}")
                }
            }
        }
        VaultBentoTile(
            eyebrow = dueEyebrow,
            value = dueValue,
            subtitle = dueSub,
            subtitleColor = if (c.hasDues) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            if (!archived && (c.hasDues || c.outstandingPaise > 0L)) {
                if (settled) {
                    VaultTonalButton(
                        text = "Paid",
                        icon = Icons.Outlined.CheckCircle,
                        onClick = onPay,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    )
                } else {
                    Button(
                        onClick = onPay,
                        shape = CircleShape,
                        colors = if (d.overdue) {
                            ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (c.outstandingPaise <= 0L) "Mark settled" else "Mark as paid",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            } else if (!archived) {
                VaultTonalButton(
                    text = "Add due date",
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            }
        }

        // Right Tile: Credit Limit & Utilization
        val hasLimit = c.limitPaise > 0L
        val freePaise = (c.limitPaise - c.outstandingPaise).coerceAtLeast(0L)
        val utilFrac = if (hasLimit) (c.outstandingPaise.toFloat() / c.limitPaise.toFloat()).coerceIn(0f, 1f) else 0f
        val utilPct = (utilFrac * 100f).toInt()
        val barColor = when {
            utilFrac >= 0.85f -> Danger
            utilFrac >= 0.60f -> Amber
            else -> MaterialTheme.colorScheme.primary
        }

        VaultBentoTile(
            eyebrow = "CREDIT LIMIT",
            value = if (hasLimit) Money.inr(c.limitPaise, true) else "Not set",
            subtitle = if (hasLimit) "${Money.inr(freePaise, true)} free" else "Tap Edit to set limit",
            onClick = if (!hasLimit) onEdit else null,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            if (hasLimit) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { utilFrac },
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    )
                    Text(
                        "$utilPct% used",
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                VaultTonalButton(
                    text = "Set limit",
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionDockTile(
    icon: ImageVector,
    label: String,
    sub: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val isDark = LocalIsDark.current
    val alpha = if (enabled) 1f else 0.42f
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        isDark -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val subColor = if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    val border = if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.45f else 0.48f)
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = bg,
        modifier = modifier
            .clip(shape)
            .border(0.75.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) Color.White.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.14f else 0.06f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = fg,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val CLIP_TOKEN = "com.devanandsb.ccmanager.CLIP_TOKEN"

private fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val token = java.util.UUID.randomUUID().toString()
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        putString(CLIP_TOKEN, token)
    }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
    Handler(Looper.getMainLooper()).postDelayed({
        val current = runCatching { cm.primaryClipDescription }.getOrNull()
        val replaced = current != null && current.extras?.getString(CLIP_TOKEN) != token
        if (!replaced) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, CLIPBOARD_CLEAR_MS)
}

@Preview(showBackground = true)
@Composable
fun DetailScreenPreview() {
    CardsTheme {
        val context = LocalContext.current
        val cardDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            CardDao::class.java.classLoader,
            arrayOf(CardDao::class.java),
        ) { _, _, _ -> null } as CardDao
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
        val dummyRepo = Repo(cardDaoProxy, ledgerDaoProxy, context)

        val sampleCard = Card(
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
        DetailScreen(
            cw = sampleCardWithDue,
            payments = emptyList(),
            repo = dummyRepo,
            statements = emptyList(),
            onOpenStatement = {},
            onImport = {},
            onBack = {},
            onEdit = {},
        )
    }
}
