package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

val LocalLocked = compositionLocalOf { false }

@Composable
fun LockScreen(onUnlock: () -> Unit, onBack: () -> Unit, triggerUnlock: Boolean = true) {
    if (LocalInspectionMode.current) {
        LockCover(triggerUnlock = false, onUnlock = onUnlock)
        return
    }
    LaunchedEffect(triggerUnlock) { if (triggerUnlock) onUnlock() }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) { LockCover(triggerUnlock, onUnlock) }
}

@Composable
private fun LockCover(triggerUnlock: Boolean, onUnlock: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (triggerUnlock && !asked) {
            asked = true
            onUnlock()
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Minimal Brand Eyebrow
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        "PRIVATE VAULT",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                    )
                }
            }

            // Minimal Centerpiece Biometric Prompt
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Box(
                    Modifier
                        .size(144.dp)
                        .clip(CircleShape)
                        .background(primary.copy(alpha = 0.10f))
                        .border(1.dp, primary.copy(alpha = 0.25f), CircleShape)
                        .clickable(onClick = onUnlock),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = primary.copy(alpha = 0.28f),
                                spotColor = primary.copy(alpha = 0.30f),
                            )
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(primary, primaryContainer))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Fingerprint,
                            contentDescription = "Unlock with fingerprint or screen lock",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }

                Column(
                    Modifier.widthIn(max = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Private Vault is locked",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Touch sensor to unlock",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Bottom Unlock Pill Button
            VaultPrimaryButton(
                text = "Unlock Vault",
                icon = Icons.Outlined.LockOpen,
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LockScreenPreview() {
    CardsTheme {
        LockScreen(
            onUnlock = {},
            onBack = {},
            triggerUnlock = false,
        )
    }
}
