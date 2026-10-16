// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 黛蓝 · 暖灯 —— 与 ui-hifi/src/theme.css 一一对应的语义色 token。 */
@Immutable
data class LingColors(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val tertiary: Color, val amber: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val background: Color, val surface: Color, val surfaceContainer: Color, val surfaceContainerHigh: Color,
    val onSurface: Color, val onSurfaceVariant: Color, val outline: Color, val outlineVariant: Color,
    val error: Color, val errorContainer: Color, val onErrorContainer: Color,
    val good: Color, val goodContainer: Color,
    val paper: Color, val paperInk: Color, val scrim: Color,
    val heroStart: Color, val heroEnd: Color, val logoStart: Color, val logoEnd: Color,
    val glass: Color, val glassBorder: Color, val glassHighlight: Color, val glassShadow: Color,
    val cardShadow: Color,
    val isDark: Boolean,
)

val LightLingColors = LingColors(
    primary = Color(0xFF3E5C8A), onPrimary = Color.White, primaryContainer = Color(0xFFDDE6F4), onPrimaryContainer = Color(0xFF16294A),
    tertiary = Color(0xFFA8691A), amber = Color(0xFFD99A3C), tertiaryContainer = Color(0xFFF7E8CF), onTertiaryContainer = Color(0xFF4A3008),
    background = Color(0xFFF5F4F0), surface = Color.White, surfaceContainer = Color(0xFFEDECE7), surfaceContainerHigh = Color(0xFFE6E4DE),
    onSurface = Color(0xFF1B1F27), onSurfaceVariant = Color(0xFF5E6470), outline = Color(0xFFC9CBD0), outlineVariant = Color(0xFFE6E4DE),
    error = Color(0xFFC2413B), errorContainer = Color(0xFFFBE3E1), onErrorContainer = Color(0xFF7A1712),
    good = Color(0xFF2E7D5B), goodContainer = Color(0xFFDDF1E6),
    paper = Color(0xFFF3EEDF), paperInk = Color(0xFF25221C), scrim = Color(0x6B0F1218),
    heroStart = Color(0xFF3E5C8A), heroEnd = Color(0xFF2E4870), logoStart = Color(0xFF3E5C8A), logoEnd = Color(0xFF2A4166),
    glass = Color(0x9EFFFFFF), glassBorder = Color(0xBFFFFFFF), glassHighlight = Color(0xE6FFFFFF), glassShadow = Color(0x291E2D46),
    cardShadow = Color(0x14142032),
    isDark = false,
)

val DarkLingColors = LingColors(
    primary = Color(0xFF9DB4DE), onPrimary = Color(0xFF0E2340), primaryContainer = Color(0xFF273A5C), onPrimaryContainer = Color(0xFFD7E3FA),
    tertiary = Color(0xFFE5B266), amber = Color(0xFFD99A3C), tertiaryContainer = Color(0xFF3D2D12), onTertiaryContainer = Color(0xFFF5D9A8),
    background = Color(0xFF0F1218), surface = Color(0xFF181C24), surfaceContainer = Color(0xFF20252F), surfaceContainerHigh = Color(0xFF272D38),
    onSurface = Color(0xFFE4E7EE), onSurfaceVariant = Color(0xFF9AA1AE), outline = Color(0xFF3A404C), outlineVariant = Color(0xFF262B35),
    error = Color(0xFFF2827B), errorContainer = Color(0xFF3F1D1B), onErrorContainer = Color(0xFFFFD9D5),
    good = Color(0xFF7BD3A8), goodContainer = Color(0xFF173327),
    paper = Color(0xFF15181E), paperInk = Color(0xFFDCDFE6), scrim = Color(0x8C000000),
    heroStart = Color(0xFF2B4268), heroEnd = Color(0xFF1C2B45), logoStart = Color(0xFF34507C), logoEnd = Color(0xFF1E2F4C),
    glass = Color(0x94202530), glassBorder = Color(0x1AFFFFFF), glassHighlight = Color(0x14FFFFFF), glassShadow = Color(0x73000000),
    cardShadow = Color(0x00000000),
    isDark = true,
)

/** 封面渐变（与稿一致）。 */
object CoverPalette {
    val Indigo = Color(0xFF4F6E9C) to Color(0xFF2E4870)
    val Amber = Color(0xFFC98A3A) to Color(0xFF8E5A1C)
    val Jade = Color(0xFF5B7A6E) to Color(0xFF2F4A40)
    val Plum = Color(0xFF8A5A7A) to Color(0xFF55324B)
    val Slate = Color(0xFF5F6B7E) to Color(0xFF363F4E)
    val Rust = Color(0xFFB0624A) to Color(0xFF6E3524)
    val all = listOf(Indigo, Amber, Jade, Plum, Slate, Rust)
    fun of(i: Int) = all[((i % all.size) + all.size) % all.size]
}
