// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.layout.heightIn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import com.lingwrite.app.data.BookCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Radius
import com.lingwrite.app.ui.theme.Space

/** 卡片：浅色下极轻阴影（对齐 theme.css --shadow），深色无阴影。[elevated] 用于 AI 便签/候选（0 2 14 / 10%）。 */
@Composable
fun LingCard(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.card,
    color: Color = Ling.colors.surface,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ling.colors
    val shadowed = when {
        c.isDark -> modifier
        elevated -> modifier.shadow(14.dp, shape, ambientColor = Color(0x1A142032), spotColor = Color(0x1A142032))
        else -> modifier.shadow(2.dp, shape, ambientColor = c.cardShadow, spotColor = Color(0x0D142032))
    }
    Column(shadowed.clip(shape).background(color), content = content)
}

/** 可点击的行，带 ripple + 背景高亮。 */
@Composable
fun Modifier.rowClick(onClick: () -> Unit): Modifier {
    val i = remember { MutableInteractionSource() }
    return this.clickable(i, ripple(color = Ling.colors.primary), onClick = onClick)
}

enum class PillKind { Primary, Neutral, Amber, Error, Good }

@Composable
fun Pill(text: String, kind: PillKind = PillKind.Primary, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val (bg, fg) = when (kind) {
        PillKind.Primary -> c.primaryContainer to c.onPrimaryContainer
        PillKind.Neutral -> c.surfaceContainer to c.onSurfaceVariant
        PillKind.Amber -> c.tertiaryContainer to c.onTertiaryContainer
        PillKind.Error -> c.errorContainer to c.onErrorContainer
        PillKind.Good -> c.goodContainer to c.good
    }
    Row(
        modifier.height(22.dp).clip(Radius.pill).background(bg).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = fg)
        Text(text, style = Ling.type.micro, color = fg, maxLines = 1)
    }
}

@Composable
fun LingIconButton(icon: ImageVector, contentDescription: String?, tint: Color = Ling.colors.onSurface, onClick: () -> Unit = {}) {
    val i = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).pressScale(i, 0.9f).clip(CircleShape)
            .clickable(i, ripple(bounded = true), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(24.dp), tint = tint) }
}

/** 顶部应用栏：64dp，返回 + 标题 + 操作。 */
@Composable
fun LingTopBar(
    title: String = "",
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    titleStyle: TextStyle = Ling.type.title,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) LingIconButton(com.lingwrite.app.ui.components.LingIcons.Back, tr(R.string.components_001), onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            if (title.isNotEmpty()) Text(title, style = titleStyle, color = Ling.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Ling.type.micro.copy(fontWeight = FontWeight.Normal), color = Ling.colors.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 8.dp), style = Ling.type.label, color = Ling.colors.primary)
}

@Composable
fun InsetDivider(start: Dp, end: Dp = 0.dp) {
    Box(Modifier.padding(start = start, end = end).fillMaxWidth().height(1.dp).background(Ling.colors.outlineVariant))
}

@Composable
fun Tile(icon: ImageVector, bg: Color, fg: Color, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(Radius.sm).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
    }
}

enum class BtnKind { Filled, Outlined, Text, Tonal }

@Composable
fun LingButton(
    text: String, kind: BtnKind = BtnKind.Filled, icon: ImageVector? = null, height: Dp = 44.dp,
    enabled: Boolean = true, modifier: Modifier = Modifier, textStyle: TextStyle = Ling.type.label.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
    onClick: () -> Unit = {},
) {
    val active = enabled && !LocalOverlayClosing.current
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    val bgTarget = when {
        !active -> c.onSurface.copy(alpha = 0.10f)
        kind == BtnKind.Filled -> c.primary
        kind == BtnKind.Tonal -> c.primaryContainer
        else -> Color.Transparent
    }
    val fgTarget = when {
        !active -> c.onSurface.copy(alpha = 0.40f)
        kind == BtnKind.Filled -> c.onPrimary
        kind == BtnKind.Tonal -> c.onPrimaryContainer
        else -> c.primary
    }
    val bg by animateColorAsState(bgTarget, tween(Motion.Medium), label = "btnBg")
    val fg by animateColorAsState(fgTarget, tween(Motion.Medium), label = "btnFg")
    var m = modifier.heightIn(min = height).pressScale(i, 0.96f).clip(Radius.pill).background(bg)
    if (kind == BtnKind.Outlined) m = m.border(1.dp, c.outline, Radius.pill)
    Row(
        m.clickable(i, ripple(color = fg), enabled = active, onClick = onClick)
            .padding(horizontal = if (kind == BtnKind.Text) 12.dp else 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(text, style = textStyle, color = fg, maxLines = 1)
    }
}

/** 扩展悬浮按钮：16dp 圆角、PrimaryContainer。 */
@Composable
fun LingFab(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    Row(
        modifier.height(56.dp).pressScale(i, 0.95f)
            .shadow(8.dp, Radius.lg, ambientColor = Color(0x2E142032), spotColor = if (c.isDark) Color(0x99000000) else Color(0x40142032))
            .clip(Radius.lg).background(c.primaryContainer)
            .clickable(i, ripple(color = c.onPrimaryContainer), onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.onPrimaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Ling.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = c.onPrimaryContainer)
    }
}

/** 书面图片写盘后加一，书架和作品页重新读文件。 */
object CoverReload {
    val rev = MutableStateFlow(0)
    fun bump() { rev.value++ }
}

/** 书封：有生成的书面就用图片，否则渐变 + 书脊高光线 + 左下书名。 */
@Composable
fun BookCover(label: String, colors: Pair<Color, Color>, width: Dp = 46.dp, height: Dp = 62.dp, modifier: Modifier = Modifier, bookId: Long = 0) {
    val ctx = LocalContext.current
    val rev by CoverReload.rev.collectAsState()
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bookId, rev) {
        value = if (bookId <= 0) null else withContext(Dispatchers.IO) {
            val f = BookCovers.file(ctx, bookId)
            if (!f.isFile) null else BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        }
    }
    if (photo != null) {
        Image(photo!!, label, modifier.size(width, height).clip(RoundedCornerShape(if (width > 50.dp) 9.dp else 8.dp)), contentScale = ContentScale.Crop)
        return
    }
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(if (width > 50.dp) 9.dp else 8.dp))
            .background(Brush.linearGradient(listOf(colors.first, colors.second), start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(30f, 400f)))
            .drawBehind {
                val x = 5.dp.toPx() + (if (width > 50.dp) 1.dp.toPx() else 0f)
                drawRect(Color.White.copy(alpha = 0.25f), topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            }
            .padding(if (width > 50.dp) 7.dp else 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(label, style = Ling.type.micro.copy(fontSize = if (width > 50.dp) 13.sp else 12.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

@Composable
fun ColumnScope.FillSpacer() = Spacer(Modifier.weight(1f))


/** 选择胶囊：替代 M3 FilterChip，对齐 .pill / .chip 规格（22–32dp、pill 圆角、选中 primaryContainer）。 */
@Composable
fun LingChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) c.primaryContainer else c.surfaceContainer, tween(Motion.Medium), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onPrimaryContainer else c.onSurfaceVariant, tween(Motion.Medium), label = "chipFg")
    val h = if (compact) 28.dp else 32.dp
    Row(
        modifier
            .height(h)
            .pressScale(i, 0.94f)
            .clip(Radius.pill)
            .background(bg)
            .clickable(i, ripple(color = c.primary), onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = Ling.type.label, color = fg, maxLines = 1)
    }
}

val ScreenPadding = PaddingValues(horizontal = Space.gutter)
