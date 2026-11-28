package com.devanandsb.ccmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devanandsb.ccmanager.data.Prefs

/** Light/dark mode and colour palette. Changes apply immediately. */
@Composable
fun AppearanceSection(prefs: Prefs) {
    val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
    SegmentedPillBar(
        items = modes.map { it.first },
        selected = Appearance.mode,
        label = { id -> modes.first { it.first == id }.second },
        onSelect = { id -> Appearance.mode = id; prefs.themeMode = id },
        modifier = Modifier.fillMaxWidth(),
    )

    val selectedPalette = Palettes.byId(Appearance.paletteId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Theme colour",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            selectedPalette.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = Palettes.all.size
        val fit = ((maxWidth + SwatchGap) / (MinSwatchCell + SwatchGap)).toInt().coerceAtLeast(3)
        val rows = (n + fit - 1) / fit
        val cols = (n + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(SwatchGap)) {
            Palettes.all.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SwatchGap)) {
                    row.forEach { p -> Swatch(p, prefs, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val SwatchGap = 8.dp
private val MinSwatchCell = 64.dp

@Composable
private fun Swatch(p: Palette, prefs: Prefs, modifier: Modifier) {
    val selected = Appearance.paletteId == p.id
    Column(
        modifier.clip(MaterialTheme.shapes.small)
            .selectable(selected, role = Role.RadioButton) { Appearance.paletteId = p.id; prefs.palette = p.id }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp)
                    else Modifier,
                )
                .clip(CircleShape)
                .background(p.swatch),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (p.swatch.luminance() > 0.5f) Color.Black else Color.White
            when {
                selected -> Icon(Icons.Filled.Check, null, tint = ink, modifier = Modifier.size(18.dp))
                p.dynamic -> Icon(Icons.Outlined.Wallpaper, null, tint = ink, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            p.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
