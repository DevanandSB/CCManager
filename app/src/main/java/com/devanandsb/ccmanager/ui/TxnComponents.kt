package com.devanandsb.ccmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devanandsb.ccmanager.data.Category
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.Txn
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.ingest.Categorizer
import com.devanandsb.ccmanager.ingest.TxnKind
import java.time.LocalDate

private fun extractCustomTxnNote(note: String): String? = note.trim().ifEmpty { null }

/** Smooth borderless pill toggle replacing outlined M3 FilterChips. */
@Composable
private fun TonalChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
    dotColor: Color? = null,
) {
    val isDark = LocalIsDark.current
    val bg by animateColorAsState(
        when {
            selected -> accent.copy(alpha = if (isDark) 0.22f else 0.15f)
            isDark -> Color.White.copy(alpha = 0.06f)
            else -> Color.Black.copy(alpha = 0.05f)
        },
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillBg",
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.70f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillBorder",
    )
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.0.dp, border, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (dotColor != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected && dotColor == null) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun TxnAvatarBadge(
    kind: String,
    title: String,
    tint: Color,
    size: Dp = 40.dp,
    cornerRadius: Dp = 14.dp,
) {
    val isDark = LocalIsDark.current
    val shape = RoundedCornerShape(cornerRadius)
    val monogram = remember(title) {
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = if (isDark) 0.16f else 0.10f))
            .border(0.6.dp, tint.copy(alpha = if (isDark) 0.28f else 0.20f), shape),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            TxnKind.PAYMENT -> Icon(Icons.Filled.Check, null, tint = tint, modifier = Modifier.size(18.dp))
            TxnKind.REFUND -> Icon(Icons.AutoMirrored.Filled.Undo, null, tint = tint, modifier = Modifier.size(18.dp))
            TxnKind.CASHBACK -> Icon(Icons.Filled.Add, null, tint = tint, modifier = Modifier.size(18.dp))
            TxnKind.FEE -> Icon(Icons.Filled.PriorityHigh, null, tint = tint, modifier = Modifier.size(18.dp))
            else -> Text(
                monogram,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = tint,
            )
        }
    }
}

@Composable
fun TxnRow(t: Txn, cw: CardWithDue?, cat: Category?, hPad: Dp = 20.dp, onClick: () -> Unit) {
    val ft = remember(t.id, t.merchant, t.kind, t.isCredit, t.note, t.sourceText) { TxnDisplay.format(t) }
    val counted = TxnKind.spendSign(t.kind, t.isCredit) != 0 && t.kind != TxnKind.REFUND
    val badgeTint = when (t.kind) {
        TxnKind.PAYMENT, TxnKind.REFUND -> Good
        TxnKind.CASHBACK -> if (t.isCredit) Good else Amber
        TxnKind.FEE -> Danger
        else -> cat?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    }
    val customNote = remember(t.id, t.note) { extractCustomTxnNote(t.note) }
    val rowTitle = if (t.kind == TxnKind.PAYMENT && ft.channel != null) "Bill payment" else ft.title
    val cardLabel = cw?.let { "${it.card.nickname} •• ${it.card.last4}" }
    val detailText = when (t.kind) {
        TxnKind.PAYMENT -> customNote ?: ft.channel?.let { "Via $it" } ?: ft.typeLabel
        TxnKind.REFUND -> customNote ?: if (rowTitle == "Refund received") "Refund" else ft.typeLabel
        TxnKind.CASHBACK, TxnKind.FEE -> customNote ?: ft.typeLabel
        else -> customNote ?: cat?.name ?: if (cw == null) "Purchase" else null
    }
    val subtitleLine = listOfNotNull(detailText, cardLabel).joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = hPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TxnAvatarBadge(
            kind = t.kind,
            title = rowTitle,
            tint = badgeTint,
            size = 40.dp,
            cornerRadius = 14.dp,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(
                rowTitle,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitleLine.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleLine,
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            (if (t.isCredit || t.kind == TxnKind.PAYMENT) "+" else "") + Money.inr(t.amountPaise, withPaise = true),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ).tabular,
            color = when {
                t.isCredit || t.kind == TxnKind.PAYMENT -> Good
                t.kind == TxnKind.FEE -> Danger
                !counted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnSheet(
    txn: Txn,
    card: CardWithDue?,
    categories: List<Category>,
    statement: Statement?,
    onDismiss: () -> Unit,
    onOpenStatement: ((Long) -> Unit)?,
    onSave: (Long?, String?, String, String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    var cat by remember(txn.id) { mutableStateOf(txn.categoryId) }
    var kind by remember(txn.id) { mutableStateOf(txn.kind) }
    var applyAll by remember(txn.id) { mutableStateOf(value = false) }
    val pattern = remember(txn.id, txn.merchant) { Categorizer.merchantKey(txn.merchant) }
    val initialCustomNote = remember(txn.id, txn.note) { extractCustomTxnNote(txn.note).orEmpty() }
    var note by remember(txn.id) { mutableStateOf(initialCustomNote) }
    var showSourceMessage by remember(txn.id) { mutableStateOf(value = false) }

    val ft = remember(txn.id, txn.merchant, kind, txn.isCredit, txn.note, txn.sourceText) {
        TxnDisplay.format(txn.merchant, kind, txn.isCredit, txn.note, txn.sourceText)
    }
    val selectedCat = categories.firstOrNull { it.id == cat }
    val heroTint = when (kind) {
        TxnKind.PAYMENT, TxnKind.REFUND -> Good
        TxnKind.CASHBACK -> if (txn.isCredit) Good else Amber
        TxnKind.FEE -> Danger
        else -> selectedCat?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    }
    val displayTitle = if (kind == TxnKind.PAYMENT && ft.channel != null) "Bill payment" else ft.title
    val dateLabel = remember(txn.epochDay) { Dates.long(LocalDate.ofEpochDay(txn.epochDay)) }
    val cardLabel = card?.let { "${it.card.nickname} •• ${it.card.last4}" }
    val subLine = listOfNotNull(dateLabel, cardLabel).joinToString(" · ")
    val amountColor = when {
        txn.isCredit || kind == TxnKind.PAYMENT -> Good
        kind == TxnKind.FEE -> Danger
        else -> MaterialTheme.colorScheme.onSurface
    }

    if (LocalLocked.current) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            val heroShape = RoundedCornerShape(20.dp)
            val heroBorder = if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.outlineVariant
            Surface(
                shape = heroShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.6.dp, heroBorder, heroShape),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TxnAvatarBadge(
                            kind = kind,
                            title = displayTitle,
                            tint = heroTint,
                            size = 44.dp,
                            cornerRadius = 14.dp,
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                displayTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subLine,
                                style = MaterialTheme.typography.bodySmall.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            (if (txn.isCredit || kind == TxnKind.PAYMENT) "+" else "") + Money.inr(txn.amountPaise, withPaise = true),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).tabular,
                            color = amountColor,
                            maxLines = 1,
                        )
                    }
                    if (statement != null && onOpenStatement != null) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { onOpenStatement(statement.id) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open statement", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            if (kind == TxnKind.SPEND || kind == TxnKind.FEE || selectedCat != null) {
                SectionHeader("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        val cColor = Color(c.color)
                        TonalChoicePill(
                            label = c.name,
                            selected = cat == c.id,
                            accent = cColor,
                            dotColor = cColor,
                            onClick = { cat = if (cat == c.id) null else c.id },
                        )
                    }
                }
                if (cat != txn.categoryId && selectedCat != null && pattern.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Panel(padded = false) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { applyAll = !applyAll }
                                .heightIn(min = 54.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Remember for $displayTitle", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Text("Use ${selectedCat.name} for matching transactions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = applyAll, onCheckedChange = { applyAll = it })
                        }
                    }
                }
            }

            SectionHeader("Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalChoicePill(label = "Spend", selected = kind == TxnKind.SPEND, onClick = { kind = TxnKind.SPEND })
                TonalChoicePill(label = "Bill payment", selected = kind == TxnKind.PAYMENT, onClick = { kind = TxnKind.PAYMENT })
                TonalChoicePill(label = "Refund", selected = kind == TxnKind.REFUND, onClick = { kind = TxnKind.REFUND })
                TonalChoicePill(label = "Cashback", selected = kind == TxnKind.CASHBACK, onClick = { kind = TxnKind.CASHBACK })
                TonalChoicePill(label = "Fee", selected = kind == TxnKind.FEE, onClick = { kind = TxnKind.FEE })
            }

            Spacer(Modifier.height(14.dp))
            val noteShape = RoundedCornerShape(16.dp)
            val noteBorder = if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.outlineVariant
            TextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("Note (optional)") },
                placeholder = { Text("e.g. split with a friend, office lunch") },
                shape = noteShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.6.dp, noteBorder, noteShape),
            )

            Button(
                onClick = {
                    val savedNote = if (note.isBlank() && initialCustomNote.isEmpty()) txn.note else note
                    onSave(cat, if (applyAll && pattern.isNotBlank()) pattern else null, kind, savedNote)
                },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp),
            ) { Text("Save", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)) }
            extra?.invoke()

            if (txn.sourceText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSourceMessage = !showSourceMessage }
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showSourceMessage) "Hide statement line details" else "Show statement line details",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (showSourceMessage) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(
                    visible = showSourceMessage,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val sourceShape = RoundedCornerShape(16.dp)
                    val sourceBorder = if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.outlineVariant
                    val sourceTitle = "Statement line"
                    Surface(
                        shape = sourceShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.6.dp, sourceBorder, sourceShape),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            SelectionContainer {
                                Text(
                                    txn.sourceText,
                                    style = MonoSmall.copy(lineHeight = 18.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(sourceTitle, txn.sourceText))
                                    Toast.makeText(context, "$sourceTitle copied", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.align(Alignment.End).height(30.dp),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun saveTxnEdits(ledger: Ledger, t: Txn, catId: Long?, pattern: String?, kind: String, note: String) {
    if (catId != t.categoryId || pattern != null) ledger.setCategory(t, catId, pattern)
    if (kind != t.kind) ledger.setKind(t.copy(categoryId = catId), kind)
    if (note.trim() != t.note) ledger.setNote(t.copy(categoryId = catId, kind = kind), note)
}
