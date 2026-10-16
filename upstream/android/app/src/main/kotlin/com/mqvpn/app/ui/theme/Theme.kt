// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF102B3F)
private val DeepNavy = Color(0xFF0A1925)
private val Teal = Color(0xFF007D76)
private val BrightTeal = Color(0xFF7BDCCF)

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F3EC),
    onPrimaryContainer = Navy,
    secondary = Navy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EDF3),
    onSecondaryContainer = Navy,
    tertiary = Color(0xFF985018),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8CF),
    onTertiaryContainer = Color(0xFF492507),
    background = Color(0xFFF4F8F8),
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
    surfaceContainer = Color(0xFFEBF3F2),
    surfaceContainerHigh = Color(0xFFE3EFED),
    surfaceContainerHighest = Color(0xFFDBEAE8),
    surfaceVariant = Color(0xFFE4EFEE),
    onSurfaceVariant = Color(0xFF425A62),
    outline = Color(0xFF6D858A),
    outlineVariant = Color(0xFFB9CECE),
    error = Color(0xFFAD3034),
    onError = Color.White,
)
private val DarkColorScheme = darkColorScheme(
    primary = BrightTeal,
    onPrimary = Color(0xFF003A36),
    primaryContainer = Color(0xFF145F5A),
    onPrimaryContainer = Color(0xFFD6F7EF),
    secondary = Color(0xFFB2D0E0),
    onSecondary = Color(0xFF143246),
    secondaryContainer = Color(0xFF29495B),
    onSecondaryContainer = Color(0xFFE1F0F8),
    tertiary = Color(0xFFFFC38C),
    onTertiary = Color(0xFF4F2808),
    tertiaryContainer = Color(0xFF6B3C1C),
    onTertiaryContainer = Color(0xFFFFE9D5),
    background = DeepNavy,
    onBackground = Color(0xFFE6EFF1),
    surface = Color(0xFF122632),
    onSurface = Color(0xFFE6EFF1),
    surfaceContainer = Color(0xFF1A313C),
    surfaceContainerHigh = Color(0xFF233D47),
    surfaceContainerHighest = Color(0xFF2C4851),
    surfaceVariant = Color(0xFF29434D),
    onSurfaceVariant = Color(0xFFBDCFD3),
    outline = Color(0xFF97AEB3),
    outlineVariant = Color(0xFF526C74),
    error = Color(0xFFFFB5B5),
    onError = Color(0xFF690009),
)

private val SmootifyTypography = Typography(
    headlineMedium = TextStyle(fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val SmootifyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MqvpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = SmootifyTypography,
        shapes = SmootifyShapes,
        content = content,
    )
}
