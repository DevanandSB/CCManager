package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devanandsb.ccmanager.R
import com.devanandsb.ccmanager.domain.Issuers

fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

/** One wording for reminder leads everywhere: "On due day", "1 day before", "3 days before". */
fun reminderLeadLabel(days: Int): String = when (days) {
    0 -> "On due day"
    1 -> "1 day before"
    else -> "$days days before"
}

/** Compact form for a summary of several leads: "On due day, 3 days before". */
fun reminderLeadsSummary(leads: List<Int>): String =
    if (leads.isEmpty()) "Off"
    else leads.mapIndexed { i, d -> reminderLeadLabel(d).let { if (i == 0) it else it.replaceFirstChar(Char::lowercase) } }.joinToString(", ")

/** Read-only field showing a day of month; tapping opens a 1–31 grid. */
@Composable
fun DayPickerField(
    label: String,
    day: Int?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    placeholder: String = "Pick a day",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = day?.let { "${ordinal(it)} of month" } ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = colors,
            label = { Text(label, maxLines = 1) },
            placeholder = { Text(placeholder) },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize().padding(top = 8.dp).clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = "Choose $label") { open = true },
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(label, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    (1..31).chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            week.forEach { d ->
                                val sel = d == day
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape)
                                        .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .clickable(role = Role.Button) { onPick(d); open = false }
                                        .semantics { contentDescription = ordinal(d) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        d.toString(),
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium).tabular,
                                    )
                                }
                            }
                            repeat(7 - week.size) { Spacer(Modifier.size(38.dp)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "29–31 fall back to the last day in shorter months.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Bundled logo for an issuer, or null when none is available (monogram fallback). */
@androidx.annotation.DrawableRes
fun bankLogo(issuer: String): Int? = when (issuer) {
    "HDFC Bank" -> R.drawable.sym_hdfc
    "SBI Card" -> R.drawable.sym_sbi
    "ICICI Bank" -> R.drawable.sym_icici
    "Axis Bank" -> R.drawable.sym_axis
    "IDFC FIRST" -> R.drawable.sym_idfc
    "AU Bank" -> R.drawable.sym_au
    "RBL Bank" -> R.drawable.sym_rbl
    "Amex" -> R.drawable.net_amex
    "HSBC" -> R.drawable.sym_hsbc
    "Standard Chartered" -> R.drawable.sym_sc
    "BOB Card" -> R.drawable.sym_bob
    "State Bank of India" -> R.drawable.sym_sbi
    "Yes Bank" -> R.drawable.sym_yes
    "Indian Bank" -> R.drawable.sym_indianbank
    "Union Bank of India" -> R.drawable.sym_union
    else -> null
}

/** Symbols that carry their own coloured box: drawn edge-to-edge so no white ring shows. */
private val FULL_BLEED = setOf("IDFC FIRST", "AU Bank", "BOB Card", "Amex")

@androidx.annotation.DrawableRes
fun networkLogo(network: String): Int? = when (network) {
    "Visa" -> R.drawable.net_visa
    "Mastercard" -> R.drawable.net_mastercard
    "RuPay" -> R.drawable.net_rupay
    "Amex" -> R.drawable.net_amex
    "Diners" -> R.drawable.net_diners
    else -> null
}

@Composable
private fun FixedLogo(@androidx.annotation.DrawableRes res: Int, height: Dp, desc: String, tint: Color? = null) {
    val painter = painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
    Image(
        painter, desc, Modifier.size(width = height * ratio, height = height),
        contentScale = ContentScale.Fit,
        colorFilter = tint?.let { ColorFilter.tint(it) },
    )
}

/** Issuer symbol on a crisp squircle white tile with hairline rim; Paytm & modern banks get sharp custom badges. */
@Composable
fun BankBadge(issuer: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    val rim = Color.Black.copy(alpha = 0.08f)
    val logo = bankLogo(issuer)
    if (logo != null) {
        val bleed = issuer in FULL_BLEED
        Box(
            modifier.size(size).clip(shape).background(Color.White).border(0.6.dp, rim, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (bleed) {
                Image(
                    painterResource(logo), issuer, Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painterResource(logo), issuer, Modifier.size(size * 0.70f),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        return
    }
    if (issuer == "Paytm") {
        Box(
            modifier.size(size).clip(shape).background(Color.White).border(0.6.dp, rim, shape)
                .semantics { contentDescription = issuer },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(0xFF002970), fontWeight = FontWeight.ExtraBold)) { append("pay") }
                    withStyle(SpanStyle(color = Color(0xFF00BAF2), fontWeight = FontWeight.ExtraBold)) { append("tm") }
                },
                fontSize = (size.value * 0.24f).sp,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
            )
        }
        return
    }
    val code = Issuers.short(issuer)
    val color = Color(Issuers.byName(issuer).color)
    Box(
        modifier.size(size).clip(shape).background(Color.White).border(0.6.dp, rim, shape)
            .semantics { contentDescription = issuer },
        contentAlignment = Alignment.Center,
    ) {
        val fs = when (code.length) { 1, 2 -> 0.41f; 3 -> 0.33f; 4 -> 0.26f; else -> 0.21f }
        Text(
            code, color = color, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp, maxLines = 1,
            fontSize = (size.value * fs).sp,
        )
    }
}

/**
 * Network logo. [onDark] (card faces) shows wordmarks as white silhouettes like printed cards;
 * Mastercard and Amex keep their colours, and Diners (which has white inside) sits on a small chip.
 */
@Composable
fun NetworkMark(network: String, modifier: Modifier = Modifier, onDark: Boolean = false, cardInk: Color = Color.White) {
    val lightCard = onDark && cardInk != Color.White
    val logo = networkLogo(network)
    if (logo != null) {
        val darkBg = (onDark && !lightCard) || (!onDark && MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        val scale = if (onDark) 1f else 0.8f
        val h = when (network) { "Visa" -> 17.dp; "RuPay" -> 19.dp; "Diners" -> 18.dp; else -> 26.dp } * scale
        when {
            darkBg && network == "Diners" -> Box(
                modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { FixedLogo(logo, h, network) }
            else -> Box(modifier, contentAlignment = Alignment.Center) {
                val keep = !darkBg || network == "Mastercard" || network == "Amex"
                FixedLogo(logo, h, network, tint = if (keep) null else Color.White)
            }
        }
        return
    }
    val ink = if (onDark) cardInk else Color(0xFF1A1F71)
    Box(modifier.semantics { contentDescription = network }, contentAlignment = Alignment.Center) {
        when (network) {
            "Mastercard" -> Canvas(Modifier.size(width = 34.dp, height = 21.dp)) {
                val r = size.height / 2
                val left = Offset(r, r)
                val right = Offset(size.width - r, r)
                drawCircle(Color(0xFFEB001B), r, left)
                drawCircle(Color(0xFFF79E1B), r, right)
                val lens = Path().apply { addOval(Rect(left, r)) }
                clipPath(lens) { drawCircle(Color(0xFFFF5F00), r, right) }
            }
            "Visa" -> Text(
                "VISA", color = ink, fontSize = 17.sp, fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp,
            )
            "RuPay" -> Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink)) { append("Ru") }
                    withStyle(SpanStyle(color = if (onDark) Color(0xFFFFB066) else Color(0xFFF58220))) { append("Pay") }
                    withStyle(SpanStyle(color = Color(0xFF2E9E4F))) { append(" ▸") }
                },
                fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
            )
            "Amex" -> Text(
                "AMEX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFF2E77BB))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            "Diners" -> Text("Diners Club", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            else -> Text(network, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
