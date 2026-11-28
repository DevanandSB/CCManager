package com.devanandsb.ccmanager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devanandsb.ccmanager.domain.CardNumbers
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.security.SecretDraft

/** Which secret fields exist in the vault (so the back can say "Not saved" without decrypting). */
data class StoredFields(val number: Boolean, val expiry: Boolean, val cvv: Boolean, val holder: Boolean)

/** How long the card takes to turn over. */
const val FLIP_MS = 450

@Composable
fun FlipCard(
    cw: CardWithDue,
    revealed: SecretDraft?,
    stored: StoredFields,
    progress: Float,
    onCopy: (label: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<SecretDraft?>(null) }
    if (revealed != null) shown = revealed
    val angle by animateFloatAsState(
        if (revealed != null) 180f else 0f, tween(FLIP_MS, easing = FastOutSlowInEasing), label = "flip",
    )
    Box(
        modifier.fillMaxWidth().graphicsLayer {
            rotationY = angle
            cameraDistance = 16f * density
        },
    ) {
        if (angle <= 90f) CardFace(cw)
        else Box(Modifier.graphicsLayer { rotationY = 180f }) { CardBack(cw, shown, stored, progress, onCopy) }
    }
}

object FlipBack {
    class Pending(val cardId: Long, val shown: SecretDraft, val stored: StoredFields)

    var pending by mutableStateOf<Pending?>(null)
        private set

    fun start(p: Pending) {
        pending = p
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (pending === p) pending = null }, 2000)
    }

    fun done(p: Pending) { if (pending === p) pending = null }
}

@Composable
fun LandingFlip(cw: CardWithDue, p: FlipBack.Pending, modifier: Modifier = Modifier) {
    val angle = remember(p) { Animatable(180f) }
    LaunchedEffect(p) {
        angle.animateTo(0f, tween(FLIP_MS, easing = FastOutSlowInEasing))
        FlipBack.done(p)
    }
    Box(
        modifier.graphicsLayer {
            rotationY = angle.value
            cameraDistance = 16f * density
        },
    ) {
        if (angle.value <= 90f) CardFace(cw, ownShadow = false)
        else Box(Modifier.graphicsLayer { rotationY = 180f }) { CardBack(cw, p.shown, p.stored, 0f) { _, _ -> } }
    }
}

@Composable
private fun CardBack(cw: CardWithDue, s: SecretDraft?, stored: StoredFields, progress: Float, onCopy: (String, String) -> Unit) {
    val c = cw.card
    val ink = remember(c.color, c.art, c.issuer, c.nickname) { cardInkFor(c) }
    val rim = remember(ink) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (ink == Color.White) 0.28f else 0.55f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.14f),
            ),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .shadow(14.dp, CardShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(CardShape)
            .border(1.dp, rim, CardShape),
    ) {
        CardSurface(c, Modifier.matchParentSize())
        CappedFontScale {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(Brush.horizontalGradient(listOf(Color(0xEB0E1015), Color(0xEB1B1E28), Color(0xEB0E1015)))),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text("Tap a detail to copy", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, modifier = Modifier.padding(end = 16.dp))
                }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Field("Card number", stored.number, s?.number?.let(CardNumbers::format), copyValue = s?.number, big = true, ink = ink, onCopy = onCopy, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Expiry", stored.expiry, s?.expiry, s?.expiry, ink = ink, onCopy = onCopy, modifier = Modifier.weight(1f))
                        Field("CVV", stored.cvv, s?.cvv, s?.cvv, ink = ink, onCopy = onCopy, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.5.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.65f)),
        )
    }
}

@Composable
private fun Field(
    label: String, stored: Boolean, value: String?, copyValue: String?,
    modifier: Modifier = Modifier, big: Boolean = false, ink: Color = Color.White, onCopy: (String, String) -> Unit,
) {
    val canCopy = stored && !copyValue.isNullOrEmpty()
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (canCopy) ink.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = canCopy, role = Role.Button, onClickLabel = "Copy $label") { onCopy(label, copyValue!!.replace(" ", "")) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label.uppercase(), color = ink.copy(alpha = 0.66f), fontFamily = InstrumentSans, fontSize = 9.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (!stored) "Not saved" else value ?: "••••",
            color = if (stored) ink else ink.copy(alpha = 0.5f),
            style = TextStyle(
                fontFamily = SourceCodePro,
                fontSize = if (big) 18.sp else 14.5.sp,
                fontWeight = if (stored) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (big) 1.2.sp else 0.5.sp,
            ).tabular,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
