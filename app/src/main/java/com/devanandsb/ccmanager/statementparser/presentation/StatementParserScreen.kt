package com.devanandsb.ccmanager.statementparser.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.devanandsb.ccmanager.ui.LocalStatementLoading
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devanandsb.ccmanager.statementparser.domain.model.ExtractionEngine
import com.devanandsb.ccmanager.statementparser.domain.model.ParsedStatementResult
import com.devanandsb.ccmanager.statementparser.domain.model.StatementTransaction
import com.devanandsb.ccmanager.statementparser.domain.model.TransactionType
import com.devanandsb.ccmanager.ui.Amber
import com.devanandsb.ccmanager.ui.Danger
import com.devanandsb.ccmanager.ui.Good
import com.devanandsb.ccmanager.ui.Gutter
import com.devanandsb.ccmanager.ui.MonoMedium
import com.devanandsb.ccmanager.ui.VaultCardContainer
import com.devanandsb.ccmanager.ui.VaultTopAppBar
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@Composable
fun StatementParserScreen(
    onBack: () -> Unit,
    viewModel: StatementParserViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val loading = LocalStatementLoading.current
    var wasLoading by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoading, uiState.selectedFileName) {
        if (uiState.isLoading) {
            wasLoading = true
            loading?.begin(coroutineScope, uiState.selectedFileName)
        } else if (wasLoading) {
            wasLoading = false
            loading?.end(coroutineScope, success = uiState.result != null)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (wasLoading) loading?.end(coroutineScope, success = false)
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = resolveFileName(context, uri)
            viewModel.onPdfSelected(uri, fileName)
        }
    }

    uiState.passwordPrompt?.let { prompt ->
        PdfPasswordDialog(
            hint = prompt.hint,
            incorrectAttempt = prompt.incorrectAttempt,
            onSubmit = viewModel::submitPassword,
            onDismiss = viewModel::dismissPasswordPrompt,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VaultTopAppBar(
                eyebrow = "STAYS ON YOUR PHONE",
                title = "Statement Reader",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = Gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("picker_card") {
                VaultCardContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Offline Statement Reader",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = uiState.selectedFileName ?: "Works offline. Supports password-protected & scanned bank PDFs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            enabled = !uiState.isLoading,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (uiState.result == null) "Select Statement PDF" else "Check Another PDF")
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                item("loading_card") {
                    VaultCardContainer {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                            Column {
                                Text(
                                    text = "Reading statement on your phone…",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Checking transactions and totals…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            uiState.errorMessage?.let { msg ->
                item("error_card") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Danger.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Danger.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Danger)
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::dismissError) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            uiState.result?.let { parsed ->
                item("status_badges") {
                    ParsedHeaderDangergesCard(
                        result = parsed,
                        onCopyJson = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("Statement Details", parsed.toPrettyJson()),
                            )
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Statement details copied to clipboard")
                            }
                        },
                    )
                }

                item("summary_card") {
                    StatementSummaryDashboardCard(parsed)
                }

                item("txns_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "EXTRACTED TRANSACTIONS (${parsed.transactions.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${parsed.pageCount} page(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (parsed.transactions.isEmpty()) {
                    item("no_txns") {
                        VaultCardContainer {
                            Text(
                                text = "Summary found, but no individual transactions could be read.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(parsed.transactions) { txn ->
                        TransactionItemCard(txn)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParsedHeaderDangergesCard(
    result: ParsedStatementResult,
    onCopyJson: () -> Unit,
) {
    val bankLabel = result.summary.bankName ?: "Detected Statement"
    val last4Label = result.summary.cardLast4Digits?.let { "•••• $it" } ?: "Card N/A"
    val engineLabel = when (result.extractionEngine) {
        ExtractionEngine.PDFBOX_VECTOR -> "Standard PDF text"
        ExtractionEngine.MLKIT_OCR -> "Scanned page reader"
    }

    VaultCardContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = bankLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "$last4Label · $engineLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val badgeColor = if (result.isMathVerified) Good else Amber
                Surface(
                    shape = CircleShape,
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (result.isMathVerified) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (result.isMathVerified) "Verified" else "Needs Review",
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeColor,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onCopyJson,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Copy statement details")
            }
        }
    }
}

@Composable
private fun StatementSummaryDashboardCard(result: ParsedStatementResult) {
    val summary = result.summary
    VaultCardContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "STATEMENT SUMMARY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStatColumn(
                    label = "Total Due",
                    value = summary.totalAmountDue?.let(::formatCurrency) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Min Due",
                    value = summary.minimumAmountDue?.let(::formatCurrency) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Due Date",
                    value = summary.paymentDueDate ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStatColumn(
                    label = "Credit Limit",
                    value = summary.creditLimit?.let(::formatCurrency) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Stmt Date",
                    value = summary.statementDate ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Opening Bal",
                    value = summary.openingBalance?.let(::formatCurrency) ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStatColumn(
                    label = "Total Debits",
                    value = formatCurrency(result.totalDebits),
                    valueColor = Danger,
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Total Credits",
                    value = formatCurrency(result.totalCredits),
                    valueColor = Good,
                    modifier = Modifier.weight(1f),
                )
                SummaryStatColumn(
                    label = "Discrepancy",
                    value = formatCurrency(result.discrepancyAmount),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryStatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MonoMedium.copy(fontWeight = FontWeight.Bold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TransactionItemCard(txn: StatementTransaction) {
    val isCredit = txn.type == TransactionType.CREDIT
    val badgeColor = if (isCredit) Good else Danger
    val badgeLabel = if (isCredit) "CR" else "DR"

    VaultCardContainer(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeColor.copy(alpha = 0.14f),
                border = BorderStroke(0.75.dp, badgeColor.copy(alpha = 0.35f)),
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = txn.description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${txn.date} · Page ${txn.page}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = (if (isCredit) "+" else "") + formatCurrency(txn.amount),
                style = MonoMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isCredit) Good else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PdfPasswordDialog(
    hint: String,
    incorrectAttempt: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text("PDF Password Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (incorrectAttempt) {
                        "Incorrect password. Please try again."
                    } else {
                        "This statement PDF is password-protected."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (incorrectAttempt) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Hint: $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Statement password") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank(),
            ) {
                Text("Unlock & Parse")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatCurrency(amount: BigDecimal): String =
    runCatching {
        val formatter = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
        formatter.format(amount)
    }.getOrElse { "₹${amount.toPlainString()}" }

private fun resolveFileName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
