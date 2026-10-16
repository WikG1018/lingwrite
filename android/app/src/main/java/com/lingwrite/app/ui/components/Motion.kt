// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 按压时轻微缩放（弹簧回弹），配合 ripple 使用。减弱动画时不缩放（MO3）。 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val reduced = Motion.reduced()
    val s by animateFloatAsState(if (isPressed && !reduced) pressed else 1f, Motion.press(), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** 作品页离开章节 tab 后置 true，避免书名和章节列表被拆掉再装上时重新淡入。 */
val LocalEntrancePlayed = staticCompositionLocalOf { false }

/** 列表进入：按序号错峰淡入 + 上移 12dp。减弱动画时直接显示（MO3）。 */
fun Modifier.enterStagger(index: Int, offsetDp: Float = 12f): Modifier = composed {
    val reduced = Motion.reduced() || LocalEntrancePlayed.current
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    val dy = remember { Animatable(if (reduced) 0f else 1f) }
    val px = with(LocalDensity.current) { offsetDp.dp.toPx() }
    LaunchedEffect(Unit) {
        if (!reduced) {
            // 上限 8 档：长列表/懒加载滚进来的项不会等很久
            delay((index.coerceIn(0, 8) * Motion.StaggerStep).toLong())
            launch { alpha.animateTo(1f, tween(Motion.Medium, easing = Motion.Standard)) }
            dy.animateTo(0f, tween(Motion.Long, easing = Motion.EmphasizedDecelerate))
        }
    }
    graphicsLayer { this.alpha = alpha.value; translationY = dy.value * px }
}
