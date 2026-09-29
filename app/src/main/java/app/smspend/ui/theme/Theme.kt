package app.smspend.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** User's appearance choice, stored in preferences. */
enum class ThemeMode(val storageKey: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

/**
 * SMSpend palette. The same role names are used in both modes so screens read
 * `Ink`, `Paper`, etc. and get the right colour for the active theme.
 */
@Immutable
data class SmSpendColors(
    /** Primary text and filled action buttons. */
    val ink: Color,
    val inkSoft: Color,
    /** Content on top of [ink] (button labels, selected segment text). */
    val mint: Color,
    /** Soft sage used for tinted backgrounds — always readable over [paper]. */
    val mintWash: Color,
    /** Active controls, links, positive deltas. */
    val mintDeep: Color,
    val paper: Color,
    val paperRaised: Color,
    val line: Color,
    val mute: Color,
    val debit: Color,
    val ok: Color,
    val warn: Color,
    val isDark: Boolean,
)

val LightColors = SmSpendColors(
    ink = Color(0xFF3D4A2E),
    inkSoft = Color(0xFF556B3D),
    mint = Color(0xFFC5D5A8),
    mintWash = Color(0xFFC5D5A8),
    mintDeep = Color(0xFF7A8F4E),
    paper = Color(0xFFF4F1E8),
    paperRaised = Color(0xFFFFFCF6),
    line = Color(0xFFDCD4C4),
    mute = Color(0xFF6F7268),
    debit = Color(0xFFB54A3A),
    ok = Color(0xFF4F6B3A),
    warn = Color(0xFFC07A1F),
    isDark = false,
)

/**
 * Dark mode keeps the warm olive character: near-black paper with a hint of
 * green, soft off-white ink, and muted (not neon) accents so amounts and
 * labels stay readable without glare.
 */
val DarkColors = SmSpendColors(
    ink = Color(0xFFE6E9DC),
    inkSoft = Color(0xFFC8CDB8),
    mint = Color(0xFF2E3A24),
    mintWash = Color(0xFF9DB27A),
    mintDeep = Color(0xFFA3B87E),
    paper = Color(0xFF161A14),
    paperRaised = Color(0xFF21271E),
    line = Color(0xFF373E31),
    mute = Color(0xFFA3A79A),
    debit = Color(0xFFE3806F),
    ok = Color(0xFF92B67C),
    warn = Color(0xFFE2A552),
    isDark = true,
)

val LocalSmSpendColors = staticCompositionLocalOf { LightColors }

/** Deep olive (light) / warm off-white (dark) — primary text and actions. */
val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.ink
val InkSoft: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.inkSoft
/** Content colour on Ink surfaces. */
val Mint: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.mint
/** Sage tint for washed backgrounds and selection chips. */
val MintWash: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.mintWash
val MintDeep: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.mintDeep
val Paper: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.paper
val PaperRaised: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.paperRaised
val Line: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.line
val Mute: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.mute
val Debit: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.debit
val Ok: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.ok
/** Burnt orange — missed-bill / attention warnings. */
val Warn: Color
    @Composable @ReadOnlyComposable get() = LocalSmSpendColors.current.warn

private fun SmSpendColors.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = ink,
        onPrimary = mint,
        secondary = mintDeep,
        onSecondary = paper,
        background = paper,
        onBackground = ink,
        surface = paperRaised,
        onSurface = ink,
        surfaceVariant = paper,
        onSurfaceVariant = mute,
        surfaceContainerLowest = paper,
        surfaceContainerLow = paperRaised,
        surfaceContainer = paperRaised,
        surfaceContainerHigh = paperRaised,
        surfaceContainerHighest = paperRaised,
        outline = line,
        outlineVariant = line,
        error = debit,
    )
} else {
    lightColorScheme(
        primary = ink,
        onPrimary = mint,
        secondary = mintDeep,
        onSecondary = Color.White,
        background = paper,
        onBackground = ink,
        surface = paperRaised,
        onSurface = ink,
        surfaceVariant = paper,
        onSurfaceVariant = mute,
        surfaceContainerLowest = paperRaised,
        surfaceContainerLow = paperRaised,
        surfaceContainer = paperRaised,
        surfaceContainerHigh = paperRaised,
        surfaceContainerHighest = paper,
        outline = line,
        outlineVariant = line,
        error = debit,
    )
}

private val Typography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun SmSpendTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalSmSpendColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = Typography,
            content = content,
        )
    }
}
