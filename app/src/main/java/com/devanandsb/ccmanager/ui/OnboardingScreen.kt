package com.devanandsb.ccmanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devanandsb.ccmanager.data.Prefs
import com.devanandsb.ccmanager.reminders.Notifications
import kotlinx.coroutines.launch

sealed interface OnboardingExit {
    data class AddCard(val start: String?) : OnboardingExit
    data object Later : OnboardingExit
}

private const val PAGES = 3

@Composable
fun OnboardingScreen(
    prefs: Prefs,
    onOwnerNameChanged: () -> Unit = {},
    onExit: (OnboardingExit) -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    var nameDraft by rememberSaveable { mutableStateOf(getCustomOwnerOverride(context).orEmpty()) }

    fun saveOwnerName(value: String) {
        setCustomOwnerOverride(context, value.trim().takeIf { it.isNotBlank() })
        onOwnerNameChanged()
    }

    fun finish(exit: OnboardingExit) {
        saveOwnerName(nameDraft)
        onExit(exit)
    }

    val restore = if (LocalInspectionMode.current) ({}) else rememberRestoreBackup { finish(OnboardingExit.Later) }
    val last = pager.currentPage == PAGES - 1
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val isDark = LocalIsDark.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = if (isDark) 0.16f else 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.12f),
                            radius = size.width * 0.95f,
                        ),
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(secondary.copy(alpha = if (isDark) 0.10f else 0.05f), Color.Transparent),
                            center = Offset(size.width * 0.10f, size.height * 0.55f),
                            radius = size.width * 0.78f,
                        ),
                    )
                },
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Architectural top bar with segmented progress rail
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gutter, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppLogoBadge(size = 36.dp)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(PAGES) { idx ->
                            val reached = idx <= pager.currentPage
                            val fill by animateFloatAsState(
                                if (reached) 1f else 0f,
                                spring(stiffness = Spring.StiffnessLow),
                                label = "seg$idx",
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fill)
                                        .clip(CircleShape)
                                        .background(primary),
                                )
                            }
                        }
                    }
                    if (!last) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier
                                .clip(CircleShape)
                                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f), CircleShape)
                                .clickable { finish(OnboardingExit.Later) },
                        ) {
                            Text(
                                "Skip",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                }

                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    when (page) {
                        0 -> WelcomePage(
                            nameDraft = nameDraft,
                            onNameChange = { v ->
                                if (v.length <= 28) {
                                    nameDraft = v
                                    saveOwnerName(v)
                                }
                            },
                        )
                        1 -> RemindersPage(prefs)
                        else -> FirstCardPage(
                            onAdd = { finish(OnboardingExit.AddCard(it)) },
                            onRestore = restore,
                        )
                    }
                }

                // Bottom CTA Rail
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gutter, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${pager.currentPage + 1} / $PAGES",
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (last) {
                        TextButton(onClick = { finish(OnboardingExit.Later) }) {
                            Text("Maybe later", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (pager.currentPage == 0) saveOwnerName(nameDraft)
                                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(50.dp)
                                .shadow(12.dp, CircleShape, ambientColor = primary.copy(alpha = 0.30f)),
                        ) {
                            Text("Next", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStage(
    visual: @Composable () -> Unit,
    kicker: String,
    headline: String,
    caption: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(236.dp),
            contentAlignment = Alignment.Center,
        ) { visual() }
        Spacer(Modifier.height(6.dp))
        Text(
            kicker.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            headline,
            style = MaterialTheme.typography.displayMedium.copy(
                lineHeight = 36.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        body()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VaultDeckShowcase() {
    val floatAnim = rememberInfiniteTransition(label = "deckFloat")
    val drift by floatAnim.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val isDark = LocalIsDark.current
    val shape = MaterialTheme.shapes.extraLarge
    val gold = Color(0xFFEAC083)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Left rear brushed titanium card
        Box(
            Modifier
                .width(196.dp)
                .aspectRatio(CARD_RATIO)
                .offset(x = (-42).dp, y = (drift * 0.6f).dp)
                .graphicsLayer {
                    rotationZ = -13f
                    alpha = 0.88f
                }
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFFD2D5DF), Color(0xFF7B8091))))
                .border(0.7.dp, Color.White.copy(alpha = 0.45f), shape),
        )
        // Right rear warm bronze-obsidian card
        Box(
            Modifier
                .width(196.dp)
                .aspectRatio(CARD_RATIO)
                .offset(x = 44.dp, y = (-drift * 0.5f).dp)
                .graphicsLayer {
                    rotationZ = 12f
                    alpha = 0.92f
                }
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF4A3519), Color(0xFF281D0D), Color(0xFF171209))))
                .border(0.7.dp, gold.copy(alpha = 0.25f), shape),
        )
        // Front flagship Private Vault Obsidian & Champagne Gold card
        Box(
            Modifier
                .width(236.dp)
                .aspectRatio(CARD_RATIO)
                .offset(y = (-drift).dp)
                .shadow(24.dp, shape, ambientColor = gold.copy(alpha = if (isDark) 0.35f else 0.20f))
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF262933), Color(0xFF15171D), Color(0xFF0C0D11))))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(gold.copy(alpha = 0.55f), Color.White.copy(alpha = 0.16f))),
                    shape,
                ),
        ) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.Transparent)))
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(Icons.Outlined.PhonelinkLock, null, tint = gold, modifier = Modifier.size(12.dp))
                            Text(
                                "ON YOUR PHONE",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color(0xFFF3EFE6),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Contactless, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(17.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Canvas(
                        Modifier
                            .size(width = 30.dp, height = 22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFFF6E7B4), Color(0xFFCBA656)))),
                    ) {
                        drawRoundRect(
                            Color(0x440D1028),
                            Offset(size.width * 0.28f, size.height * 0.18f),
                            Size(size.width * 0.44f, size.height * 0.64f),
                            CornerRadius(2.dp.toPx()),
                            style = Stroke(0.8.dp.toPx()),
                        )
                    }
                    Text(
                        "••••  ••••  4821",
                        style = MonoMedium.copy(fontSize = 13.sp),
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "PRIVATE VAULT",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = gold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(gold))
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xE6F4F6FF)))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(
    nameDraft: String,
    onNameChange: (String) -> Unit,
) {
    OnboardingStage(
        visual = { VaultDeckShowcase() },
        kicker = "Private Vault",
        headline = "Every card,\none quiet place.",
    ) {
        VaultCardContainer(
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "How should we address you?",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = onNameChange,
                    placeholder = { Text("Your name (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave blank to continue as $DEFAULT_OWNER_NAME.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DueRadarVisual() {
    val pulse = rememberInfiniteTransition(label = "radarPulse")
    val ring by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "r",
    )
    val primary = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(0.9f).height(160.dp)) {
            val arcPath = Path().apply {
                moveTo(0f, size.height * 0.72f)
                cubicTo(size.width * 0.32f, size.height * 0.18f, size.width * 0.68f, size.height * 0.18f, size.width, size.height * 0.56f)
            }
            drawPath(
                arcPath,
                color = primary.copy(alpha = 0.22f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            val nodeCenter = Offset(size.width * 0.74f, size.height * 0.34f)
            drawCircle(primary.copy(alpha = (1f - ring) * 0.35f), radius = 14.dp.toPx() + ring * 26.dp.toPx(), center = nodeCenter)
            drawCircle(primary, radius = 7.dp.toPx(), center = nodeCenter)
        }

        VaultCardContainer(
            shape = MaterialTheme.shapes.large,
            borderColor = primary.copy(alpha = 0.32f),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .offset(y = 26.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Alarm, null, tint = primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Amazon Pay due in 3 days", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text("₹1,400 · ICICI Bank •• 4821", style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UrgencyBadge("9:00 AM", UrgencyLevel.Good)
            }
        }
    }
}

@Composable
private fun RemindersPage(prefs: Prefs) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notif = remember(tick, inspection) { inspection || Notifications.enabled(context) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    OnboardingStage(
        visual = { DueRadarVisual() },
        kicker = "Due Reminders",
        headline = "Never pay a\nlate fee again.",
        caption = "We will remind you at 9:00 AM on the reminder day.",
    ) {
        PermissionCapsule(
            icon = Icons.Outlined.NotificationsNone,
            allowed = notif,
        ) {
            val canAsk = Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (canAsk) {
                prefs.askedNotifications = true
                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
    }
}

@Composable
private fun PermissionCapsule(
    icon: ImageVector,
    allowed: Boolean,
    onAllow: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(
        if (allowed) Good.copy(alpha = 0.40f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        label = "permBorder",
    )
    VaultCardContainer(
        shape = MaterialTheme.shapes.large,
        borderColor = border,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (allowed) Good.copy(alpha = 0.16f) else primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (allowed) Icons.Filled.Check else icon, null, tint = if (allowed) Good else primary, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Bill notifications", style = MaterialTheme.typography.titleSmall)
                Text("We will remind you at 9:00 AM on the reminder day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (allowed) {
                UrgencyBadge("Ready", UrgencyLevel.Good)
            } else {
                Button(
                    onClick = onAllow,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(34.dp),
                ) { Text("Enable", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
internal fun BlueprintCardStage(
    modifier: Modifier = Modifier,
    badgeText: String = "READY FOR CARD #1",
    onClick: (() -> Unit)? = null,
) {
    val shimmer = rememberInfiniteTransition(label = "blueprintSweep")
    val sweep by shimmer.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val primary = MaterialTheme.colorScheme.primary
    val gold = Color(0xFFEAC083)
    val shape = MaterialTheme.shapes.extraLarge

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Angled translucent silhouette frame behind
        Box(
            Modifier
                .width(248.dp)
                .aspectRatio(CARD_RATIO)
                .graphicsLayer { rotationZ = -6f }
                .clip(shape)
                .background(primary.copy(alpha = 0.08f))
                .border(0.75.dp, primary.copy(alpha = 0.24f), shape),
        )
        // Sculpted obsidian frosted card glass frame
        Box(
            Modifier
                .width(256.dp)
                .aspectRatio(CARD_RATIO)
                .shadow(22.dp, shape, ambientColor = gold.copy(alpha = 0.28f))
                .clip(shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .background(Brush.linearGradient(listOf(Color(0xFF252832), Color(0xFF15171D), Color(0xFF0C0D11))))
                .drawBehind {
                    val x = sweep * size.width
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                            start = Offset(x - size.width * 0.25f, 0f),
                            end = Offset(x + size.width * 0.25f, size.height),
                        ),
                    )
                    // Corner precision brackets
                    val b = 14.dp.toPx()
                    val pad = 12.dp.toPx()
                    val st = 1.5.dp.toPx()
                    val c = gold.copy(alpha = 0.78f)
                    drawLine(c, Offset(pad, pad), Offset(pad + b, pad), st, StrokeCap.Round)
                    drawLine(c, Offset(pad, pad), Offset(pad, pad + b), st, StrokeCap.Round)
                    drawLine(c, Offset(size.width - pad, pad), Offset(size.width - pad - b, pad), st, StrokeCap.Round)
                    drawLine(c, Offset(size.width - pad, pad), Offset(size.width - pad, pad + b), st, StrokeCap.Round)
                    drawLine(c, Offset(pad, size.height - pad), Offset(pad + b, size.height - pad), st, StrokeCap.Round)
                    drawLine(c, Offset(pad, size.height - pad), Offset(pad, size.height - pad - b), st, StrokeCap.Round)
                    drawLine(c, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - b, size.height - pad), st, StrokeCap.Round)
                    drawLine(c, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - b), st, StrokeCap.Round)
                }
                .border(1.dp, Brush.linearGradient(listOf(gold.copy(alpha = 0.42f), Color.White.copy(alpha = 0.14f))), shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(gold.copy(alpha = 0.18f))
                        .border(0.8.dp, gold.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = gold,
                )
            }
        }
    }
}

@Composable
private fun FirstCardPage(onAdd: (String?) -> Unit, onRestore: () -> Unit) {
    OnboardingStage(
        visual = { BlueprintCardStage(onClick = { onAdd("scan") }) },
        kicker = "Get started",
        headline = "Add your\nfirst card.",
        caption = "Scan, tap NFC, or enter manually.",
    ) {
        CardLaunchpadDock(onAdd)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Restore from a backup", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun CardLaunchpadDock(onAdd: (String?) -> Unit) {
    val nfc = nfcAvailable()
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Spotlight full-width Scan action
        Button(
            onClick = { onAdd("scan") },
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, MaterialTheme.shapes.large, ambientColor = primary.copy(alpha = 0.35f)),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.DocumentScanner, null, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Scan card with camera", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text("Fastest — fills card number & expiry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.84f))
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (nfc) {
                VaultCardContainer(
                    shape = MaterialTheme.shapes.large,
                    onClick = { onAdd("tap") },
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Nfc, null, tint = primary, modifier = Modifier.size(19.dp)) }
                        Column {
                            Text("Tap NFC", style = MaterialTheme.typography.titleSmall)
                            Text("Hold card", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            VaultCardContainer(
                shape = MaterialTheme.shapes.large,
                onClick = { onAdd(null) },
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Keyboard, null, tint = primary, modifier = Modifier.size(19.dp)) }
                    Column {
                        Text("Enter manually", style = MaterialTheme.typography.titleSmall)
                        Text("Type details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingScreenPreview() {
    CardsTheme {
        OnboardingScreen(prefs = Prefs(LocalContext.current), onExit = {})
    }
}
