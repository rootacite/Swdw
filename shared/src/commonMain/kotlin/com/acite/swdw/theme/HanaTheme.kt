package com.acite.swdw.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HanaBg = Color(0xFF100E19)
val HanaPanel = Color(0xFF181427)
val HanaCard = Color(0xFF201A35)
val HanaCardHover = Color(0xFF282042)
val HanaStroke = Color(0xFF362F52)
val HanaStrokeGlow = Color(0xFF4E4275)

val HanaPink = Color(0xFFFF8DA1)
val HanaPinkLight = Color(0xFFFFAEC0)
val HanaPinkDark = Color(0xFFE25D78)

val HanaLilac = Color(0xFFB39BFF)
val HanaLilacLight = Color(0xFFD6C7FF)
val HanaLilacDark = Color(0xFF8564E6)

val HanaMint = Color(0xFF5CD7A3)
val HanaMintGlow = Color(0xFF63EBBB)
val HanaBlue = Color(0xFF3EA4F8)

val HanaText = Color(0xFFF6F4FF)
val HanaTextDim = Color(0xFF9E97B8)
val HanaTextMuted = Color(0xFF6B6484)

val HanaAmber = Color(0xFFFBBF24)
val HanaRed = Color(0xFFF87171)

private val HanaDarkColorScheme = darkColorScheme(
    primary = HanaPink,
    onPrimary = Color.White,
    primaryContainer = HanaCard,
    onPrimaryContainer = HanaPinkLight,
    secondary = HanaLilac,
    onSecondary = Color.White,
    secondaryContainer = HanaCardHover,
    onSecondaryContainer = HanaLilacLight,
    tertiary = HanaMint,
    onTertiary = Color.Black,
    background = HanaBg,
    onBackground = HanaText,
    surface = HanaPanel,
    onSurface = HanaText,
    surfaceVariant = HanaCard,
    onSurfaceVariant = HanaTextDim,
    outline = HanaStroke,
    outlineVariant = HanaStrokeGlow
)

@Composable
fun HanaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HanaDarkColorScheme,
        content = content
    )
}
