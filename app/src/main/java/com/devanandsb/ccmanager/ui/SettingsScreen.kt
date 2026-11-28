package com.devanandsb.ccmanager.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.room.withTransaction
import com.devanandsb.ccmanager.CardsApp
import com.devanandsb.ccmanager.data.MailSource
import com.devanandsb.ccmanager.data.Prefs
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.reminders.Notifications
import com.devanandsb.ccmanager.reminders.Reminders
import com.devanandsb.ccmanager.security.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@Composable
fun SettingsScreen(
    prefs: Prefs,
    mailAccounts: List<MailSource> = emptyList(),
    mailSyncRunning: Boolean = false,
    onSyncMail: () -> Unit = {},
    onMail: () -> Unit,
    onPasswords: () -> Unit = {},
    onBillReminders: () -> Unit = {},
    onStatementParser: () -> Unit = {},
    onBack: () -> Unit,
    onArchived: () -> Unit = {},
    archivedCount: Int = 0,
    onDataWiped: () -> Unit = {},
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var fingerprintLock by remember { mutableStateOf(prefs.appLockEnabled) }
    var timeout by remember { mutableIntStateOf(prefs.lockTimeoutSec) }
    var leads by remember { mutableStateOf(prefs.defaultLeads) }
    var lastBackup by remember { mutableLongStateOf(prefs.lastBackupAt) }
    var confirmWipe by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        fingerprintLock = prefs.appLockEnabled
    }
    val deviceSecure = remember(tick, inspection) { inspection || Auth.deviceSecure(context) }

    val exportBackup = rememberExportBackup { lastBackup = prefs.lastBackupAt }
    val restoreBackup = rememberRestoreBackup { lastBackup = prefs.lastBackupAt }

    val replaySplash = LocalReplaySplash.current
    val personalizeOwner = LocalPersonalizeOwner.current
    val ownerName = LocalOwnerName.current
    val ownerInitials = remember(ownerName) {
        val parts = ownerName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        when {
            parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
            parts.size == 1 -> parts.first().take(2).uppercase()
            else -> "CC"
        }
    }
    val connectedAccounts = remember(mailAccounts) {
        mailAccounts.filter { it.user.isNotBlank() }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Delete all data on phone?",
            body = "This removes all saved cards, card numbers, statements, transactions, saved PDF passwords, and Gmail settings from this phone. This can't be undone.",
            confirmLabel = if (wiping) "Deleting…" else "Delete everything",
            destructive = true,
            onDismiss = { if (!wiping) confirmWipe = false },
            onConfirm = {
                if (wiping || inspection) return@ConfirmDialog
                scope.launch {
                    wiping = true
                    val verified = if (deviceSecure && activity != null) {
                        Auth.unlockVault(
                            activity,
                            "Delete all data on phone",
                            "Confirm screen lock to delete everything",
                        ) { true } ?: false
                    } else {
                        true
                    }
                    if (!verified) {
                        wiping = false
                        confirmWipe = false
                        Toast.makeText(context, "Delete cancelled", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val app = CardsApp.from(context)
                    val cardIds = withContext(Dispatchers.IO) { app.db.backup().cards().map { it.id } }
                    cardIds.forEach { Reminders.cancel(context, it) }
                    withContext(Dispatchers.IO) {
                        app.db.withTransaction {
                            val b = app.db.backup()
                            b.clearSenderCards()
                            b.clearPendingPdfs()
                            b.clearImportLog()
                            b.clearTxns()
                            b.clearStatements()
                            b.clearPayments()
                            b.clearSecrets()
                            b.clearCards()
                            b.clearRules()
                            b.clearCategories()
                            b.clearMailSource()
                            app.db.dao().clearHolderNames()
                        }
                    }
                    setCustomOwnerOverride(context, null)
                    prefs.lastBackupAt = 0L
                    lastBackup = 0L
                    wiping = false
                    confirmWipe = false
                    Toast.makeText(context, "All data deleted on this phone", Toast.LENGTH_LONG).show()
                    onDataWiped()
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopAppBar(
                title = "Settings",
                onBack = onBack,
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
        ) {
            // Identity Block matching settings.html
            VaultCardContainer(
                onClick = { personalizeOwner?.invoke() },
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ownerInitials,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            ownerName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "All data stays on your phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .then(if (replaySplash != null) Modifier.clickable(onClick = replaySplash) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.VerifiedUser,
                            contentDescription = "Stored safely on your phone",
                            tint = Good,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Appearance & Colour Section (prominently placed near the top for immediate access)
            SectionHeader("Appearance & colour")
            Panel { AppearanceSection(prefs) }
            Spacer(Modifier.height(10.dp))
            Panel(padded = false) {
                ListRow(
                    icon = Icons.Outlined.Person,
                    title = "Vault owner name",
                    subtitle = ownerName,
                    iconTint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    onClick = { personalizeOwner?.invoke() },
                )
            }

            // Gmail Accounts Section matching settings.html
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "GMAIL ACCOUNTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (connectedAccounts.isNotEmpty()) {
                    if (mailSyncRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            onClick = onSyncMail,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp),
                        ) {
                            Text("Sync now", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (connectedAccounts.isNotEmpty()) {
                val isDark = LocalIsDark.current
                val mailCircleBg = if (isDark) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                }
                Panel(padded = false) {
                    connectedAccounts.forEachIndexed { idx, account ->
                        if (idx > 0) RowDivider(inset = true)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onMail)
                                .heightIn(min = 60.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(mailCircleBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Mail,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    account.user.trim(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "App password active · ${formatMailSyncSubtitle(account.lastSyncAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Good,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = onMail,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text(
                                    "Edit",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    RowDivider(inset = false)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onMail)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AddCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Add another Gmail account",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                VaultCardContainer(contentPadding = PaddingValues(20.dp)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Mail,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "No Gmail connected yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "Add a Gmail account to fetch your monthly statements automatically, or upload PDFs manually anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Button(
                            onClick = onMail,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text("Connect Gmail", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Security & Reminders Section matching settings.html
            SectionHeader("Security & reminders")
            Panel(padded = false) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = deviceSecure) {
                            val next = !fingerprintLock
                            if (next) {
                                fingerprintLock = true
                                prefs.appLockEnabled = true
                            } else if (activity != null) {
                                scope.launch {
                                    val ok = Auth.authenticate(
                                        activity,
                                        "Turn off fingerprint lock",
                                        "Confirm your screen lock to turn this off",
                                    )
                                    if (ok) {
                                        fingerprintLock = false
                                        prefs.appLockEnabled = false
                                    }
                                }
                            } else {
                                fingerprintLock = false
                                prefs.appLockEnabled = false
                            }
                        }
                        .heightIn(min = 60.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Fingerprint lock",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (deviceSecure) "Ask for fingerprint before showing card numbers"
                            else "Set up a screen lock in phone settings to turn this on",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = fingerprintLock && deviceSecure,
                        enabled = deviceSecure,
                        onCheckedChange = { checked ->
                            if (checked) {
                                fingerprintLock = true
                                prefs.appLockEnabled = true
                            } else if (activity != null) {
                                scope.launch {
                                    val ok = Auth.authenticate(
                                        activity,
                                        "Turn off fingerprint lock",
                                        "Confirm your screen lock to turn this off",
                                    )
                                    if (ok) {
                                        fingerprintLock = false
                                        prefs.appLockEnabled = false
                                    }
                                }
                            } else {
                                fingerprintLock = false
                                prefs.appLockEnabled = false
                            }
                        },
                    )
                }
                if (fingerprintLock && deviceSecure) {
                    RowDivider(inset = false)
                    LockTimeoutRow(timeout) { timeout = it; prefs.lockTimeoutSec = it }
                }
                RowDivider(inset = false)
                ListRow(
                    icon = Icons.Outlined.Key,
                    title = "Saved PDF passwords",
                    subtitle = "Manage details used to open bank PDFs",
                    iconTint = Amber,
                    chevron = true,
                    onClick = onPasswords,
                )
                RowDivider(inset = false)
                ListRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "Bill due reminders",
                    subtitle = "Preview how notifications look",
                    iconTint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    onClick = onBillReminders,
                )
                RowDivider(inset = false)
                ListRow(
                    icon = Icons.Outlined.Description,
                    title = "PDF statement reader",
                    subtitle = "Read & check any bank PDF right on your phone",
                    iconTint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    onClick = onStatementParser,
                )
                RowDivider(inset = false)
                AutofillRow()
            }

            Spacer(Modifier.height(10.dp))
            Panel {
                val chosen = remember(leads) { Billing.parseLeads(leads) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 5, 3, 2, 1, 0).forEach { day ->
                        val selected = day in chosen
                        VaultFilterChip(
                            label = reminderLeadLabel(day),
                            selected = selected,
                            leadingIcon = if (selected) Icons.Filled.Check else null,
                            onClick = {
                                val next = if (selected) chosen - day else chosen + day
                                leads = next.sortedDescending().joinToString(",")
                                prefs.defaultLeads = leads
                            },
                        )
                    }
                }
                Text(
                    "For new cards. We will remind you at 9:00 AM on the reminder day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            ReminderHealth(tick)

            // Your Data Section matching settings.html
            SectionHeader("Your data")
            Panel(padded = false) {
                ListRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Save a backup file",
                    subtitle = if (lastBackup == 0L) "Save a password-protected copy of everything"
                    else "Last saved ${Dates.long(Instant.ofEpochMilli(lastBackup).atZone(ZoneId.systemDefault()).toLocalDate())}",
                    iconTint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    onClick = exportBackup,
                )
                RowDivider(inset = false)
                ListRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = "Restore from backup",
                    subtitle = "Replaces everything on this phone",
                    iconTint = MaterialTheme.colorScheme.primary,
                    chevron = true,
                    onClick = restoreBackup,
                )
                RowDivider(inset = false)
                ListRow(
                    icon = Icons.Outlined.Archive,
                    title = "Archived cards",
                    subtitle = if (archivedCount == 0) "None" else "$archivedCount archived",
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    chevron = true,
                    onClick = onArchived,
                )
                RowDivider(inset = false)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { confirmWipe = true }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Delete all data on phone",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun formatMailSyncSubtitle(lastSyncAt: Long): String {
    if (lastSyncAt <= 0L) return "Inbox, Spam & Bin"
    val now = System.currentTimeMillis()
    val diffMs = (now - lastSyncAt).coerceAtLeast(0L)
    val mins = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    if (mins < 2) return "Synced just now"
    if (mins < 60) return "Synced ${mins}m ago"
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val syncDate = Instant.ofEpochMilli(lastSyncAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        hours < 12 -> "Synced ${hours}h ago"
        syncDate == today -> "Synced today"
        else -> "Synced ${Dates.short(syncDate)}"
    }
}

internal fun openSystemSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

@Composable
private fun ReminderHealth(tick: Int) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val notif = remember(tick, inspection) { inspection || Notifications.enabled(context) }
    val exact = remember(tick, inspection) { inspection || Reminders.canExact(context) }
    Panel(padded = false) {
        if (notif && exact) {
            ListRow(
                icon = Icons.Filled.CheckCircle,
                title = "Reminders are all set",
                subtitle = "Notifications and exact alarms are ready",
                iconTint = Good,
            )
            return@Panel
        }
        if (!notif) {
            Health("Notifications blocked", "Tap to allow, or reminders stay silent.") {
                openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        if (!exact) {
            if (!notif) RowDivider()
            Health("Exact alarms not allowed", "Reminders may arrive late. Tap to allow.") {
                if (Build.VERSION.SDK_INT >= 31) {
                    openSystemSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                }
            }
        }
    }
}

@Composable
private fun Health(title: String, detail: String, onFix: () -> Unit) {
    ListRow(Icons.Filled.Warning, title, detail, iconTint = Amber, chevron = true, onClick = onFix)
}

private val LockChoices = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes")

@Composable
private fun LockTimeoutRow(timeout: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(
            Icons.Outlined.Lock,
            "Lock after leaving the app",
            LockChoices.firstOrNull { it.first == timeout }?.second ?: "After $timeout seconds",
            iconTint = MaterialTheme.colorScheme.primary,
            chevron = true,
            onClick = { open = true },
        )
        DropdownMenu(open, onDismissRequest = { open = false }, offset = DpOffset(56.dp, 0.dp)) {
            LockChoices.forEach { (sec, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onChange(sec); open = false },
                    trailingIcon = { if (sec == timeout) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun AutofillRow() {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val manager = remember(inspection) { if (inspection) null else context.getSystemService(android.view.autofill.AutofillManager::class.java) }
    var on by remember { mutableStateOf(manager?.hasEnabledAutofillServices() == true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!inspection) on = manager?.hasEnabledAutofillServices() == true }
    ListRow(
        Icons.Outlined.CreditCard,
        if (on) "Card autofill is on" else "Fill cards at checkout",
        if (on) "Only full-number cards are suggested (last-4-only cards stay private). Filling asks for your fingerprint or PIN."
        else "Use CC Manager as the phone's autofill service. Only full-number cards are suggested, and every fill asks for your fingerprint or PIN.",
        iconTint = MaterialTheme.colorScheme.primary,
        chevron = true,
        onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "package:${context.packageName}".toUri())
            runCatching { context.startActivity(intent) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        },
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    CardsTheme {
        val context = LocalContext.current
        SettingsScreen(
            prefs = Prefs(context),
            mailAccounts = listOf(
                MailSource(id = 1, user = "user@example.com", lastSyncAt = System.currentTimeMillis() - 60_000L),
                MailSource(id = 2, user = "billing@example.com", lastSyncAt = System.currentTimeMillis() - 7_200_000L),
            ),
            onMail = {},
            onBack = {},
        )
    }
}
