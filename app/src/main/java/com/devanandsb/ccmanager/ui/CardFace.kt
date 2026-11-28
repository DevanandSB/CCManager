package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.devanandsb.ccmanager.data.CardKind
import com.devanandsb.ccmanager.data.hasDues
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money

const val CARD_RATIO = 1.586f
val CardShape = RoundedCornerShape(28.dp)

private const val VIBRANCE = 0.30f
private const val SOFTNESS = 0.70f

fun Modifier.cardBackground(color: Long, seed: Long): Modifier = drawWithCache(CardBackground(color, seed))

private fun cardTone(hue: Float, sat: Float, light: Float) = Color(
    ColorUtils.HSLToColor(
        floatArrayOf(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f)),
    ),
)

private fun cardHsl(color: Long): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(color.toInt(), it) }

fun cardBase(color: Long): Color {
    val (h, s, l) = cardHsl(color)
    return cardTone(h, (s + VIBRANCE * 0.45f).coerceAtMost(1f), l + VIBRANCE * 0.04f)
}

fun cardInk(color: Long): Color = if (cardBase(color).luminance() > 0.22f) Color(0xFF15171C) else Color.White

private data class CardBackground(val color: Long, val seed: Long) : (CacheDrawScope) -> DrawResult {
    override fun invoke(scope: CacheDrawScope): DrawResult = with(scope) { build() }

    private fun CacheDrawScope.build(): DrawResult {
        val rnd = java.util.Random(seed * 7919L + 13L)
        val (h, s, l) = cardHsl(color)
        fun tone(hue: Float, sat: Float, light: Float) = cardTone(hue, sat, light)
        val sat = (s + VIBRANCE * 0.45f).coerceAtMost(1f)
        val base = tone(h, sat, l + VIBRANCE * 0.04f)
        val tones = listOf(
            tone(h + 8f, sat, l + 0.22f),
            tone(h + 30f + rnd.nextFloat() * 25f, sat + 0.1f, l + 0.08f + VIBRANCE * 0.06f),
            tone(h - 30f - rnd.nextFloat() * 25f, sat + 0.1f, l + 0.05f),
            tone(h, sat * 0.9f, l - 0.12f),
        )
        val peak = 0.9f - SOFTNESS * 0.35f
        val mid = 0.4f - SOFTNESS * 0.1f
        val midStop = 0.45f + SOFTNESS * 0.15f
        val w = size.width
        val hgt = size.height
        val blobs = tones.map { t ->
            val center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * hgt)
            val radius = (110f + rnd.nextFloat() * 90f) * (1f + SOFTNESS * 0.6f) / 320f * w
            Brush.radialGradient(
                0f to t.copy(alpha = peak), midStop to t.copy(alpha = mid), 1f to t.copy(alpha = 0f),
                center = center, radius = radius,
            ) to Pair(center, radius)
        }
        return onDrawBehind {
            drawRect(base)
            blobs.forEach { (brush, cr) -> drawCircle(brush, radius = cr.second, center = cr.first) }
        }
    }
}

data class BadgeStyle(val text: String, val bg: Color, val fg: Color)

private val CardRed = Color(0xFFFF453A)
private val CardAmber = Color(0xFFFFB340)

fun dueBadge(cw: CardWithDue): BadgeStyle {
    val d = cw.due
    val reallyPaid = d.lastCyclePaid && cw.card.outstandingPaise <= 0L
    return when {
        d.overdue -> BadgeStyle(if (d.daysLeft == -1L) "Overdue 1d" else "Overdue ${-d.daysLeft}d", CardRed, Color.White)
        reallyPaid -> BadgeStyle("Paid", Color.White.copy(alpha = 0.22f), Color.White)
        d.daysLeft == 0L -> BadgeStyle("Due today", CardRed, Color.White)
        d.daysLeft <= 2 -> BadgeStyle("Due in ${d.daysLeft}d", CardRed, Color.White)
        d.daysLeft <= 5 -> BadgeStyle("Due in ${d.daysLeft}d", CardAmber, Color(0xFF231600))
        else -> BadgeStyle("Due in ${d.daysLeft}d", Color.White.copy(alpha = 0.22f), Color.White)
    }
}

@Composable
fun DueBadge(cw: CardWithDue, ink: Color = Color.White) {
    var s = dueBadge(cw)
    if (ink != Color.White && s.fg == Color.White && s.bg != CardRed) s = s.copy(bg = ink.copy(alpha = 0.14f), fg = ink)
    Text(
        s.text,
        color = s.fg,
        style = TextStyle(fontFamily = InstrumentSans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold).tabular,
        modifier = Modifier
            .clip(CircleShape)
            .background(s.bg)
            .border(0.5.dp, s.fg.copy(alpha = 0.20f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun CardFace(cw: CardWithDue, modifier: Modifier = Modifier, ownShadow: Boolean = true) {
    val c = cw.card
    val onCard = remember(c.color, c.art, c.issuer, c.nickname) { cardInkFor(c) }
    val dim = onCard.copy(alpha = if (onCard == Color.White) 0.76f else 0.70f)
    val specularRim = remember(onCard) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (onCard == Color.White) 0.34f else 0.60f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.16f),
            ),
        )
    }
    val gloss = remember {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.14f),
            0.42f to Color.White.copy(alpha = 0.02f),
            1f to Color.Black.copy(alpha = 0.12f),
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .then(if (ownShadow) Modifier.shadow(16.dp, CardShape, ambientColor = Color.Black.copy(alpha = 0.40f), spotColor = Color.Black.copy(alpha = 0.50f)) else Modifier)
            .clip(CardShape)
            .border(1.dp, specularRim, CardShape)
            .semantics {
                contentDescription = "${c.issuer} ${c.nickname}, ending ${c.last4}, " +
                    if (c.hasDues) "due ${Dates.long(cw.due.dueDate)}, outstanding ${Money.inr(cw.totalUsedPaise)}"
                    else if (c.isCredit) "due date not set, outstanding ${Money.inr(cw.totalUsedPaise)}"
                    else "${CardKind.label(c.kind)} card"
            },
    ) {
        CardSurface(c, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(gloss))
        CappedFontScale {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth().height(STRIP_HEIGHT - 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    BankBadge(c.issuer, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.nickname, color = onCard,
                                style = TextStyle(fontFamily = Mulish, fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            if (c.pinned) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.PushPin, "Pinned", tint = dim, modifier = Modifier.size(13.dp))
                            }
                        }
                        Text("${c.issuer} · •• ${c.last4}", color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 12.sp, fontWeight = FontWeight.Medium).tabular, maxLines = 1)
                    }
                    if (c.hasDues) DueBadge(cw, onCard)
                }
                Spacer(Modifier.weight(1f))
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
                        "•••• •••• •••• ${c.last4}", color = onCard,
                        style = TextStyle(fontFamily = SourceCodePro, fontSize = 15.5.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold).tabular,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (c.isCredit && !c.billingSet) {
                        if (cw.totalUsedPaise > 0L) Column {
                            Text("OUTSTANDING", color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 9.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold))
                            Text(Money.inr(cw.totalUsedPaise), color = onCard, style = TextStyle(fontFamily = Mulish, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold).tabular, maxLines = 1)
                        } else {
                            Text(CardKind.label(c.kind).uppercase(), color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold))
                        }
                    } else if (c.isCredit) {
                        if (cw.totalUsedPaise > 0L) {
                            Column {
                                Text("OUTSTANDING", color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 9.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold))
                                Text(Money.inr(cw.totalUsedPaise), color = onCard, style = TextStyle(fontFamily = Mulish, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold).tabular, maxLines = 1)
                            }
                        }
                        Column(horizontalAlignment = if (cw.totalUsedPaise > 0L) Alignment.End else Alignment.Start) {
                            Text(if (cw.due.lastCyclePaid) "NEXT DUE" else "PAY BY", color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 9.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold))
                            Text(Dates.short(cw.due.dueDate), color = onCard, style = TextStyle(fontFamily = InstrumentSans, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold).tabular, maxLines = 1)
                        }
                    } else {
                        Text(CardKind.label(c.kind).uppercase(), color = dim, style = TextStyle(fontFamily = InstrumentSans, fontSize = 10.5.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
                    }
                    if (!(c.issuer == "Amex" && c.network == "Amex")) NetworkMark(c.network, onDark = true, cardInk = onCard)
                }
            }
        }
    }
}

@Composable
fun CappedFontScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= MAX_CARD_FONT_SCALE) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, MAX_CARD_FONT_SCALE), content = content)
}

private const val MAX_CARD_FONT_SCALE = 1.15f

@Composable
internal fun Chip() {
    val metallic = remember {
        Brush.linearGradient(
            listOf(Color(0xFFF5E7BA), Color(0xFFD1AF62), Color(0xFFEDD998), Color(0xFFB89242)),
        )
    }
    Canvas(
        Modifier
            .size(width = 36.dp, height = 27.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(metallic)
            .border(0.5.dp, Color(0x44000000), RoundedCornerShape(6.dp)),
    ) {
        val w = size.width
        val h = size.height
        val line = Color(0x48000000)
        val stroke = 1.dp.toPx()
        drawRoundRect(line, Offset(w * 0.28f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.64f), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        drawLine(line, Offset(0f, h * 0.38f), Offset(w * 0.28f, h * 0.38f), stroke)
        drawLine(line, Offset(0f, h * 0.62f), Offset(w * 0.28f, h * 0.62f), stroke)
        drawLine(line, Offset(w * 0.72f, h * 0.38f), Offset(w, h * 0.38f), stroke)
        drawLine(line, Offset(w * 0.72f, h * 0.62f), Offset(w, h * 0.62f), stroke)
        drawLine(line, Offset(w * 0.50f, 0f), Offset(w * 0.50f, h * 0.18f), stroke)
        drawLine(line, Offset(w * 0.50f, h * 0.82f), Offset(w * 0.50f, h), stroke)
    }
}

val STRIP_HEIGHT = 64.dp
