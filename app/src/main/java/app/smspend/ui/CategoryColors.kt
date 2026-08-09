package app.smspend.ui

import androidx.compose.ui.graphics.Color
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.Ok

/** Complementary olive-family palette — stable per category key. */
object CategoryColors {
    private val palette = listOf(
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

    fun forKey(categoryKey: String): Color {
        if (SpendCategory.countsAsSaved(categoryKey)) return Ok
        val preset = SpendCategory.entries.indexOfFirst { it.name == categoryKey }
        if (preset >= 0) return palette[preset % palette.size]
        val hash = categoryKey.fold(0) { acc, c -> 31 * acc + c.code }
        return palette[kotlin.math.abs(hash) % palette.size]
    }
}
