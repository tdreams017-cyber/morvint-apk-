package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MorventCyan,
    onPrimary = Color(0xFF001E26),
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = Color(0xFF9CF0FF),
    secondary = MorventPurple,
    onSecondary = Color(0xFF28004B),
    secondaryContainer = Color(0xFF56168F),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = MorventEmerald,
    onTertiary = Color(0xFF002919),
    tertiaryContainer = Color(0xFF054F34),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = MorventDarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = MorventDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = MorventDarkSurfaceElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = MorventDarkBorder,
    outlineVariant = Color(0xFF1E293B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E8FF),
    onSecondaryContainer = Color(0xFF6B21A8),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    background = MorventLightBg,
    onBackground = Color(0xFF0F172A),
    surface = MorventLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = MorventLightSurfaceElevated,
    onSurfaceVariant = Color(0xFF475569),
    outline = MorventLightBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek futuristic dark mode for Morvent assistant
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
