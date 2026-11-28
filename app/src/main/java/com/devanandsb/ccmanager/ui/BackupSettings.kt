package com.devanandsb.ccmanager.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import com.devanandsb.ccmanager.CardsApp
import com.devanandsb.ccmanager.data.Backup
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.reminders.Reminders
import com.devanandsb.ccmanager.security.Auth
import com.devanandsb.ccmanager.security.SecretDraft
import com.devanandsb.ccmanager.security.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings rows for the encrypted backup file: export (password → unlock → save) and restore
 * (pick → password → summary → confirm → unlock → replace).
 */
@Composable
fun BackupSection() {
    if (LocalInspectionMode.current) {
        Panel(padded = false) {
            ListRow(Icons.Outlined.CloudUpload, "Save a backup file", "Never backed up. Save a password-protected copy of everything.", chevron = true)
            RowDivider()
            ListRow(Icons.Outlined.CloudDownload, "Restore from backup", "Replaces everything on this phone", chevron = true)
        }
        return
    }
    val context = LocalContext.current
    val app = remember { CardsApp.from(context) }
    var lastBackup by remember { mutableLongStateOf(app.prefs.lastBackupAt) }
    val exportBackup = rememberExportBackup { lastBackup = app.prefs.lastBackupAt }
    val restore = rememberRestoreBackup()

    Panel(padded = false) {
        ListRow(
            Icons.Outlined.CloudUpload, "Save a backup file",
            if (lastBackup == 0L) "Never backed up. Save a password-protected copy of everything."
            else "Last saved " + Dates.long(Instant.ofEpochMilli(lastBackup).atZone(ZoneId.systemDefault()).toLocalDate()),
            chevron = true, onClick = exportBackup,
        )
        RowDivider()
        ListRow(
            Icons.Outlined.CloudDownload, "Restore from backup", "Replaces everything on this phone",
            chevron = true, onClick = restore,
        )
    }
}

/** Export backup flow with sheet & document saver; returns the action that starts it. */
@Composable
fun rememberExportBackup(onSaved: () -> Unit = {}): () -> Unit {
    if (LocalInspectionMode.current) return {}
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val app = remember { CardsApp.from(context) }
    val scope = rememberCoroutineScope()

    var exporting by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<ByteArray?>(null) } // sealed backup waiting for a save location
    var busy by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingFile
        pendingFile = null
        if (uri != null && bytes != null) scope.launch {
            val ok = write(context, uri, bytes)
            if (ok) {
                app.prefs.lastBackupAt = System.currentTimeMillis()
                onSaved()
            }
            toast(context, if (ok) "Backup saved" else "Couldn't save the backup")
        }
    }

    if (exporting) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        val valid = pw.length >= 8 && pw == pw2
        EditSheet(
            title = "Save a backup file",
            note = "Choose a password for the file. You'll need it to restore, and it can't be recovered if you forget it.",
            onDismiss = { if (!busy) exporting = false },
            confirmLabel = if (busy) "Preparing…" else "Continue",
            confirmEnabled = valid && !busy,
            onConfirm = {
                // Take the password out of Compose state now; the char copy is wiped once sealed.
                val password = pw.toCharArray()
                pw = ""; pw2 = ""
                scope.launch {
                    busy = true
                    val r = runCatching {
                        // Card numbers and CVVs are behind the fingerprint; unlock once for all of them.
                        val secrets = Auth.withVault(activity, "Save a backup file") {
                            withContext(Dispatchers.IO) {
                                app.db.backup().secrets().associateBy({ it.cardId }) { Vault.decrypt(it) }
                            }
                        } ?: return@runCatching null
                        withContext(Dispatchers.Default) {
                            Backup.seal(Backup.collect(app.db, secrets, app.prefs), password)
                        }
                    }
                    password.fill('\u0000')
                    busy = false
                    r.exceptionOrNull()?.let {
                        if (it is CancellationException) throw it
                        toast(context, if (it is Vault.KeyLost) it.message ?: "Couldn't create backup." else "Couldn't create backup. Please try again.")
                        return@launch
                    }
                    val bytes = r.getOrNull()
                    if (bytes == null) { toast(context, "Backup cancelled"); return@launch }
                    exporting = false
                    pendingFile = bytes
                    saver.launch("cards-backup-${LocalDate.now()}.cardsbackup")
                }
            },
        ) {
            PasswordField("Backup password", pw, error = pw.isNotEmpty() && pw.length < 8, hint = "At least 8 characters") { pw = it }
            PasswordField("Repeat password", pw2, error = pw2.isNotEmpty() && pw2 != pw, hint = if (pw2.isNotEmpty() && pw2 != pw) "Doesn't match" else null) { pw2 = it }
            Text(
                "Includes cards with their numbers and CVVs, statements, transactions, payments, notes and settings. " +
                    "Not included: your Gmail app password.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    return { exporting = true }
}

/**
 * Restore: pick a file → password → summary → confirm → unlock → replace everything. Shows its own sheets
 * and dialogs; returns the action that starts it. [onRestored] gets the number of cards restored.
 */
@Composable
fun rememberRestoreBackup(onRestored: (Int) -> Unit = {}): () -> Unit {
    if (LocalInspectionMode.current) return {}
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return {}
    val app = remember { CardsApp.from(context) }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<ByteArray?>(null) } // picked file waiting for its password
    var ready by remember { mutableStateOf<Backup.Contents?>(null) } // decrypted, waiting for confirmation
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            opening = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (opening == null) toast(context, "Couldn't read that file")
        }
    }

    opening?.let { file ->
        var pw by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        EditSheet(
            title = "Restore from backup", note = "Enter the password you chose when exporting.",
            onDismiss = { if (!busy) opening = null },
            confirmLabel = if (busy) "Opening…" else "Open backup",
            confirmEnabled = pw.isNotEmpty() && !busy,
            onConfirm = {
                val password = pw.toCharArray()
                pw = ""
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.Default) { runCatching { Backup.parse(Backup.open(file, password)) } }
                    password.fill('\u0000')
                    busy = false
                    r.onSuccess { ready = it; opening = null }
                        .onFailure { error = it.message ?: "Couldn't open this backup" }
                }
            },
        ) {
            PasswordField("Backup password", pw, error = error != null, hint = error) { pw = it; error = null }
        }
    }

    ready?.let { c ->
        val s = c.summary
        val made = Dates.long(Instant.ofEpochMilli(s.createdAt).atZone(ZoneId.systemDefault()).toLocalDate())
        ConfirmDialog(
            title = "Replace everything?",
            body = "This backup is from $made: ${s.cards} card${if (s.cards == 1) "" else "s"}, ${s.statements} statements, " +
                "${s.transactions} transactions. Everything currently on this phone is replaced. This can't be undone.",
            confirmLabel = "Restore",
            onDismiss = { ready = null },
            onConfirm = {
                ready = null
                scope.launch {
                    // Alarms are keyed by card id; the wipe would leave this phone's ones firing (maybe for another card).
                    val oldIds = app.db.backup().cards().map { it.id }
                    val n = runCatching {
                        // Re-encrypt card secrets with this phone's own keys (needs an unlock). JSON and Keystore
                        // work stays off the main thread; the prompt itself comes back to it.
                        Auth.withVault(activity, "Restore backup") {
                            val secureDevice = Auth.deviceSecure(activity)
                            withContext(Dispatchers.Default) {
                                Backup.restore(app.db, c, app.prefs, requireProfileAuth = secureDevice) { d: SecretDraft ->
                                    Vault.encrypt(d).let { Backup.CardSecretBlobs(it.number, it.expiry, it.cvv, it.holder) }
                                }
                            }
                        }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        toast(context, if (it is Vault.KeyLost || it is Backup.WrongPassword) it.message ?: "Couldn't restore backup." else "Couldn't restore backup. Please try again.")
                        return@launch
                    } ?: run {
                        toast(context, "Restore cancelled"); return@launch
                    }
                    // The wipe only happens on success (one transaction), so cancel the old alarms now.
                    oldIds.forEach { Reminders.cancel(context, it) }
                    Appearance.paletteId = app.prefs.palette
                    Appearance.mode = app.prefs.themeMode
                    Reminders.rescheduleAll(context)
                    toast(context, "Restored $n card${if (n == 1) "" else "s"}")
                    onRestored(n)
                }
            },
        )
    }
    return { picker.launch(arrayOf("*/*")) }
}

@Composable
private fun PasswordField(label: String, value: String, error: Boolean, hint: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, isError = error,
        shape = MaterialTheme.shapes.small,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

private suspend fun write(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
