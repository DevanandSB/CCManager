package com.devanandsb.ccmanager.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.devanandsb.ccmanager.data.Card as CardEntity
import com.devanandsb.ccmanager.data.ImportLog
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.ingest.StatementSync
import com.devanandsb.ccmanager.security.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcomes the user can fix on the phone: a PDF password, or which card it belongs to. */
val FIXABLE = setOf("locked", "unmatched")

/** "Needs you": one row per email we couldn't finish, each opening its own fix. */
@Composable
fun NeedsYouSection(items: List<ImportLog>, onFix: (ImportLog) -> Unit) {
    if (items.isEmpty()) return
    SectionHeader(if (items.size == 1) "1 statement needs you" else "${items.size} statements need you", color = Amber)
    Panel(padded = false, color = Amber.copy(alpha = 0.12f)) {
        items.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            ListRow(
                if (e.outcome == "locked") Icons.Outlined.Lock else Icons.Outlined.CreditCard,
                e.subject.ifBlank { "(no subject)" },
                if (e.outcome == "locked") "Locked. Enter its password" else "Pick which card it's for",
                iconTint = Amber, chevron = true, onClick = { onFix(e) },
            )
        }
    }
}

/** Password or card picker for one kept PDF. Retries on the phone; nothing is fetched again. */
@Composable
fun ImportFixDialog(
    entry: ImportLog,
    cards: List<CardEntity>,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onResult: (StatementSync.Outcome) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val loading = LocalStatementLoading.current
    val locked = entry.outcome == "locked"
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(true) }
    var cardId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    EditSheet(
        title = if (locked) "Unlock statement" else "Which card?",
        note = if (locked) null else entry.subject.ifBlank { "(no subject)" },
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = if (busy) "Trying…" else if (locked) "Unlock & import statement" else "Import",
        confirmEnabled = !busy && (if (locked) password.isNotBlank() else cardId != null),
        onConfirm = {
            busy = true
            scope.launch {
                val r = if (locked && rememberPassword && Auth.deviceSecure(context) && activity != null) {
                    Auth.withVault(activity, "Unlock to save statement password") {
                        loading?.begin(scope, entry.subject)
                        var res: StatementSync.Outcome? = null
                        try {
                            res = StatementSync.fix(context, entry.key, password, cardId, remember = rememberPassword)
                            res
                        } finally {
                            loading?.end(scope, success = res?.statementId != null)
                        }
                    } ?: StatementSync.Outcome(entry.outcome, "Authorization cancelled.")
                } else {
                    loading?.begin(scope, entry.subject)
                    var res: StatementSync.Outcome? = null
                    try {
                        res = StatementSync.fix(context, entry.key, password.takeIf { locked }, cardId, remember = rememberPassword)
                        res
                    } finally {
                        loading?.end(scope, success = res?.statementId != null)
                    }
                }
                busy = false
                if ((r.code in FIXABLE && r.code == entry.outcome) || r.message == "Authorization cancelled.") error = r.message
                else onResult(r)
            }
        },
    ) {
        if (locked) {
            // PDF file summary card matching upload_unlock_statement
            VaultCardContainer(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VaultStatus.warnContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PictureAsPdf,
                            null,
                            tint = VaultStatus.onWarnContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.subject.ifBlank { "Protected statement PDF" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Password-protected PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("PDF password") },
                placeholder = { Text("Enter PDF password") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = MonoMedium,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Usually in the bank's email, e.g. first 4 letters of your name + DDMM of birth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VaultCardContainer(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                onClick = { rememberPassword = !rememberPassword },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Remember password", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Save on your phone so future statements open automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                }
            }
        } else {
            val creditCards = cards.filter { it.isCredit }
            if (creditCards.isEmpty()) {
                Text(
                    "Add a credit card first, then pick it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else Panel(padded = false) {
                creditCards.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { cardId = c.id; error = null }
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BankBadge(c.issuer, size = 34.dp)
                        Text("${c.nickname} •• ${c.last4}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        RadioButton(cardId == c.id, null)
                    }
                }
            }
            if (entry.sender.isNotBlank()) {
                Text(
                    "Statements from this sender will go to this card from now on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Danger) }
        InlineAction("Skip this statement", onSkip)
    }
}

/** Opens the system file picker for a PDF and runs it through the import pipeline. */
@Composable
fun rememberPdfImporter(onOutcome: (StatementSync.Outcome) -> Unit): () -> Unit {
    val importForCard = rememberPdfImporterForCard { outcome, _ -> onOutcome(outcome) }
    return { importForCard(null) }
}

/** Opens the system file picker for a PDF, remembering an optional target [cardId] context. */
@Composable
fun rememberPdfImporterForCard(onOutcome: (StatementSync.Outcome, Long?) -> Unit): (Long?) -> Unit {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val loading = LocalStatementLoading.current
    var pendingCardId by remember { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cid = pendingCardId
        pendingCardId = null
        if (uri != null) scope.launch {
            onOutcome(importUri(context, uri, activity, scope, loading), cid)
        }
    }
    return { cid ->
        pendingCardId = cid
        launcher.launch(arrayOf("application/pdf"))
    }
}

/**
 * Reads a content URI (picked or shared) once into memory and imports it.
 * If saved statement passwords are protected by screen lock, prompts via device lock
 * and automatically continues importing the already-read bytes.
 */
suspend fun importUri(
    context: Context,
    uri: Uri,
    activity: FragmentActivity? = context as? FragmentActivity,
    loadingScope: kotlinx.coroutines.CoroutineScope? = null,
    loadingController: StatementLoadingController? = null,
): StatementSync.Outcome {
    val read = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "statement.pdf"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
            name to bytes
        }.getOrNull()
    } ?: return StatementSync.Outcome("error", "Couldn't read that file.")
    val (fileName, bytes) = read
    if (bytes.size > 20_000_000) return StatementSync.Outcome("error", "That PDF is too large.")

    var trackingActive = false
    val beginLoading: () -> Unit = {
        if (!trackingActive && loadingController != null && loadingScope != null) {
            trackingActive = true
            loadingController.begin(loadingScope, fileName)
        }
    }
    val endLoading: (Boolean) -> Unit = { success ->
        if (trackingActive && loadingController != null && loadingScope != null) {
            trackingActive = false
            loadingController.end(loadingScope, success = success)
        }
    }

    beginLoading()
    var out = try {
        StatementSync.importFile(context, fileName, bytes)
    } catch (t: Throwable) {
        endLoading(false)
        throw t
    }
    if (out.code == "auth_required") {
        endLoading(false)
        val unlocked = if (activity != null && Auth.deviceSecure(activity)) {
            Auth.unlockVault(
                activity,
                "Verify screen lock",
                "Unlock saved statement passwords to open this PDF",
            ) {
                try {
                    withContext(Dispatchers.IO) {
                        val ledger = com.devanandsb.ccmanager.CardsApp.from(context).repo.ledger
                        ledger.profileOrThrow(ledger.profileSourceOnce())
                    }
                    true
                } catch (e: android.security.keystore.UserNotAuthenticatedException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
            } ?: false
        } else {
            false
        }
        out = if (unlocked) {
            beginLoading()
            try {
                StatementSync.importFile(context, fileName, bytes)
            } catch (t: Throwable) {
                endLoading(false)
                throw t
            }
        } else {
            out.copy(code = "locked", message = "PDF is password-protected.")
        }
        if (out.code == "auth_required") {
            out = out.copy(code = "locked", message = "PDF is password-protected.")
        }
    }
    endLoading(out.statementId != null)
    return out
}
