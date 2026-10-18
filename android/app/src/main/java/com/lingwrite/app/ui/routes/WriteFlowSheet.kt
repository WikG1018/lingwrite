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
import com.lingwrite.app.ui.ai.AiErrorCard
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.ai.CardTone
import com.lingwrite.app.ui.ai.ToneCard
import com.lingwrite.app.ui.ai.adoptFlash
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.PlanSync
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

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
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId, chapterId) {
        before = runCatching { WriteFlow.before(bookId, chapterId, chapterNumber) }.getOrNull()
    }
    // 「写后」每次切过去都重新统计：正文在面板打开期间可能变了。
    LaunchedEffect(bookId, chapterId, step) {
        if (step == 2) after = runCatching { WriteFlow.after(bookId, chapterId) }.getOrNull()
    }
    fun generate() {
        if (loading) return
        loading = true; error = null; msg = null; copied = null
        scope.launch {
            runCatching { WriteFlow.directions(bookId, chapterId, chapterNumber) }
                .onSuccess { directions = it; if (it.isEmpty()) error = tr(R.string.wf_023) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = it.message ?: tr(R.string.wf_023) }
            loading = false
        }
    }
    LingSheet(onDismiss, tr(R.string.wf_001), layout = SheetLayout.Viewport) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf(tr(R.string.wf_002), tr(R.string.wf_003), tr(R.string.wf_004)), step) { step = it }
            Text(tr(R.string.wf_022), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant)
            // 三段之间：新内容 Medium 淡入，旧内容 Short 淡出（motion §6 / §18）。
            AnimatedContent(step, transitionSpec = { fadeIn(tween(Motion.Medium, 60)) togetherWith fadeOut(tween(Motion.Short)) }, label = "wfStep") { st ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (st) {
                0 -> {
                    val b = before
                    b?.plan?.takeIf { it.event.isNotBlank() || it.hook.isNotBlank() || it.cliff.isNotBlank() || it.note.isNotBlank() }?.let { p ->
                        LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_005), style = Ling.type.label, color = c.primary)
                                if (p.event.isNotBlank()) Text(p.event, style = Ling.type.body, color = c.onSurface)
                                if (p.hook.isNotBlank()) Text(tr(R.string.wf_025, tr(R.string.outline_052), p.hook), style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (p.cliff.isNotBlank()) Text(tr(R.string.wf_025, tr(R.string.outline_053), p.cliff), style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (p.storyTime.isNotBlank()) Text(tr(R.string.wf_025, tr(R.string.outline_094), p.storyTime), style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    b?.foreshadows?.takeIf { it.isNotEmpty() }?.let { list ->
                        LingCard(Modifier.fillMaxWidth().enterStagger(1)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_006), style = Ling.type.label, color = c.primary)
                                list.take(5).forEach { f -> Text("· ${f.content}" + (f.payoff?.let { tr(R.string.wf_024, ChapterLabel.short(it)) } ?: ""), style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2) }
                            }
                        }
                    }
                    b?.summaries?.takeIf { it.isNotEmpty() }?.let { list ->
                        LingCard(Modifier.fillMaxWidth().enterStagger(2)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr(R.string.wf_007), style = Ling.type.label, color = c.primary)
                                list.takeLast(3).forEach { (n, t) -> Text(tr(R.string.wf_025, ChapterLabel.short(n), t.take(80)), style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2) }
                            }
                        }
                    }
                    // AI 状态走统一组件：思考（三点 + 骨架）→ 候选卡 / 错误卡（重试、换模型）。
                    AnimatedContent(
                        when { loading -> 1; error != null -> 2; directions.isNotEmpty() -> 3; else -> 0 },
                        transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) },
                        label = "wfAi",
                    ) { state ->
                        when (state) {
                            1 -> ToneCard(CardTone.Amber, tr(R.string.wf_026), LingIcons.SparkleFilled, Modifier.fillMaxWidth()) { AiThinking(tr(R.string.wf_009)) }
                            2 -> AiErrorCard(error.orEmpty(), modifier = Modifier.fillMaxWidth(), onRetry = { generate() },
                                onChangeModel = EditorHooks.onOpenModels?.let { f -> { onDismiss(); f() } })
                            3 -> ToneCard(CardTone.Amber, tr(R.string.wf_026), LingIcons.SparkleFilled, Modifier.fillMaxWidth(), trailing = {
                                LingButton(tr(R.string.ai_components_008), BtnKind.Text, height = 32.dp) { generate() }
                            }) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    directions.forEachIndexed { i, d ->
                                        Row(Modifier.fillMaxWidth().enterStagger(i).adoptFlash(if (copied == i) i else null).padding(vertical = 2.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("${i + 1}.", style = Ling.type.body, color = c.tertiary)
                                            Text(d, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                            LingButton(tr(R.string.wf_010), BtnKind.Text, height = 32.dp) { clip.setText(AnnotatedString(d)); copied = i; msg = tr(R.string.wf_011) }
                                        }
                                    }
                                }
                            }
                            else -> LingButton(tr(R.string.wf_008), BtnKind.Tonal, icon = LingIcons.Sparkle, modifier = Modifier.fillMaxWidth()) { generate() }
                        }
                    }
                    msg?.let { Text(it, style = Ling.type.caption, color = c.primary) }
                }
                1 -> {
                    LingButton(tr(R.string.wf_012), BtnKind.Tonal, icon = LingIcons.Bolt, modifier = Modifier.fillMaxWidth().enterStagger(0), height = 48.dp) { onStuck() }
                    LingButton(tr(R.string.wf_013), BtnKind.Text, icon = LingIcons.Help, modifier = Modifier.fillMaxWidth().enterStagger(1), height = 48.dp) { onAsk() }
                    LingButton(tr(R.string.wf_014), BtnKind.Text, height = 40.dp, modifier = Modifier.enterStagger(2)) { onDismiss() }
                }
                else -> {
                    val a = after
                    if (a == null) DelayedSkeleton(true, cards = 1)
                    else LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.wf_015), style = Ling.type.label, color = c.primary)
                            Text(tr(R.string.wf_016, a.wordCount), style = Ling.type.body, color = c.onSurface)
                            Text(if (a.planFilled) tr(R.string.wf_017) else tr(R.string.wf_018), style = Ling.type.caption, color = if (a.planFilled) c.onSurfaceVariant else c.error)
                            if (a.landedGaps.isNotEmpty()) Text(tr(R.string.wf_019, a.landedGaps.joinToString(tr(R.string.data_043)) { PlanSync.fieldLabel(it) }), style = Ling.type.caption, color = c.error)
                            Text(tr(R.string.wf_020, a.openForeshadows), style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (a.aiTasteFlags > 0) Text(tr(R.string.wf_021, a.aiTasteFlags), style = Ling.type.caption, color = c.tertiary)
                        }
                    }
                }
            }
            }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
