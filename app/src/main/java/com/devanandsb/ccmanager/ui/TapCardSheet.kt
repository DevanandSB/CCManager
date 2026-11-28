package com.devanandsb.ccmanager.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.capture.ChipCard
import com.devanandsb.ccmanager.capture.EmvReader

@Composable
fun nfcAvailable(): Boolean {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    return remember {
        if (isInspection) false
        else {
            try {
                NfcAdapter.getDefaultAdapter(context) != null
            } catch (_: Throwable) {
                false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapCardSheet(onRead: (ChipCard) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val activity = context as? Activity
    val adapter = remember {
        if (isInspection) null
        else {
            try {
                NfcAdapter.getDefaultAdapter(context)
            } catch (_: Throwable) {
                null
            }
        }
    }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    var status by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    DisposableEffect(adapter, enabled) {
        if (adapter == null || !enabled || activity == null) return@DisposableEffect onDispose { }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        try {
            adapter.enableReaderMode(activity, { tag ->
                val iso = IsoDep.get(tag)
                if (iso == null) { activity.runOnUiThread { status = "That isn't a payment card." }; return@enableReaderMode }
                activity.runOnUiThread { reading = true; status = null }
                val card = runCatching { EmvReader.read(iso) }.getOrNull()
                activity.runOnUiThread {
                    reading = false
                    if (card != null) onRead(card)
                    else status = "Couldn't read the card. Keep it still against the phone and try again."
                }
            }, flags, null)
        } catch (_: Throwable) {
            // ignore
        }
        onDispose { runCatching { adapter.disableReaderMode(activity) } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                if (reading) CircularProgressIndicator(Modifier.size(44.dp))
                else Icon(Icons.Outlined.Contactless, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                when {
                    !enabled -> "NFC is off"
                    reading -> "Reading… keep holding"
                    else -> "Hold your card to the back of the phone"
                },
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Text(
                if (!enabled) "Turn on NFC to read the card's number and expiry."
                else "Fills in the number, expiry and network. Type the CVV yourself; it isn't stored on the chip.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            if (!enabled) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) { Text("Open NFC settings") }
                TextButton(onClick = { enabled = adapter?.isEnabled == true }) { Text("I've turned it on") }
            }
        }
    }
}
