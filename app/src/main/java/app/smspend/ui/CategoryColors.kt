package app.smspend.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.DarkColors
import app.smspend.ui.theme.LightColors
import app.smspend.ui.theme.LocalSmSpendColors

/** Complementary olive-family palette — stable per category key. */
object CategoryColors {
    private val lightPalette = listOf(
        Color(0xFF3D4A2E), // deep olive
        Color(0xFFC4A35A), // ochre
        Color(0xFFB56A4E), // terracotta
        Color(0xFF7A8F4E), // sage leaf
        Color(0xFF5B7C8A), // dusty blue
        Color(0xFF8B6B5E), // plum-brown
        Color(0xFFB89B3E), // mustard
        Color(0xFF6A9E8F), // soft teal
        Color(0xFF556B3D), // mid olive
        Color(0xFF9C7A5C), // warm taupe
        Color(0xFF6E7F4A), // moss
    )

    /** Same hues lifted for dark paper so icons and bars stay legible. */
    private val darkPalette = listOf(
        Color(0xFFB7C59C), // deep olive
        Color(0xFFDBBF78), // ochre
        Color(0xFFD68D71), // terracotta
        Color(0xFFA6BB80), // sage leaf
        Color(0xFF8AADBD), // dusty blue
        Color(0xFFBB998C), // plum-brown
        Color(0xFFD4BA5F), // mustard
        Color(0xFF92C6B6), // soft teal
        Color(0xFFACBF8D), // mid olive
        Color(0xFFC5A586), // warm taupe
        Color(0xFFA8B97D), // moss
    )

    /** Theme-aware; call from composable scope. */
    @Composable
    @ReadOnlyComposable
    fun forKey(categoryKey: String): Color =
        forKey(categoryKey, LocalSmSpendColors.current.isDark)

    /** Pure variant for draw scopes and `remember` blocks. */
    fun forKey(categoryKey: String, dark: Boolean): Color {
        val colors = if (dark) DarkColors else LightColors
        if (SpendCategory.countsAsSaved(categoryKey)) return colors.ok
        val palette = if (dark) darkPalette else lightPalette
        val preset = SpendCategory.entries.indexOfFirst { it.name == categoryKey }
        if (preset >= 0) return palette[preset % palette.size]
        val hash = categoryKey.fold(0) { acc, c -> 31 * acc + c.code }
        return palette[kotlin.math.abs(hash) % palette.size]
    }
}
