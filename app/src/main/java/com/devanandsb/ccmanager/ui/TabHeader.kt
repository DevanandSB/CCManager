package com.devanandsb.ccmanager.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.time.LocalTime
import java.time.ZoneId

const val DEFAULT_OWNER_NAME = "Cardholder"

val LocalReplaySplash = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalOwnerName = staticCompositionLocalOf { DEFAULT_OWNER_NAME }
val LocalPersonalizeOwner = staticCompositionLocalOf<(() -> Unit)?> { null }

fun isCustomOwnerName(ownerName: String): Boolean {
    val clean = ownerName.trim()
    return clean.isNotBlank() &&
        !clean.equals(DEFAULT_OWNER_NAME, ignoreCase = true) &&
        !clean.equals("Reserve Member", ignoreCase = true) &&
        !clean.equals("Premier Member", ignoreCase = true) &&
        !clean.equals("My Wallet", ignoreCase = true) &&
        !clean.equals("Private Vault", ignoreCase = true) &&
        !clean.equals("CC Manager", ignoreCase = true)
}

fun timeOfDayGreeting(now: LocalTime = LocalTime.now(ZoneId.systemDefault())): String = when (now.hour) {
    in 5..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    in 17..20 -> "Good Evening"
    else -> "Good Night"
}

data class OwnerResolution(
    val name: String,
    val isPersonalized: Boolean,
    val source: String,
)

private const val OWNER_PREFS = "ui_personalization"
private const val KEY_CUSTOM_NAME = "custom_owner_name"

private fun ownerPrefs(context: Context) =
    context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)

fun getCustomOwnerOverride(context: Context): String? =
    ownerPrefs(context).getString(KEY_CUSTOM_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

fun setCustomOwnerOverride(context: Context, name: String?) {
    val clean = name?.trim()?.takeIf { it.isNotBlank() }
    ownerPrefs(context).edit {
        if (clean == null) remove(KEY_CUSTOM_NAME) else putString(KEY_CUSTOM_NAME, clean)
    }
}

fun getPostSpendPaise(context: Context, cardId: Long): Long =
    if (cardId <= 0L) 0L else ownerPrefs(context).getLong("post_spend_$cardId", 0L).coerceAtLeast(0L)

fun setPostSpendPaise(context: Context, cardId: Long, paise: Long) {
    if (cardId <= 0L) return
    ownerPrefs(context).edit {
        if (paise > 0L) putLong("post_spend_$cardId", paise) else remove("post_spend_$cardId")
    }
}

fun getAppliedUnstmtPayKeys(context: Context, cardId: Long): Set<String> =
    if (cardId <= 0L) emptySet() else ownerPrefs(context).getStringSet("applied_unstmt_pays_$cardId", emptySet())?.toSet().orEmpty()

fun addAppliedUnstmtPayKeys(context: Context, cardId: Long, keys: Set<String>) {
    if (cardId <= 0L || keys.isEmpty()) return
    val merged = getAppliedUnstmtPayKeys(context, cardId) + keys
    ownerPrefs(context).edit { putStringSet("applied_unstmt_pays_$cardId", merged) }
}

fun clearAppliedUnstmtPayKeys(context: Context, cardId: Long) {
    if (cardId <= 0L) return
    ownerPrefs(context).edit { remove("applied_unstmt_pays_$cardId") }
}

private fun toTitleWords(s: String): String =
    s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { w ->
        if (w.length <= 2 && w.all(Char::isLetter)) w.uppercase()
        else w.lowercase().replaceFirstChar { it.titlecase() }
    }

/** Fits long personal names cleanly inside the top header without breaking leading initials. */
private fun fitOwnerHeadline(raw: String): String {
    val titled = toTitleWords(raw)
    if (titled.length <= 18) return titled
    val words = titled.split(' ')
    if (words.size == 1) return titled.take(18)
    val lead = buildList {
        for (w in words) {
            add(w)
            if (w.length >= 3) break
        }
    }.joinToString(" ")
    if (lead.length in 2..18) return lead
    return words.firstOrNull { it.length >= 3 }?.take(18) ?: words[0].take(18)
}

/** Fast synchronous resolution: uses user-entered vault name or greets as Cardholder. */
fun resolveMobileOwnerName(context: Context): String {
    getCustomOwnerOverride(context)?.let { return fitOwnerHeadline(it) }
    return DEFAULT_OWNER_NAME
}

/** Full background resolution without any Contacts or Account permissions. */
suspend fun resolveMobileOwnerNameAsync(context: Context): OwnerResolution = withContext(Dispatchers.IO) {
    getCustomOwnerOverride(context)?.let {
        return@withContext OwnerResolution(fitOwnerHeadline(it), isPersonalized = true, source = "Entered by you")
    }
    OwnerResolution(DEFAULT_OWNER_NAME, isPersonalized = false, source = "Default greeting")
}

@Composable
fun PersonalizeOwnerSheet(
    owner: OwnerResolution,
    onDismiss: () -> Unit,
    onSaveCustom: (String) -> Unit,
    onResetDefault: () -> Unit,
) {
    val context = LocalContext.current
    val hasCustom = remember(owner.name, owner.isPersonalized) { getCustomOwnerOverride(context) != null }
    var customDraft by remember(owner.name, owner.source) {
        mutableStateOf(getCustomOwnerOverride(context).orEmpty())
    }
    val trimmed = customDraft.trim()

    EditSheet(
        title = "How should we address you?",
        note = "Stored only on your phone to personalize your Private Vault.",
        onDismiss = onDismiss,
        confirmLabel = if (trimmed.isNotEmpty()) "Save name" else "Continue as $DEFAULT_OWNER_NAME",
        confirmEnabled = true,
        onConfirm = {
            if (trimmed.isNotEmpty()) onSaveCustom(trimmed)
            else {
                onResetDefault()
                onDismiss()
            }
        },
    ) {
        OutlinedTextField(
            value = customDraft,
            onValueChange = { if (it.length <= 28) customDraft = it },
            label = { Text("Your name (optional)") },
            placeholder = { Text(DEFAULT_OWNER_NAME) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasCustom) {
            TextButton(
                onClick = {
                    onResetDefault()
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Use $DEFAULT_OWNER_NAME")
            }
        }
    }
}
