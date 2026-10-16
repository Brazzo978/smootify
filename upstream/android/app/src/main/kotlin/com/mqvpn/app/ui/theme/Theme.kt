// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF11314A)
private val Teal = Color(0xFF087F78)
private val LightBackground = Color(0xFFF5F8F8)
private val LightSurface = Color(0xFFFFFFFF)
private val DarkBackground = Color(0xFF0D1922)
private val DarkSurface = Color(0xFF152630)

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EFEB),
    onPrimaryContainer = Navy,
    secondary = Navy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9F0),
    onSecondaryContainer = Navy,
    tertiary = Color(0xFFAD5B20),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4CD),
    onTertiaryContainer = Color(0xFF4B260C),
    background = LightBackground,
    onBackground = Navy,
    surface = LightSurface,
    surfaceContainer = Color(0xFFEFF5F4),
    surfaceContainerHigh = Color(0xFFE8F0EF),
    surfaceContainerHighest = Color(0xFFDFEBE9),
    onSurface = Navy,
    surfaceVariant = Color(0xFFE5EEEE),
    onSurfaceVariant = Color(0xFF52656D),
    outline = Color(0xFF7D9298),
    error = Color(0xFFBA3237),
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF75D8CC),
    onPrimary = Color(0xFF003B38),
    primaryContainer = Color(0xFF145E5B),
    onPrimaryContainer = Color(0xFFD4F4EF),
    secondary = Color(0xFFA8C7DC),
    onSecondary = Color(0xFF15344A),
    secondaryContainer = Color(0xFF304C5E),
    onSecondaryContainer = Color(0xFFDAEBF5),
    tertiary = Color(0xFFFFBA7F),
    onTertiary = Color(0xFF4B260C),
    tertiaryContainer = Color(0xFF6B3A16),
    onTertiaryContainer = Color(0xFFFFE4CD),
    background = DarkBackground,
    onBackground = Color(0xFFE2EBEF),
    surface = DarkSurface,
    surfaceContainer = Color(0xFF1A3039),
    surfaceContainerHigh = Color(0xFF233B43),
    surfaceContainerHighest = Color(0xFF2B454C),
    onSurface = Color(0xFFE2EBEF),
    surfaceVariant = Color(0xFF2B424B),
    onSurfaceVariant = Color(0xFFB7C8CE),
    outline = Color(0xFF8FA6AC),
)

@Composable
fun MqvpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
