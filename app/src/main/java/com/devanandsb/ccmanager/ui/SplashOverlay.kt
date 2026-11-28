package com.devanandsb.ccmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devanandsb.ccmanager.R
import kotlin.math.PI
import kotlin.math.sin

private val VaultObsidianMidnight = Color(0xFF0C0D11)
private val VaultObsidianSurface = Color(0xFF14161D)
private val VaultObsidianTile = Color(0xFF1E2027)
private val VaultObsidianElevated = Color(0xFF282B33)
private val VaultGold = Color(0xFFEAC083)
private val VaultGoldSoft = Color(0xFFF4DFC5)
private val VaultGoldMuted = Color(0xFFCBA656)
private val VaultPorcelain = Color(0xFFE8E9EE)
private val VaultSubText = Color(0xFFB8BAC4)

/** Clickable CC Manager app icon emblem in headers; tapping opens the animated On-Device Enclave showcase. */
@Composable
fun AppLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.radialGradient(listOf(VaultObsidianElevated, VaultObsidianSurface)))
            .border(0.75.dp, VaultGold.copy(alpha = 0.34f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_fg),
            contentDescription = "CC Manager Logo",
            modifier = Modifier.requiredSize(size * 1.58f),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Animated Luxury Privacy & Hardware-Encryption Showcase shown when tapping the App Logo Emblem.
 * Demonstrates air-gapped on-device persistence & fingerprint-bound cryptographic protection.
 */
@Composable
fun VaultSecurityModal(onDismiss: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    val pulse = rememberInfiniteTransition(label = "vaultPulse")
    val ringExpand by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ring",
    )
    val glowPulse by pulse.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xEB08090D))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = VaultObsidianSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        val s = lerp(0.88f, 1f, enter.value)
                        scaleX = s
                        scaleY = s
                        alpha = enter.value
                    }
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                VaultGold.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.08f),
                                VaultGoldMuted.copy(alpha = 0.30f),
                            ),
                        ),
                        RoundedCornerShape(28.dp),
                    )
                    .clickable(enabled = false) {},
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to VaultObsidianElevated.copy(alpha = 0.45f),
                                0.45f to Color.Transparent,
                            ),
                        )
                        .padding(horizontal = 22.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Animated Air-Gapped Silicon Fortress Emblem + Fingerprint Core
                    Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2, size.height / 2)
                            val maxR = size.minDimension * 0.48f
                            drawCircle(
                                color = VaultGold.copy(alpha = (1f - ringExpand) * 0.42f),
                                radius = lerp(maxR * 0.48f, maxR, ringExpand),
                                center = center,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            val ring2 = ((ringExpand + 0.5f) % 1f)
                            drawCircle(
                                color = VaultGoldSoft.copy(alpha = (1f - ring2) * 0.28f),
                                radius = lerp(maxR * 0.48f, maxR, ring2),
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                        Box(
                            Modifier
                                .size(84.dp)
                                .graphicsLayer { scaleX = glowPulse; scaleY = glowPulse }
                                .clip(RoundedCornerShape(24.dp))
                                .background(Brush.radialGradient(listOf(VaultObsidianElevated, VaultObsidianMidnight)))
                                .border(1.2.dp, VaultGold.copy(alpha = 0.55f), RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint = VaultGold,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Surface(
                        shape = CircleShape,
                        color = VaultGold.copy(alpha = 0.14f),
                        modifier = Modifier.border(0.6.dp, VaultGold.copy(alpha = 0.40f), CircleShape),
                    ) {
                        Text(
                            "STORED ONLY ON YOUR PHONE",
                            color = VaultGold,
                            fontFamily = InstrumentSans,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Your Cards, Only Here",
                        style = MaterialTheme.typography.headlineMedium,
                        color = VaultPorcelain,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Every card and bill lives only on this phone. Nothing is shared online. No account needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultSubText,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(20.dp))

                    VaultPillarRow(
                        icon = Icons.Outlined.PhonelinkLock,
                        title = "Completely Offline",
                        body = "No internet, no servers, no analytics. Your data never leaves this phone.",
                    )
                    Spacer(Modifier.height(10.dp))
                    VaultPillarRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = "Locked with Your Fingerprint",
                        body = "Card numbers are protected by your fingerprint — only you can open them.",
                    )

                    Spacer(Modifier.height(22.dp))
                    VaultPrimaryButton(
                        text = "Got it",
                        icon = Icons.Outlined.Shield,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultPillarRow(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(VaultObsidianTile)
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(VaultGold.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = VaultGold, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = VaultPorcelain)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = VaultSubText)
        }
    }
}

/**
 * 1-second luxury 3D Obsidian & Champagne Gold credit card splash animation shown cleanly on launch.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    BackHandler(enabled = true) { }
    val anim = remember { Animatable(0f) }
    val ownerName = LocalOwnerName.current
    val hasCustomName = isCustomOwnerName(ownerName)
    val greeting = remember { timeOfDayGreeting() }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, tween(durationMillis = 1000, easing = LinearEasing))
        onFinished()
    }

    val t = anim.value
    val enter = FastOutSlowInEasing.transform((t / 0.40f).coerceIn(0f, 1f))
    val shimmer = FastOutSlowInEasing.transform(((t - 0.16f) / 0.56f).coerceIn(0f, 1f))
    val wordFade = FastOutSlowInEasing.transform(((t - 0.12f) / 0.30f).coerceIn(0f, 1f))
    val exit = FastOutSlowInEasing.transform(((t - 0.78f) / 0.22f).coerceIn(0f, 1f))
    val overallAlpha = 1f - exit

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overallAlpha }
            .background(VaultObsidianMidnight)
            .drawBehind {
                val glowAlpha = sin(t.coerceIn(0f, 1f) * PI).toFloat().coerceIn(0f, 1f) * 0.26f
                drawCircle(
                    Brush.radialGradient(
                        0f to VaultGold.copy(alpha = glowAlpha),
                        0.55f to VaultObsidianElevated.copy(alpha = glowAlpha * 0.42f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.44f),
                        radius = size.width * 0.68f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp),
        ) {
            val cardShape = RoundedCornerShape(28.dp)
            val cardAlpha = (t / 0.16f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .width(268.dp)
                    .aspectRatio(CARD_RATIO)
                    .graphicsLayer {
                        val s = lerp(0.82f, 1.0f, enter) + 0.05f * exit
                        scaleX = s
                        scaleY = s
                        rotationY = lerp(-28f, 0f, enter)
                        rotationX = lerp(14f, 0f, enter)
                        cameraDistance = 16f * density
                        alpha = cardAlpha
                    }
                    .shadow(
                        elevation = 26.dp,
                        shape = cardShape,
                        ambientColor = VaultGold.copy(alpha = 0.28f),
                        spotColor = Color.Black,
                    )
                    .clip(cardShape)
                    .background(
                        Brush.linearGradient(
                            0f to VaultObsidianElevated,
                            0.52f to VaultObsidianSurface,
                            1f to VaultObsidianMidnight,
                        ),
                    )
                    .border(
                        1.15.dp,
                        Brush.linearGradient(
                            listOf(
                                VaultGold.copy(alpha = 0.65f),
                                Color.White.copy(alpha = 0.14f),
                                VaultGoldMuted.copy(alpha = 0.35f),
                            ),
                        ),
                        cardShape,
                    ),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val center = lerp(-0.55f * w, 1.55f * w, shimmer)
                    val band = w * 0.42f
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.42f to VaultGoldSoft.copy(alpha = 0.08f),
                            0.50f to Color.White.copy(alpha = 0.22f),
                            0.58f to VaultGoldSoft.copy(alpha = 0.08f),
                            1f to Color.Transparent,
                            start = Offset(center - band, 0f),
                            end = Offset(center + band, h),
                        ),
                    )
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "PRIVATE VAULT",
                            color = VaultGold.copy(alpha = 0.92f),
                            fontFamily = InstrumentSans,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.8.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.Contactless,
                            contentDescription = null,
                            tint = VaultGold.copy(alpha = 0.80f),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Canvas(
                        Modifier
                            .size(width = 36.dp, height = 26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.linearGradient(listOf(VaultGoldSoft, VaultGold, VaultGoldMuted))),
                    ) {
                        val w = size.width
                        val h = size.height
                        val line = Color(0x4D291800)
                        val stroke = 1.dp.toPx()
                        drawRoundRect(
                            line,
                            Offset(w * 0.28f, h * 0.18f),
                            Size(w * 0.44f, h * 0.64f),
                            CornerRadius(3.dp.toPx()),
                            style = Stroke(stroke),
                        )
                        drawLine(line, Offset(0f, h * 0.38f), Offset(w * 0.28f, h * 0.38f), stroke)
                        drawLine(line, Offset(0f, h * 0.62f), Offset(w * 0.28f, h * 0.62f), stroke)
                        drawLine(line, Offset(w * 0.72f, h * 0.38f), Offset(w, h * 0.38f), stroke)
                        drawLine(line, Offset(w * 0.72f, h * 0.62f), Offset(w, h * 0.62f), stroke)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "••••  ••••  ••••  ••••",
                            color = VaultPorcelain.copy(alpha = 0.92f),
                            style = MonoMedium.copy(fontSize = 13.sp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(VaultGold.copy(alpha = 0.92f)),
                            )
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(VaultPorcelain.copy(alpha = 0.78f)),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = wordFade
                    translationY = lerp(10.dp.toPx(), 0f, wordFade)
                },
            ) {
                if (hasCustomName) {
                    Text(
                        greeting.uppercase(),
                        color = VaultGold,
                        fontFamily = InstrumentSans,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.8.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ownerName,
                        color = VaultPorcelain,
                        fontFamily = Mulish,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "CC MANAGER",
                        color = VaultPorcelain,
                        fontFamily = Mulish,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 5.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(lerp(12f, 56f, shimmer).dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, VaultGold, Color.Transparent))),
                )
            }
        }
    }
}
