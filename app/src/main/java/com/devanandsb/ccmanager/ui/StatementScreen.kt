package com.devanandsb.ccmanager.ui

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devanandsb.ccmanager.data.Category
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.Payment
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.Txn
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.ingest.TxnKind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

internal enum class StatementTxnFilter(val label: String) {
    ALL("All"),
    SPEND("Spent"),
    PAID("Paid"),
    REFUND("Refunds"),
    FEE("Fees"),
}

internal data class StatementBreakdown(
    val spentPaise: Long,
    val feesPaise: Long,
    val paidInStatementPaise: Long,
    val paidAfterBillPaise: Long,
    val postStatementSpendPaise: Long,
    val refundPaise: Long,
    val cashbackPaise: Long,
    val spends: List<Txn>,
    val fees: List<Txn>,
    val paymentLines: List<Txn>,
    val refundLines: List<Txn>,
    val cashbackLines: List<Txn>,
    val otherCreditLines: List<Txn>,
)

internal fun computeStatementBreakdown(
    st: Statement,
    lines: List<Txn>,
    cardPayments: List<Payment>,
    cardLiveTxns: List<Txn>,
    isLatestForCard: Boolean,
    outstandingPaise: Long? = null,
    cardLimitPaise: Long = 0L,
): StatementBreakdown {
    val fees = lines.filter { !it.isCredit && it.kind == TxnKind.FEE }
    val spends = lines.filter { !it.isCredit && it.kind != TxnKind.FEE && it.kind != TxnKind.CASHBACK }
    val paymentLines = lines.filter { it.isCredit && it.kind == TxnKind.PAYMENT }
    val refundLines = lines.filter { it.isCredit && it.kind == TxnKind.REFUND }
    val cashbackLines = lines.filter { it.isCredit && it.kind == TxnKind.CASHBACK }
    val otherCreditLines = lines.filter {
        it.isCredit && it.kind != TxnKind.PAYMENT && it.kind != TxnKind.REFUND && it.kind != TxnKind.CASHBACK
    }
    val spentPaise = if (lines.isNotEmpty()) spends.sumOf { it.amountPaise } else st.debitsPaise
    val feesPaise = fees.sumOf { it.amountPaise }
    val paidInStatementPaise = paymentLines.sumOf { it.amountPaise }
    val refundPaise = refundLines.sumOf { it.amountPaise }
    val cashbackLineSum = cashbackLines.sumOf { it.amountPaise }
    val cashbackPaise = maxOf(cashbackLineSum, st.cashbackPaise ?: 0L)

    val manualDuePays = cardPayments.filter {
        it.cardId == st.cardId && st.dueEpochDay != null && it.dueEpochDay > 0L &&
            abs(it.dueEpochDay - st.dueEpochDay) <= 10L
    }
    val manualPaidSum = manualDuePays.sumOf { it.amountPaise }
    val resolvedDuePaise = Repo.effectiveStatementDuePaise(st, cardLimitPaise) ?: 0L

    val (paidAfterBillPaise, postSpendPaise) = if (isLatestForCard) {
        val bd = Repo.computePostStatementBreakdown(st, cardPayments, cardLiveTxns, lines)
        val fallbackPaid = if (outstandingPaise != null && resolvedDuePaise > 0L) {
            (resolvedDuePaise - outstandingPaise).coerceAtLeast(0L)
        } else {
            0L
        }
        maxOf(bd.paidPaise, fallbackPaid) to bd.postSpendPaise
    } else {
        manualPaidSum to 0L
    }

    return StatementBreakdown(
        spentPaise = spentPaise,
        feesPaise = feesPaise,
        paidInStatementPaise = paidInStatementPaise,
        paidAfterBillPaise = paidAfterBillPaise,
        postStatementSpendPaise = postSpendPaise,
        refundPaise = refundPaise,
        cashbackPaise = cashbackPaise,
        spends = spends,
        fees = fees,
        paymentLines = paymentLines,
        refundLines = refundLines,
        cashbackLines = cashbackLines,
        otherCreditLines = otherCreditLines,
    )
}

@Composable
fun StatementScreen(
    statementId: Long,
    ledger: Ledger,
    repo: Repo,
    cards: List<CardWithDue>,
    categories: List<Category>,
    statements: List<Statement>,
    payments: List<Payment>,
    liveTxns: List<Txn> = emptyList(),
    onBack: () -> Unit,
    onOpenStatement: (Long) -> Unit = {},
) {
    val start = statements.firstOrNull { it.id == statementId }
    val cardId = start?.cardId
    val pages = remember(statements, cardId) { statements.filter { it.cardId == cardId }.sortedBy { it.statementEpochDay } }
    if (start == null || pages.isEmpty()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { VaultTopAppBar(title = "Statement", onBack = onBack) },
        ) { Column(Modifier.padding(it)) {} }
        return
    }
    val cw = cards.firstOrNull { it.card.id == cardId }
    val cardLiveTxns = remember(liveTxns, cardId) { liveTxns.filter { it.cardId == cardId && it.statementId == null } }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == statementId }.coerceAtLeast(0)) { pages.size }
    val scope = rememberCoroutineScope()
    val current = pages.getOrNull(pager.currentPage) ?: start
    var menu by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var editHeader by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<Txn?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteLine by remember { mutableStateOf<Txn?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val fromEmail = current.sourceMsgId.isNotBlank()
            val stmtMonth = LocalDate.ofEpochDay(current.statementEpochDay)
                .format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH))
            VaultTopAppBar(
                title = "$stmtMonth Statement",
                subtitle = cw?.let { "${it.card.nickname} · •• ${it.card.last4}" },
                onBack = onBack,
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                if (fromEmail) Icons.Outlined.MailOutline else Icons.Outlined.UploadFile,
                                null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                if (fromEmail) "From email" else "Uploaded PDF",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Box {
                        VaultIconButton(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = "More",
                            onClick = { menu = true },
                        )
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit totals") }, onClick = { menu = false; editHeader = true })
                            DropdownMenuItem(text = { Text("Add line") }, onClick = { menu = false; editLine = newLine(current, credit = false) })
                            DropdownMenuItem(text = { Text("Re-read offline") }, onClick = {
                                menu = false
                                if (cw != null) scope.launch { ledger.reparse(current.id, cw.card.issuer) }
                            })
                            DropdownMenuItem(text = { Text("View extracted text") }, onClick = { menu = false; showText = true })
                            DropdownMenuItem(text = { Text("Delete statement") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), key = { pages[it].id }) { i ->
            val next = statements.filter { it.status == Ledger.STATUS_REVIEW && it.id != pages[i].id }.minByOrNull { it.statementEpochDay }
            StatementPage(
                st = pages[i],
                ledger = ledger,
                repo = repo,
                cw = cw,
                categories = categories,
                payments = payments,
                cardLiveTxns = cardLiveTxns,
                allPages = pages,
                pageIndex = i,
                onSelectPage = { idx -> scope.launch { pager.animateScrollToPage(idx) } },
                isLatestForCard = (i == pages.lastIndex),
                onEditTotals = { editHeader = true },
                onAddLine = { credit -> editLine = newLine(pages[i], credit) },
                nextReview = next,
                reviewLeft = statements.count { it.status == Ledger.STATUS_REVIEW && it.id != pages[i].id },
                onNext = { next?.let { onOpenStatement(it.id) } },
            )
        }
    }

    if (showText) {
        AlertDialog(
            onDismissRequest = { showText = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Extracted text") },
            text = {
                SelectionContainer {
                    Text(
                        current.rawText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showText = false }) { Text("Close") } },
        )
    }

    if (editHeader) {
        val st = current
        var due by remember(st.id) { mutableStateOf(st.dueEpochDay?.let(LocalDate::ofEpochDay)) }
        var total by remember(st.id) { mutableStateOf(st.totalDuePaise?.let(Money::toInput) ?: "") }
        var min by remember(st.id) { mutableStateOf(st.minDuePaise?.let(Money::toInput) ?: "") }
        var opening by remember(st.id) { mutableStateOf(st.openingPaise?.let(Money::toInput) ?: "") }
        val valid = listOf(total, min, opening).all { it.isBlank() || Money.parse(it) != null }
        EditSheet(
            title = "Statement totals", note = "As printed on the bank's statement.",
            onDismiss = { editHeader = false }, confirmLabel = "Save", confirmEnabled = valid,
            onConfirm = {
                editHeader = false
                scope.launch {
                    ledger.editStatementHeader(
                        st.id, due?.toEpochDay(),
                        total.takeIf { it.isNotBlank() }?.let(Money::parse),
                        min.takeIf { it.isNotBlank() }?.let(Money::parse),
                        opening.takeIf { it.isNotBlank() }?.let(Money::parse),
                    )
                }
            },
        ) {
            DateField("Due date", due, { due = it }, initialPick = LocalDate.ofEpochDay(st.statementEpochDay).plusDays(20))
            MoneyField("Total due", total) { total = it }
            MoneyField("Minimum due", min) { min = it }
            MoneyField("Opening balance", opening) { opening = it }
        }
    }

    editLine?.let { t ->
        LineDialog(
            t,
            onDismiss = { editLine = null },
            onSave = { nt -> editLine = null; scope.launch { ledger.saveLine(nt) } },
            onDelete = if (t.id != 0L) ({ editLine = null; deleteLine = t }) else null,
        )
    }
    deleteLine?.let { t ->
        ConfirmDeleteLine(t, onDismiss = { deleteLine = null }, onConfirm = { deleteLine = null; scope.launch { ledger.deleteLine(t) } })
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this statement?",
            body = "Its transactions are removed from this phone too. The email stays in your inbox. This can't be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val id = current.id
                scope.launch { ledger.deleteStatement(id); onBack() }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatementPage(
    st: Statement,
    ledger: Ledger,
    repo: Repo,
    cw: CardWithDue?,
    categories: List<Category>,
    payments: List<Payment>,
    cardLiveTxns: List<Txn>,
    allPages: List<Statement>,
    pageIndex: Int,
    onSelectPage: (Int) -> Unit,
    isLatestForCard: Boolean,
    onEditTotals: () -> Unit,
    onAddLine: (credit: Boolean) -> Unit,
    nextReview: Statement? = null,
    reviewLeft: Int = 0,
    onNext: () -> Unit = {},
) {
    val lines by remember(st.id) { ledger.linesFor(st.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val catById = remember(categories) { categories.associateBy { it.id } }
    var open by remember { mutableStateOf<Txn?>(null) }
    var editLine by remember { mutableStateOf<Txn?>(null) }
    var deleteLine by remember { mutableStateOf<Txn?>(null) }
    var paying by remember { mutableStateOf(false) }
    var showMath by remember { mutableStateOf(false) }
    var filter by rememberSaveable(st.id) { mutableStateOf(StatementTxnFilter.ALL) }

    val review = st.status == Ledger.STATUS_REVIEW
    val wasReview = remember(st.id) { review }
    val computed = st.openingPaise?.let { it + st.debitsPaise - st.creditsPaise }
    val diff = if (computed != null && st.totalDuePaise != null) computed - st.totalDuePaise else null
    val cardPayments = remember(payments, st.cardId) { payments.filter { it.cardId == st.cardId } }
    val paid = remember(cardPayments, st.dueEpochDay) {
        cardPayments.filter { st.dueEpochDay != null && it.dueEpochDay > 0L && kotlin.math.abs(it.dueEpochDay - st.dueEpochDay) <= 10L }
    }
    val breakdown = remember(st, lines, cardPayments, cardLiveTxns, isLatestForCard, cw?.card?.outstandingPaise, cw?.card?.limitPaise) {
        computeStatementBreakdown(
            st = st,
            lines = lines,
            cardPayments = cardPayments,
            cardLiveTxns = cardLiveTxns,
            isLatestForCard = isLatestForCard,
            outstandingPaise = cw?.card?.outstandingPaise,
            cardLimitPaise = cw?.card?.limitPaise ?: 0L,
        )
    }
    val spendsByCat = remember(breakdown.spends) {
        breakdown.spends.groupBy { it.categoryId }.toList().sortedByDescending { (_, l) -> l.sumOf { it.amountPaise } }
    }

    val totalDue = Repo.effectiveStatementDuePaise(st, cw?.card?.limitPaise ?: 0L) ?: 0L
    val remainingDue = (totalDue - breakdown.paidAfterBillPaise).coerceAtLeast(0L)
    val stmtDate = LocalDate.ofEpochDay(st.statementEpochDay)
    val dueDate = st.dueEpochDay?.let(LocalDate::ofEpochDay)
    val accent = cw?.card?.let { remember(it.id, it.art, it.issuer, it.nickname, it.color) { cardAccentColor(it) } }
        ?: MaterialTheme.colorScheme.primary
    val isDark = LocalIsDark.current
    val hairline = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Gutter, 14.dp, Gutter, 40.dp)) {
        if (allPages.size > 1) {
            item(key = "cycle_switcher") {
                LazyRow(
                    contentPadding = PaddingValues(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(allPages, key = { _, item -> item.id }) { idx, page ->
                        val active = idx == pageIndex
                        val monthLabel = LocalDate.ofEpochDay(page.statementEpochDay)
                            .format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH))
                        VaultFilterChip(
                            label = monthLabel,
                            selected = active,
                            onClick = { onSelectPage(idx) },
                        )
                    }
                }
            }
        }

        // 1) Statement Overview Card (Stitch statement/code.html)
        item {
            VaultCardContainer(
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(20.dp),
            ) {
                val resolvedDueOpt = Repo.effectiveStatementDuePaise(st, cw?.card?.limitPaise ?: 0L)
                val showRemaining = totalDue > 0L && breakdown.paidAfterBillPaise > 0L && remainingDue < totalDue

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (showRemaining) "REMAINING TO PAY" else "TOTAL AMOUNT DUE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (resolvedDueOpt == null) "—"
                            else Money.inr(if (showRemaining) remainingDue else totalDue, true),
                            style = MaterialTheme.typography.displayMedium.tabular,
                            color = if (showRemaining && remainingDue == 0L) Good else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            buildString {
                                if (showRemaining) append("Statement ${Money.inr(totalDue, true)}")
                                st.minDuePaise?.let { min ->
                                    if (isNotEmpty()) append(" · ")
                                    append("Minimum due ${Money.inr(min, true)}")
                                }
                            }.ifBlank { "Billed ${Dates.long(stmtDate)}" },
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    StatusChip(st, breakdown.paidAfterBillPaise, cw?.card?.limitPaise ?: 0L)
                }

                if (totalDue > 0L && breakdown.paidAfterBillPaise > 0L) {
                    val frac = (breakdown.paidAfterBillPaise.toFloat() / totalDue.toFloat()).coerceIn(0f, 1f)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { frac },
                        color = Good,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${Money.inr(breakdown.paidAfterBillPaise, true)} paid", style = MaterialTheme.typography.labelSmall, color = Good)
                        Text(
                            if (remainingDue == 0L) "Settled" else "${Money.inr(remainingDue, true)} left",
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = if (remainingDue == 0L) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(0.75.dp).background(hairline))
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroMetaCell("Generated", Dates.short(stmtDate), modifier = Modifier.weight(1f))
                    HeroMetaCell(
                        "Due date",
                        dueDate?.let { Dates.short(it) } ?: "—",
                        valueColor = if (dueDate != null && breakdown.paidAfterBillPaise < totalDue && ChronoUnit.DAYS.between(LocalDate.now(), dueDate) <= 3) Danger else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    HeroMetaCell(
                        "Credit limit",
                        cw?.card?.limitPaise?.takeIf { it > 0L }?.let { Money.inr(it, true) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }

                val canPay = st.dueEpochDay != null && (if (totalDue > 0) breakdown.paidAfterBillPaise < totalDue else paid.isEmpty())
                if (canPay) {
                    Spacer(Modifier.height(18.dp))
                    VaultPrimaryButton(
                        text = when {
                            breakdown.paidAfterBillPaise > 0 -> "Add payment"
                            totalDue <= 0 -> "Mark as settled"
                            else -> "Mark as paid"
                        },
                        icon = Icons.Filled.Check,
                        onClick = { paying = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 2) Clean 2x2 Interactive Summary Tiles
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Cycle summary",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (diff != null) {
                    val ok = abs(diff) <= 100
                    StatusPill(
                        if (ok) "Balanced" else "Differs ${Money.inr(abs(diff), true)}",
                        if (ok) Good else Amber,
                    ) { showMath = !showMath }
                } else {
                    StatusPill("Details", MaterialTheme.colorScheme.primary) { showMath = !showMath }
                }
            }
            Spacer(Modifier.height(10.dp))

            val paidPrimaryPaise = if (breakdown.paidAfterBillPaise > 0L) breakdown.paidAfterBillPaise else breakdown.paidInStatementPaise
            val paidCaption = when {
                breakdown.paidAfterBillPaise > 0L && breakdown.paidInStatementPaise > 0L ->
                    "Toward bill · ${Money.inr(breakdown.paidInStatementPaise, true)} prior"
                breakdown.paidAfterBillPaise > 0L -> "Paid toward this bill"
                breakdown.paymentLines.isNotEmpty() ->
                    "${breakdown.paymentLines.size} payment${if (breakdown.paymentLines.size == 1) "" else "s"} in cycle"
                else -> "No payments yet"
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatementMetricTile(
                        label = "Spent",
                        value = Money.inr(breakdown.spentPaise, true),
                        caption = "${breakdown.spends.size} purchase${if (breakdown.spends.size == 1) "" else "s"}",
                        accentColor = accent,
                        selected = filter == StatementTxnFilter.SPEND,
                        onClick = { filter = if (filter == StatementTxnFilter.SPEND) StatementTxnFilter.ALL else StatementTxnFilter.SPEND },
                        modifier = Modifier.weight(1f),
                    )
                    StatementMetricTile(
                        label = "Paid",
                        value = if (paidPrimaryPaise > 0L) Money.inr(paidPrimaryPaise, true) else "₹0",
                        caption = paidCaption,
                        accentColor = Good,
                        valueColor = if (paidPrimaryPaise > 0L) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = filter == StatementTxnFilter.PAID,
                        onClick = { filter = if (filter == StatementTxnFilter.PAID) StatementTxnFilter.ALL else StatementTxnFilter.PAID },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatementMetricTile(
                        label = "Refunds",
                        value = if (breakdown.refundPaise > 0L) Money.inr(breakdown.refundPaise, true) else "₹0",
                        caption = if (breakdown.refundLines.isNotEmpty()) {
                            "${breakdown.refundLines.size} refund${if (breakdown.refundLines.size == 1) "" else "s"}"
                        } else {
                            "No refunds"
                        },
                        accentColor = Good,
                        valueColor = if (breakdown.refundPaise > 0L) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = filter == StatementTxnFilter.REFUND,
                        onClick = { filter = if (filter == StatementTxnFilter.REFUND) StatementTxnFilter.ALL else StatementTxnFilter.REFUND },
                        modifier = Modifier.weight(1f),
                    )
                    StatementMetricTile(
                        label = "Fees",
                        value = if (breakdown.feesPaise > 0L) Money.inr(breakdown.feesPaise, true) else "₹0",
                        caption = if (breakdown.fees.isNotEmpty()) {
                            "${breakdown.fees.size} fee${if (breakdown.fees.size == 1) "" else "s"} & interest"
                        } else {
                            "No fees"
                        },
                        accentColor = Danger,
                        valueColor = if (breakdown.feesPaise > 0L) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = filter == StatementTxnFilter.FEE,
                        onClick = { filter = if (filter == StatementTxnFilter.FEE) StatementTxnFilter.ALL else StatementTxnFilter.FEE },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            st.openingPaise?.takeIf { it != 0L }?.let { prev ->
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(
                        text = "Prev balance · ${Money.inr(prev, true)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(showMath, enter = expandVertically(), exit = shrinkVertically()) {
                Panel(Modifier.padding(top = 12.dp)) {
                    KeyValue("Statement date", Dates.long(stmtDate))
                    dueDate?.let { KeyValue("Due date", Dates.long(it)) }
                    st.periodStartEpochDay?.let { KeyValue("Cycle started", Dates.long(LocalDate.ofEpochDay(it))) }
                    st.openingPaise?.let { KeyValue("Previous balance", Money.inr(it, true)) }
                    KeyValue("Debits (spent + fees)", Money.inr(st.debitsPaise, true))
                    KeyValue("Credits (paid + refunds)", Money.inr(st.creditsPaise, true), valueColor = Good)
                    if (cw != null && st.rawText.isNotBlank()) {
                        InlineAction("Re-read PDF offline") { scope.launch { ledger.reparse(st.id, cw.card.issuer) } }
                    }
                    InlineAction("Edit bill details", onEditTotals)
                }
            }

            if (!review && st.note.isNotBlank() && st.note != "Accepted after review") {
                Text(
                    st.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                )
            }

            // 3) Top Spending Categories Card (Stitch statement/code.html)
            if (spendsByCat.isNotEmpty() && breakdown.spentPaise > 0L) {
                Spacer(Modifier.height(16.dp))
                val segments = spendsByCat.take(6).mapNotNull { (catId, list) ->
                    val sum = list.sumOf { it.amountPaise }
                    if (sum <= 0L) null else {
                        val cat = catId?.let { catById[it] }
                        SpendSegment(
                            label = cat?.name ?: "Other",
                            amountPaise = sum,
                            fraction = (sum.toFloat() / breakdown.spentPaise.toFloat()).coerceIn(0f, 1f),
                            color = cat?.let { Color(it.color) } ?: accent,
                        )
                    }
                }
                VaultCardContainer(
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(20.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Where you spent most", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${spendsByCat.size} ${if (spendsByCat.size == 1) "category" else "categories"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    ProportionalSpendBar(segments = segments)
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        spendsByCat.forEach { (catId, list) ->
                            val sum = list.sumOf { it.amountPaise }
                            val cat = catId?.let { catById[it] }
                            val pct = ((sum * 100.0) / breakdown.spentPaise).toInt().coerceAtLeast(1)
                            val dotColor = cat?.let { Color(it.color) } ?: accent
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                                    Text(
                                        cat?.name ?: "Other",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        Money.inr(sum, true),
                                        style = MaterialTheme.typography.titleSmall.tabular,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "$pct%",
                                        style = MaterialTheme.typography.bodySmall.tabular,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (review) {
                Spacer(Modifier.height(12.dp))
                ReviewHelp(st, lines, diff, ledger, onAddLine, onEditTotals)
            } else if (wasReview) {
                Spacer(Modifier.height(12.dp))
                Panel(color = Good.copy(alpha = 0.12f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = Good)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("All sorted", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (nextReview == null) "No other statements need a look." else "$reviewLeft more need${if (reviewLeft == 1) "s" else ""} a look.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (nextReview != null) FilledTonalButton(onClick = onNext, shape = CircleShape) { Text("Next") }
                    }
                }
            }

            // 4) Interactive Filter Pills for Transaction Details
            if (lines.isNotEmpty()) {
                val filterItems = buildList {
                    add(StatementTxnFilter.ALL to lines.size)
                    add(StatementTxnFilter.SPEND to breakdown.spends.size)
                    add(StatementTxnFilter.PAID to breakdown.paymentLines.size)
                    add(StatementTxnFilter.REFUND to breakdown.refundLines.size)
                    if (breakdown.fees.isNotEmpty()) add(StatementTxnFilter.FEE to breakdown.fees.size)
                }
                LazyRow(
                    contentPadding = PaddingValues(top = 18.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filterItems, key = { it.first.name }) { (f, count) ->
                        VaultFilterChip(
                            label = "${f.label} ($count)",
                            selected = filter == f,
                            onClick = { filter = f },
                        )
                    }
                }
            }
        }

        // 5) Full Transaction Details
        if (lines.isEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                Panel {
                    Text("No transactions listed", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No purchase or payment rows were found in this PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cw != null && st.rawText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = { scope.launch { ledger.reparse(st.id, cw.card.issuer) } }, shape = CircleShape) {
                            Text("Read statement again")
                        }
                    }
                }
            }
        } else {
            when (filter) {
                StatementTxnFilter.ALL -> {
                    if (breakdown.spends.isNotEmpty()) {
                        item { SectionHeader("Purchases", trailing = Money.inr(breakdown.spentPaise, true)) }
                        spendsByCat.forEach { (catId, l) ->
                            val c = catId?.let { catById[it] }
                            item(key = "c${st.id}-$catId") {
                                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp)) {
                                    Text(c?.name ?: "Other", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    Text(Money.inr(l.sumOf { it.amountPaise }, true), style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            item(key = "p${st.id}-$catId") {
                                Panel(padded = false) {
                                    l.forEachIndexed { idx, t ->
                                        if (idx > 0) RowDivider()
                                        TxnRow(t, null, c, hPad = 16.dp) { open = t }
                                    }
                                }
                            }
                        }
                    }
                    if (breakdown.fees.isNotEmpty()) {
                        item { SectionHeader("Bank fees & interest", trailing = Money.inr(breakdown.feesPaise, true), color = Danger) }
                        item(key = "fees-${st.id}") {
                            Panel(padded = false) {
                                breakdown.fees.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                    if (breakdown.paymentLines.isNotEmpty()) {
                        item { SectionHeader("Payments received", trailing = Money.inr(breakdown.paidInStatementPaise, true), color = Good) }
                        item(key = "pay-${st.id}") {
                            Panel(padded = false) {
                                breakdown.paymentLines.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                    if (breakdown.refundLines.isNotEmpty()) {
                        item { SectionHeader("Refunds", trailing = Money.inr(breakdown.refundPaise, true), color = Good) }
                        item(key = "ref-${st.id}") {
                            Panel(padded = false) {
                                breakdown.refundLines.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                    val otherCredits = breakdown.cashbackLines + breakdown.otherCreditLines
                    if (otherCredits.isNotEmpty()) {
                        item { SectionHeader("Other credits", trailing = Money.inr(otherCredits.sumOf { it.amountPaise }, true), color = Good) }
                        item(key = "ocr-${st.id}") {
                            Panel(padded = false) {
                                otherCredits.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                }
                StatementTxnFilter.SPEND -> {
                    item { SectionHeader("Purchases", trailing = Money.inr(breakdown.spentPaise, true)) }
                    if (breakdown.spends.isEmpty()) {
                        item { Panel { Text("No purchases in this statement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    } else {
                        item(key = "f-spend-${st.id}") {
                            Panel(padded = false) {
                                breakdown.spends.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                }
                StatementTxnFilter.PAID -> {
                    item { SectionHeader("Payments in statement", trailing = Money.inr(breakdown.paidInStatementPaise, true), color = Good) }
                    if (breakdown.paymentLines.isEmpty()) {
                        item {
                            Panel {
                                Text(
                                    if (breakdown.paidAfterBillPaise > 0L) "No bill payments appeared inside this PDF, and ${Money.inr(breakdown.paidAfterBillPaise, true)} was paid after statement generation."
                                    else "No bill payment rows in this statement.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        item(key = "f-paid-${st.id}") {
                            Panel(padded = false) {
                                breakdown.paymentLines.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                }
                StatementTxnFilter.REFUND -> {
                    item { SectionHeader("Refunds", trailing = Money.inr(breakdown.refundPaise, true), color = Good) }
                    if (breakdown.refundLines.isEmpty()) {
                        item { Panel { Text("No refund rows in this statement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    } else {
                        item(key = "f-ref-${st.id}") {
                            Panel(padded = false) {
                                breakdown.refundLines.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                }
                StatementTxnFilter.FEE -> {
                    item { SectionHeader("Bank fees & interest", trailing = Money.inr(breakdown.feesPaise, true), color = Danger) }
                    if (breakdown.fees.isEmpty()) {
                        item { Panel { Text("No fee or interest rows in this statement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    } else {
                        item(key = "f-fee-${st.id}") {
                            Panel(padded = false) {
                                breakdown.fees.forEachIndexed { idx, t ->
                                    if (idx > 0) RowDivider()
                                    TxnRow(t, null, catById[t.categoryId], hPad = 16.dp) { open = t }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { t ->
        TxnSheet(
            txn = t, card = cw, categories = categories, statement = null,
            onDismiss = { open = null }, onOpenStatement = null,
            onSave = { catId, pattern, kind, note -> open = null; scope.launch { saveTxnEdits(ledger, t, catId, pattern, kind, note) } },
            extra = if (t.statementId == st.id) ({
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open = null; editLine = t }, shape = CircleShape, modifier = Modifier.weight(1f)) { Text("Edit line") }
                    OutlinedButton(onClick = { open = null; deleteLine = t }, shape = CircleShape, modifier = Modifier.weight(1f)) { Text("Delete", color = Danger) }
                }
            }) else null,
        )
    }
    editLine?.let { t ->
        LineDialog(t, onDismiss = { editLine = null }, onSave = { nt -> editLine = null; scope.launch { ledger.saveLine(nt) } }, onDelete = null)
    }
    deleteLine?.let { t ->
        ConfirmDeleteLine(t, onDismiss = { deleteLine = null }, onConfirm = { deleteLine = null; scope.launch { ledger.deleteLine(t) } })
    }
    if (paying && cw != null && st.dueEpochDay != null) {
        MarkPaidSheet(
            dueLabel = Dates.weekday(LocalDate.ofEpochDay(st.dueEpochDay)),
            total = (totalDue - breakdown.paidAfterBillPaise).coerceAtLeast(0L), minimum = st.minDuePaise,
            onDismiss = { paying = false },
            onConfirm = { amt -> paying = false; scope.launch { repo.markPaid(st.cardId, st.dueEpochDay, amt) } },
        )
    }
}

@Composable
private fun HeroMetaCell(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).tabular,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatementMetricTile(
    label: String,
    value: String,
    caption: String,
    accentColor: Color,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDark.current
    val baseBorder = if (selected) accentColor.copy(alpha = 0.65f)
    else if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    val bg = if (selected) accentColor.copy(alpha = if (isDark) 0.14f else 0.08f)
    else MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        modifier = modifier
            .border(if (selected) 1.2.dp else 0.7.dp, baseBorder, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) accentColor else accentColor.copy(alpha = 0.55f)))
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).tabular,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusChip(st: Statement, paidSum: Long, cardLimitPaise: Long = 0L) {
    val total = Repo.effectiveStatementDuePaise(st, cardLimitPaise) ?: 0L
    val due = st.dueEpochDay?.let(LocalDate::ofEpochDay)
    val days = due?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val (text, level) = when {
        st.status == Ledger.STATUS_REVIEW && st.openingPaise != null -> "Check totals" to UrgencyLevel.Warning
        total <= 0 -> "Nothing due" to UrgencyLevel.Good
        paidSum >= total -> "Paid" to UrgencyLevel.Good
        paidSum > 0 -> "Part paid" to UrgencyLevel.Warning
        days == null -> "Due date missing" to UrgencyLevel.Warning
        days < 0 -> "Overdue" to UrgencyLevel.Urgent
        days == 0L -> "Due today" to UrgencyLevel.Urgent
        days == 1L -> "Due tomorrow" to UrgencyLevel.Warning
        else -> "Due ${Dates.short(due)}" to (if (days <= 5) UrgencyLevel.Warning else UrgencyLevel.Neutral)
    }
    UrgencyBadge(text = text, level = level, showDot = level == UrgencyLevel.Urgent)
}

@Composable
private fun ReviewHelp(st: Statement, lines: List<Txn>, diff: Long?, ledger: Ledger, onAddLine: (Boolean) -> Unit, onEditTotals: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tol = 100L
    if (st.totalDuePaise != null && st.dueEpochDay != null && diff == null && lines.isNotEmpty()) return
    Panel(color = Amber.copy(alpha = 0.12f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, null, tint = Amber)
                Text("Check statement totals", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
            }
            when {
                st.totalDuePaise == null -> {
                    Text("The bill total wasn't found in this PDF. Tap below to enter it.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = onEditTotals, shape = CircleShape) { Text("Enter bill total") }
                }
                lines.isEmpty() -> {
                    Text("No transactions were found in this statement.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onAddLine(false) }, shape = CircleShape) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add transaction")
                    }
                }
                diff != null -> {
                    val flip = lines.firstOrNull { abs(2 * it.amountPaise - abs(diff)) <= tol && (if (diff > 0) !it.isCredit else it.isCredit) }
                    val dupe = if (diff > 0) lines.firstOrNull { t ->
                        !t.isCredit && abs(t.amountPaise - diff) <= tol &&
                            lines.count { it.amountPaise == t.amountPaise && it.merchant == t.merchant && !it.isCredit } > 1
                    } else null
                    val flipLabel = flip?.let { TxnDisplay.format(it).title }
                    val dupeLabel = dupe?.let { TxnDisplay.format(it).title }
                    Text(
                        if (diff > 0) "The transactions come to ${Money.inr(diff, true)} more than the bill total."
                        else "The transactions come to ${Money.inr(-diff, true)} less than the bill total.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        flip != null -> {
                            Text(
                                "\"$flipLabel\" (${Money.inr(flip.amountPaise, true)}) looks like a ${if (flip.isCredit) "purchase" else "payment or refund"}.",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            FilledTonalButton(onClick = { scope.launch { ledger.saveLine(flip.copy(isCredit = !flip.isCredit)) } }, shape = CircleShape) {
                                Text(if (flip.isCredit) "Change to purchase" else "Change to payment / refund")
                            }
                        }
                        dupe != null -> {
                            Text("\"$dupeLabel\" appears twice.", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FilledTonalButton(onClick = { scope.launch { ledger.deleteLine(dupe) } }, shape = CircleShape) { Text("Remove duplicate") }
                        }
                        diff < 0 -> FilledTonalButton(onClick = { onAddLine(false) }, shape = CircleShape) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add missing purchase")
                        }
                        else -> FilledTonalButton(onClick = { onAddLine(true) }, shape = CircleShape) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add missing payment or refund")
                        }
                    }
                }
            }
            InlineAction("Looks good anyway, confirm") { scope.launch { ledger.markReviewed(st.id) } }
        }
    }
}

@Composable
private fun ConfirmDeleteLine(t: Txn, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Delete this line?",
        body = "\"${t.merchant.ifBlank { "Untitled" }}\" (${Money.inr(t.amountPaise, true)}) is removed from this statement. This can't be undone.",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

private fun newLine(st: Statement, credit: Boolean) = Txn(
    cardId = st.cardId, statementId = st.id, epochDay = st.statementEpochDay, merchant = "",
    amountPaise = 0, isCredit = credit, categoryId = null, dedupeKey = "",
)

@Composable
private fun LineDialog(t: Txn, onDismiss: () -> Unit, onSave: (Txn) -> Unit, onDelete: (() -> Unit)?) {
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.ofEpochDay(t.epochDay)) }
    var merchant by remember { mutableStateOf(t.merchant) }
    var amount by remember { mutableStateOf(if (t.amountPaise > 0) Money.toInput(t.amountPaise) else "") }
    var credit by remember { mutableStateOf(t.isCredit) }
    val a = Money.parse(amount)
    val valid = date != null && a != null && a > 0 && merchant.isNotBlank()
    EditSheet(
        title = if (t.id == 0L) "Add line" else "Edit line",
        onDismiss = onDismiss, confirmLabel = "Save", confirmEnabled = valid,
        onConfirm = { onSave(t.copy(epochDay = date!!.toEpochDay(), merchant = merchant.trim(), amountPaise = a!!, isCredit = credit)) },
    ) {
        DateField("Date", date, { date = it })
        OutlinedTextField(merchant, { merchant = it }, label = { Text("Description") }, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
        MoneyField("Amount", amount) { amount = it }
        Panel(padded = false) {
            Row(
                Modifier.fillMaxWidth().clickable { credit = !credit }.heightIn(min = 54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Credit (payment or refund)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = credit, onCheckedChange = { credit = it })
            }
        }
        if (onDelete != null) InlineAction("Delete this line", onDelete)
    }
}

@Preview(showBackground = true)
@Composable
fun StatementScreenPreview() {
    CardsTheme {
        val context = LocalContext.current
        val cardDaoProxy = java.lang.reflect.Proxy.newProxyInstance(
            com.devanandsb.ccmanager.data.CardDao::class.java.classLoader,
            arrayOf(com.devanandsb.ccmanager.data.CardDao::class.java)
        ) { _, _, _ -> null } as com.devanandsb.ccmanager.data.CardDao
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
        val dummyRepo = Repo(cardDaoProxy, ledgerDaoProxy, context)
        val dummyLedger = Ledger(ledgerDaoProxy) { _ -> }

        StatementScreen(
            statementId = 1L,
            ledger = dummyLedger,
            repo = dummyRepo,
            cards = emptyList(),
            categories = emptyList(),
            statements = emptyList(),
            payments = emptyList(),
            onBack = {},
        )
    }
}
