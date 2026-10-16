// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.delay

/** 150ms 内数据到了就不显示骨架，避免闪一下（motion 第 15 节）。 */
@Composable
fun rememberDelayedVisible(visible: Boolean, delayMs: Long = 150): Boolean {
    val shown = remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) { delay(delayMs); shown.value = true } else shown.value = false
    }
    return shown.value
}

/** 骨架条：surfaceContainer 色、6dp 圆角、透明度 0.6↔1 往返（不做 shimmer）。减弱动画时为静态色块（透明度 0.8）。 */
@Composable
fun Skeleton(widthFraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp, alpha: Float? = null) {
    val reduced = Motion.reduced()
    val a = alpha ?: if (reduced) 0.8f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.Long, easing = Motion.Standard), RepeatMode.Reverse), label = "skeletonAlpha",
        ).value
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .graphicsLayer { this.alpha = a }
                .background(Ling.colors.surfaceContainer),
        )
    }
}

/** 多行骨架：默认宽度依次 90% / 74% / 62%，行距 10dp。同一块共用一个 transition。 */
@Composable
fun SkeletonBlock(lines: Int = 3, modifier: Modifier = Modifier, heights: Dp = 12.dp) {
    val reduced = Motion.reduced()
    val shared: Float? = if (reduced) 0.8f else {
        val t = rememberInfiniteTransition(label = "skeletonBlock")
        t.animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.Long, easing = Motion.Standard), RepeatMode.Reverse), label = "blockAlpha",
        ).value
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val widths = floatArrayOf(0.9f, 0.74f, 0.62f)
        repeat(lines) { i -> Skeleton(widths[i % widths.size], height = heights, alpha = shared) }
    }
}

/** 带标题行的骨架卡（设定整理建议、读者报告等首屏用）。 */
@Composable
fun SkeletonCard(lines: Int = 3, modifier: Modifier = Modifier) {
    LingCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Skeleton(0.42f, height = 14.dp)
            SkeletonBlock((lines - 1).coerceAtLeast(1))
        }
    }
}
