// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.R
import com.lingwrite.app.data.AfterStatus
import com.lingwrite.app.data.BeforeData
import com.lingwrite.app.data.WriteFlow
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling

/** 写前/写中/写后三段的轻量工作流；只给候选和清单，不自动写正文。 */
@Composable
fun WriteFlowSheet(
    bookId: Long,
    chapterId: Long,
    chapterNumber: Int,
    onDismiss: () -> Unit,
    onStuck: () -> Unit,
    onAsk: () -> Unit,
) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var step by remember { mutableIntStateOf(0) }
    var before by remember { mutableStateOf<BeforeData?>(null) }
    var after by remember { mutableStateOf<AfterStatus?>(null) }
    var directions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId, chapterId) {
        before = runCatching { WriteFlow.before(bookId, chapterId, chapterNumber) }.getOrNull()
        after = runCatching { WriteFlow.after(bookId, chapterId) }.getOrNull()
    }
    LingSheet(onDismiss, tr(R.string.wf_001), layout = SheetLayout.Viewport) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf(tr(R.string.wf_002), tr(R.string.wf_003), tr(R.string.wf_004)), step) { step = it }
            Text(tr(R.string.wf_022), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant)
            when (step) {
                0 -> {
                    val b = before
                    b?.plan?.takeIf { !it.isEmpty() }?.let { p ->
                        LingCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_005), style = Ling.type.label, color = c.primary)
                                if (p.event.isNotBlank()) Text(p.event, style = Ling.type.body, color = c.onSurface)
                                if (p.hook.isNotBlank()) Text(tr(R.string.outline_052) + "：" + p.hook, style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (p.cliff.isNotBlank()) Text(tr(R.string.outline_053) + "：" + p.cliff, style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (p.storyTime.isNotBlank()) Text(tr(R.string.outline_094) + "：" + p.storyTime, style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    b?.foreshadows?.takeIf { it.isNotEmpty() }?.let { list ->
                        LingCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_006), style = Ling.type.label, color = c.primary)
                                list.take(5).forEach { f -> Text("· ${f.content}" + (f.payoff?.let { "（计划第 $it 章回收）" } ?: ""), style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2) }
                            }
                        }
                    }
                    b?.summaries?.takeIf { it.isNotEmpty() }?.let { list ->
                        LingCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_007), style = Ling.type.label, color = c.primary)
                                list.takeLast(3).forEach { (n, t) -> Text("第 $n 章：${t.take(80)}", style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2) }
                            }
                        }
                    }
                    LingButton(if (loading) tr(R.string.wf_009) else tr(R.string.wf_008), BtnKind.Tonal, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        loading = true; msg = null
                        scope.launch {
                            directions = runCatching { WriteFlow.directions(bookId, chapterId, chapterNumber) }.getOrDefault(emptyList())
                            if (directions.isEmpty()) msg = tr(R.string.wf_023)
                            loading = false
                        }
                    }
                    directions.forEachIndexed { i, d ->
                        LingCard {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${i + 1}. $d", style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                LingButton(tr(R.string.wf_010), BtnKind.Text, height = 32.dp) { clip.setText(AnnotatedString(d)); msg = tr(R.string.wf_011) }
                            }
                        }
                    }
                }
                1 -> {
                    LingButton(tr(R.string.wf_012), BtnKind.Tonal, icon = LingIcons.Bolt, modifier = Modifier.fillMaxWidth(), height = 48.dp) { onStuck() }
                    LingButton(tr(R.string.wf_013), BtnKind.Text, icon = LingIcons.Help, modifier = Modifier.fillMaxWidth(), height = 48.dp) { onAsk() }
                    LingButton(tr(R.string.wf_014), BtnKind.Text, height = 40.dp) { onDismiss() }
                }
                else -> {
                    val a = after
                    if (a == null) Text(tr(R.string.publish_003), style = Ling.type.body, color = c.onSurfaceVariant)
                    else LingCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.wf_015), style = Ling.type.label, color = c.primary)
                            Text(tr(R.string.wf_016, a.wordCount), style = Ling.type.body, color = c.onSurface)
                            Text(if (a.planFilled) tr(R.string.wf_017) else tr(R.string.wf_018), style = Ling.type.caption, color = if (a.planFilled) c.onSurfaceVariant else c.error)
                            if (a.landedGaps.isNotEmpty()) Text(tr(R.string.wf_019, a.landedGaps.joinToString("、")), style = Ling.type.caption, color = c.error)
                            Text(tr(R.string.wf_020, a.openForeshadows), style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (a.aiTasteFlags > 0) Text(tr(R.string.wf_021, a.aiTasteFlags), style = Ling.type.caption, color = c.tertiary)
                        }
                    }
                    msg?.let { Text(it, style = Ling.type.caption, color = c.primary) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
