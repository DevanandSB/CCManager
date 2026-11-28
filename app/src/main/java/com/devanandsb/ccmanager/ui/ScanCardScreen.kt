package com.devanandsb.ccmanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devanandsb.ccmanager.capture.CardText
import com.devanandsb.ccmanager.capture.ScannedCard
import com.devanandsb.ccmanager.domain.BinLookup
import com.devanandsb.ccmanager.domain.Issuers
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ScanCardScreen(onResult: (ScannedCard) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = LocalIsDark.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<ScannedCard?>(null) }
    var resolvedIssuer by remember { mutableStateOf<String?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        val activity = context as? android.app.Activity
        blocked = !ok && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!granted && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted = true
    }

    // Show the luxury tick emblem inside the reticle, resolve BIN issuer, then redirect to Add Card
    LaunchedEffect(captured) {
        val s = captured ?: return@LaunchedEffect
        val digits = s.number.orEmpty().filter(Char::isDigit)
        val binIssuer = if (digits.length >= 6) {
            withContext(Dispatchers.IO) { BinLookup.find(context, digits) }?.issuer
                ?.takeIf { name -> Issuers.all.any { it.name == name } }
        } else null
        resolvedIssuer = s.issuer ?: binIssuer
        delay(1800.milliseconds)
        onResult(
            ScannedCard(
                number = s.number,
                expiry = s.expiry,
                issuer = s.issuer ?: binIssuer,
            ),
        )
    }

    val stageBg = if (granted) Color(0xFF06070B) else MaterialTheme.colorScheme.background

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(stageBg)) {
            if (granted) {
                CameraReader(
                    captured = captured,
                    resolvedIssuer = resolvedIssuer,
                    onCaptured = { s ->
                        if (captured == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            captured = s
                        }
                    },
                )
            } else if (asked) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Camera access is off",
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (blocked) "Turn on Camera in this app's permissions to scan a card, or type the details instead."
                            else "Allow the camera to scan a card, or type the details instead.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = {
                            if (blocked) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                runCatching { context.startActivity(intent) }
                            } else {
                                permission.launch(Manifest.permission.CAMERA)
                            }
                        }) { Text(if (blocked) "Open settings" else "Allow camera") }
                    }
                }
            }

            // Close pill always accessible at top-left
            val closeBg = if (granted) Color.Black.copy(alpha = 0.58f) else MaterialTheme.colorScheme.surfaceContainerHigh
            val closeBorder = when {
                granted -> Color.White.copy(alpha = 0.25f)
                isDark -> Color.White.copy(alpha = 0.09f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            val closeTint = if (granted) Color.White else MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(14.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(closeBg)
                    .border(0.75.dp, closeBorder, CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "Close", tint = closeTint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraReader(
    captured: ScannedCard?,
    resolvedIssuer: String?,
    onCaptured: (ScannedCard) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf("Fit the card inside the frame") }
    var locking by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val main = ContextCompat.getMainExecutor(context)
        var lastNumber: String? = null
        var expiry: String? = null
        var issuer: String? = null
        var done = false
        var providerRef: ProcessCameraProvider? = null
        var disposed = false

        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null || done) { proxy.close(); return@setAnalyzer }
            recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { text ->
                    if (done) return@addOnSuccessListener
                    val seen = CardText.parse(text.text)
                    if (seen.expiry != null) expiry = seen.expiry
                    if (seen.issuer != null) issuer = seen.issuer
                    val n = seen.number
                    when {
                        n != null && n == lastNumber -> {
                            done = true
                            locking = true
                            hint = "Opening Add card…"
                            onCaptured(ScannedCard(n, expiry, issuer))
                        }
                        n != null -> {
                            lastNumber = n
                            locking = true
                            hint = "Locking card digits…"
                        }
                        else -> {
                            locking = false
                            hint = "Fit the card inside the frame"
                        }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val provider = runCatching { future.get() }.getOrElse { hint = "Couldn't open the camera"; return@addListener }
            providerRef = provider
            val p = CameraPreview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            runCatching {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
                boundCamera = cam
                hasFlash = cam.cameraInfo.hasFlashUnit()
            }.onFailure { hint = "Couldn't open the camera" }
        }, main)

        onDispose {
            disposed = true
            done = true
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            runCatching { providerRef?.unbindAll() }
            analysis.clearAnalyzer()
            recognizer.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ preview }, Modifier.fillMaxSize())

        ScanOverlayContent(
            captured = captured,
            resolvedIssuer = resolvedIssuer,
            locking = locking,
            hint = hint,
            hasFlash = hasFlash,
            torchOn = torchOn,
            onToggleTorch = {
                val next = !torchOn
                torchOn = next
                runCatching { boundCamera?.cameraControl?.enableTorch(next) }
            },
        )
    }
}

@Composable
private fun ScanOverlayContent(
    captured: ScannedCard?,
    resolvedIssuer: String?,
    locking: Boolean,
    hint: String,
    hasFlash: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
) {
    val sweepTransition = rememberInfiniteTransition(label = "scanSweep")
    val sweep by sweepTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
        label = "laserY",
    )
    val isCaptured = captured != null
    val isLocked = locking || isCaptured
    val frameColor = if (isLocked) Good else MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        // Top camera bar: centered ON-DEVICE SCANNER label + right flash button (close button sits at top-left in parent)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .height(42.dp),
        ) {
            Text(
                "ON-DEVICE SCANNER",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center),
            )
            if (hasFlash) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (torchOn) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.58f))
                        .border(0.75.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .clickable(onClick = onToggleTorch),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        if (torchOn) "Flash on" else "Flash off",
                        tint = if (torchOn) MaterialTheme.colorScheme.onPrimary else Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CARD_RATIO)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (isCaptured) Color(0xFF0C1210).copy(alpha = 0.86f) else Color.Transparent)
                    .border(1.5.dp, frameColor.copy(alpha = if (isLocked) 0.80f else 0.40f), RoundedCornerShape(28.dp))
                    .drawBehind {
                        drawLockBrackets(
                            color = frameColor,
                            cornerRadius = 28.dp.toPx(),
                            armLength = 24.dp.toPx(),
                            strokeWidth = 3.5.dp.toPx(),
                            inset = 2.dp.toPx(),
                        )
                        if (!isCaptured) {
                            val y = sweep * size.height
                            drawLine(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, frameColor.copy(alpha = 0.92f), Color.Transparent),
                                ),
                                start = Offset(16.dp.toPx(), y),
                                end = Offset(size.width - 16.dp.toPx(), y),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isCaptured,
                    enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.78f, animationSpec = tween(260)),
                ) {
                    val last4 = captured?.number.orEmpty().filter(Char::isDigit).takeLast(4)
                    val summaryLabel = listOfNotNull(
                        (resolvedIssuer ?: captured?.issuer)?.takeIf { it.isNotBlank() },
                        last4.takeIf { it.length == 4 }?.let { "•••• $it" },
                    ).joinToString(" · ")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(92.dp)
                                    .clip(CircleShape)
                                    .background(Good.copy(alpha = 0.14f))
                                    .border(1.dp, Good.copy(alpha = 0.30f), CircleShape),
                            )
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Good)
                                    .border(3.dp, Good.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Card scanned",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "CARD SCANNED",
                                color = Good,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            if (summaryLabel.isNotBlank()) {
                                Text(
                                    summaryLabel,
                                    color = Color.White,
                                    style = MonoSmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = if (isLocked) Good.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.68f),
                modifier = Modifier.border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape),
            ) {
                Text(
                    if (isCaptured) "Card captured · Opening Add card…" else hint,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            Text(
                "Card details stay offline on your phone and are never uploaded.",
                color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun DrawScope.drawLockBrackets(
    color: Color,
    cornerRadius: Float,
    armLength: Float,
    strokeWidth: Float,
    inset: Float = 0f,
) {
    val w = size.width
    val h = size.height
    val r = cornerRadius.coerceAtMost((minOf(w, h) - inset * 2f) / 3f)
    val arm = armLength.coerceAtMost((minOf(w, h) - inset * 2f - r * 2f) / 2.2f)
    val style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val tl = Path().apply {
        moveTo(inset, inset + r + arm)
        lineTo(inset, inset + r)
        arcTo(Rect(inset, inset, inset + r * 2f, inset + r * 2f), 180f, 90f, false)
        lineTo(inset + r + arm, inset)
    }
    val tr = Path().apply {
        moveTo(w - inset - r - arm, inset)
        lineTo(w - inset - r, inset)
        arcTo(Rect(w - inset - r * 2f, inset, w - inset, inset + r * 2f), 270f, 90f, false)
        lineTo(w - inset, inset + r + arm)
    }
    val br = Path().apply {
        moveTo(w - inset, h - inset - r - arm)
        lineTo(w - inset, h - inset - r)
        arcTo(Rect(w - inset - r * 2f, h - inset - r * 2f, w - inset, h - inset), 0f, 90f, false)
        lineTo(w - inset - r - arm, h - inset)
    }
    val bl = Path().apply {
        moveTo(inset + r + arm, h - inset)
        lineTo(inset + r, h - inset)
        arcTo(Rect(inset, h - inset - r * 2f, inset + r * 2f, h - inset), 90f, 90f, false)
        lineTo(inset, h - inset - r - arm)
    }
    drawPath(tl, color, style = style)
    drawPath(tr, color, style = style)
    drawPath(br, color, style = style)
    drawPath(bl, color, style = style)
}

@Preview(showBackground = true)
@Composable
fun ScanCardCapturedPreview() {
    CardsTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF06070B))) {
            ScanOverlayContent(
                captured = ScannedCard("4111111111111111", "08/29", "HDFC Bank"),
                resolvedIssuer = "HDFC Bank",
                locking = true,
                hint = "Opening Add card…",
                hasFlash = true,
                torchOn = false,
                onToggleTorch = {},
            )
        }
    }
}
