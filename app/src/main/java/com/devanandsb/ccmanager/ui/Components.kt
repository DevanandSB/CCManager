package com.devanandsb.ccmanager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.domain.Money

val Gutter = 20.dp
val VaultEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.tabular,
                color = color,
            )
        }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    color: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalIsDark.current
    val resolvedColor = if (color != Color.Unspecified) color
    else if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val shape = MaterialTheme.shapes.large
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.45f)
    val elev = if (isDark) Modifier else Modifier.shadow(
        elevation = 3.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.04f),
        spotColor = Color.Black.copy(alpha = 0.05f),
    )
    Surface(
        shape = shape,
        color = resolvedColor,
        modifier = modifier
            .fillMaxWidth()
            .then(elev)
            .border(0.75.dp, borderColor, shape),
    ) {
        Column(if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun KeyValue(k: String, v: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium.tabular, color = valueColor)
    }
}

@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    solidBadge: Boolean = false,
) {
    val isDark = LocalIsDark.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (solidBadge) iconTint
                        else if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (solidBadge) Color.White else iconTint, modifier = Modifier.size(18.dp))
            }
        } else leading?.invoke()
        Column(Modifier.weight(1f).padding(start = if (icon != null || leading != null) 14.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall.tabular,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        action?.invoke()
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun RowDivider(inset: Boolean = true) =
    HorizontalDivider(
        thickness = 0.7.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = if (inset) 66.dp else 16.dp),
    )

@Composable
fun StatusPill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
        modifier = Modifier
            .border(0.6.dp, color.copy(alpha = 0.28f), CircleShape)
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun <T> SegmentedPillBar(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDark.current
    val trackShape = RoundedCornerShape(14.dp)
    val thumbShape = RoundedCornerShape(11.dp)
    val trackColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val thumbSelected = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
    Row(
        modifier
            .clip(trackShape)
            .background(trackColor)
            .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), trackShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { item ->
            val sel = item == selected
            val bg by animateColorAsState(
                if (sel) thumbSelected else Color.Transparent,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segBg",
            )
            val fg by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segFg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (sel && !isDark) Modifier.shadow(2.dp, thumbShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                        else Modifier,
                    )
                    .clip(thumbShape)
                    .background(bg)
                    .then(
                        if (sel && isDark) Modifier.border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thumbShape)
                        else Modifier,
                    )
                    .selectable(selected = sel, role = Role.Tab) { onSelect(item) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MoneyField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, prefix = { Text("₹ ") }, singleLine = true,
        shape = MaterialTheme.shapes.small,
        isError = value.isNotBlank() && Money.parse(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalLocked.current) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp),
            ) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (LocalLocked.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Danger else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MarkPaidSheet(dueLabel: String, total: Long, minimum: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    if (total <= 0) {
        EditSheet(
            title = "No payment needed",
            note = "Nothing is due for the payment due $dueLabel. Mark it settled to stop its reminders.",
            onDismiss = onDismiss, confirmLabel = "Mark as settled", onConfirm = { onConfirm(0) },
        ) {}
        return
    }
    val hasMin = minimum != null && minimum in 1 until total
    var choice by remember { mutableStateOf("total") }
    var other by remember { mutableStateOf("") }
    val amount = when (choice) {
        "total" -> total
        "min" -> minimum
        else -> Money.parse(other)?.takeIf { it > 0 }
    }
    EditSheet(
        title = "Mark as paid", note = "For the payment due $dueLabel. Its reminders stop.",
        onDismiss = onDismiss, confirmLabel = amount?.let { "Mark ${Money.inr(it, true)} paid" } ?: "Mark as paid",
        confirmEnabled = amount != null, onConfirm = { amount?.let(onConfirm) },
    ) {
        Panel(padded = false) {
            PayOption("Full amount", Money.inr(total, true), choice == "total") { choice = "total" }
            if (hasMin) {
                RowDivider(inset = false)
                PayOption("Minimum due", Money.inr(minimum, true), choice == "min") { choice = "min" }
            }
            RowDivider(inset = false)
            PayOption("Other amount", null, choice == "other") { choice = "other" }
        }
        if (choice == "other") MoneyField("Amount paid", other) { other = it }
    }
}

@Composable
private fun PayOption(label: String, value: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge.tabular) }
    }
}

@Composable
fun AmountSheet(title: String, note: String, initialPaise: Long, confirmLabel: String = "Save", onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf(Money.toInput(initialPaise)) }
    val parsed = Money.parse(text)
    EditSheet(title, onDismiss, confirmLabel, confirmEnabled = parsed != null, onConfirm = { parsed?.let(onConfirm) }, note = note) {
        MoneyField("Amount", text) { text = it }
    }
}

@Composable
fun InlineAction(text: String, onClick: () -> Unit) =
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(text) }

@Composable
fun GoogleGMark(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.White,
        modifier = modifier
            .size(38.dp)
            .border(0.6.dp, Color.Black.copy(alpha = 0.08f), CircleShape),
    ) {
        Canvas(Modifier.padding(9.dp)) {
            val stroke = size.minDimension * 0.22f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0xFFEA4335), startAngle = -145f, sweepAngle = 98f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFFFBBC05), startAngle = 140f, sweepAngle = 76f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFF34A853), startAngle = 42f, sweepAngle = 100f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFF4285F4), startAngle = -6f, sweepAngle = 52f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            val cy = size.height / 2f
            drawLine(
                color = Color(0xFF4285F4),
                start = Offset(size.width * 0.50f, cy),
                end = Offset(size.width - inset, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
        }
    }
}

// ---------- Stitch "Private Vault" Reusable Components ----------

/**
 * Frosted translucent sticky top app bar matching Stitch `header` specs:
 * 80-86% surface fill, hairline outlineVariant bottom border, 40dp circular back/icon buttons.
 */
@Composable
fun VaultTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backDescription: String = "Back",
    navigationContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isDark = LocalIsDark.current
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.88f else 0.90f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.40f else 0.32f)

    Column(
        modifier
            .fillMaxWidth()
            .background(surfaceColor),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 64.dp)
                .padding(horizontal = Gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (navigationContent != null) {
                navigationContent()
            } else if (onBack != null) {
                VaultIconButton(
                    icon = backIcon,
                    contentDescription = backDescription,
                    onClick = onBack,
                )
            }
            Column(Modifier.weight(1f)) {
                eyebrow?.let {
                    Text(
                        it.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    title,
                    style = if (subtitle == null && eyebrow == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
        HorizontalDivider(thickness = 0.75.dp, color = borderColor)
    }
}

/**
 * 40dp circular button matching Stitch `button-icon` (frosted bordered) or `button-icon-filled` (primary filled).
 */
@Composable
fun VaultIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 40.dp,
    tint: Color = Color.Unspecified,
) {
    val isDark = LocalIsDark.current
    val bg = when {
        filled -> MaterialTheme.colorScheme.primary
        isDark -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)
    }
    val iconColor = when {
        tint != Color.Unspecified -> tint
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderMod = if (filled) {
        Modifier.shadow(
            elevation = 8.dp,
            shape = CircleShape,
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        )
    } else {
        Modifier.border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f), CircleShape)
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        modifier = modifier.size(size).then(borderMod),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = iconColor, modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * Full-width or inline pill CTA matching Stitch `button-primary`.
 */
@Composable
fun VaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        modifier = modifier.heightIn(min = 50.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Secondary pill button with tonal container and subtle outline matching Stitch secondary actions.
 */
@Composable
fun VaultTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val isDark = LocalIsDark.current
    val bg = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        modifier = modifier
            .heightIn(min = 48.dp)
            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f), CircleShape),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Filter pill chip matching Stitch `chip-filter-selected` and `chip-action`.
 */
@Composable
fun VaultFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val isDark = LocalIsDark.current
    val selectedBg = if (isDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val selectedFg = if (isDark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface
    val bg by animateColorAsState(
        if (selected) selectedBg else Color.Transparent,
        animationSpec = tween(150),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) selectedFg else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(150),
        label = "chipFg",
    )
    val border = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)

    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.9.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = fg, modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Standard Private Vault squircle surface container (20dp or 28dp) with hairline outlineVariant border.
 */
@Composable
fun VaultCardContainer(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalIsDark.current
    val bg = if (containerColor != Color.Unspecified) containerColor
    else if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val stroke = if (borderColor != Color.Unspecified) borderColor
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.45f)
    val elev = if (isDark) Modifier else Modifier.shadow(
        elevation = 3.dp,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.04f),
        spotColor = Color.Black.copy(alpha = 0.05f),
    )
    Surface(
        shape = shape,
        color = bg,
        modifier = modifier
            .fillMaxWidth()
            .then(elev)
            .border(0.75.dp, stroke, shape)
            .then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Bento metric tile used in DetailScreen and Overview grids.
 */
@Composable
fun VaultBentoTile(
    eyebrow: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    VaultCardContainer(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.headlineLarge.tabular,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(12.dp))
                footer()
            }
        }
    }
}

/**
 * 64dp circular credit utilization meter matching Stitch `cards_home/code.html`.
 */
@Composable
fun CircularUtilizationGauge(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 520, easing = VaultEase),
        label = "utilGauge",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColor = when {
        clamped >= 0.85f -> Danger
        clamped >= 0.60f -> Amber
        else -> MaterialTheme.colorScheme.primary
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.tabular,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

data class SpendSegment(
    val label: String,
    val amountPaise: Long,
    val fraction: Float,
    val color: Color,
)

/**
 * Segmented proportional horizontal bar matching Stitch `statement/code.html`.
 */
@Composable
fun ProportionalSpendBar(
    segments: List<SpendSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val filtered = segments.filter { it.fraction > 0f }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (filtered.isEmpty()) {
            Spacer(Modifier.fillMaxWidth().fillMaxHeight())
        } else {
            filtered.forEach { seg ->
                Box(
                    Modifier
                        .weight(seg.fraction.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(seg.color),
                )
            }
        }
    }
}

/**
 * Monospace masked or revealed card number using Source Code Pro.
 */
@Composable
fun MaskedCardNumber(
    last4: String,
    modifier: Modifier = Modifier,
    fullNumber: String? = null,
    revealed: Boolean = false,
    style: TextStyle = MonoMedium,
    color: Color = Color.Unspecified,
) {
    val cleanLast4 = last4.filter(Char::isDigit).takeLast(4).padStart(4, '•')
    val display = if (revealed && !fullNumber.isNullOrBlank()) {
        fullNumber.filter(Char::isDigit).chunked(4).joinToString(" ")
    } else {
        "•••• •••• •••• $cleanLast4"
    }
    Text(
        display,
        style = style,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

enum class UrgencyLevel { Urgent, Warning, Good, Neutral }

/**
 * Semantic urgency badge matching Stitch (`2d left`, `Due 28 Nov`, `Paid`).
 */
@Composable
fun UrgencyBadge(
    text: String,
    level: UrgencyLevel,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
) {
    val status = VaultStatus
    val isDark = LocalIsDark.current
    val (bg, fg, dot) = when (level) {
        UrgencyLevel.Urgent -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.error)
        UrgencyLevel.Warning -> Triple(
            if (isDark) MaterialTheme.colorScheme.primaryContainer else status.warnContainer,
            if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else status.onWarnContainer,
            status.warn,
        )
        UrgencyLevel.Good -> Triple(status.goodContainer, status.onGoodContainer, status.good)
        UrgencyLevel.Neutral -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondary)
    }
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showDot) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
