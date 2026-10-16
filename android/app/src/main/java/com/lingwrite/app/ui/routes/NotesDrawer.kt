// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.lazy.itemsIndexed
import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingIconButton
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.launch

/** 便签种类（ui-spec §2）：方向 / 节拍 / 卡文 / 随手问 / 改法。 */
private fun kindLabel(k: String) = when (k) {
    "direction" -> tr(R.string.notes_drawer_001); "beat" -> tr(R.string.notes_drawer_002); "stuck" -> tr(R.string.notes_drawer_003); "ask" -> tr(R.string.ask_panel_018); "fix" -> tr(R.string.notes_drawer_004); else -> k
}
private fun kindPill(k: String) = when (k) { "direction" -> PillKind.Primary; "beat" -> PillKind.Amber; "stuck" -> PillKind.Error; else -> PillKind.Neutral }

/**
 * 本章便签抽屉（MD3）：右侧 320dp，按种类分组；每条可标为完成 / 删除 / 复制。
 * 辅助模式下 AI 的所有产出都落在这里，绝不进正文。
 */
@Composable
fun NotesDrawer(chapterId: Long, open: Boolean, onClose: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val notes by remember(chapterId) { AppGraph.db.notes().notes(chapterId) }.collectAsState(emptyList())
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val groups = listOf("direction", "beat", "stuck", "ask", "fix").map { k -> k to notes.filter { it.kind == k } }.filter { it.second.isNotEmpty() }
    // 首次打开便签抽屉的一次性提示（MD3）
    val tip = open && prefs != null && !prefs!!.notesTipShown

    BackHandler(open) { onClose() }
    val reduced = Motion.reduced()
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtMost(360.dp)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(Motion.Short)), exit = fadeOut(tween(Motion.Short))) {
            Box(Modifier.fillMaxSize().background(c.scrim).clickable(onClick = onClose))
        }
        AnimatedVisibility(open, modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduced) fadeIn(tween(Motion.Short)) else slideInHorizontally(tween(Motion.Medium, easing = Motion.EmphasizedDecelerate)) { it } + fadeIn(tween(Motion.Short)),
            exit = if (reduced) fadeOut(tween(Motion.Short)) else slideOutHorizontally(tween(Motion.Medium, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut(tween(Motion.Short))) {
            Column(
                Modifier.fillMaxHeight().width(drawerWidth).clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)).background(c.surface).statusBarsPadding().navigationBarsPadding()
                    .clickable(enabled = false, onClick = {}),
            ) {
                LingTopBar(tr(R.string.notes_drawer_005), if (notes.isEmpty()) null else tr(R.string.notes_drawer_006, notes.count { !it.done }), onBack = onClose)
                if (tip) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.primaryContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr(R.string.notes_drawer_007), style = Ling.type.caption, color = c.onPrimaryContainer, modifier = Modifier.weight(1f))
                        LingButton(tr(R.string.editor_002), BtnKind.Text, height = 30.dp) { scope.launch { AppGraph.prefs.update { it.copy(notesTipShown = true) } } }
                    }
                }
                if (notes.isEmpty()) {
                    Text(
                        tr(R.string.notes_drawer_008),
                        style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                    )
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    groups.forEach { (kind, list) ->
                        item(key = "h-$kind") {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill(kindLabel(kind), kindPill(kind))
                                Text("${list.size}", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        itemsIndexed(list, key = { _, it -> it.id }) { ni, n -> NoteRow(n, Modifier.enterStagger(ni), onDone = {
                            scope.launch { AppGraph.db.notes().update(n.copy(done = !n.done)) }
                        }, onDelete = { scope.launch { AppGraph.db.notes().delete(n.id) } }, onCopy = { clipboard.setText(AnnotatedString(n.text)) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(n: ChapterNoteEntity, modifier: Modifier = Modifier, onDone: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit) {
    val c = Ling.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (n.done) c.primary else c.surfaceContainer)
                .clickable(onClick = onDone),
            contentAlignment = Alignment.Center,
        ) { if (n.done) Icon(LingIcons.Check, tr(R.string.notes_drawer_009), tint = c.onPrimary, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(
            n.text,
            style = Ling.type.body.copy(lineHeight = 21.sp, textDecoration = if (n.done) TextDecoration.LineThrough else null),
            color = if (n.done) c.onSurfaceVariant else c.onSurface,
            modifier = Modifier.weight(1f).padding(top = 1.dp),
            maxLines = 6, overflow = TextOverflow.Ellipsis,
        )
        LingIconButton(LingIcons.Copy, tr(R.string.ai_components_005), tint = c.onSurfaceVariant, onClick = onCopy)
        LingIconButton(LingIcons.Delete, tr(R.string.check_044), tint = c.onSurfaceVariant, onClick = onDelete)
    }
}
