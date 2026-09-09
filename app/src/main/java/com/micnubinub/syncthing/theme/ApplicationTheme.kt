package com.micnubinub.syncthing.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF03A9F4),
    primaryContainer = Color(0xFF03A9F4),
    secondary = Color(0xFFFFC107),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0288D1),
    primaryContainer = Color(0xFF0288D1),
    secondary = Color(0xFFFFC107),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)
val typography = Typography(
    bodyLarge = TextStyle(fontSize = 20.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    bodySmall = TextStyle(fontSize = 16.sp),
)

@Composable
fun ApplicationTheme(
    content: @Composable () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = remember(isDarkTheme, context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDarkTheme)
                dynamicDarkColorScheme(context)
            else
                dynamicLightColorScheme(context)
        } else {
            if (isDarkTheme)
                DarkColorScheme
            else
                LightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
        typography = typography
    )
}
