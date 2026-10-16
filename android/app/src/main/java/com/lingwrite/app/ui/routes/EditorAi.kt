// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.AdoptTo
import com.lingwrite.app.data.AiAdopt
import com.lingwrite.app.data.LingJson
import com.lingwrite.app.data.TimeFmt
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.CallLogEntity
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.ui.ai.AiCandidateCard
import com.lingwrite.app.ui.ai.AiErrorCard
import com.lingwrite.app.ui.ai.AiNoteCard
import com.lingwrite.app.ui.ai.AiRun
import com.lingwrite.app.ui.ai.AiRunState
import com.lingwrite.app.ui.ai.AiTweakSheet
import com.lingwrite.app.ui.ai.AdoptTarget
import com.lingwrite.app.ui.ai.Candidate
import com.lingwrite.app.ui.ai.adoptHighlight
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SuggestionCard
import com.lingwrite.app.ui.screens.ToolChips
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** 一条待发送的批注：圈出的原文 + 作者意见。 */
data class Annotation(val quote: String, val note: String, val anchor: com.lingwrite.app.data.TextAnchor? = null)

/** 批注结果：每条可以微调后采纳。 */
data class Revision(val a: Annotation, var revised: String, var accepted: Boolean = true)

/** 每章一份的 AI 会话状态（离开编辑器即清空；批注攒在内存里）。 */
class AiSession {
    var tool by mutableStateOf<Prompts.Task?>(null)
    /** 本章当前生效的写作模式（MD2） */
    var mode by mutableStateOf(WriteMode.Assist)
    /** F1 统一状态机：Idle → Thinking → Streaming → Ready → Adopted（任何状态可进 Error） */
    val run = AiRun()
    var text by mutableStateOf("")
    var streaming by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var logId by mutableStateOf<Long?>(null)
    var round by mutableIntStateOf(0)
    var target by mutableStateOf<IntRange?>(null)
    var anchor by mutableStateOf<com.lingwrite.app.data.TextAnchor?>(null)
    var noteAnchor: com.lingwrite.app.data.TextAnchor? = null
    var job: Job? = null
    // A3 多候选
    var multi by mutableStateOf(false)
    val cands = mutableStateListOf<Candidate>()
    var selected by mutableIntStateOf(0)
    var candCount by mutableIntStateOf(0)
    // C5 草稿区（生成模式“起草本章”）
    val draftSegs = mutableStateListOf<String>()
    var draftStreaming by mutableIntStateOf(-1)
    var draftTotal by mutableIntStateOf(0)
    var draftOpen by mutableStateOf(false)
    // A1 随手问
    var ask by mutableStateOf(false)
    var annotations = mutableStateListOf<Annotation>()
    var revisions by mutableStateOf<List<Revision>?>(null)
    var annotating by mutableStateOf(false)
    var tweak by mutableStateOf(false)
    var noteFor by mutableStateOf<String?>(null)
    var logs by mutableStateOf(false)
    /** 工具条胶囊：卡文急救 / 校对本章（A5、A9）。编辑器路由观察这两个标记后打开对应面板。 */
    var wantStuck by mutableStateOf(false)
    var wantProof by mutableStateOf(false)
    /** [keepRun] = true 时保留 AiRun 的 Adopted 态，让采纳高亮能播完（由 UI 调 settle 回到 Idle）。 */
    fun clear(keepRun: Boolean = false) {
        job?.cancel(); tool = null; text = ""; streaming = false; error = null; logId = null; target = null; anchor = null
        multi = false; cands.clear(); selected = 0; candCount = 0
        if (!keepRun) run.reset()
    }
    fun clearDraft() { job?.cancel(); draftSegs.clear(); draftStreaming = -1; draftTotal = 0; draftOpen = false }
}

private val sessions = mutableMapOf<Long, AiSession>()
fun sessionFor(chapterId: Long) = sessions.getOrPut(chapterId) { AiSession() }

object EditorAi {
    fun install() {
        EditorHooks.aiPanel = { st, open -> AiPanel(st, open) }
        EditorHooks.overlays = { st, nav -> AiOverlays(st, nav) }
        val prevMore = EditorHooks.moreItems
        EditorHooks.moreItems = { st, close, nav ->
            prevMore(this, st, close, nav)
            val s = sessionFor(st.chapter.id)
            SheetAction(LingIcons.Help, "随手问", sub = "问设定、问前情、问现实知识；回答不进正文") { s.ask = true; close() }
            SheetAction(LingIcons.SparkleFilled, "本章 AI 使用记录", sub = "调用类型、范围、是否采纳（不作为平台认可的证明）") { s.logs = true; close() }
        }
    }
}

private fun outcomeLabel(o: String) = when (o) { "accepted" -> "原样采纳"; "edited" -> "修改后采纳"; "rejected" -> "未采纳"; "failed" -> "失败"; "noted" -> "存便签"; "auto" -> "自动任务"; else -> "未处理" }
private fun taskLabel(t: String) = when (t) {
    "continue" -> "续写"; "directions" -> "给方向"; "rewrite" -> "改写"; "expand" -> "扩写"; "polish" -> "润色"; "deai" -> "去 AI 腔"; "dialog" -> "对话"
    "describe" -> "描写"; "shorten" -> "缩写"; "annotate" -> "批注修改"; "summary" -> "摘要"; "check" -> "一致性检查"; "extract" -> "设定提取"; "foreshadow" -> "伏笔建议"
    "wizard" -> "创作向导"; "style" -> "文风学习"; "aitaste" -> "AI 味检查"; "ask" -> "随手问"; else -> t
}

@Composable
private fun ColumnScope.AiPanel(st: EditorState, open: MutableState<Boolean>) {
    val s = remember(st.chapter.id) { sessionFor(st.chapter.id) }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val p = prefs ?: return
    val sel = st.value.selection
    val hasSel = !sel.collapsed
    val tools = Prompts.toolbarTools(s.mode.id, hasSel)

    fun runMulti(task: Prompts.Task, n: Int) {
        s.streaming = true
        s.run.begin()
        val text = st.value.text
        val range = if (hasSel) sel.min until sel.max else null
        s.target = range
        s.anchor = range?.let { com.lingwrite.app.data.TextAnchor(text, it.first, it.last + 1) }
        val quote = range?.let { text.substring(it.first, it.last + 1) } ?: ""
        val cursor = if (hasSel) sel.min else sel.end
        s.job = scope.launch {
            val ctx = PromptHooks.context(st.chapter.bookId, st.chapter.id, cursor, text)
            val system = Prompts.BASE + "\n" + PromptHooks.style(st.chapter.bookId, p)
            val user = (if (ctx.isNotBlank()) "$ctx\n\n" else "") + "【任务】" + task.instruction(quote, p)
            AppGraph.ai.streamMany(task.id, Slot.Writing, system, listOf(ChatMessage("user", user)), n, task.maxTokens, st.chapter.bookId, st.chapter.id,
                scope = if (quote.isNotEmpty()) "选中 ${quote.length} 字" else "光标处").collect { e ->
                when (e) {
                    is AiService.AiMulti.Partial -> {
                        if (e.index < s.cands.size) s.cands[e.index] = s.cands[e.index].copy(text = e.text)
                        else if (e.index == s.cands.size) s.cands += Candidate(e.text, sourceIndex = e.index)
                        s.run.stream(e.text, e.index)
                    }
                    is AiService.AiMulti.Done -> {
                        val slot = s.cands.indexOfFirst { it.sourceIndex == e.index }
                        val candidate = Candidate(e.text, null, e.logId, e.index)
                        if (slot >= 0) s.cands[slot] = candidate else s.cands += candidate
                    }
                    is AiService.AiMulti.Failed -> {
                        s.cands.removeAll { it.sourceIndex == e.index }
                        if (e.index == 0) { s.error = e.message; s.streaming = false; s.run.fail(e.message) }
                    }
                    is AiService.AiMulti.Finished -> { s.streaming = false; s.run.ready(s.cands.toList()) }
                }
            }
        }
    }

    /** C5 起草本章：按节拍分段起草，流式进草稿区，不直接写正文。 */
    fun runDraftLocal(st: EditorState, s: AiSession, p: com.lingwrite.app.data.AppPrefs) {
        s.clearDraft(); s.tool = null; s.draftOpen = true; s.error = null
        s.job = scope.launch {
            val plan = AppGraph.db.plan().chapterOnce(st.chapter.id)
            val beats = com.lingwrite.app.data.parseBeats(plan?.beats ?: "[]")
            if (beats.isEmpty()) {
                s.error = "还没有本章节拍。先点「起草本章」旁的节拍生成，或去总纲工作台第 ⑥ 步。"
                return@launch
            }
            s.draftTotal = beats.size.coerceIn(2, 3).let { beats.size }
            val n = (beats.size + 1) / 2 // 每段约 2 条节拍
            s.draftTotal = n
            val style = com.lingwrite.app.data.ai.PromptHooks.style(st.chapter.bookId, p)
            for (i in 0 until n) {
                s.draftStreaming = i
                val done = s.draftSegs.joinToString(System.lineSeparator())
                val partBeats = beats.drop(i * 2).take(2).map { it.text }
                val user = com.lingwrite.app.data.ChainPrompts.draft(
                    com.lingwrite.app.data.ChainPrompts.upstream(AppGraph.db.plan().bookOnce(st.chapter.bookId) ?: com.lingwrite.app.data.db.BookPlanEntity(st.chapter.bookId)),
                    partBeats, style, i + 1, n, done,
                )
                var seg = ""
                AppGraph.ai.stream("draft", Slot.Writing, com.lingwrite.app.data.ai.Prompts.BASE, listOf(ChatMessage("user", user)), 1600, st.chapter.bookId, st.chapter.id, scope = "起草本章").collect { e ->
                    when (e) {
                        is AiStream.Partial -> { seg = e.text; if (s.draftSegs.size <= i) s.draftSegs += e.text else s.draftSegs[i] = e.text }
                        is AiStream.Finished -> { seg = e.text; if (s.draftSegs.size <= i) s.draftSegs += e.text else s.draftSegs[i] = e.text }
                        is AiStream.Error -> { s.error = e.message; return@collect }
                    }
                }
                if (seg.isBlank()) break
            }
            s.draftStreaming = -1
        }
    }

    fun run(task: Prompts.Task) {
        s.job?.cancel()
        if (task.id == "draft") { runDraftLocal(st, s, p); return }
        s.tool = task; s.text = ""; s.error = null; s.round++
        s.run.begin()
        val n = if (Prompts.multiCandidateTask(task.id, s.mode.id, hasSel)) p.candidates.coerceIn(1, 3) else 1
        s.multi = n > 1; s.cands.clear(); s.selected = 0; s.candCount = n
        if (n > 1) { runMulti(task, n); return }
        s.streaming = true
        val text = st.value.text
        val range = if (hasSel) sel.min until sel.max else null
        s.target = range
        s.anchor = range?.let { com.lingwrite.app.data.TextAnchor(text, it.first, it.last + 1) }
        val quote = range?.let { text.substring(it.first, it.last + 1) } ?: ""
        val cursor = if (hasSel) sel.min else sel.end
        s.job = scope.launch {
            val ctx = PromptHooks.context(st.chapter.bookId, st.chapter.id, cursor, text)
            val system = Prompts.BASE + "\n" + PromptHooks.style(st.chapter.bookId, p)
            val user = (if (ctx.isNotBlank()) "$ctx\n\n" else "") + "【任务】" + task.instruction(quote, p)
            AppGraph.ai.stream(task.id, Slot.Writing, system, listOf(ChatMessage("user", user)), task.maxTokens, st.chapter.bookId, st.chapter.id,
                scope = if (quote.isNotEmpty()) "选中 ${quote.length} 字" else "光标处").collect { e ->
                when (e) {
                    is AiStream.Partial -> { s.text = e.text; s.run.stream(e.text) }
                    is AiStream.Finished -> { s.text = e.text; s.logId = e.logId; s.streaming = false; s.run.ready(listOf(Candidate(e.text, null, e.logId))) }
                    is AiStream.Error -> { s.error = e.message; s.streaming = false; s.run.fail(e.message) }
                }
            }
        }
    }

    /** 存进本章便签（MD3）：辅助模式的落点，绝不进正文。 */
    fun saveNote(kind: String, text: String) {
        scope.launch {
            AiAdopt.saveNote(st.chapter.id, kind, text, s.logId)
            s.run.adopted(AdoptTarget.Note)
            s.clear(keepRun = true)
        }
    }

    fun accept(text: String, edited: Boolean) {
        scope.launch {
            // 辅助类输出（给方向）绝不写进正文：存便签（MD1 红线，adoptTo 与测试共用同一套判断）
            val to = AiAdopt.adoptTo(s.tool?.id ?: "", s.target != null)
            if (to == AdoptTo.Note) { saveNote("direction", text); return@launch }
            AppGraph.books.saveContent(st.chapter.id, st.value.text)
            AppGraph.books.snapshot(st.chapter.id, "ai", "AI 采纳前")
            val r = s.target
            val cur = st.value.text
            if (r != null && s.anchor?.valid(cur) != true) { s.error = "正文已修改，这份建议对应旧选区。请重新选中并生成，原稿未改动。"; return@launch }
            // 辅助模式改选中的句子：改的是作者自己写的字，按“AI 建议 + 人工修改”计
            val kind = if (s.mode == WriteMode.Assist && r != null) "edited" else if (edited) "edited" else "gen"
            if (r != null && r.last < cur.length) st.replace(r.first, r.last + 1, text, kind)
            else {
                val at = st.value.selection.end.coerceIn(0, cur.length)
                val needBreak = s.tool?.id in setOf("continue", "dialog") && at > 0 && cur[at - 1] != '\n' && text.length > 60
                st.replace(at, at, (if (needBreak) "\n" else "") + text, kind)
            }
            s.logId?.let { AppGraph.ai.setOutcome(it, if (edited) "edited" else "accepted") }
            s.run.adopted(AdoptTarget.Body)
            s.clear(keepRun = true)
        }
    }

    fun acceptCandidate() {
        val cand = s.cands.getOrNull(s.selected) ?: return
        scope.launch {
            AppGraph.books.saveContent(st.chapter.id, st.value.text)
            AppGraph.books.snapshot(st.chapter.id, "ai", "AI 采纳前")
            val r = s.target
            val cur = st.value.text
            if (r != null && s.anchor?.valid(cur) != true) { s.error = "正文已修改，这份建议对应旧选区。请重新选中并生成，原稿未改动。"; return@launch }
            val kind = if (cand.edited != null) "edited" else "gen"
            if (r != null && r.last < cur.length) st.replace(r.first, r.last + 1, cand.display, kind)
            else {
                val at = st.value.selection.end.coerceIn(0, cur.length)
                val needBreak = s.tool?.id in setOf("continue", "dialog") && at > 0 && cur[at - 1] != '\n' && cand.display.length > 60
                st.replace(at, at, (if (needBreak) "\n" else "") + cand.display, kind)
            }
            // F4：记录候选数与采纳下标
            cand.logId?.let { AppGraph.db.ai().setChoice(it, s.candCount, cand.sourceIndex.takeIf { i -> i >= 0 } ?: s.selected) }
            cand.logId?.let { AppGraph.ai.setOutcome(it, if (cand.edited != null) "edited" else "accepted") }
            s.run.adopted(AdoptTarget.Body)
            s.clear(keepRun = true)
        }
    }

    AnimatedVisibility(open.value || s.tool != null || s.annotations.isNotEmpty() || s.draftOpen || s.run.state is AiRunState.Adopted,
        enter = fadeIn(tween(Motion.Medium)) + slideInVertically(tween(Motion.Long, easing = Motion.EmphasizedDecelerate)) { it / 3 },
        exit = fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Medium)) { it / 3 }) {
        Column {
            // C5 草稿区（生成模式起草本章）
            if (s.draftOpen) DraftPane(
                segments = s.draftSegs.toList(),
                total = s.draftTotal,
                streamingIndex = s.draftStreaming,
                onAdopt = { i ->
                    scope.launch {
                        AppGraph.books.saveContent(st.chapter.id, st.value.text); AppGraph.books.snapshot(st.chapter.id, "ai", "草稿采纳前")
                        val at = st.value.selection.end.coerceIn(0, st.value.text.length)
                        val sep = if (at > 0 && !st.value.text.endsWith(System.lineSeparator())) System.lineSeparator() else ""
                        st.replace(at, at, sep + s.draftSegs.getOrNull(i).orEmpty(), "gen")
                        s.run.adopted(AdoptTarget.Body)
                    }
                },
                onTweak = { s.selected = it; s.tweak = true },
                onReroll = { s.draftSegs.removeAt(it); s.draftStreaming = it; scope.launch { s.job = launch { runDraftLocal(st, s, p) } } },
                onAdoptAll = {
                    scope.launch {
                        AppGraph.books.saveContent(st.chapter.id, st.value.text); AppGraph.books.snapshot(st.chapter.id, "ai", "草稿采纳前")
                        val at = st.value.selection.end.coerceIn(0, st.value.text.length)
                        st.replace(at, at, System.lineSeparator() + s.draftSegs.joinToString(System.lineSeparator()), "gen")
                        s.clearDraft()
                    }
                },
                onDismiss = { s.draftOpen = false },
            )
            val t = s.tool
            if (t != null && s.error == null) {
                val header = buildString {
                    append(if (t.id == "directions") "写作方向" else "${t.label}${if (s.multi) " · ${s.candCount} 个候选" else "建议"}")
                    if (s.round > 1) append(" · 第 ${s.round} 次")
                }
                when {
                    // 辅助模式的给方向：便签卡，绝不进正文
                    t.id == "directions" && s.mode == WriteMode.Assist -> AiNoteCard(
                        header, s.text.ifEmpty { "…" },
                        busy = s.streaming,
                        onAnother = { s.logId?.let { id -> scope.launch { AppGraph.ai.setOutcome(id, "rejected") } }; run(t) },
                        onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.text)) },
                        onNote = { saveNote("direction", s.text) },
                    )
                    // 多候选（生成模式）
                    s.multi -> AiCandidateCard(
                        header, s.cands.toList(), s.selected,
                        streaming = s.streaming,
                        onSelect = { s.selected = it },
                        onRegenerate = {
                            s.cands.filter { it.logId != null }.forEach { cand -> scope.launch { AppGraph.ai.setOutcome(cand.logId!!, "rejected") } }
                            run(t)
                        },
                        onTweak = { s.tweak = true },
                        onAccept = { acceptCandidate() },
                        acceptLabel = if (s.target != null) "替换" else "采纳",
                    )
                    // 辅助模式改写选中句：并排对比，采纳按“人工修改”计
                    s.mode == WriteMode.Assist && s.target != null -> AssistCompareCard(
                        header, original = st.value.text.substring(s.target!!.first.coerceIn(0, st.value.text.length), (s.target!!.last + 1).coerceIn(0, st.value.text.length)),
                        revised = s.text.ifEmpty { "…" }, streaming = s.streaming,
                        onClose = { s.logId?.let { id -> scope.launch { AppGraph.ai.setOutcome(id, "rejected") } }; s.clear() },
                        onAnother = { s.logId?.let { id -> scope.launch { AppGraph.ai.setOutcome(id, "rejected") } }; run(t) },
                        onTweak = { s.tweak = true },
                        onAccept = { accept(s.text, false) },
                    )
                    else -> SuggestionCard(
                        text = s.text.ifEmpty { "…" }, header = header, streaming = s.streaming,
                        onClose = { s.logId?.let { id -> scope.launch { AppGraph.ai.setOutcome(id, "rejected") } }; s.clear() },
                        onAnother = { s.logId?.let { id -> scope.launch { AppGraph.ai.setOutcome(id, "rejected") } }; run(t) },
                        onEdit = { s.tweak = true },
                        onAccept = { accept(s.text, false) },
                        acceptLabel = if (s.target != null) "替换" else "采纳",
                        editLabel = "微调",
                    )
                }
            } else if (t != null && s.error != null) {
                AiErrorCard(s.error!!, onRetry = { run(t) }, onChangeModel = EditorHooks.onOpenModels?.let { f -> { f() } })
            }
            // F1 + motion §22：采纳高亮（amber 30% 淡到常规底色，1.2s 后回到 Idle）
            val adoptedState = s.run.state
            if (adoptedState is AiRunState.Adopted) {
                val label = when (adoptedState.target) {
                    AdoptTarget.Body -> "已采纳到正文"
                    AdoptTarget.Note -> "已存进本章便签"
                    AdoptTarget.Lore -> "已存进设定库"
                    AdoptTarget.Foreshadow -> "已登记伏笔"
                    AdoptTarget.Outline -> "已写进章纲"
                }
                Row(
                    Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).then(Modifier.adoptHighlight("gen", s.run.runId)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LingIcons.Check, null, tint = Ling.colors.onSurface, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = Ling.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Ling.colors.onSurface)
                }
                LaunchedEffect(s.run.runId) { kotlinx.coroutines.delay(1200); s.run.settle() }
            }
            if (s.annotations.isNotEmpty() && t == null) AnnotationBar(s.annotations.size, s.annotating, onClear = { s.annotations.clear() }) {
                sendAnnotations(st, s, scope)
            }
            // 工具条胶囊（modes.md §5）：辅助模式常驻「卡文急救」；「校对本章」两种模式都有
            val chips = buildList {
                addAll(tools.map { it.label })
                add("随手问")
                if (s.mode == WriteMode.Assist) add("卡文急救")
                add("校对本章")
                if (hasSel) add("＋批注")
            }
            ToolChips(-1, chips) { i ->
                when (val chip = chips.getOrNull(i)) {
                    null -> {}
                    "随手问" -> s.ask = true
                    "卡文急救" -> s.wantStuck = true
                    "校对本章" -> s.wantProof = true
                    "＋批注" -> { s.noteFor = st.value.text.substring(sel.min, sel.max); s.noteAnchor = com.lingwrite.app.data.TextAnchor(st.value.text, sel.min, sel.max) }
                    else -> tools.firstOrNull { it.label == chip }?.let { run(it) }
                }
            }
        }
    }
}

/** 辅助模式改写选中句的并排对比卡（modes.md §2）。 */
@Composable
private fun AssistCompareCard(header: String, original: String, revised: String, streaming: Boolean, onClose: () -> Unit, onAnother: () -> Unit, onTweak: () -> Unit, onAccept: () -> Unit) {
    val c = Ling.colors
    com.lingwrite.app.ui.ai.ToneCard(
        com.lingwrite.app.ui.ai.CardTone.Primary, header, LingIcons.EditNote, Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        trailing = { LingIconButton(LingIcons.Close, "关闭", tint = c.onSurfaceVariant, onClick = onClose) },
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            Text("原文", style = Ling.type.micro, color = c.onSurfaceVariant)
            Text(original, style = Ling.type.body.copy(lineHeight = 22.sp, textDecoration = TextDecoration.LineThrough), color = c.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("改后", style = Ling.type.micro, color = c.primary)
            Row(verticalAlignment = Alignment.Top) {
                Text(revised, style = Ling.type.bodyLarge.copy(lineHeight = 24.sp), color = c.onSurface, modifier = Modifier.weight(1f, fill = false))
                if (streaming) com.lingwrite.app.ui.ai.AiStreamCursor(Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton(if (streaming) "生成中…" else "再来一次", BtnKind.Text, height = 36.dp, enabled = !streaming, onClick = onAnother)
                LingButton("微调", BtnKind.Outlined, height = 36.dp, enabled = !streaming, onClick = onTweak)
                LingButton("替换选中", BtnKind.Tonal, height = 36.dp, enabled = !streaming, onClick = onAccept)
            }
            Text("辅助模式下只替换你选中的句子，标记为“AI 建议 + 人工修改”。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun sendAnnotations(st: EditorState, s: AiSession, scope: kotlinx.coroutines.CoroutineScope) {
    val items = s.annotations.toList()
    s.annotating = true
    scope.launch {
        val p = AppGraph.prefs.flow.first()
        val r = AppGraph.ai.complete("annotate", Prompts.BASE + "\n" + PromptHooks.style(st.chapter.bookId, p),
            Prompts.annotateInstruction(items.map { it.quote to it.note }), st.chapter.bookId, st.chapter.id, 2000, Slot.Writing)
        s.annotating = false
        r.onSuccess { raw ->
            val arr = runCatching { LingJson.parseToJsonElement(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1)).jsonArray }.getOrNull()
            if (arr == null) { s.error = "模型没有按格式返回，换个模型或重试"; s.tool = Prompts.rewrite; return@onSuccess }
            s.revisions = items.mapIndexed { i, a ->
                val rev = arr.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull == i + 1 }?.jsonObject?.get("revised")?.jsonPrimitive?.contentOrNull
                Revision(a, rev ?: a.quote, rev != null)
            }
        }.onFailure { s.error = it.message; s.tool = Prompts.rewrite }
    }
}

@Composable
private fun AnnotationBar(n: Int, busy: Boolean, onClear: () -> Unit, onSend: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tertiaryContainer).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(LingIcons.EditNote, null, tint = c.onTertiaryContainer)
        Spacer(Modifier.width(8.dp))
        Text("已攒 $n 条批注", style = Ling.type.label, color = c.onTertiaryContainer, modifier = Modifier.weight(1f))
        LingButton("清空", BtnKind.Text, height = 34.dp, onClick = onClear)
        LingButton(if (busy) "修改中…" else "一起发给 AI", height = 34.dp, enabled = !busy, onClick = onSend)
    }
}

@Composable
private fun AiOverlays(st: EditorState, nav: NavHostController) {
    val s = remember(st.chapter.id) { sessionFor(st.chapter.id) }
    val scope = rememberCoroutineScope()
    val c = Ling.colors
    if (s.ask) AskPanel(st.chapter.id, st.chapter.bookId, st.chapter.number,
        quote = st.value.selection.takeIf { !it.collapsed }?.let { st.value.text.substring(it.min, it.max) },
        onDismiss = { s.ask = false },
        onToLoreCard = { qsn, ans ->
            // 问出来的东西想留下：进设定库待确认区（A2），作者看过才算数
            scope.launch {
                AppGraph.db.lore().insertCard(com.lingwrite.app.data.db.LoreCardEntity(
                    bookId = st.chapter.bookId, type = "term", name = qsn.take(10).ifBlank { "新设定" },
                    fields = com.lingwrite.app.data.encodeFields(listOf(com.lingwrite.app.data.Field("说明", ans.take(300)))),
                    pending = true,
                ))
            }
        })
    if (s.tweak) {
        val draftMode = s.draftOpen && s.tool == null && !s.multi && s.selected in s.draftSegs.indices
        val current = when {
            draftMode -> s.draftSegs[s.selected]
            s.multi -> s.cands.getOrNull(s.selected)?.display ?: s.text
            else -> s.text
        }
        val original = if (draftMode || s.multi) "" else s.target?.let { r -> st.value.text.substring(r.first.coerceIn(0, st.value.text.length), (r.last + 1).coerceIn(0, st.value.text.length)) } ?: ""
        AiTweakSheet(original, current, onDismiss = { s.tweak = false }, onAccept = { t ->
            if (s.multi) {
                // 多候选：微调结果存回当前候选，作者再点采纳
                s.cands[s.selected] = s.cands[s.selected].copy(edited = t)
                s.tweak = false
            } else if (s.draftOpen && s.selected in s.draftSegs.indices && s.tool == null) {
                s.draftSegs[s.selected] = t.trim()
                s.tweak = false
            } else {
                val edited = t.trim() != s.text.trim()
                s.tweak = false
                scope.launch {
                    if (s.tool?.id == "directions") {
                        // 给方向微调后同样不进正文
                        AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = st.chapter.id, kind = "direction", text = t))
                        s.logId?.let { AppGraph.ai.setOutcome(it, "noted") }
                        s.clear()
                        return@launch
                    }
                    AppGraph.books.saveContent(st.chapter.id, st.value.text); AppGraph.books.snapshot(st.chapter.id, "ai", "AI 采纳前")
                    val r = s.target
                    if (r != null && s.anchor?.valid(st.value.text) != true) { s.error = "正文已修改，请重新生成后采纳。原稿未改动。"; s.tweak = false; return@launch }
                    val kind = if (s.mode == WriteMode.Assist && r != null) "edited" else if (edited) "edited" else "gen"
                    if (r != null && r.last < st.value.text.length) st.replace(r.first, r.last + 1, t, kind)
                    else st.replace(st.value.selection.end, st.value.selection.end, t, kind)
                    s.logId?.let { AppGraph.ai.setOutcome(it, if (edited) "edited" else "accepted") }
                    s.clear()
                }
            }
        })
    }
    s.noteFor?.let { q ->
        var note by remember(q) { mutableStateOf("") }
        LingSheet({ s.noteFor = null }, "添加批注") {
            Text("「${q.take(80)}${if (q.length > 80) "…" else ""}」", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            LingTextField(note, { note = it }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), placeholder = "这里想怎么改？比如：节奏太慢，删掉一半", singleLine = false, minLines = 2)
            Text("可以先攒多条，再一次发给 AI。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            LingButton("加入批注", modifier = Modifier.padding(20.dp).fillMaxWidth(), height = 50.dp, enabled = note.isNotBlank()) { s.annotations += Annotation(q, note.trim(), s.noteAnchor); s.noteFor = null; s.noteAnchor = null }
        }
    }
    s.revisions?.let { revs ->
        val local = remember(revs) { revs.map { mutableStateOf(it.revised) to mutableStateOf(it.accepted) } }
        LingSheet({ s.revisions = null }, "批注修改结果", layout = SheetLayout.Viewport) {
            s.error?.let { Text(it, color = c.error, modifier = Modifier.padding(20.dp)) }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(revs.indices.toList()) { i ->
                    val r = revs[i]
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("批注 ${i + 1}：${r.a.note}", style = Ling.type.label, color = c.tertiary)
                        Text(r.a.quote, style = Ling.type.caption.copy(textDecoration = TextDecoration.LineThrough), color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        LingTextField(local[i].first.value, { local[i].first.value = it }, singleLine = false, minLines = 2, maxLines = 8)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.Checkbox(local[i].second.value, { local[i].second.value = it })
                            Text("采纳这一条", style = Ling.type.body, color = c.onSurface)
                        }
                    }
                }
            }
            LingButton("应用选中的修改", modifier = Modifier.padding(20.dp).fillMaxWidth(), height = 50.dp) {
                scope.launch {
                    val selected = revs.indices.filter { local[it].second.value }
                    val original = st.value.text
                    if (selected.any { revs[it].a.anchor?.valid(original) != true }) {
                        s.error = "正文已修改或批注位置失效，请重新选择原文并生成。未应用任何修改。"; return@launch
                    }
                    AppGraph.books.saveContent(st.chapter.id, st.value.text); AppGraph.books.snapshot(st.chapter.id, "ai", "批注修改前")
                    if (st.value.text != original) { s.error = "正文在保存期间已修改，请重新生成。"; return@launch }
                    var anyEdited = false
                    selected.sortedByDescending { revs[it].a.anchor!!.start }.forEach { i ->
                        val q = revs[i].a.quote; val at = revs[i].a.anchor!!.start
                        if (at >= 0) {
                            val edited = local[i].first.value != revs[i].revised
                            anyEdited = anyEdited || edited
                            st.replace(at, at + q.length, local[i].first.value, if (edited) "edited" else "gen")
                        }
                    }
                    AppGraph.db.ai().logs(null, 1).first().firstOrNull()?.let { AppGraph.ai.setOutcome(it.id, if (anyEdited) "edited" else "accepted") }
                    s.annotations.clear(); s.revisions = null
                }
            }
        }
    }
    if (s.logs) {
        val logs by remember(st.chapter.id) { AppGraph.db.ai().chapterLogs(st.chapter.id) }.collectAsState(emptyList())
        LingSheet({ s.logs = false }, "本章 AI 使用记录", layout = SheetLayout.Viewport) {
            if (logs.isEmpty()) Text("本章还没有调用过 AI。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) { items(logs, key = { it.id }) { LogRow(it) } }
            Text("记录只存在本机，可在导出时一并导出；它不是平台认可的证明。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun LogRow(l: CallLogEntity) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${taskLabel(l.task)} · ${l.model}", style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
            Text("${TimeFmt.clock(l.time)} · ${l.scope.ifBlank { l.provider }} · ${l.inputTokens + l.outputTokens} tokens${if (l.estimated) "（估算）" else ""}", style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        Pill(outcomeLabel(l.outcome), when (l.outcome) { "accepted" -> PillKind.Primary; "edited", "noted" -> PillKind.Amber; "failed" -> PillKind.Error; else -> PillKind.Neutral })
    }
}
