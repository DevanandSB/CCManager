package com.devanandsb.ccmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.CardKind
import com.devanandsb.ccmanager.data.CardSecret
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.BinLookup
import com.devanandsb.ccmanager.domain.CardNumbers
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Issuers
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.security.EncryptedDraft
import com.devanandsb.ccmanager.security.SecretDraft
import com.devanandsb.ccmanager.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val TOP_FIELDS = setOf("number", "last4", "expiry", "cvv", "nickname")

@Composable
fun EditScreen(
    cardId: Long,
    repo: Repo,
    defaultLeads: String,
    onDone: () -> Unit,
    startWith: String? = null,
    onOpenCard: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val isNew = cardId == 0L

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var original by rememberSaveable { mutableStateOf<Long?>(null) }
    var pinned by rememberSaveable { mutableStateOf(false) }

    var nickname by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf(Issuers.all.last().name) }
    var network by rememberSaveable { mutableStateOf("Visa") }
    var last4 by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }

    // Pending bill amount (goes into outstandingPaise) + spend after statement (informational only)
    var hasPending by rememberSaveable { mutableStateOf(false) }
    var pendingAmount by rememberSaveable { mutableStateOf("") }
    var hasPostSpend by rememberSaveable { mutableStateOf(false) }
    var postSpendAmount by rememberSaveable { mutableStateOf("") }

    // Billing cycle & due date
    var knowBillingDetails by rememberSaveable { mutableStateOf(false) }
    var statementDay by rememberSaveable { mutableStateOf("") }
    var dueOffset by rememberSaveable { mutableStateOf(Issuers.all.first().dueOffset.toString()) }
    var billingSet by rememberSaveable { mutableStateOf(false) }
    var loadedDue by rememberSaveable { mutableStateOf<Long?>(null) }

    var leads by rememberSaveable { mutableStateOf(defaultLeads) }
    var notes by rememberSaveable { mutableStateOf("") }
    var art by rememberSaveable { mutableStateOf<String?>(null) }

    // Vaulted credentials (encrypted at rest)
    var number by remember { mutableStateOf("") }
    var manualLast4Mode by rememberSaveable { mutableStateOf(false) }
    var numberAutoFilledLast4 by rememberSaveable { mutableStateOf(false) }
    var expiry by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }
    var stored by rememberSaveable { mutableStateOf(listOf(false, false, false, false)) }

    var networkManual by rememberSaveable { mutableStateOf(false) }
    var issuerManual by rememberSaveable { mutableStateOf(false) }
    var issuerFromCard by rememberSaveable { mutableStateOf(false) }
    var cardIssuer by remember { mutableStateOf<String?>(null) }
    var dueOffsetManual by rememberSaveable { mutableStateOf(false) }

    var openExtras by rememberSaveable { mutableStateOf(false) }
    var showDesign by rememberSaveable { mutableStateOf(false) }
    var showBank by rememberSaveable { mutableStateOf(false) }
    var netOpen by remember { mutableStateOf(false) }
    var pickingDue by rememberSaveable { mutableStateOf(false) }

    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    fun clear(key: String) { if (key in errors) errors -= key }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val canTap = nfcAvailable()
    var tapping by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val locked = LocalLocked.current

    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(locked) {
        if (locked || started || startWith == null) return@LaunchedEffect
        started = true
        if (startWith == "scan") {
            scanning = true
        } else if (startWith == "tap") {
            if (canTap) tapping = true
            else android.widget.Toast.makeText(context, "This phone can't read cards by tap. Scan it or type the details.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (tapping && !locked) TapCardSheet(
        onRead = { c ->
            tapping = false
            number = c.number
            last4 = c.number.takeLast(4)
            manualLast4Mode = false
            numberAutoFilledLast4 = true
            (c.network ?: CardNumbers.detectNetwork(c.number))?.let { if (!networkManual) network = it }
            c.expiry?.let { expiry = it }
            c.issuer?.let { cardIssuer = it }
            errors = errors - "number" - "last4" - "expiry"
        },
        onDismiss = { tapping = false },
    )

    if (scanning && !locked) ScanCardScreen(
        onResult = { s ->
            scanning = false
            s.number?.let { n ->
                number = n
                last4 = n.takeLast(4)
                manualLast4Mode = false
                numberAutoFilledLast4 = true
                CardNumbers.detectNetwork(n)?.let { if (!networkManual) network = it }
            }
            s.expiry?.let { expiry = it }
            s.issuer?.let { cardIssuer = it }
            errors = errors - "number" - "last4" - "expiry"
        },
        onDismiss = { scanning = false },
    )

    LaunchedEffect(cardId) {
        if (loaded) return@LaunchedEffect
        val cw = repo.cardWithDue(cardId) ?: return@LaunchedEffect onDone()
        val c = cw.card
        nickname = c.nickname; issuer = c.issuer; network = c.network; last4 = c.last4
        limit = Money.toInput(c.limitPaise)
        hasPending = c.outstandingPaise > 0L
        pendingAmount = if (c.outstandingPaise > 0L) Money.toInput(c.outstandingPaise) else ""
        val savedPostSpend = getPostSpendPaise(appContext, cardId)
        hasPostSpend = savedPostSpend > 0L
        postSpendAmount = if (savedPostSpend > 0L) Money.toInput(savedPostSpend) else ""
        statementDay = c.statementDay.toString(); dueOffset = c.dueOffsetDays.toString()
        billingSet = c.billingSet
        knowBillingDetails = c.billingSet || c.limitPaise > 0L || c.outstandingPaise > 0L || savedPostSpend > 0L
        dueOffsetManual = c.billingSet
        loadedDue = cw.due.dueDate.toEpochDay().takeIf { c.billingSet }
        leads = c.reminderLeads; notes = c.notes; original = c.createdAt; pinned = c.pinned; art = c.art
        val sec = repo.secret(cardId)
        sec?.let { stored = listOf(it.numberEnc != null, it.expiryEnc != null, it.cvvEnc != null, it.holderEnc != null) }
        val hasFullStored = sec?.let { it.numberEnc != null || it.expiryEnc != null || it.cvvEnc != null } ?: false
        manualLast4Mode = !hasFullStored
        loaded = true
    }

    fun cycleDue(): LocalDate? {
        val d = statementDay.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val o = dueOffset.toIntOrNull()?.takeIf { it in 0..60 } ?: return null
        return nextDue(d, o)
    }

    fun pickDue(due: LocalDate) {
        val existingDay = statementDay.toIntOrNull()?.takeIf { it in 1..31 }
        if (existingDay != null) {
            val ymPrev = YearMonth.from(due).minusMonths(1)
            val sCurr = Billing.statementDate(YearMonth.from(due), existingDay)
            val sPrev = Billing.statementDate(ymPrev, existingDay)
            val stmt = if (!sCurr.isAfter(due) && ChronoUnit.DAYS.between(sCurr, due) > 0) sCurr else sPrev
            val diff = ChronoUnit.DAYS.between(stmt, due).toInt()
            if (diff in 1..60) {
                dueOffset = diff.toString()
                dueOffsetManual = true
            } else {
                val rawDiff = ChronoUnit.DAYS.between(sCurr, due).toInt()
                if (rawDiff == 0) {
                    dueOffset = "0"
                    dueOffsetManual = true
                } else {
                    val off = dueOffset.toIntOrNull()?.takeIf { dueOffsetManual && it in 0..60 } ?: Issuers.byName(issuer).dueOffset
                    dueOffset = off.toString()
                    statementDay = due.minusDays(off.toLong()).dayOfMonth.toString()
                }
            }
        } else {
            val off = dueOffset.toIntOrNull()?.takeIf { dueOffsetManual && it in 0..60 } ?: Issuers.byName(issuer).dueOffset
            dueOffset = off.toString()
            statementDay = due.minusDays(off.toLong()).dayOfMonth.toString()
        }
        knowBillingDetails = true
        billingSet = true; loadedDue = due.toEpochDay()
        errors = errors - "statementDay" - "dueOffset"
    }

    fun changeIssuer(name: String) {
        if (issuer == name) return
        val keepDue = if (billingSet) cycleDue() else null
        issuer = name
        if (isNew && !dueOffsetManual) {
            val off = Issuers.byName(name).dueOffset
            dueOffset = off.toString()
            keepDue?.let { statementDay = it.minusDays(off.toLong()).dayOfMonth.toString() }
        }
    }

    LaunchedEffect(cardIssuer) {
        val name = cardIssuer ?: return@LaunchedEffect
        if (!issuerManual) { changeIssuer(name); issuerFromCard = true }
        cardIssuer = null
    }

    val digits = number.filter(Char::isDigit)
    LaunchedEffect(digits.take(6)) {
        if (digits.length < 6) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { BinLookup.find(appContext, digits) } ?: return@LaunchedEffect
        val resolved = info.issuer?.let { raw ->
            val canon = Issuers.canonical(raw)
            Issuers.all.firstOrNull { it.name.equals(canon, ignoreCase = true) }
                ?: Issuers.detect(raw)?.let { detected -> Issuers.all.firstOrNull { it.name == detected } }
        }
        resolved?.let { iss ->
            if (!issuerManual && !issuerFromCard) changeIssuer(iss.name)
        }
    }
    val numberWarning = when {
        manualLast4Mode || digits.isEmpty() -> null
        digits.length < 12 -> "Too short"
        !CardNumbers.luhnValid(digits) -> "Doesn't look like a valid card number"
        else -> null
    }

    var duplicate by remember { mutableStateOf<PendingSave?>(null) }
    var savedPostSpendP by remember { mutableLongStateOf(0L) }

    suspend fun commit(card: Card, enc: EncryptedDraft?, clearSecrets: Boolean = false) {
        val savedId = repo.save(card, enc, clearSecrets = clearSecrets)
        setPostSpendPaise(appContext, savedId, savedPostSpendP)
        onDone()
    }

    fun save() {
        error = null
        val effectiveDigits = if (manualLast4Mode) "" else digits
        val effectiveExpiry = if (manualLast4Mode) "" else expiry.trim()
        val effectiveCvv = if (manualLast4Mode) "" else cvv.trim()
        val l4 = if (effectiveDigits.length >= 12) effectiveDigits.takeLast(4) else last4.trim()
        val effectiveBilling = knowBillingDetails && billingSet
        val day = statementDay.trim().toIntOrNull().let { if (effectiveBilling) it else it?.takeIf { d -> d in 1..31 } ?: 1 }
        val offset = dueOffset.trim().toIntOrNull().let { if (effectiveBilling) it else it?.takeIf { o -> o in 0..60 } ?: Issuers.byName(issuer).dueOffset }
        val limitP = if (knowBillingDetails) Money.parse(limit) else 0L
        val pendingP = if (knowBillingDetails && hasPending && pendingAmount.isNotBlank()) Money.parse(pendingAmount) else 0L
        val postSpendP = if (knowBillingDetails && hasPostSpend && postSpendAmount.isNotBlank()) Money.parse(postSpendAmount) else 0L

        errors = buildMap {
            if (!manualLast4Mode) {
                if (effectiveDigits.isEmpty() && (isNew || !stored[0])) {
                    put("number", "Enter card number, or switch to Last 4 only")
                } else if (effectiveDigits.isNotEmpty() && numberWarning != null) {
                    put("number", "Check the card number" + if (stored[0]) ", or clear it to keep the stored one" else ", or clear it")
                }
                if (!Regex("\\d{4}").matches(l4)) put("number", "Enter a complete card number, or switch to Last 4 only")
                if (effectiveExpiry.isNotBlank() && !Regex("(0[1-9]|1[0-2])/\\d{2}").matches(effectiveExpiry)) put("expiry", "Use MM/YY")
                if (effectiveCvv.isNotBlank() && !Regex("\\d{3,4}").matches(effectiveCvv)) put("cvv", "3 or 4 digits")
            } else {
                if (!Regex("\\d{4}").matches(l4)) put("last4", "Enter the last 4 digits")
            }
            if (day == null || day !in 1..31) put("statementDay", "Pick the bill generated day")
            if (offset == null || offset !in 0..60) put("dueOffset", "Invalid due date offset")
            if (limitP == null) put("limit", "Not a valid amount")
            if (knowBillingDetails && hasPending && pendingAmount.isNotBlank() && pendingP == null) put("pendingAmount", "Not a valid amount")
            if (knowBillingDetails && hasPostSpend && postSpendAmount.isNotBlank() && postSpendP == null) put("postSpendAmount", "Not a valid amount")
        }
        if (errors.isNotEmpty()) {
            val top = errors.keys.any { it in TOP_FIELDS }
            val bottom = errors.keys.any { it == "pendingAmount" || it == "postSpendAmount" }
            scope.launch {
                val target = when {
                    top -> 0
                    bottom -> scroll.maxValue
                    else -> scroll.maxValue / 2
                }
                scroll.animateScrollTo(target)
            }
            return
        }

        val resolvedNickname = nickname.trim().ifBlank { "$issuer Credit Card" }
        savedPostSpendP = (postSpendP ?: 0L).takeIf { knowBillingDetails && hasPostSpend } ?: 0L
        val card = Card(
            id = cardId,
            nickname = resolvedNickname,
            issuer = issuer,
            network = network,
            last4 = l4,
            limitPaise = limitP ?: 0L,
            outstandingPaise = pendingP ?: 0L,
            statementDay = day!!,
            dueOffsetDays = offset!!,
            color = Issuers.byName(issuer).color,
            pinned = pinned,
            reminderLeads = Billing.parseLeads(leads).joinToString(","),
            notes = notes.trim(),
            createdAt = original ?: System.currentTimeMillis(),
            kind = CardKind.CREDIT,
            art = art,
            billingSet = effectiveBilling,
        )
        val draft = SecretDraft(effectiveDigits, effectiveExpiry, effectiveCvv, "")
        val shouldClearSecrets = !isNew && manualLast4Mode && stored.any { it }
        val networkKnown = !isNew || networkManual || CardNumbers.detectNetwork(effectiveDigits) != null

        scope.launch {
            saving = true
            try {
                val enc = if (draft.isEmpty || manualLast4Mode) null else {
                    withContext(Dispatchers.IO) { Vault.encrypt(draft) }
                }
                if (isNew || effectiveDigits.length >= 12) {
                    val full = effectiveDigits.takeIf { it.length >= 12 && enc?.number != null }
                    findDuplicate(repo, card, full, networkKnown)?.let { dup ->
                        duplicate = PendingSave(card, enc, dup)
                        return@launch
                    }
                }
                commit(card, enc, clearSecrets = shouldClearSecrets)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is Vault.KeyLost) e.message
                else "Couldn't save your card details right now. Nothing was saved."
                scroll.animateScrollTo(0)
            } finally {
                saving = false
            }
        }
    }

    fun snapshot() = listOf(
        manualLast4Mode, nickname, issuer, network, last4, limit, knowBillingDetails, hasPending, pendingAmount, hasPostSpend, postSpendAmount,
        statementDay, dueOffset, billingSet, leads, notes, art.orEmpty(),
    ).joinToString("\u0000")
    var baseline by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded) { if (loaded && baseline == null) baseline = snapshot() }
    val dirty = loaded && (
        (baseline != null && snapshot() != baseline) ||
            (!manualLast4Mode && (number.isNotEmpty() || expiry.isNotEmpty() || cvv.isNotEmpty()))
        )
    fun close() { if (dirty && !saving) confirmDiscard = true else onDone() }
    BackHandler(enabled = dirty && !saving) { confirmDiscard = true }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = if (isNew) "This card hasn't been added yet. What you've typed will be lost." else "Your edits to this card haven't been saved.",
            confirmLabel = "Discard",
            onDismiss = { confirmDiscard = false },
            onConfirm = { confirmDiscard = false; onDone() },
        )
    }

    if (showDesign) DesignSheet(issuer, nickname, art, onSelect = { art = it }, onDismiss = { showDesign = false })

    val shownDue = if (!billingSet || !knowBillingDetails) null else loadedDue?.let(LocalDate::ofEpochDay) ?: cycleDue()
    if (pickingDue && !locked) DueDateDialog(
        initial = shownDue,
        removable = billingSet,
        onPick = { pickingDue = false; pickDue(it) },
        onRemove = { pickingDue = false; billingSet = false; loadedDue = null },
        onDismiss = { pickingDue = false },
    )

    duplicate?.takeIf { !locked }?.let { p ->
        val d = p.match
        AlertDialog(
            onDismissRequest = { duplicate = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("You already have this card") },
            text = {
                Text("${d.nickname} (${d.issuer} ending ${d.last4}) " + if (d.archivedAt != null) "is in Archived cards." else "is already saved.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        duplicate = null
                        scope.launch {
                            saving = true
                            try {
                                commit(p.card, p.enc)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't save the card. Please try again."
                                scroll.animateScrollTo(0)
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text("Add anyway") }
            },
            dismissButton = { TextButton(onClick = { duplicate = null; onOpenCard(d.id) }) { Text("Open existing") } },
        )
    }

    if (showBank) BankSheet(issuer, onSelect = { issuerManual = true; changeIssuer(it) }, onDismiss = { showBank = false })

    val isDark = LocalIsDark.current
    val matched = CardArts.match(issuer, nickname)
    val chosen = CardArts.byId(art)
    val shownArt = when (art) { null -> matched; BANK_COLOURS -> null; else -> chosen }
    val previewColor = Issuers.byName(issuer).color
    val ambientColor = remember(shownArt, previewColor) {
        Color(shownArt?.accent?.firstOrNull() ?: shownArt?.colors?.firstOrNull() ?: previewColor)
    }
    val previewInk = if (shownArt != null) (if (shownArt.light) Color(0xFF15171C) else Color.White) else cardInk(previewColor)
    val previewL4 = if (!manualLast4Mode && digits.length >= 12) digits.takeLast(4) else last4.ifBlank { "••••" }
    val fieldColors = editFieldColors()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.verticalGradient(
                        0f to ambientColor.copy(alpha = if (isDark) 0.22f else 0.10f),
                        0.6f to ambientColor.copy(alpha = if (isDark) 0.07f else 0.03f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                VaultTopAppBar(
                    title = if (isNew) "Add credit card" else "Edit credit card",
                    subtitle = if (issuer == "Other") "Credit Card" else "$issuer · Credit Card",
                    onBack = ::close,
                    backIcon = Icons.Filled.Close,
                    backDescription = "Cancel",
                    actions = {
                        if (loaded) {
                            Button(
                                onClick = ::save,
                                enabled = !saving,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                                modifier = Modifier.height(38.dp),
                            ) {
                                Text(if (isNew) "Save card" else "Save", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    },
                )
            },
        ) { pad ->
            if (!loaded) return@Scaffold
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = Gutter)
                    .padding(top = 14.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                error?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                // Live Interactive CRED / Stitch-style Credit Card Preview
                LiveCardPreviewHero(
                    issuer = issuer,
                    nickname = nickname,
                    network = network,
                    digits = if (manualLast4Mode) "" else digits,
                    last4 = previewL4,
                    expiry = if (manualLast4Mode) "" else expiry,
                    cvv = if (manualLast4Mode) "" else cvv,
                    last4Only = manualLast4Mode,
                    stored = stored,
                    shownArt = shownArt,
                    previewColor = previewColor,
                    previewInk = previewInk,
                    onClickDesign = { showDesign = true },
                )

                // Quick-capture strip
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CaptureActionTile(Icons.Outlined.PhotoCamera, "Scan card", Modifier.weight(1f)) { scanning = true }
                    if (canTap) CaptureActionTile(Icons.Outlined.Contactless, "Tap card (NFC)", Modifier.weight(1f)) { tapping = true }
                }

                // Section 1: Card Details (2-way choice: Full number OR Last 4 only -> Nickname -> Bank & Network)
                VaultCardContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionEyebrow("1. Card details")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    if (manualLast4Mode) Icons.Outlined.Shield else Icons.Outlined.Lock,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    if (manualLast4Mode) "Privacy only" else "Locked on your phone",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        CardEntryModeSelector(
                            last4Only = manualLast4Mode,
                            onSelect = { wantLast4Only ->
                                if (wantLast4Only == manualLast4Mode) return@CardEntryModeSelector
                                if (wantLast4Only) {
                                    if (digits.length >= 4 && last4.isBlank()) {
                                        last4 = digits.takeLast(4)
                                    }
                                    number = ""
                                    expiry = ""
                                    cvv = ""
                                    numberAutoFilledLast4 = false
                                    manualLast4Mode = true
                                    errors = errors - "number" - "expiry" - "cvv"
                                } else {
                                    manualLast4Mode = false
                                    errors = errors - "last4"
                                }
                            },
                        )

                        val detected = CardNumbers.detectNetwork(digits)
                        if (!manualLast4Mode) {
                            OutlinedTextField(
                                value = number,
                                onValueChange = { v ->
                                    val clean = v.filter(Char::isDigit).take(19)
                                    if (clean.take(6) != digits.take(6)) {
                                        issuerFromCard = false
                                    }
                                    number = clean
                                    CardNumbers.detectNetwork(clean)?.let { if (!networkManual) network = it }
                                    if (clean.length >= 12) {
                                        last4 = clean.takeLast(4)
                                        numberAutoFilledLast4 = true
                                    } else if (clean.isEmpty() && numberAutoFilledLast4) {
                                        last4 = ""
                                        numberAutoFilledLast4 = false
                                    }
                                    clear("number"); clear("last4")
                                },
                                label = { Text("Card number") },
                                placeholder = { Text(if (stored[0]) "Stored (•••• ${last4.ifBlank { "••••" }}). Type to replace." else "0000 0000 0000 0000") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = fieldColors,
                                textStyle = MonoMedium,
                                isError = errors["number"] != null || numberWarning != null,
                                supportingText = (errors["number"] ?: numberWarning)?.let { { Text(it) } },
                                trailingIcon = {
                                    if (detected != null) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                            modifier = Modifier.padding(end = 10.dp),
                                        ) {
                                            Text(
                                                detected.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                },
                                visualTransformation = CardNumberSpacing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(
                                value = last4,
                                onValueChange = { v ->
                                    val clean = v.filter(Char::isDigit).take(4)
                                    last4 = clean
                                    numberAutoFilledLast4 = false
                                    clear("last4"); clear("number")
                                },
                                label = { Text("Last 4 digits") },
                                placeholder = { Text("1234") },
                                prefix = { Text("••••  ", style = MonoMedium) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = fieldColors,
                                textStyle = MonoMedium,
                                isError = errors["last4"] != null,
                                supportingText = (errors["last4"] ?: "Enough to track bills, statements & reminders")
                                    .let { msg -> { Text(msg) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            OutlinedTextField(
                                value = if (manualLast4Mode) "" else expiry.filter(Char::isDigit),
                                onValueChange = { v ->
                                    val d = v.filter(Char::isDigit).take(4)
                                    expiry = if (d.length > 2) d.take(2) + "/" + d.drop(2) else d
                                    clear("expiry")
                                },
                                enabled = !manualLast4Mode,
                                label = { Text("Expiry") },
                                placeholder = {
                                    Text(
                                        when {
                                            manualLast4Mode -> "Disabled"
                                            stored[1] -> "••/••"
                                            else -> "MM/YY"
                                        },
                                    )
                                },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = fieldColors,
                                textStyle = MonoMedium,
                                isError = !manualLast4Mode && errors["expiry"] != null,
                                supportingText = if (!manualLast4Mode) errors["expiry"]?.let { { Text(it) } } else null,
                                visualTransformation = ExpirySlash,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = if (manualLast4Mode) "" else cvv,
                                onValueChange = { cvv = it.filter(Char::isDigit).take(4); clear("cvv") },
                                enabled = !manualLast4Mode,
                                label = { Text("CVV") },
                                placeholder = {
                                    Text(
                                        when {
                                            manualLast4Mode -> "Disabled"
                                            stored[2] -> "•••"
                                            else -> "123"
                                        },
                                    )
                                },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                colors = fieldColors,
                                textStyle = MonoMedium,
                                isError = !manualLast4Mode && errors["cvv"] != null,
                                supportingText = if (!manualLast4Mode) errors["cvv"]?.let { { Text(it) } } else null,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                                modifier = Modifier.weight(1f),
                            )
                        }

                        AnimatedVisibility(visible = manualLast4Mode) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), MaterialTheme.shapes.small),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Lock,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        "MM/YY, CVV & checkout suggestions in other apps are disabled.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Field(
                            nickname, { nickname = it; clear("nickname") },
                            "Card nickname",
                            hint = "e.g. $issuer Credit Card",
                            caps = KeyboardCapitalization.Words,
                            colors = fieldColors,
                        )

                        // Restored clean two-column select bar for Bank & Card Network
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SelectField(
                                "Bank", issuer, open = showBank, onClick = { showBank = true }, modifier = Modifier.weight(1.4f),
                                leadingIcon = { BankBadge(issuer, size = 24.dp) },
                                colors = fieldColors,
                            )
                            SelectField(
                                "Network", network, open = netOpen, onClick = { netOpen = true }, modifier = Modifier.weight(1f),
                                inline = { NetworkMark(network) },
                                colors = fieldColors,
                            ) {
                                DropdownMenu(expanded = netOpen, onDismissRequest = { netOpen = false }) {
                                    Issuers.networks.forEach { n ->
                                        DropdownMenuItem(
                                            text = { Text(n) },
                                            leadingIcon = { Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { NetworkMark(n) } },
                                            trailingIcon = if (n == network) { { Icon(Icons.Filled.Check, null) } } else null,
                                            onClick = { network = n; networkManual = true; netOpen = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: Optional Billing Details On-Off Switch + Luxury Statement-Sync Copy
                VaultCardContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !knowBillingDetails
                                    knowBillingDetails = next
                                    if (!next) {
                                        billingSet = false
                                        loadedDue = null
                                        errors = errors - "statementDay" - "dueOffset" - "limit" - "pendingAmount" - "postSpendAmount"
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionEyebrow("2. Billing profile")
                                Text(
                                    "Do you know your billing date?",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Add your billing date, payment due date, and credit limit now—or leave this off and we’ll effortlessly extract every detail from the statement you upload.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = knowBillingDetails,
                                onCheckedChange = { checked ->
                                    knowBillingDetails = checked
                                    if (!checked) {
                                        billingSet = false
                                        loadedDue = null
                                        errors = errors - "statementDay" - "dueOffset" - "limit" - "pendingAmount" - "postSpendAmount"
                                    }
                                },
                            )
                        }

                        AnimatedVisibility(visible = !knowBillingDetails) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.shapes.medium),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Zero manual setup required. Upload your monthly PDF statement anytime to populate your cycle, limit, and dues automatically.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = knowBillingDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                                val sd = statementDay.toIntOrNull()?.takeIf { it in 1..31 }
                                val off = dueOffset.toIntOrNull()

                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (billingSet) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            0.75.dp,
                                            if (billingSet) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                            MaterialTheme.shapes.medium,
                                        ),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Schedule,
                                            null,
                                            tint = if (billingSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        val cycleSummary = when {
                                            shownDue != null && sd != null ->
                                                "Bills on ${ordinal(sd)} · Pay by ${Dates.weekday(shownDue)}" + (off?.let { " (+${it}d)" } ?: "")
                                            shownDue != null ->
                                                "Pay by ${Dates.weekday(shownDue)}" + (off?.let { " · $it days after bill" } ?: "")
                                            billingSet && sd != null && off != null ->
                                                "Bills on ${ordinal(sd)} · Due $off days later"
                                            else ->
                                                "Select your statement day and due date to activate cycle & upload reminders"
                                        }
                                        Text(
                                            cycleSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (billingSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    DayPickerField(
                                        label = "Statement day",
                                        day = sd?.takeIf { billingSet || statementDay.isNotEmpty() },
                                        onPick = {
                                            statementDay = it.toString()
                                            billingSet = true
                                            loadedDue = null
                                            clear("statementDay")
                                        },
                                        colors = fieldColors,
                                        placeholder = "Pick day",
                                        modifier = Modifier.weight(1f),
                                    )

                                    Box(Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = shownDue?.let { Dates.short(it) } ?: "",
                                            onValueChange = {},
                                            readOnly = true,
                                            singleLine = true,
                                            shape = MaterialTheme.shapes.small,
                                            colors = fieldColors,
                                            label = { Text("Due date", maxLines = 1) },
                                            placeholder = { Text("Pick due") },
                                            trailingIcon = {
                                                Icon(
                                                    Icons.Outlined.Event,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Box(
                                            Modifier
                                                .matchParentSize()
                                                .padding(top = 8.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable(role = Role.Button, onClickLabel = "Choose due date") { pickingDue = true },
                                        )
                                    }
                                }
                                errors["statementDay"]?.let { ErrorLine(it) }

                                Field(
                                    limit,
                                    { limit = it; clear("limit") },
                                    "Credit limit (INR)",
                                    hint = "e.g. 3,50,000",
                                    keyboard = KeyboardType.Decimal,
                                    prefix = "₹ ",
                                    support = errors["limit"],
                                    colors = fieldColors,
                                )

                                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                                Text("Any amount is pending to pay?", style = MaterialTheme.typography.titleSmall)
                                YesNoToggle(hasPending) {
                                    hasPending = it
                                    if (!it) { pendingAmount = ""; clear("pendingAmount") }
                                }
                                AnimatedVisibility(hasPending) {
                                    Field(
                                        pendingAmount,
                                        { pendingAmount = it; clear("pendingAmount") },
                                        "Pending bill amount",
                                        keyboard = KeyboardType.Decimal,
                                        prefix = "₹ ",
                                        support = errors["pendingAmount"],
                                        colors = fieldColors,
                                    )
                                }

                                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                                Text("Did you spend anything after statement?", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "New spending not in your current bill — tracked separately from your pending bill.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                YesNoToggle(hasPostSpend) {
                                    hasPostSpend = it
                                    if (!it) { postSpendAmount = ""; clear("postSpendAmount") }
                                }
                                AnimatedVisibility(hasPostSpend) {
                                    Field(
                                        postSpendAmount,
                                        { postSpendAmount = it; clear("postSpendAmount") },
                                        "Spent after statement",
                                        keyboard = KeyboardType.Decimal,
                                        prefix = "₹ ",
                                        support = errors["postSpendAmount"],
                                        colors = fieldColors,
                                    )
                                }
                            }
                        }
                    }
                }

                // Expandable Reminders & Notes
                Panel(padded = false) {
                    ExpandRow(
                        Icons.Outlined.Tune,
                        "More options",
                        "Reminders · ${reminderLeadsSummary(Billing.parseLeads(leads))}",
                        openExtras,
                        { openExtras = !openExtras },
                    ) {
                        val chosenLeads = Billing.parseLeads(leads).toSet()
                        Text("Reminder days before due", style = MaterialTheme.typography.labelLarge)
                        val opts = (listOf(7, 5, 3, 2, 1, 0) + chosenLeads).distinct().sortedDescending()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            opts.forEach { dDay ->
                                val on = dDay in chosenLeads
                                VaultFilterChip(
                                    label = reminderLeadLabel(dDay),
                                    selected = on,
                                    onClick = {
                                        val next = if (on) chosenLeads - dDay else chosenLeads + dDay
                                        leads = next.sortedDescending().joinToString(",")
                                    },
                                )
                            }
                        }
                        Field(notes, { notes = it }, "Notes", single = false, colors = fieldColors)
                    }
                }

                VaultPrimaryButton(
                    text = if (saving) "Saving…" else if (isNew) "Save card" else "Save changes",
                    onClick = ::save,
                    enabled = !saving,
                    icon = Icons.Filled.Check,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CardEntryModeSelector(
    last4Only: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val isDark = LocalIsDark.current
    val trackShape = RoundedCornerShape(14.dp)
    val thumbShape = RoundedCornerShape(11.dp)
    val trackColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val thumbSelected = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
    val options = listOf(
        false to ("Full number" to Icons.Outlined.CreditCard),
        true to ("Last 4 only" to Icons.Outlined.Shield),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(trackColor)
            .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), trackShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (isLast4, meta) ->
            val (label, icon) = meta
            val sel = isLast4 == last4Only
            val bg by animateColorAsState(
                if (sel) thumbSelected else Color.Transparent,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "entryModeBg",
            )
            val fg by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "entryModeFg",
            )
            Row(
                Modifier
                    .weight(1f)
                    .then(
                        if (sel && !isDark) Modifier.shadow(2.dp, thumbShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                        else Modifier,
                    )
                    .clip(thumbShape)
                    .background(bg)
                    .then(
                        if (sel) Modifier.border(
                            0.7.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.45f else 0.30f),
                            thumbShape,
                        ) else Modifier,
                    )
                    .selectable(selected = sel, role = Role.Tab) { onSelect(isLast4) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (sel) MaterialTheme.colorScheme.onSurface else fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveCardPreviewHero(
    issuer: String,
    nickname: String,
    network: String,
    digits: String,
    last4: String,
    expiry: String,
    cvv: String,
    last4Only: Boolean,
    stored: List<Boolean>,
    shownArt: CardArt?,
    previewColor: Long,
    previewInk: Color,
    onClickDesign: () -> Unit,
) {
    val cardShape = RoundedCornerShape(24.dp)
    val dim = previewInk.copy(alpha = if (previewInk == Color.White) 0.75f else 0.68f)
    val rim = remember(previewInk) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (previewInk == Color.White) 0.36f else 0.62f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.18f),
            ),
        )
    }
    val gloss = remember {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.15f),
            0.42f to Color.White.copy(alpha = 0.02f),
            1f to Color.Black.copy(alpha = 0.14f),
        )
    }
    val formattedPan = remember(digits, last4) {
        when {
            digits.length >= 12 -> digits.chunked(4).joinToString(" ")
            digits.isNotEmpty() -> digits.take(16).padEnd(16, '•').chunked(4).joinToString(" ")
            else -> "•••• •••• •••• ${last4.filter(Char::isDigit).takeLast(4).padStart(4, '•')}"
        }
    }
    val expiryShown = remember(expiry, stored, last4Only) {
        if (last4Only) return@remember "••/••"
        val clean = expiry.filter(Char::isDigit)
        when {
            clean.isNotEmpty() -> {
                val padded = clean.padEnd(4, '•')
                "${padded.take(2)}/${padded.drop(2).take(2)}"
            }
            stored.getOrNull(1) == true -> "••/••"
            else -> "MM/YY"
        }
    }
    val cvvShown = remember(cvv, stored, last4Only) {
        if (last4Only) return@remember "•••"
        when {
            cvv.isNotEmpty() -> "•".repeat(cvv.length)
            stored.getOrNull(2) == true -> "•••"
            else -> "•••"
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(194.dp)
            .shadow(16.dp, cardShape, ambientColor = Color.Black.copy(alpha = 0.36f), spotColor = Color.Black.copy(alpha = 0.48f))
            .clip(cardShape)
            .border(1.dp, rim, cardShape)
            .clickable(role = Role.Button, onClickLabel = "Customise card design", onClick = onClickDesign),
    ) {
        DesignSwatch(shownArt, previewColor, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(gloss))
        CappedFontScale {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BankBadge(issuer, size = 34.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                        Text(
                            nickname.ifBlank { "$issuer Credit Card" },
                            color = previewInk,
                            style = TextStyle(fontFamily = Mulish, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$issuer · Credit Card",
                            color = dim,
                            style = TextStyle(fontFamily = InstrumentSans, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = previewInk.copy(alpha = 0.15f),
                        modifier = Modifier.border(0.6.dp, previewInk.copy(alpha = 0.25f), CircleShape),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(Icons.Outlined.Palette, null, tint = previewInk, modifier = Modifier.size(13.dp))
                            Text(
                                "Design",
                                color = previewInk,
                                style = TextStyle(fontFamily = InstrumentSans, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip()
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.Contactless,
                        contentDescription = null,
                        tint = dim.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formattedPan,
                        color = previewInk,
                        style = TextStyle(
                            fontFamily = SourceCodePro,
                            fontSize = 15.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold,
                        ).tabular,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column {
                            Text(
                                "VALID THRU",
                                color = dim,
                                style = TextStyle(fontFamily = InstrumentSans, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                expiryShown,
                                color = previewInk,
                                style = TextStyle(fontFamily = SourceCodePro, fontSize = 13.sp, fontWeight = FontWeight.SemiBold).tabular,
                            )
                        }
                        Column {
                            Text(
                                "CVV",
                                color = dim,
                                style = TextStyle(fontFamily = InstrumentSans, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                cvvShown,
                                color = previewInk,
                                style = TextStyle(fontFamily = SourceCodePro, fontSize = 13.sp, fontWeight = FontWeight.SemiBold).tabular,
                            )
                        }
                    }
                    if (!(issuer == "Amex" && network == "Amex")) {
                        NetworkMark(network, onDark = true, cardInk = previewInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun YesNoToggle(value: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedPillBar(
        items = listOf(false, true),
        selected = value,
        label = { if (it) "Yes" else "No" },
        onSelect = onChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CaptureActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isDark = LocalIsDark.current
    val bg = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.60f)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = bg,
        modifier = modifier.height(52.dp).border(0.75.dp, border, MaterialTheme.shapes.large),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private class PendingSave(val card: Card, val enc: EncryptedDraft?, val match: Card)

private suspend fun findDuplicate(repo: Repo, card: Card, full: String?, networkKnown: Boolean): Card? {
    val candidates = repo.allCards().filter { it.id != card.id && it.last4 == card.last4 }.sortedBy { it.archivedAt != null }
    for (c in candidates) {
        val blob = if (full != null) repo.secret(c.id)?.numberEnc else null
        val theirs = blob?.let { b ->
            withContext(Dispatchers.IO) { runCatching { Vault.decrypt(CardSecret(c.id, b, null, null, null)).number }.getOrNull() }
        }
        if (theirs != null) {
            if (theirs.filter(Char::isDigit) == full) return c
            continue
        }
        if (c.issuer == card.issuer && (!networkKnown || c.network == card.network)) return c
    }
    return null
}

private fun nextDue(day: Int, offset: Int, today: LocalDate = LocalDate.now()): LocalDate {
    var ym = YearMonth.from(today).minusMonths(3)
    while (true) {
        val due = Billing.statementDate(ym, day).plusDays(offset.toLong())
        if (!due.isBefore(today)) return due
        ym = ym.plusMonths(1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateDialog(initial: LocalDate?, removable: Boolean, onPick: (LocalDate) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            ) { Text("OK") }
        },
        dismissButton = {
            if (removable) TextButton(onClick = onRemove) { Text("Remove") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(
            state,
            title = { Text("Next payment due date", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

private val TILE = 36.dp
private val TileShape = RoundedCornerShape(11.dp)

@Composable
private fun IconTile(icon: ImageVector) = Box(
    Modifier.size(TILE).clip(TileShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }

@Composable
private fun editFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
)

@Composable
private fun SelectField(
    label: String,
    value: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    inline: (@Composable () -> Unit)? = null,
    colors: TextFieldColors = editFieldColors(),
    menu: @Composable () -> Unit = {},
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = colors,
            label = { Text(label, maxLines = 1) },
            leadingIcon = leadingIcon,
            trailingIcon = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null) },
            textStyle = if (inline != null) LocalTextStyle.current.copy(color = Color.Transparent) else LocalTextStyle.current,
            modifier = Modifier.fillMaxWidth(),
        )
        if (inline != null) Box(Modifier.matchParentSize().padding(top = 8.dp, start = 16.dp), contentAlignment = Alignment.CenterStart) { inline() }
        Box(
            Modifier.matchParentSize().padding(top = 8.dp).clip(MaterialTheme.shapes.small)
                .semantics { contentDescription = "$label: $value" }
                .clickable(role = Role.DropdownList, onClickLabel = "Choose $label", onClick = onClick),
        )
        menu()
    }
}

@Composable
private fun ExpandRow(
    icon: ImageVector, title: String, subtitle: String, open: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ListRow(
        icon = null, title = title, subtitle = subtitle, onClick = onToggle,
        leading = { IconTile(icon) },
        action = {
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (open) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    val progress by animateFloatAsState(if (open) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "expand")
    if (open || progress > 0f) {
        Column(
            Modifier.fillMaxWidth().clipToBounds()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val h = (p.height * progress).roundToInt()
                    layout(p.width, h) { p.placeRelative(0, 0) }
                }
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ErrorLine(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
)

private object CardNumberSpacing : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = raw.chunked(4).joinToString(" ")
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset == 0) 0 else (offset + (offset - 1) / 4).coerceAtMost(out.length)
            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), map)
    }
}

private object ExpirySlash : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = if (raw.length > 2) raw.take(2) + "/" + raw.drop(2) else raw
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = (if (offset <= 2) offset else offset + 1).coerceAtMost(out.length)
            override fun transformedToOriginal(offset: Int) = (if (offset <= 2) offset else offset - 1).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), map)
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    single: Boolean = true,
    support: String? = null,
    info: String? = null,
    prefix: String? = null,
    colors: TextFieldColors = editFieldColors(),
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it) } },
        trailingIcon = trailing,
        singleLine = single,
        minLines = if (single) 1 else 3,
        shape = MaterialTheme.shapes.small,
        colors = colors,
        isError = support != null,
        supportingText = (support ?: info)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = caps, autoCorrectEnabled = keyboard == KeyboardType.Text),
        modifier = modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true)
@Composable
fun EditScreenPreview() {
    CardsTheme {
        val context = LocalContext.current
        val cardDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            com.devanandsb.ccmanager.data.CardDao::class.java.classLoader,
            arrayOf(com.devanandsb.ccmanager.data.CardDao::class.java),
        ) { _, _, _ -> null } as com.devanandsb.ccmanager.data.CardDao
        val ledgerDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            com.devanandsb.ccmanager.data.LedgerDao::class.java.classLoader,
            arrayOf(com.devanandsb.ccmanager.data.LedgerDao::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                kotlinx.coroutines.flow.Flow::class.java -> kotlinx.coroutines.flow.flowOf(emptyList<Any>())
                List::class.java -> emptyList<Any>()
                else -> null
            }
        } as com.devanandsb.ccmanager.data.LedgerDao
        val dummyRepo = Repo(cardDaoProxy, ledgerDaoProxy, context)

        EditScreen(
            cardId = 0L,
            repo = dummyRepo,
            defaultLeads = "5,2,0",
            onDone = {},
        )
    }
}
