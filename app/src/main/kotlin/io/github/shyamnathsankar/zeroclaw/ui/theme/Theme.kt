@file:OptIn(ExperimentalTextApi::class)

package io.github.shyamnathsankar.zeroclaw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.shyamnathsankar.zeroclaw.R

/**
 * Palette: sage paper, ink type, pastel accents (light) or ink-green paper with sage type (dark).
 * Shared with assets/zc-theme/theme.css. Reading a colour inside a composable subscribes to [dark],
 * so switching appearance recomposes the whole UI.
 */
object Zc {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val Sage get() = pick(0xFFDDE1DA, 0xFF121411)
    val Paper get() = pick(0xFFE7EAE4, 0xFF1B1E19)
    val PaperDeep get() = pick(0xFFD2D7CF, 0xFF252922)
    val Ink get() = pick(0xFF0F100D, 0xFFE8EBE4)
    val InkSoft get() = pick(0xFF363932, 0xFFC3C8BD)
    val Muted get() = pick(0xFF5E625A, 0xFF8E9488)
    val Hairline get() = pick(0x260F100D, 0x26E8EBE4)
    val Forest get() = pick(0xFF3D7A4E, 0xFF6FBF85)
    val Error get() = pick(0xFFC8423B, 0xFFE06A62)

    // Pastels and the text drawn on them look the same in both modes.
    val Lilac = Color(0xFFD8B4F6)
    val Mint = Color(0xFFA8D9B8)
    val Butter = Color(0xFFEFE35D)
    val Apricot = Color(0xFFF6A067)
    val OnPastel = Color(0xFF0F100D)

    /** The terminal is always a dark console. */
    val Console = Color(0xFF0F100D)
    val ConsoleText = Color(0xFFDDE1DA)
}

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700))

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private val ZcTypography = Typography(
    displayLarge = TextStyle(fontFamily = InstrumentSerif, fontSize = 88.sp, lineHeight = 84.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = InstrumentSerif, fontSize = 60.sp, lineHeight = 60.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = InstrumentSerif, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = InstrumentSerif, fontSize = 32.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

private fun zcColors(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Zc.Ink,
        onPrimary = Zc.Sage,
        secondary = Zc.Forest,
        onSecondary = Zc.Sage,
        tertiary = Zc.Lilac,
        background = Zc.Sage,
        onBackground = Zc.Ink,
        surface = Zc.Sage,
        onSurface = Zc.Ink,
        surfaceVariant = Zc.Paper,
        onSurfaceVariant = Zc.Muted,
        surfaceContainer = Zc.Paper,
        surfaceContainerHigh = Zc.Paper,
        surfaceContainerHighest = Zc.PaperDeep,
        outline = Zc.Hairline,
        outlineVariant = Zc.Hairline,
        error = Zc.Error,
    )
}

@Composable
fun ZcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = zcColors(Zc.dark), typography = ZcTypography, content = content)
}
