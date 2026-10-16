// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Ling

val LocalPaneVisible = staticCompositionLocalOf { true }
val LocalNarrowDetail = staticCompositionLocalOf { false }

fun paneVisibility(visible: Boolean): Modifier = if (visible) Modifier else Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }

/** Stable slots: resizing changes measurement, never the editor/AI composition lifetime. */
@Composable
fun WritingWorkspace(
    toolsOpen: Boolean, aiSelected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier,
    directory: @Composable () -> Unit, manuscript: @Composable () -> Unit,
    tools: @Composable () -> Unit, ai: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
    val tablet = enabled && maxWidth >= 720.dp
    val wide = maxWidth >= 1200.dp
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Box(paneVisibility(tablet && (wide || !toolsOpen)).testTag("writing-directory")) { directory() }
        Box(Modifier.testTag("writing-manuscript")) { manuscript() }
        Box(paneVisibility(tablet && toolsOpen).testTag("writing-tools")) { CompositionLocalProvider(LocalPaneVisible provides (tablet && toolsOpen)) { tools() } }
        Box(paneVisibility(!tablet || (toolsOpen && aiSelected)).testTag("writing-ai")) { ai() }
    }) { slots, bounds ->
        val w = bounds.maxWidth; val h = bounds.maxHeight
        val tablet = enabled && w >= 720.dp.roundToPx(); val wide = w >= 1200.dp.roundToPx()
        val aux = if (tablet && toolsOpen) 320.dp.roundToPx() else 0
        val dir = if (tablet && (wide || !toolsOpen)) 220.dp.roundToPx() else 0
        val body = w - dir - aux
        val d = slots[0].measure(Constraints.fixed(dir, h))
        val m = slots[1].measure(Constraints.fixed(body, h))
        val t = slots[2].measure(Constraints.fixed(aux, h))
        val aiWidth = if (tablet) aux else body
        val a = slots[3].measure(Constraints(maxWidth = aiWidth, maxHeight = (h - 100.dp.roundToPx()).coerceAtLeast(0)))
        layout(w, h) {
            if (dir > 0) d.placeRelative(0, 0)
            m.placeRelative(dir, 0)
            if (aux > 0) t.placeRelative(dir + body, 0)
            if (!tablet) a.placeRelative(dir, (h - a.height - 56.dp.roundToPx()).coerceAtLeast(0))
            else if (aux > 0 && aiSelected) a.placeRelative(dir + body, 100.dp.roundToPx())
        }
    }
    }
}

/** Both children stay composed when a split window becomes a phone-sized window. */
@Composable
fun AdaptiveMasterDetail(selected: Boolean, modifier: Modifier = Modifier, master: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val paneVisible = LocalPaneVisible.current
    val fallbackHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(modifier) {
    val wide = maxWidth >= 720.dp
    Layout(modifier = Modifier.fillMaxSize(), content = { Box(paneVisibility(wide || !selected)) { CompositionLocalProvider(LocalPaneVisible provides (paneVisible && (wide || !selected))) { master() } }; Box(paneVisibility(wide || selected)) { CompositionLocalProvider(LocalPaneVisible provides (paneVisible && (wide || selected)), LocalNarrowDetail provides (paneVisible && selected && !wide)) { detail() } } }) { slots, bounds ->
        val w = bounds.maxWidth; val h = if (bounds.hasBoundedHeight) bounds.maxHeight else fallbackHeight.roundToPx()
        val wide = w >= 720.dp.roundToPx()
        val left = if (wide) (w * .38f).toInt().coerceAtMost(420.dp.roundToPx()) else if (selected) 0 else w
        val right = if (wide) w - left else if (selected) w else 0
        val m = slots[0].measure(Constraints.fixed(left, h))
        val d = slots[1].measure(Constraints.fixed(right, h))
        layout(w, h) { if (left > 0) m.placeRelative(0, 0); if (right > 0) d.placeRelative(if (wide) left else 0, 0) }
    }
    }
}

@Composable
fun DetailHost(inline: Boolean, title: String, onDismiss: () -> Unit, showBack: Boolean = true, content: @Composable () -> Unit) {
    if (inline) Column(Modifier.fillMaxSize().background(Ling.colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = Ling.type.titleSmall, color = Ling.colors.onSurface, modifier = Modifier.weight(1f))
            if (showBack) LingButton("返回列表", BtnKind.Text, height = 36.dp, onClick = onDismiss)
        }
        Box(Modifier.weight(1f)) { content() }
    } else LingSheet(onDismiss, title, layout = SheetLayout.Viewport) { content() }
}
