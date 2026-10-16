// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.delay

enum class SheetLayout { Scroll, Viewport }
private enum class OverlayKind { Sheet, Confirm }

/** The parent may remove its conditional content; this owner retains it until exit completes. */
private class OverlayEntry(val kind: OverlayKind) {
    var title by mutableStateOf<String?>(null)
    var layout by mutableStateOf(SheetLayout.Scroll)
    var partial by mutableStateOf(false)
    var content by mutableStateOf<@Composable ColumnScope.() -> Unit>({})
    var onDismiss: () -> Unit = {}
    var closing by mutableStateOf(false)
    var notify = false
    var finished = false
    var suspending = false
    var detached = false
    var suspended by mutableStateOf(false)
}

private class OverlayOwner {
    var active by mutableStateOf<OverlayEntry?>(null)
    private val pending = ArrayDeque<OverlayEntry>()
    private val suspended = ArrayDeque<OverlayEntry>()
    fun layers(): List<OverlayEntry> = suspended.toList() + listOfNotNull(active)
    fun attach(entry: OverlayEntry) {
        if (active == null) active = entry else {
            pending.addLast(entry)
            active?.let { if (!it.closing) { it.suspending = true; it.closing = true } }
        }
    }
    fun close(entry: OverlayEntry, notify: Boolean) {
        if (entry.finished) return
        entry.notify = entry.notify || notify
        entry.suspending = false
        if (active === entry) entry.closing = true else { pending.remove(entry); suspended.remove(entry); entry.finished = true }
    }
    fun detach(entry: OverlayEntry) {
        entry.detached = true
        close(entry, false)
    }
    fun finish(entry: OverlayEntry) {
        if (active !== entry || entry.finished) return
        if (entry.suspending && !entry.detached) {
            entry.closing = false; entry.suspending = false; entry.suspended = true; suspended.addLast(entry)
        } else {
            entry.finished = true
            if (entry.notify) entry.onDismiss()
        }
        active = pending.removeFirstOrNull() ?: suspended.removeLastOrNull()
        active?.suspended = false
    }
}
private val LocalOverlayOwner = staticCompositionLocalOf<OverlayOwner?> { null }
val LocalOverlayClosing = staticCompositionLocalOf { false }

/** One persistent owner for the entire themed scene, including direct Route tests. */
@Composable
fun LingOverlayRoot(content: @Composable () -> Unit) {
    val owner = remember { OverlayOwner() }
    CompositionLocalProvider(LocalOverlayOwner provides owner) {
        Box(Modifier.fillMaxSize().background(Ling.colors.background)) {
            content()
            owner.active // Observe layer changes; suspended forms remain composed behind the active dialog.
            owner.layers().forEach { entry -> key(entry) {
                when (entry.kind) {
                    OverlayKind.Sheet -> SheetScene(owner, entry)
                    OverlayKind.Confirm -> ConfirmScene(owner, entry)
                }
            } }
        }
    }
}

@Composable
internal fun RegisterSheet(onDismiss: () -> Unit, title: String?, skipPartial: Boolean, layout: SheetLayout, content: @Composable ColumnScope.() -> Unit) {
    RegisterOverlay(OverlayKind.Sheet, onDismiss, title, !skipPartial, layout, content)
}

@Composable
internal fun RegisterConfirm(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RegisterOverlay(OverlayKind.Confirm, onDismiss, null, false, SheetLayout.Scroll, content)
}

@Composable
private fun RegisterOverlay(kind: OverlayKind, onDismiss: () -> Unit, title: String?, partial: Boolean, layout: SheetLayout, content: @Composable ColumnScope.() -> Unit) {
    val owner = LocalOverlayOwner.current
    if (owner == null) {
        LingOverlayRoot { RegisterOverlay(kind, onDismiss, title, partial, layout, content) }
        return
    }
    val entry = remember { OverlayEntry(kind) }
    SideEffect {
        entry.title = title; entry.partial = partial; entry.layout = layout
        entry.content = content; entry.onDismiss = onDismiss
    }
    DisposableEffect(owner, entry) {
        owner.attach(entry)
        onDispose { owner.detach(entry) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetScene(owner: OverlayOwner, entry: OverlayEntry) {
    val c = Ling.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !entry.partial)
    ModalBottomSheet(
        onDismissRequest = { owner.close(entry, true) }, sheetState = state,
        containerColor = c.surface, scrimColor = c.scrim,
        sheetMaxWidth = 640.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline)) },
    ) {
        BackHandler(!entry.closing && !entry.suspended) { owner.close(entry, true) }
        val density = LocalDensity.current
        val keyboard = WindowInsets.ime.getBottom(density)
        val top = WindowInsets.statusBars.getTop(density)
        val nav = WindowInsets.navigationBars.getBottom(density)
        val height = LocalConfiguration.current.screenHeightDp.dp - with(density) { (top + maxOf(keyboard, nav)).toDp() } - 36.dp
        val focus = LocalFocusManager.current
        val ime = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { focus.clearFocus(); ime?.hide() }
        var wasSuspended by remember { mutableStateOf(false) }
        LaunchedEffect(entry.closing, entry.suspended) {
            if (entry.closing) {
                focus.clearFocus(); ime?.hide()
                state.hide()
                owner.finish(entry)
            } else if (wasSuspended && !entry.suspended) {
                state.show()
            }
            wasSuspended = entry.suspended
        }
        CompositionLocalProvider(LocalOverlayClosing provides (entry.closing || entry.suspended)) {
            Column(Modifier.fillMaxWidth().heightIn(max = height.coerceAtLeast(0.dp)).padding(bottom = if (keyboard > 0) 12.dp else with(density) { nav.toDp() } + 12.dp)) {
                entry.title?.let { Text(it, style = Ling.type.title, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) }
                val body = entry.content
                when (entry.layout) {
                    SheetLayout.Scroll -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { body() }
                    SheetLayout.Viewport -> Column(Modifier.fillMaxWidth()) { body() }
                }
            }
        }
    }
}

@Composable
private fun ConfirmScene(owner: OverlayOwner, entry: OverlayEntry) {
    val c = Ling.colors
    val reduced = Motion.reduced()
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered && !entry.closing && !entry.suspended) 1f else 0f, tween(Motion.Short), label = "confirmation-opacity")
    val scale by animateFloatAsState(if (entered && !entry.closing && !entry.suspended) 1f else if (reduced) 1f else .96f, tween(Motion.Short, easing = Motion.Standard), label = "confirmation-scale")
    Dialog({ owner.close(entry, true) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(!entry.closing && !entry.suspended) { owner.close(entry, true) }
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { it.setWindowAnimations(0); it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
            onDispose { }
        }
        LaunchedEffect(Unit) { entered = true }
        LaunchedEffect(entry.closing) { if (entry.closing) { delay(Motion.Short.toLong()); owner.finish(entry) } }
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = c.scrim.alpha * alpha)).clickable { owner.close(entry, true) }.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth()
                .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(24.dp)).background(c.surface)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }.padding(24.dp).verticalScroll(rememberScrollState())) {
                CompositionLocalProvider(LocalOverlayClosing provides (entry.closing || entry.suspended)) { entry.content(this) }
            }
        }
    }
}
