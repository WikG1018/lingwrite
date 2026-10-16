// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 间距 token：4dp 栅格。 */
object Space {
    val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp
    /** 页面左右外边距 */
    val gutter = 16.dp
    /** 有 dock 时内容底部留白 */
    val dockClearance = 116.dp
}

/** 圆角 token。 */
object Radius {
    val xs = RoundedCornerShape(8.dp)
    val sm = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(14.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * M3 emphasized 曲线（MO1）：两段三次贝塞尔 (0,0)→(0.05,0)→(0.133,0.06)→(0.167,0.4)→(0.208,0.82)→(0.25,1)→(1,1)。
 * 用纯数学求值而不是 PathEasing：后者在 JVM 单测里依赖 androidx.graphics.path 原生库。
 */
class EmphasizedPathEasing : Easing {
    private val firstX = floatArrayOf(0f, .05f, .133f, .167f)
    private val firstY = floatArrayOf(0f, 0f, .06f, .4f)
    private val secondX = floatArrayOf(.167f, .208f, .25f, 1f)
    private val secondY = floatArrayOf(.4f, .82f, 1f, 1f)
    private fun bez(p: FloatArray, t: Float): Float {
        val u = 1f - t
        return u*u*u*p[0] + 3f*u*u*t*p[1] + 3f*u*t*t*p[2] + t*t*t*p[3]
    }
    override fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        val xs = if (x <= .167f) firstX else secondX
        val ys = if (x <= .167f) firstY else secondY
        var lo = 0f; var hi = 1f
        repeat(20) { val t = (lo + hi)/2f; if (bez(xs,t) < x) lo = t else hi = t }
        return bez(ys,(lo+hi)/2f)
    }
}

/** 动效 token：时长 + 曲线（Material 3 emphasized 系列）。 */
object Motion {
    const val Short = 150
    const val Medium = 280
    const val Long = 420
    const val StaggerStep = 35
    /** M3 emphasized path：两段三次贝塞尔（MO1）。前段更慢、后段更快，用于页面转场和步骤推进。 */
    val Emphasized: Easing = EmphasizedPathEasing()
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** 标准曲线：页内的普通变化（列表淡入、颜色、小位移）。 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    /** 候选 Pager 松手吸附（motion §21）。 */
    fun <T> snap() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    /** 内容尺寸生长（流式候选卡）。 */
    fun <T> grow() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 减弱动画（MO3）：由 LingTheme 统一提供，所有动效降级都要读它。 */
    @androidx.compose.runtime.Composable
    fun reduced(): Boolean = LocalReducedMotion.current
}
