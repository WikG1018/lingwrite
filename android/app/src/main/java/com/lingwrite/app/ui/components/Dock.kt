// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.haze

enum class DockTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Shelf("书架", LingIcons.Shelf, LingIcons.ShelfFilled),
    Ideas("灵感", LingIcons.Idea, LingIcons.IdeaFilled),
    Stats("统计", LingIcons.Stats, LingIcons.Stats),
    Settings("设置", LingIcons.Settings, LingIcons.SettingsFilled),
}

private val DockShape = RoundedCornerShape(32.dp)

/** 屏幕边缘渐隐。顶部和底部用同一组透明度，只是方向相反。高度各自按边缘控件来，顶部不跟底部的 96dp。 */
internal fun edgeFade(color: Color, atTop: Boolean): Brush {
    val solid = color
    val mid = color.copy(alpha = 0.92f)
    val clear = color.copy(alpha = 0f)
    return if (atTop) Brush.verticalGradient(0f to solid, 0.25f to mid, 1f to clear)
    else Brush.verticalGradient(0f to clear, 0.75f to mid, 1f to solid)
}

/**
 * 页面容器：内容作为毛玻璃的模糊源，dock 悬浮其上。
 * [tab] 为 null 时 dock 下滑淡出（编辑器、首次使用页）。
 * 模糊由 Haze 实现（Android 12+ RenderEffect）；设备不支持或关闭模糊时自动降级为 [com.lingwrite.app.ui.theme.LingColors.glass] 的半透明纯色。
 */
@Composable
fun DockHost(
    tab: DockTab?,
    onSelect: (DockTab) -> Unit,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haze = remember { HazeState() }
    var last by remember { mutableStateOf(tab ?: DockTab.Shelf) }
    if (tab != null) last = tab
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().haze(haze)) { content() }
        // 和底部同一层、同一条渐隐，但只盖住状态栏再多 16dp。不挡点击。
        val topFade = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topFade)
                .background(edgeFade(Ling.colors.background, atTop = true))
        )
        AnimatedVisibility(
            visible = tab != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (Motion.reduced()) fadeIn(tween(Motion.Medium)) else fadeIn(tween(Motion.Medium)) + slideInVertically(tween(Motion.Long, easing = Motion.EmphasizedDecelerate)) { it / 2 },
            exit = if (Motion.reduced()) fadeOut(tween(Motion.Short)) else fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Medium, easing = Motion.EmphasizedAccelerate)) { it / 2 },
        ) {
            Box(Modifier.fillMaxWidth()) {
                // 底部渐隐：让滚到 dock 下方的内容柔和消失
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                        .background(edgeFade(Ling.colors.background, atTop = false))
                )
                GlassDock(last, onSelect, haze, blurEnabled, Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 8.dp, top = 24.dp))
            }
        }
    }
}

@Composable
fun GlassDock(selected: DockTab, onSelect: (DockTab) -> Unit, haze: HazeState, blurEnabled: Boolean, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val style = HazeStyle(
        backgroundColor = c.background,
        tints = listOf(HazeTint(c.glass)),
        blurRadius = 22.dp,
        noiseFactor = 0.04f,
        fallbackTint = HazeTint(c.glass.copy(alpha = if (c.isDark) 0.92f else 0.90f)),
    )
    val glass = if (blurEnabled) Modifier.hazeChild(haze, style) else Modifier.background(c.glass.copy(alpha = 0.92f))
    BoxWithConstraints(
        modifier.fillMaxWidth().height(64.dp)
            .shadow(18.dp, DockShape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
            .clip(DockShape)
            .then(glass)
            .border(1.dp, c.glassBorder, DockShape)
            .drawWithContent {
                drawContent()
                // 顶部内高光（玻璃边缘反光）
                drawRoundRect(
                    Brush.verticalGradient(0f to c.glassHighlight, 0.08f to Color.Transparent),
                    cornerRadius = CornerRadius(32.dp.toPx()), style = Stroke(1.dp.toPx()),
                    topLeft = Offset(0f, 1.dp.toPx() / 2),
                )
            }
            .padding(horizontal = 6.dp),
    ) {
        val itemW = (maxWidth) / DockTab.entries.size
        val indicatorX by animateDpAsState(itemW * selected.ordinal + (itemW - 52.dp) / 2, Motion.bouncy(), label = "dockIndicator")
        // 滑动的选中胶囊
        Box(
            Modifier.offset(x = indicatorX, y = 9.dp).size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp))
                .background(c.primary.copy(alpha = 0.16f))
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround) {
            DockTab.entries.forEach { t -> DockItem(t, t == selected, Modifier.weight(1f)) { onSelect(t) } }
        }
    }
}

@Composable
private fun DockItem(tab: DockTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (selected) c.primary else c.onSurfaceVariant, tween(Motion.Medium), label = "dockTint")
    Column(
        modifier.fillMaxHeight().pressScale(i, 0.88f)
            .clickable(i, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(52.dp, 30.dp), contentAlignment = Alignment.Center) {
            Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, Modifier.size(23.dp), tint = tint)
        }
        Spacer(Modifier.height(2.dp))
        Text(tab.label, style = Ling.type.dock, color = tint)
    }
}
