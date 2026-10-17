// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import com.lingwrite.app.ui.components.rowClick
import androidx.compose.foundation.lazy.itemsIndexed
import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ContextEngine
import com.lingwrite.app.data.allNames
import com.lingwrite.app.data.ai.AiStream
import com.lingwrite.app.data.ai.AskPrompts
import com.lingwrite.app.data.ai.ChatMessage
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.db.IdeaEntity
import com.lingwrite.app.ui.ai.AiErrorCard
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.ai.ContextChips
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 随手问面板（A1）：盖在键盘上方的问答，回答绝不进正文。
 * 历史按章保存在 ideas(kind=ask, chapterId)，最多显示 20 条。
 * [loreScope] = true 时是 A15「问设定」：上下文换成按章生效的状态、关系、检索结果，回答必须附出处。
 */
@Composable
fun AskPanel(chapterId: Long, bookId: Long, chapterNo: Int, quote: String?, onDismiss: () -> Unit, onToLoreCard: ((question: String, answer: String) -> Unit)? = null, loreScope: Boolean = false) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val history by remember(chapterId) { AppGraph.db.stats().askHistory(chapterId) }.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }
    var streamingText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    // 胶囊不只是标签：移除哪个，请求里就不带哪块上下文
    var chips by remember { mutableStateOf(if (loreScope) listOf(tr(R.string.ask_panel_001), tr(R.string.ask_panel_002), tr(R.string.ask_panel_003)) else listOf(tr(R.string.ask_panel_004), tr(R.string.ask_panel_005), tr(R.string.ask_panel_006))) }

    LaunchedEffect(history.size, busy, streamingText) { if (history.isNotEmpty() || busy) listState.animateScrollToItem((history.size.coerceAtMost(20)) * 2) }

    fun send(question: String) {
        if (question.isBlank() || busy) return
        busy = true; error = null; streamingText = ""; pending = question; q = ""
        job = scope.launch {
            // A15 问设定：按章生效的全部状态、关系、检索结果（“以第 {当前章} 章时为准”）；胶囊控制带哪几块
            val brief = if (loreScope) {
                val chapter = AppGraph.db.chapters().get(chapterId) ?: return@launch
                val cur = chapter.number
                val cards = AppGraph.db.lore().cardsOnce(bookId).filter { !it.pending && it.inject != "never" && (it.firstChapter == null || it.firstChapter <= cur) }
                val states = AppGraph.db.lore().statesForBook(bookId).groupBy { it.cardId }
                val named = cards.filter { card -> card.inject == "always" || card.allNames().any { question.contains(it) } }
                val statesText = if (tr(R.string.ask_panel_001) in chips) named.joinToString("\n") { card ->
                    val f = com.lingwrite.app.data.resolveAt(card, states[card.id].orEmpty(), cur)
                    tr(R.string.ask_panel_007, card.name) + f.joinToString(tr(R.string.ask_panel_008)) { "${it.k}=${it.v}" + (it.changedAt?.let { a -> tr(R.string.ask_panel_009, a) } ?: "") }
                } else ""
                val relText = if (tr(R.string.ask_panel_002) in chips) {
                    val names = cards.associate { it.id to it.name }
                    com.lingwrite.app.data.relationsAt(AppGraph.db.lore().relationsOnce(bookId), cur)
                        .joinToString(tr(R.string.ask_panel_008)) { tr(R.string.ask_panel_010, names[it.fromId], names[it.toId], trText(it.label)) }
                } else ""
                val hits = if (tr(R.string.ask_panel_003) in chips) runCatching { ContextEngine.retrieve(bookId, chapter, named.filter { it.type == "character" }.map { it.name }, question) }.getOrDefault(emptyList()) else emptyList()
                listOfNotNull(
                    statesText.takeIf { it.isNotBlank() }?.let { trZh(R.string.ask_panel_011, cur, it) },
                    relText.takeIf { it.isNotBlank() }?.let { trZh(R.string.ask_panel_012, it) },
                    hits.takeIf { it.isNotEmpty() }?.let { trZh(R.string.ask_panel_013) + hits.joinToString("\n") { h -> trZh(R.string.ask_panel_014, h.number, h.text.take(200)) } },
                ).joinToString("\n\n")
            } else {
                val parts = buildList {
                    if (tr(R.string.ask_panel_005) in chips) { add(ContextEngine.Part.Characters); add(ContextEngine.Part.Lore(question)) }
                    if (tr(R.string.ask_panel_006) in chips) add(ContextEngine.Part.PrevSummaries(5))
                    if (tr(R.string.ask_panel_004) in chips) add(ContextEngine.Part.ChapterPlan)
                }
                if (parts.isEmpty()) trZh(R.string.ask_panel_015) else ContextEngine.brief(bookId, chapterId, parts.toSet())
            }
            val hist = AppGraph.db.stats().askHistory(chapterId).first().takeLast(3).map { it.text to it.answer }
            val chapter = AppGraph.db.chapters().get(chapterId)
            val sys = if (loreScope) AskPrompts.SYS + trZh(R.string.ask_panel_016) else AskPrompts.SYS
            val user = AskPrompts.user(book?.title.orEmpty(), chapter?.number ?: chapterNo, brief, chapter?.content.orEmpty(), quote, hist, question)
            var answer = ""
            AppGraph.ai.stream("ask", Slot.Writing, sys, listOf(ChatMessage("user", user)), 600, bookId, chapterId, scope = if (loreScope) trZh(R.string.ask_panel_017) else trZh(R.string.ask_panel_018))
                .collect { e ->
                    when (e) {
                        is AiStream.Partial -> streamingText = e.text
                        is AiStream.Finished -> { answer = e.text; busy = false }
                        is AiStream.Error -> { error = e.message; busy = false }
                    }
                }
            if (answer.isNotBlank()) {
                AppGraph.db.stats().insertIdea(IdeaEntity(text = question, kind = "ask", answer = answer, bookId = bookId, chapterId = chapterId))
                streamingText = ""; pending = ""
            }
        }
    }

    LingSheet(onDismiss, if (loreScope) tr(R.string.ask_panel_017) else tr(R.string.ask_panel_018), skipPartial = false, layout = SheetLayout.Viewport) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Text(tr(R.string.ask_panel_019), style = Ling.type.micro, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val showEmpty = history.isEmpty() && !busy && streamingText.isBlank() && error == null && pending.isBlank()
                if (showEmpty) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr(R.string.ask_panel_020), style = Ling.type.body, color = c.onSurfaceVariant)
                        listOf(tr(R.string.ask_panel_021), tr(R.string.ask_panel_022), tr(R.string.ask_panel_023)).forEachIndexed { si, sample ->
                            // 示例问题：在 sheet 底色上用 surfaceContainer 区分，整卡可点，前置图标
                            LingCard(Modifier.fillMaxWidth().enterStagger(si), color = c.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().rowClick { q = sample }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(LingIcons.Sparkle, null, Modifier.size(16.dp), tint = c.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(sample, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(history.takeLast(20), key = { _, it -> it.id }) { hi, h ->
                        Column(Modifier.fillMaxWidth().enterStagger(hi), horizontalAlignment = Alignment.End) {
                            Text(h.text, style = Ling.type.body, color = c.onPrimaryContainer, modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(c.primaryContainer).widthIn(max = 320.dp))
                        }
                        AskAnswer(h.answer, onCopy = { clipboard.setText(AnnotatedString(h.answer)) },
                            onNote = {
                                scope.launch { AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = chapterId, kind = "ask", text = tr(R.string.ask_panel_024, h.text, h.answer))) }
                            },
                            onLore = onToLoreCard?.let { cb -> { cb(h.text, h.answer) } })
                    }
                    if (busy || streamingText.isNotBlank() || error != null) item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Text(pending, style = Ling.type.body, color = c.onPrimaryContainer, modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(c.primaryContainer).widthIn(max = 320.dp))
                        }
                        when {
                            error != null -> AiErrorCard(error!!, modifier = Modifier.padding(top = 8.dp), onRetry = { send(pending) })
                            busy && streamingText.isBlank() -> AiThinking(tr(R.string.ask_panel_025), Modifier.padding(12.dp))
                            else -> AskAnswer(streamingText, onCopy = { clipboard.setText(AnnotatedString(streamingText)) })
                        }
                    }
                }
            }
            // 输入区：上下文胶囊 + 输入框 + 发送
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (!quote.isNullOrBlank()) Text(tr(R.string.ask_panel_026, quote.take(24)), style = Ling.type.micro, color = c.primary, modifier = Modifier.padding(bottom = 4.dp))
                ContextChips(chips, Modifier.padding(bottom = 6.dp)) { i -> chips = chips.toMutableList().also { it.removeAt(i) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingTextField(q, { q = it }, Modifier.weight(1f), placeholder = tr(R.string.ask_panel_027), singleLine = true)
                    val i = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(44.dp).pressScale(i, 0.90f).clip(RoundedCornerShape(22.dp))
                            .background(if (q.isBlank() || busy) c.surfaceContainer else c.primary)
                            .clickable(i, indication = null, enabled = q.isNotBlank() && !busy, onClick = { send(q) }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(LingIcons.Send, tr(R.string.ask_panel_028), tint = if (q.isBlank() || busy) c.onSurfaceVariant else c.onPrimary, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

/** AI 回答气泡：【出处】高亮标注。onNote 为空（流式中）不显示存便签；onLore 非空显示「转设定卡」。 */
@Composable
private fun AskAnswer(answer: String, onCopy: () -> Unit, onNote: (() -> Unit)? = null, onLore: (() -> Unit)? = null) {
    val c = Ling.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).background(c.surfaceContainer).padding(12.dp)) {
        val annotated = remember(answer) { buildAnnotatedString {
            val src = Regex("【[^】]{1,24}】")
            var i = 0
            src.findAll(answer).forEach { m ->
                append(answer.substring(i, m.range.first))
                withStyle(SpanStyle(color = c.primary, fontSize = 12.sp)) { append(m.value) }
                i = m.range.last + 1
            }
            if (i < answer.length) append(answer.substring(i))
        } }
        Text(annotated, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurface)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(tr(R.string.ai_components_005), BtnKind.Text, height = 32.dp, onClick = onCopy)
            if (onNote != null) LingButton(tr(R.string.ask_panel_029), BtnKind.Text, height = 32.dp, onClick = onNote)
            if (onLore != null) LingButton(tr(R.string.ask_panel_030), BtnKind.Text, height = 32.dp, onClick = onLore)
        }
    }
}
