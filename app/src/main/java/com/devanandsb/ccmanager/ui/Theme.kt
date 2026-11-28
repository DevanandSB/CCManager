package com.devanandsb.ccmanager.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.devanandsb.ccmanager.R

// ---------- Type (Private Vault: Mulish + Instrument Sans + Source Code Pro) ----------

@OptIn(ExperimentalTextApi::class)
private fun varFont(resId: Int, weight: FontWeight, wght: Int) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(wght)),
)

val Mulish = FontFamily(
    varFont(R.font.mulish, FontWeight.Normal, 400),
    varFont(R.font.mulish, FontWeight.Medium, 500),
    varFont(R.font.mulish, FontWeight.SemiBold, 600),
    varFont(R.font.mulish, FontWeight.Bold, 700),
    varFont(R.font.mulish, FontWeight.ExtraBold, 800),
    varFont(R.font.mulish, FontWeight.Black, 900),
)

val InstrumentSans = FontFamily(
    varFont(R.font.instrument_sans, FontWeight.Normal, 400),
    varFont(R.font.instrument_sans, FontWeight.Medium, 500),
    varFont(R.font.instrument_sans, FontWeight.SemiBold, 600),
    varFont(R.font.instrument_sans, FontWeight.Bold, 700),
)

val SourceCodePro = FontFamily(
    varFont(R.font.source_code_pro, FontWeight.Normal, 400),
    varFont(R.font.source_code_pro, FontWeight.Medium, 500),
    varFont(R.font.source_code_pro, FontWeight.SemiBold, 600),
    varFont(R.font.source_code_pro, FontWeight.Bold, 700),
)

private val base = Typography()

private fun TextStyle.mulish(weight: FontWeight, size: Int, line: Int, tracking: Double) =
    copy(fontFamily = Mulish, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private fun TextStyle.instrument(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    copy(fontFamily = InstrumentSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val Type = Typography(
    displayLarge = base.displayLarge.mulish(FontWeight.ExtraBold, 40, 46, -0.75),
    displayMedium = base.displayMedium.mulish(FontWeight.ExtraBold, 32, 38, -0.5),
    displaySmall = base.displaySmall.mulish(FontWeight.Bold, 28, 34, -0.4),
    headlineLarge = base.headlineLarge.mulish(FontWeight.ExtraBold, 24, 30, -0.3),
    headlineMedium = base.headlineMedium.mulish(FontWeight.Bold, 20, 26, -0.2),
    headlineSmall = base.headlineSmall.mulish(FontWeight.Bold, 18, 24, -0.1),
    titleLarge = base.titleLarge.mulish(FontWeight.Bold, 18, 24, -0.1),
    titleMedium = base.titleMedium.instrument(FontWeight.SemiBold, 16, 22, 0.0),
    titleSmall = base.titleSmall.instrument(FontWeight.SemiBold, 14, 20, 0.0),
    bodyLarge = base.bodyLarge.instrument(FontWeight.Normal, 16, 24, 0.0),
    bodyMedium = base.bodyMedium.instrument(FontWeight.Normal, 14, 20, 0.0),
    bodySmall = base.bodySmall.instrument(FontWeight.Normal, 12, 16, 0.1),
    labelLarge = base.labelLarge.instrument(FontWeight.SemiBold, 14, 18, 0.1),
    labelMedium = base.labelMedium.instrument(FontWeight.SemiBold, 12, 16, 0.2),
    labelSmall = base.labelSmall.instrument(FontWeight.SemiBold, 10, 14, 1.4),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

val MonoMedium = TextStyle(
    fontFamily = SourceCodePro,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
)

val MonoSmall = TextStyle(
    fontFamily = SourceCodePro,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
    fontFeatureSettings = "tnum",
)

// ---------- Color ----------

@Immutable
data class StatusColors(
    val good: Color,
    val warn: Color,
    val bad: Color,
    val goodContainer: Color = Color(0xFFD1F7E3),
    val onGoodContainer: Color = Color(0xFF064E2B),
    val warnContainer: Color = Color(0xFFFFEDD5),
    val onWarnContainer: Color = Color(0xFF6B3B00),
    val badContainer: Color = Color(0xFF93000A),
    val onBadContainer: Color = Color(0xFFFFDAD6),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    bad = Color(0xFFFFB4AB),
    goodContainer = Color(0xFF0E3D28),
    onGoodContainer = Color(0xFFD1F7E3),
    warnContainer = Color(0xFF4A2C00),
    onWarnContainer = Color(0xFFFFEDD5),
    badContainer = Color(0xFF93000A),
    onBadContainer = Color(0xFFFFDAD6),
)

private val LightStatus = StatusColors(
    good = Color(0xFF0E7C47),
    warn = Color(0xFF9A5800),
    bad = Color(0xFFBA1A1A),
    goodContainer = Color(0xFFD1F7E3),
    onGoodContainer = Color(0xFF064E2B),
    warnContainer = Color(0xFFFFEDD5),
    onWarnContainer = Color(0xFF6B3B00),
    badContainer = Color(0xFFFFDAD6),
    onBadContainer = Color(0xFF93000A),
)

private val LocalStatus = staticCompositionLocalOf { DarkStatus }
val LocalIsDark = staticCompositionLocalOf { true }

val Good: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.good
val Amber: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.warn
val Danger: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.bad
val VaultStatus: StatusColors @Composable @ReadOnlyComposable get() = LocalStatus.current

internal data class Tones(
    val bg: Long,
    val surfaceLowest: Long,
    val surfaceLow: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val surfaceHighest: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val text: Long,
    val sub: Long,
    val line: Long,
    val lineSoft: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    val inversePrimary: Long,
    val good: Long,
    val warn: Long,
    val bad: Long,
)

class Palette internal constructor(
    val id: String,
    val name: String,
    val swatch: Color,
    private val dark: Tones?,
    private val light: Tones?,
) {
    val dynamic get() = dark == null

    internal fun scheme(isDark: Boolean): ColorScheme? {
        val t = (if (isDark) dark else light) ?: return null
        val c = { v: Long -> Color(v) }
        return if (isDark) darkColorScheme(
            primary = c(t.primary),
            onPrimary = c(t.onPrimary),
            primaryContainer = c(t.primaryContainer),
            onPrimaryContainer = c(t.onPrimaryContainer),
            secondary = c(t.secondary),
            onSecondary = c(t.onSecondary),
            secondaryContainer = c(t.secondaryContainer),
            onSecondaryContainer = c(t.onSecondaryContainer),
            tertiary = c(t.tertiary),
            onTertiary = c(t.onTertiary),
            tertiaryContainer = c(t.tertiaryContainer),
            onTertiaryContainer = c(t.onTertiaryContainer),
            background = c(t.bg),
            onBackground = c(t.text),
            surface = c(t.bg),
            onSurface = c(t.text),
            surfaceVariant = c(t.surfaceHigh),
            onSurfaceVariant = c(t.sub),
            surfaceDim = c(t.bg),
            surfaceBright = c(0xFF34363E),
            surfaceContainerLowest = c(t.surfaceLowest),
            surfaceContainerLow = c(t.surfaceLow),
            surfaceContainer = c(t.surface),
            surfaceContainerHigh = c(t.surfaceHigh),
            surfaceContainerHighest = c(t.surfaceHighest),
            outline = c(t.line),
            outlineVariant = c(t.lineSoft),
            error = c(t.error),
            onError = c(t.onError),
            errorContainer = c(t.errorContainer),
            onErrorContainer = c(t.onErrorContainer),
            inverseSurface = c(t.inverseSurface),
            inverseOnSurface = c(t.inverseOnSurface),
            inversePrimary = c(t.inversePrimary),
        ) else lightColorScheme(
            primary = c(t.primary),
            onPrimary = c(t.onPrimary),
            primaryContainer = c(t.primaryContainer),
            onPrimaryContainer = c(t.onPrimaryContainer),
            secondary = c(t.secondary),
            onSecondary = c(t.onSecondary),
            secondaryContainer = c(t.secondaryContainer),
            onSecondaryContainer = c(t.onSecondaryContainer),
            tertiary = c(t.tertiary),
            onTertiary = c(t.onTertiary),
            tertiaryContainer = c(t.tertiaryContainer),
            onTertiaryContainer = c(t.onTertiaryContainer),
            background = c(t.bg),
            onBackground = c(t.text),
            surface = c(t.bg),
            onSurface = c(t.text),
            surfaceVariant = c(t.surfaceHigh),
            onSurfaceVariant = c(t.sub),
            surfaceDim = c(0xFFDAD9DF),
            surfaceBright = c(0xFFFFFFFF),
            surfaceContainerLowest = c(t.surfaceLowest),
            surfaceContainerLow = c(t.surfaceLow),
            surfaceContainer = c(t.surface),
            surfaceContainerHigh = c(t.surfaceHigh),
            surfaceContainerHighest = c(t.surfaceHighest),
            outline = c(t.line),
            outlineVariant = c(t.lineSoft),
            error = c(t.error),
            onError = c(t.onError),
            errorContainer = c(t.errorContainer),
            onErrorContainer = c(t.onErrorContainer),
            inverseSurface = c(t.inverseSurface),
            inverseOnSurface = c(t.inverseOnSurface),
            inversePrimary = c(t.inversePrimary),
        )
    }

    internal fun status(isDark: Boolean): StatusColors {
        val baseStatus = if (isDark) DarkStatus else LightStatus
        val t = (if (isDark) dark else light) ?: return baseStatus
        return baseStatus.copy(good = Color(t.good), warn = Color(t.warn), bad = Color(t.bad))
    }
}

// Canonical Stitch Private Vault: Obsidian Graphite (#111216) + Champagne Gold (#EAC083) Dark
private val PrivateVaultDark = Tones(
    bg = 0xFF111216,
    surfaceLowest = 0xFF0C0D11,
    surfaceLow = 0xFF181A20,
    surface = 0xFF1E2027,
    surfaceHigh = 0xFF282B33,
    surfaceHighest = 0xFF32353F,
    primary = 0xFFEAC083,
    onPrimary = 0xFF291800,
    primaryContainer = 0xFF614413,
    onPrimaryContainer = 0xFFFFEEDB,
    secondary = 0xFFC3C6D6,
    onSecondary = 0xFF2C303D,
    secondaryContainer = 0xFF2C303D,
    onSecondaryContainer = 0xFFDFE2F3,
    tertiary = 0xFFD7C4AA,
    onTertiary = 0xFF3A2E1C,
    tertiaryContainer = 0xFF241A09,
    onTertiaryContainer = 0xFFF4DFC5,
    text = 0xFFE8E9EE,
    sub = 0xFFB8BAC4,
    line = 0xFF8E9096,
    lineSoft = 0xFF363842,
    error = 0xFFFFB4AB,
    onError = 0xFF690005,
    errorContainer = 0xFF93000A,
    onErrorContainer = 0xFFFFDAD6,
    inverseSurface = 0xFFE8E9EE,
    inverseOnSurface = 0xFF18191D,
    inversePrimary = 0xFF795926,
    good = 0xFF34D399,
    warn = 0xFFFBBF24,
    bad = 0xFFFFB4AB,
)

// Canonical Stitch Private Vault: Warm Porcelain (#F8F8FA) + Deep Obsidian Ink (#171B27) & Brushed Bronze (#795926) Light
private val PrivateVaultLight = Tones(
    bg = 0xFFF8F8FA,
    surfaceLowest = 0xFFFFFFFF,
    surfaceLow = 0xFFF2F2F5,
    surface = 0xFFECECEF,
    surfaceHigh = 0xFFE4E4E8,
    surfaceHighest = 0xFFDCDCE2,
    primary = 0xFF171B27,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFF2C303D,
    onPrimaryContainer = 0xFFDFE2F3,
    secondary = 0xFF795926,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFF4DFC5,
    onSecondaryContainer = 0xFF442B00,
    tertiary = 0xFF524531,
    onTertiary = 0xFFFFFFFF,
    tertiaryContainer = 0xFFE3E2E7,
    onTertiaryContainer = 0xFF1A1B1F,
    text = 0xFF18191D,
    sub = 0xFF46464C,
    line = 0xFF76777C,
    lineSoft = 0xFFC7C6CC,
    error = 0xFFBA1A1A,
    onError = 0xFFFFFFFF,
    errorContainer = 0xFFFFDAD6,
    onErrorContainer = 0xFF93000A,
    inverseSurface = 0xFF22242A,
    inverseOnSurface = 0xFFF2F2F5,
    inversePrimary = 0xFFEAC083,
    good = 0xFF0E7C47,
    warn = 0xFF9A5800,
    bad = 0xFFBA1A1A,
)

private fun obsidianDarkAccent(
    primary: Long,
    onPrimary: Long,
    container: Long,
    onContainer: Long,
) = PrivateVaultDark.copy(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = container,
    onPrimaryContainer = onContainer,
)

private fun porcelainLightAccent(
    primary: Long,
    container: Long,
    onContainer: Long,
) = PrivateVaultLight.copy(
    primary = primary,
    primaryContainer = container,
    onPrimaryContainer = onContainer,
)

object Palettes {
    val all: List<Palette> = buildList {
        // Default ("indigo" in Prefs): Signature Private Vault Obsidian & Champagne Gold
        add(Palette("indigo", "Private Vault", Color(0xFFEAC083), PrivateVaultDark, PrivateVaultLight))
        add(Palette("imperial", "Imperial", Color(0xFF7B89FF),
            obsidianDarkAccent(0xFF7B89FF, 0xFFFFFFFF, 0xFF212859, 0xFFE6EAFF),
            porcelainLightAccent(0xFF4338CA, 0xFFE5E8FF, 0xFF131963)))
        add(Palette("sapphire", "Royal Sapphire", Color(0xFF4C8DFF),
            obsidianDarkAccent(0xFF5A98FF, 0xFFFFFFFF, 0xFF162542, 0xFFD8E6FF),
            porcelainLightAccent(0xFF0A66FF, 0xFFE5EEFF, 0xFF002D7A)))
        add(Palette("emerald", "Racing Emerald", Color(0xFF34D399),
            obsidianDarkAccent(0xFF34D399, 0xFF032316, 0xFF113224, 0xFFD1FAE5),
            porcelainLightAccent(0xFF0E7C47, 0xFFD1F7E3, 0xFF064E2B)))
        add(Palette("ocean", "Azure Crystal", Color(0xFF38BDF8),
            obsidianDarkAccent(0xFF38BDF8, 0xFF041E2E, 0xFF123244, 0xFFD6F0FC),
            porcelainLightAccent(0xFF0284C7, 0xFFDDF3FE, 0xFF033754)))
        add(Palette("coral", "Rose Copper", Color(0xFFF08A5D),
            obsidianDarkAccent(0xFFF08A5D, 0xFF290E03, 0xFF3A2016, 0xFFFFE0D6),
            porcelainLightAccent(0xFFC95124, 0xFFFFE4DB, 0xFF4F1504)))
        add(Palette("rose", "Rose Diamond", Color(0xFFF48FB1),
            obsidianDarkAccent(0xFFF48FB1, 0xFF330616, 0xFF3D1927, 0xFFFFDCE8),
            porcelainLightAccent(0xFFB83B68, 0xFFFFE2EC, 0xFF520B24)))
        add(Palette("mono", "Brushed Platinum", Color(0xFFE5E7EB),
            obsidianDarkAccent(0xFFF3F4F6, 0xFF0A0B0E, 0xFF252832, 0xFFF3F4F6),
            porcelainLightAccent(0xFF171B27, 0xFFE4E4E8, 0xFF18191D)))
        if (Build.VERSION.SDK_INT >= 31) add(Palette("dynamic", "Wallpaper", Color(0xFF9AA5B1), null, null))
    }

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

object Appearance {
    var paletteId by mutableStateOf("indigo")
    var mode by mutableStateOf("system")
}

@Composable
fun CardsTheme(content: @Composable () -> Unit) {
    val dark = when (Appearance.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val palette = Palettes.byId(Appearance.paletteId)
    val ctx = LocalContext.current
    val scheme = palette.scheme(dark) ?: if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else Palettes.all.first().scheme(dark)!!
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalStatus provides palette.status(dark),
        LocalIsDark provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = Type, shapes = Shape, content = content)
    }
}
