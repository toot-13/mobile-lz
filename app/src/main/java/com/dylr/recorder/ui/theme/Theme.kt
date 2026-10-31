package com.dylr.recorder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Rose = Color(0xFFFF2D55)
private val RoseDeep = Color(0xFF8E0B27)
private val Mint = Color(0xFF00C2A8)
private val Amber = Color(0xFFFFB020)
private val Ink = Color(0xFF121014)
private val InkSoft = Color(0xFF232128)
private val Cloud = Color(0xFFFDF6F8)
private val CloudSoft = Color(0xFFF2E7EC)

private val DarkColors = darkColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    primaryContainer = RoseDeep,
    onPrimaryContainer = Color(0xFFFFE3E9),
    secondary = Mint,
    onSecondary = Color(0xFF00312B),
    secondaryContainer = Color(0xFF0F3B36),
    onSecondaryContainer = Color(0xFFB6FFF2),
    tertiary = Amber,
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFF4A3200),
    onTertiaryContainer = Color(0xFFFFE2B2),
    background = Ink,
    onBackground = Color(0xFFF4EDF1),
    surface = InkSoft,
    onSurface = Color(0xFFF4EDF1),
    surfaceVariant = Color(0xFF302D35),
    onSurfaceVariant = Color(0xFFCFC7D2),
    outline = Color(0xFF918A96),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1220),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColors = lightColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E0),
    onPrimaryContainer = RoseDeep,
    secondary = Mint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F2E9),
    onSecondaryContainer = Color(0xFF00352E),
    tertiary = Amber,
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFFFFE2B2),
    onTertiaryContainer = Color(0xFF3A2600),
    background = Cloud,
    onBackground = Ink,
    surface = CloudSoft,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFE2E8),
    onSurfaceVariant = Color(0xFF4A434B),
    outline = Color(0xFF7E767F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/** 全局文字样式表，供各屏幕复用。 */
object DouyinLiveRecTextStyles {
    val ScreenTitle: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val SectionTitle: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val Body: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val Caption: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val Mono: TextStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

private val DouyinLiveRecTypography = Typography(
    headlineSmall = DouyinLiveRecTextStyles.ScreenTitle,
    titleMedium = DouyinLiveRecTextStyles.SectionTitle,
    bodyMedium = DouyinLiveRecTextStyles.Body,
    bodySmall = DouyinLiveRecTextStyles.Caption,
    labelSmall = DouyinLiveRecTextStyles.Caption
)

@Composable
fun DouyinLiveRecTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val scheme = if (darkTheme) {
                runCatching { dynamicDarkColorScheme(context) }.getOrNull()
            } else {
                runCatching { dynamicLightColorScheme(context) }.getOrNull()
            }
            scheme ?: if (darkTheme) DarkColors else LightColors
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DouyinLiveRecTypography,
        content = content
    )
}
