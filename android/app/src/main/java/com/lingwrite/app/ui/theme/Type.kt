// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lingwrite.app.R

/** MiSans 构建时从小米官方下载（不随仓库分发）；取不到时退回系统字体。 */
val MiSans = if (!com.lingwrite.app.BuildConfig.HAS_MISANS) FontFamily.Default else FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold),
    Font(R.font.misans_semibold, FontWeight.Bold),
)

private fun style(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = MiSans, fontSize = size, lineHeight = line, fontWeight = weight, letterSpacing = tracking,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** 字体层级 token（px 值取自高保真稿，1px = 1dp）。 */
@Immutable
data class LingType(
    val display: TextStyle = style(28.sp, 38.sp, FontWeight.SemiBold, (-0.4).sp),
    val headline: TextStyle = style(22.sp, 28.sp, FontWeight.SemiBold, (-0.3).sp),
    val title: TextStyle = style(20.sp, 26.sp, FontWeight.SemiBold, (-0.2).sp),
    val titleSmall: TextStyle = style(17.sp, 24.sp, FontWeight.SemiBold),
    val subhead: TextStyle = style(16.sp, 22.sp, FontWeight.SemiBold),
    val bodyLarge: TextStyle = style(15.sp, 24.sp),
    val body: TextStyle = style(14.sp, 21.sp),
    val label: TextStyle = style(13.5.sp, 19.sp, FontWeight.Medium),
    /** 卷标题（作品页、大纲页）：与 label 同号，但统一 SemiBold。 */
    val volumeHeader: TextStyle = style(13.5.sp, 19.sp, FontWeight.SemiBold),
    val caption: TextStyle = style(12.5.sp, 18.sp),
    val micro: TextStyle = style(11.5.sp, 15.sp, FontWeight.Medium),
    val dock: TextStyle = style(11.sp, 14.sp, FontWeight.Medium),
    val numeral: TextStyle = style(22.sp, 26.sp, FontWeight.SemiBold),
    /** 正文编辑器：17sp / 31 行高，0.2 字距。 */
    val manuscript: TextStyle = style(17.sp, 31.sp, tracking = 0.012.em),
)
