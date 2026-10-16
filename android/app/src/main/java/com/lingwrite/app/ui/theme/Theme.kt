// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalLingColors = staticCompositionLocalOf { LightLingColors }
val LocalLingType = staticCompositionLocalOf { LingType() }
/** 减弱动画（MO3）：设置项开启，或系统动画时长缩放为 0。 */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Ling {
    val colors: LingColors @Composable @ReadOnlyComposable get() = LocalLingColors.current
    val type: LingType @Composable @ReadOnlyComposable get() = LocalLingType.current
}

/** 系统的动画时长缩放（Settings.Global.ANIMATOR_DURATION_SCALE），用 ContentObserver 实时跟踪。 */
@Composable
private fun rememberAnimatorScaleZero(): State<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        val resolver = ctx.contentResolver
        fun read() {
            val v = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            state.value = v == 0f
        }
        read()
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = read()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state
}

@Composable
fun LingTheme(dark: Boolean = isSystemInDarkTheme(), reduceMotionOverride: String? = null, content: @Composable () -> Unit) {
    val c = if (dark) DarkLingColors else LightLingColors
    val t = LingType()
    val systemZero by rememberAnimatorScaleZero()
    val reduced = when (reduceMotionOverride) {
        "on" -> true
        "off" -> false
        else -> systemZero
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        tertiary = c.tertiary, tertiaryContainer = c.tertiaryContainer, onTertiaryContainer = c.onTertiaryContainer,
        background = c.background, onBackground = c.onSurface, surface = c.surface, onSurface = c.onSurface,
        surfaceVariant = c.surfaceContainer, onSurfaceVariant = c.onSurfaceVariant,
        surfaceContainer = c.surfaceContainer, surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerLow = c.surface, surfaceContainerHighest = c.surfaceContainerHigh,
        outline = c.outline, outlineVariant = c.outlineVariant, error = c.error,
        errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer, scrim = c.scrim,
    )
    val typo = Typography(
        headlineSmall = t.headline, titleLarge = t.title, titleMedium = t.subhead, titleSmall = t.label,
        bodyLarge = t.bodyLarge, bodyMedium = t.body, bodySmall = t.caption,
        labelLarge = t.label, labelMedium = t.caption, labelSmall = t.micro,
    )
    CompositionLocalProvider(
        LocalLingColors provides c, LocalLingType provides t, LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typo) { com.lingwrite.app.ui.components.LingOverlayRoot(content) }
    }
}
