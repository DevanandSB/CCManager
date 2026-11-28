package com.devanandsb.ccmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val LOADING_THRESHOLD_MS = 500L
private const val COMPLETION_HOLD_MS = 380L

@Stable
class StatementLoadingController {
    var active by mutableStateOf(false)
        private set
    var visible by mutableStateOf(false)
        private set
    var completed by mutableStateOf(false)
        private set
    var fileName by mutableStateOf<String?>(null)
        private set

    private var depth = 0
    private var thresholdJob: Job? = null
    private var dismissJob: Job? = null

    fun begin(scope: CoroutineScope, fileName: String? = null) {
        dismissJob?.cancel()
        dismissJob = null
        completed = false
        if (!fileName.isNullOrBlank()) this.fileName = fileName
        depth++
        active = true
        if (depth == 1 && !visible) {
            thresholdJob?.cancel()
            thresholdJob = scope.launch {
                delay(LOADING_THRESHOLD_MS)
                if (active) visible = true
            }
        }
    }

    fun end(scope: CoroutineScope, success: Boolean = false) {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth > 0) return
        active = false
        thresholdJob?.cancel()
        thresholdJob = null
        if (visible) {
            if (success) {
                completed = true
                dismissJob?.cancel()
                dismissJob = scope.launch {
                    delay(COMPLETION_HOLD_MS)
                    if (!active) {
                        visible = false
                        completed = false
                        fileName = null
                    }
                }
            } else {
                visible = false
                completed = false
                fileName = null
            }
        } else {
            completed = false
            fileName = null
        }
    }
}

val LocalStatementLoading = compositionLocalOf<StatementLoadingController?> { null }

private data class ParsingMessage(
    val title: String,
    val subtitle: String,
    val stepIndex: Int,
)

private val ParsingMessages = listOf(
    ParsingMessage("Opening your statement…", "Checking pages and layout on your phone", 0),
    ParsingMessage("Fetching card summary & bill due…", "Finding total due, minimum due & due date", 1),
    ParsingMessage("Reading every transaction…", "Gathering merchants, spends & refunds", 2),
    ParsingMessage("Organizing spend categories…", "Grouping dining, travel, shopping & bills", 2),
    ParsingMessage("Double-checking the totals…", "Making sure every rupee adds up", 3),
    ParsingMessage("Polishing your statement view…", "Just a moment more—almost ready", 3),
)

private val MilestoneLabels = listOf("Pages", "Summary", "Spends", "Totals")

@Composable
fun StatementParsingOverlay(
    visible: Boolean,
    completed: Boolean,
    fileName: String?,
) {
    BackHandler(enabled = visible) {
        // Keep statement import intact while loading overlay is active.
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(220)) + scaleOut(targetScale = 1.02f, animationSpec = tween(220)),
    ) {
        StatementParsingOverlayContent(
            completed = completed,
            fileName = fileName,
        )
    }
}

@Composable
private fun StatementParsingOverlayContent(
    completed: Boolean,
    fileName: String?,
) {
    val isDark = LocalIsDark.current
    val colors = MaterialTheme.colorScheme
    val status = VaultStatus

    var msgIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(completed) {
        if (completed) return@LaunchedEffect
        msgIndex = 0
        while (true) {
            delay(1650L)
            msgIndex = (msgIndex + 1) % ParsingMessages.size
        }
    }

    val currentMessage = if (completed) {
        ParsingMessage("Statement details fetched!", "Opening your statement summary", 4)
    } else {
        ParsingMessages[msgIndex]
    }

    val infinite = rememberInfiniteTransition(label = "statementParsing")
    val orbitAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbitAngle",
    )
    val counterOrbitAngle by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(13000, easing = LinearEasing), RepeatMode.Restart),
        label = "counterOrbit",
    )
    val scanProgress by infinite.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(tween(1550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scanBeam",
    )
    val floatPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "floatPhase",
    )
    val shimmerSlide by infinite.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "shimmerSlide",
    )

    val doneScale by animateFloatAsState(
        targetValue = if (completed) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "doneScale",
    )

    val cardBg = if (isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest
    val cardBorder = if (completed) status.good.copy(alpha = 0.70f) else colors.primary.copy(alpha = 0.36f)
    val trackBg = if (isDark) colors.surfaceContainerHighest else colors.surfaceContainerHigh

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // Theme-adaptive animated radial aurora backdrop
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dx1 = cos(floatPhase.toDouble()).toFloat() * w * 0.08f
            val dy1 = sin(floatPhase.toDouble()).toFloat() * h * 0.05f
            val dx2 = sin((floatPhase * 0.8f).toDouble()).toFloat() * w * 0.09f
            val dy2 = cos((floatPhase * 0.8f).toDouble()).toFloat() * h * 0.06f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = if (isDark) 0.22f else 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.5f + dx1, h * 0.38f + dy1),
                    radius = w * 0.78f,
                ),
                center = Offset(w * 0.5f + dx1, h * 0.38f + dy1),
                radius = w * 0.78f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.tertiary.copy(alpha = if (isDark) 0.16f else 0.12f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.25f - dx2, h * 0.65f + dy2),
                    radius = w * 0.62f,
                ),
                center = Offset(w * 0.25f - dx2, h * 0.65f + dy2),
                radius = w * 0.62f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.secondary.copy(alpha = if (isDark) 0.14f else 0.10f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.82f + dx2, h * 0.24f - dy2),
                    radius = w * 0.50f,
                ),
                center = Offset(w * 0.82f + dx2, h * 0.24f - dy2),
                radius = w * 0.50f,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Gutter, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top filename pill
            Box(
                modifier = Modifier.padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isDark) colors.surfaceContainer.copy(alpha = 0.88f) else colors.surfaceContainerLowest.copy(alpha = 0.92f),
                    modifier = Modifier.border(0.75.dp, colors.outlineVariant.copy(alpha = 0.45f), CircleShape),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PictureAsPdf,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = fileName?.takeIf { it.isNotBlank() } ?: "Statement PDF",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Centerpiece: Orbital Vault Statement Scanner
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .graphicsLayer {
                            scaleX = doneScale
                            scaleY = doneScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Rotating orbital rings + floating theme particles
                    Canvas(Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val outerR = size.minDimension * 0.47f
                        val innerR = size.minDimension * 0.39f

                        rotate(orbitAngle, center) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        Color.Transparent,
                                        colors.primary.copy(alpha = 0.55f),
                                        colors.primary.copy(alpha = 0.10f),
                                        Color.Transparent,
                                    ),
                                    center = center,
                                ),
                                startAngle = 0f,
                                sweepAngle = 220f,
                                useCenter = false,
                                topLeft = Offset(center.x - outerR, center.y - outerR),
                                size = Size(outerR * 2f, outerR * 2f),
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                            )
                            val dotAngleRad = 1.9
                            val dotPos = Offset(
                                center.x + cos(dotAngleRad).toFloat() * outerR,
                                center.y + sin(dotAngleRad).toFloat() * outerR,
                            )
                            drawCircle(colors.primary, radius = 4.5.dp.toPx(), center = dotPos)
                        }

                        rotate(counterOrbitAngle, center) {
                            drawArc(
                                color = colors.tertiary.copy(alpha = 0.30f),
                                startAngle = 35f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = Offset(center.x - innerR, center.y - innerR),
                                size = Size(innerR * 2f, innerR * 2f),
                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                            )
                            drawArc(
                                color = colors.secondary.copy(alpha = 0.25f),
                                startAngle = 215f,
                                sweepAngle = 95f,
                                useCenter = false,
                                topLeft = Offset(center.x - innerR, center.y - innerR),
                                size = Size(innerR * 2f, innerR * 2f),
                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }

                        // Soft floating particles around the card
                        for (i in 0 until 6) {
                            val baseAngle = (i * 60f) * (PI / 180.0) + orbitAngle * 0.008
                            val r = outerR * (0.86f + 0.10f * sin(floatPhase + i * 1.1f))
                            val px = center.x + cos(baseAngle).toFloat() * r
                            val py = center.y + sin(baseAngle).toFloat() * r
                            val particleAlpha = 0.28f + 0.35f * (0.5f + 0.5f * sin(floatPhase * 1.4f + i))
                            drawCircle(
                                color = if (i % 2 == 0) colors.primary.copy(alpha = particleAlpha)
                                else status.good.copy(alpha = particleAlpha),
                                radius = (2.2f + (i % 2)).dp.toPx(),
                                center = Offset(px, py),
                            )
                        }
                    }

                    // Stylized Floating Statement Card with Live Laser Scan Beam
                    val bobY = sin(floatPhase.toDouble()).toFloat() * 5f
                    val cardShape = RoundedCornerShape(22.dp)
                    Box(
                        modifier = Modifier
                            .size(width = 148.dp, height = 186.dp)
                            .graphicsLayer { translationY = bobY }
                            .shadow(
                                elevation = if (isDark) 18.dp else 12.dp,
                                shape = cardShape,
                                ambientColor = colors.primary.copy(alpha = 0.25f),
                                spotColor = colors.primary.copy(alpha = 0.30f),
                            )
                            .clip(cardShape)
                            .background(cardBg)
                            .border(1.2.dp, cardBorder, cardShape),
                    ) {
                        if (completed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(status.goodContainer.copy(alpha = if (isDark) 0.35f else 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = status.good,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Text(
                                        text = "Ready!",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        } else {
                            Canvas(Modifier.fillMaxSize()) {
                                val padX = 16.dp.toPx()
                                val barW = size.width - padX * 2f
                                val beamY = size.height * scanProgress

                                // Miniature card chip / bank emblem
                                drawRoundRect(
                                    color = colors.primary.copy(alpha = 0.22f),
                                    topLeft = Offset(padX, 18.dp.toPx()),
                                    size = Size(28.dp.toPx(), 20.dp.toPx()),
                                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                                )
                                drawRoundRect(
                                    color = colors.primary.copy(alpha = 0.65f),
                                    topLeft = Offset(padX + 6.dp.toPx(), 24.dp.toPx()),
                                    size = Size(16.dp.toPx(), 8.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                                )
                                drawRoundRect(
                                    color = colors.onSurfaceVariant.copy(alpha = 0.24f),
                                    topLeft = Offset(padX + 38.dp.toPx(), 21.dp.toPx()),
                                    size = Size(barW - 38.dp.toPx(), 6.dp.toPx()),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                )
                                drawRoundRect(
                                    color = colors.onSurfaceVariant.copy(alpha = 0.16f),
                                    topLeft = Offset(padX + 38.dp.toPx(), 31.dp.toPx()),
                                    size = Size((barW - 38.dp.toPx()) * 0.62f, 5.dp.toPx()),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                )

                                // Skeleton statement rows that illuminate as the beam passes
                                val rowYs = listOf(60.dp.toPx(), 84.dp.toPx(), 108.dp.toPx(), 132.dp.toPx(), 156.dp.toPx())
                                val widths = listOf(0.82f, 0.65f, 0.90f, 0.58f, 0.76f)
                                rowYs.forEachIndexed { idx, y ->
                                    val dist = kotlin.math.abs(beamY - y)
                                    val glow = (1f - (dist / 36.dp.toPx())).coerceIn(0f, 1f)
                                    val lineColor = if (glow > 0.1f) {
                                        colors.primary.copy(alpha = 0.22f + 0.65f * glow)
                                    } else {
                                        colors.onSurfaceVariant.copy(alpha = 0.16f)
                                    }
                                    val dotColor = if (glow > 0.25f) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.25f)

                                    drawCircle(
                                        color = dotColor,
                                        radius = (3.5f + glow * 1.2f).dp.toPx(),
                                        center = Offset(padX + 4.dp.toPx(), y + 4.dp.toPx()),
                                    )
                                    drawRoundRect(
                                        color = lineColor,
                                        topLeft = Offset(padX + 16.dp.toPx(), y),
                                        size = Size((barW - 42.dp.toPx()) * widths[idx], 8.dp.toPx()),
                                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                    )
                                    drawRoundRect(
                                        color = lineColor.copy(alpha = (lineColor.alpha * 0.9f).coerceAtMost(1f)),
                                        topLeft = Offset(size.width - padX - 20.dp.toPx(), y),
                                        size = Size(20.dp.toPx(), 8.dp.toPx()),
                                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                    )
                                }

                                // Sweeping glowing scan beam
                                val glowHalf = 22.dp.toPx()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            colors.primary.copy(alpha = 0.28f),
                                            colors.primary.copy(alpha = 0.42f),
                                            colors.primary.copy(alpha = 0.28f),
                                            Color.Transparent,
                                        ),
                                        startY = beamY - glowHalf,
                                        endY = beamY + glowHalf,
                                    ),
                                    topLeft = Offset(0f, beamY - glowHalf),
                                    size = Size(size.width, glowHalf * 2f),
                                )
                                drawLine(
                                    brush = Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            colors.primary,
                                            colors.primary,
                                            Color.Transparent,
                                        ),
                                    ),
                                    start = Offset(6.dp.toPx(), beamY),
                                    end = Offset(size.width - 6.dp.toPx(), beamY),
                                    strokeWidth = 2.2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                }

                // Animated Rotating Title + Subtitle
                AnimatedContent(
                    targetState = currentMessage,
                    transitionSpec = {
                        (slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(300)))
                            .togetherWith(slideOutVertically(tween(260)) { -it / 3 } + fadeOut(tween(240)))
                    },
                    label = "parsingMessage",
                ) { msg ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = if (completed) Icons.Filled.CheckCircle else Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = if (completed) status.good else colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = msg.title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = colors.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = msg.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Shimmering Theme Progress Track + 4 Step Milestones
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(trackBg),
                    ) {
                        if (completed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(status.good),
                            )
                        } else {
                            Canvas(Modifier.fillMaxSize()) {
                                val barLen = size.width * 0.42f
                                val startX = size.width * shimmerSlide - barLen * 0.5f
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            colors.primary,
                                            colors.tertiary,
                                            Color.Transparent,
                                        ),
                                        startX = startX,
                                        endX = startX + barLen,
                                    ),
                                    topLeft = Offset(startX, 0f),
                                    size = Size(barLen, size.height),
                                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MilestoneLabels.forEachIndexed { idx, label ->
                            val done = completed || currentMessage.stepIndex > idx
                            val activeStep = !completed && currentMessage.stepIndex == idx
                            val pillBg by animateColorAsState(
                                targetValue = when {
                                    done -> status.goodContainer
                                    activeStep -> colors.primaryContainer
                                    else -> trackBg.copy(alpha = 0.65f)
                                },
                                label = "stepBg",
                            )
                            val pillFg by animateColorAsState(
                                targetValue = when {
                                    done -> status.onGoodContainer
                                    activeStep -> colors.onPrimaryContainer
                                    else -> colors.onSurfaceVariant
                                },
                                label = "stepFg",
                            )
                            Surface(
                                shape = CircleShape,
                                color = pillBg,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (done) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = pillFg,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = pillFg,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Privacy Reassurance Pill
            Surface(
                shape = CircleShape,
                color = if (isDark) colors.surfaceContainer.copy(alpha = 0.85f) else colors.surfaceContainerLowest.copy(alpha = 0.90f),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.40f), CircleShape),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = status.good,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "Processed privately on your phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StatementParsingOverlayPreview() {
    CardsTheme {
        CompositionLocalProvider(LocalStatementLoading provides null) {
            StatementParsingOverlay(
                visible = true,
                completed = false,
                fileName = "HDFC_Infinia_Nov2026.pdf",
            )
        }
    }
}
