// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FsDraft(val bookId: Long, val chapterId: Long?, val number: Int, val quote: String, val start: Int, val edit: ForeshadowEntity? = null)

object CheckModule {
    var fsDraft by mutableStateOf<FsDraft?>(null)
    var checkFor by mutableStateOf<Long?>(null)

    fun install() {
        WorkTabs.foreshadow = { bookId -> ForeshadowWorkspace(bookId, LocalNav.current) }
        WorkTabs.overdueCount = { bookId ->
            AppGraph.db.checks().foreshadows(bookId).combine(AppGraph.db.chapters().chapterHeaders(bookId)) { fs, chs ->
                val cur = currentChapter(chs)
                val real = fs.filter { !it.pending }
                real.count { fsState(it, cur) == FsState.Overdue } to real.count { it.status == "open" }
            }
        }
        EditorHooks.onForeshadow = { st, _ ->
            val sel = st.value.selection
            val q = if (sel.collapsed) "" else st.value.text.substring(sel.min, sel.max)
            fsDraft = FsDraft(st.chapter.bookId, st.chapter.id, st.chapter.number, q.take(200), if (sel.collapsed) -1 else sel.min)
        }
        EditorHooks.onCheck = { st, _ -> checkFor = st.chapter.id }
        val prevOv = EditorHooks.overlays
        EditorHooks.overlays = { st, nav ->
            prevOv(st, nav)
            fsDraft?.let { d -> if (d.chapterId == st.chapter.id) ForeshadowSheet(d) { fsDraft = null } }
            if (checkFor == st.chapter.id) ChapterCheckSheet(st.chapter.id, onJump = { i ->
                val t = st.value.text
                val s = if (i.start in t.indices) i.start else locate(t, i.quote)
                if (s >= 0) st.value = TextFieldValue(t, TextRange(s, (s + i.quote.length).coerceAtMost(t.length)))
                checkFor = null
            }) { checkFor = null }
        }
        val prevDone = EditorHooks.onChapterDone
        EditorHooks.onChapterDone = { id ->
            prevDone(id)
            AppGraph.scope.launch {
                if (AppGraph.prefs.flow.first().autoCheck) {
                    val n = runCatching { Consistency.check(id) }
                    runCatching { Consistency.suggestForeshadows(id) }
                    // A2：写完一章自动整理设定（复用第一步抽出的事实）
                    runCatching { com.lingwrite.app.data.LoreSuggest.generate(id) }
                    n.onSuccess { k -> com.lingwrite.app.data.TaskHooks.done(id, k) }
                }
            }
        }
        ContextHooks.extra += { b, c, t -> Consistency.contextParts(b, c, t) }
        ExtraDestinations.list.add { nav: NavHostController ->
            composable("issues/{bookId}") { e: androidx.navigation.NavBackStackEntry -> IssuesRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
        }
        SettingsRegistry.extra += { p, _ ->
            val scope = rememberCoroutineScope()
            "写作" to SettingRow(LingIcons.FactCheck, "写完一章自动检查", "摘要、章纲落实、一致性、伏笔建议（用自动任务模型）", switch = p.autoCheck, tone = Tone.Good,
                onSwitch = { v -> scope.launch { AppGraph.prefs.update { it.copy(autoCheck = v) } } })
        }
    }
}

fun currentChapter(chs: List<ChapterEntity>) = chs.filter { it.wordCount > 0 }.maxOfOrNull { it.number } ?: 1

@Composable
fun ForeshadowTab(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val dao = AppGraph.db.checks()
    val scope = rememberCoroutineScope()
    val fs by remember(bookId) { dao.foreshadows(bookId) }.collectAsState(emptyList())
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val issues by remember(bookId) { dao.issues(bookId) }.collectAsState(emptyList())
    val cards by remember(bookId) { AppGraph.db.lore().cards(bookId) }.collectAsState(emptyList())
    val cur = currentChapter(chs)
    var filter by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(bottom = 12.dp)) {
        LingCard(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().rowClick { nav.navigate("issues/$bookId") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val open = issues.count { it.status == "open" }
                Tile(LingIcons.FactCheck, if (open > 0) c.errorContainer else c.goodContainer, if (open > 0) c.onErrorContainer else c.good)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("一致性检查", style = Ling.type.titleSmall, color = c.onSurface)
                    Text(if (open > 0) "$open 条待处理 · 人设、实力、时间线、关系" else "没有待处理的问题", style = Ling.type.caption, color = c.onSurfaceVariant)
                }
                androidx.compose.material3.Icon(LingIcons.Chevron, null, tint = c.onSurfaceVariant)
            }
        }
        ForeshadowList(fs, cur, cards.associate { it.id to it.name }, filter, { filter = it },
            onOpen = { f -> scope.launch { CheckModule.fsDraft = FsDraft(bookId, f.chapterId, f.plantNumber, f.quote, f.quoteStart, f) } },
            onAdopt = { f -> scope.launch { dao.updateForeshadow(f.copy(pending = false)) } },
            onDismiss = { f -> scope.launch { dao.deleteForeshadow(f.id) } })
        LingButton("登记伏笔", BtnKind.Tonal, icon = LingIcons.BookmarkAdd, modifier = Modifier.padding(16.dp)) { CheckModule.fsDraft = FsDraft(bookId, null, cur, "", -1) }
    }
    CheckModule.fsDraft?.let { d -> if (d.bookId == bookId && d.chapterId.let { id -> id == null || d.edit != null }) ForeshadowSheet(d) { CheckModule.fsDraft = null } }
}

@Composable
fun ForeshadowList(fs: List<ForeshadowEntity>, cur: Int, cardNames: Map<Long, String>, filter: Int, onFilter: (Int) -> Unit,
                   onOpen: (ForeshadowEntity) -> Unit, onAdopt: (ForeshadowEntity) -> Unit, onDismiss: (ForeshadowEntity) -> Unit) {
    val c = Ling.colors
    val pending = fs.filter { it.pending }
    val real = fs.filter { !it.pending }
    val labels = listOf("未回收", "已回收", "放弃")
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val n = real.count { it.status == listOf("open", "paid", "dropped")[i] }
            LingChip(filter == i, { onFilter(i) }, "$l $n")
        }
    }
    if (pending.isNotEmpty()) {
        SectionLabel("AI 建议的伏笔 · 确认后才进台账")
        LingCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = c.tertiaryContainer.copy(alpha = .45f)) {
            pending.forEachIndexed { i, f ->
                if (i > 0) InsetDivider(16.dp)
                Column(Modifier.padding(14.dp)) {
                    Text(f.content, style = Ling.type.body, color = c.onSurface)
                    if (f.quote.isNotBlank()) Text("第 ${f.plantNumber} 章：「${f.quote}」", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton("不是伏笔", BtnKind.Text, height = 34.dp) { onDismiss(f) }
                        LingButton("微调", BtnKind.Tonal, height = 34.dp) { onOpen(f) }
                        LingButton("确认", height = 34.dp) { onAdopt(f) }
                    }
                }
            }
        }
    }
    val shown = real.filter { it.status == listOf("open", "paid", "dropped")[filter] }
        .sortedWith(compareBy({ fsState(it, cur).ordinal }, { it.payoff ?: Int.MAX_VALUE }))
    Spacer(Modifier.height(8.dp))
    if (shown.isEmpty()) Text(if (filter == 0) "还没有未回收的伏笔。在正文选中一段，点“登记伏笔”。" else "这里还是空的。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
    else LingCard(Modifier.padding(horizontal = 16.dp)) {
        shown.forEachIndexed { i, f ->
            if (i > 0) InsetDivider(16.dp)
            val st = fsState(f, cur)
            Row(Modifier.fillMaxWidth().rowClick { onOpen(f) }.padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(when (st) { FsState.Overdue -> c.error; FsState.Due -> c.amber; FsState.Paid -> c.good; else -> c.outline }))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(f.content, style = Ling.type.bodyLarge.copy(fontSize = 15.sp), color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = buildList {
                        add("第 ${f.plantNumber} 章埋")
                        f.payoff?.let { add(if (f.status == "paid") "第 ${f.paidAt ?: it} 章回收" else "计划第 $it 章回收") }
                        f.cardId?.let { cardNames[it] }?.let { add("关联 $it") }
                    }.joinToString(" · ")
                    Text(meta, style = Ling.type.caption, color = if (st == FsState.Overdue) c.error else c.onSurfaceVariant)
                    if (f.quote.isNotBlank()) Text("「${f.quote}」", style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (st) {
                    FsState.Overdue -> Pill("超期 ${cur - (f.payoff ?: cur)} 章", PillKind.Error)
                    FsState.Due -> Pill("临近", PillKind.Amber)
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun ForeshadowSheet(d: FsDraft, inline: Boolean = false, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val cards by remember(d.bookId) { AppGraph.db.lore().cards(d.bookId) }.collectAsState(emptyList())
    val e = d.edit
    var content by rememberSaveable(d.edit?.id, d.chapterId) { mutableStateOf(e?.content.orEmpty()) }
    var quote by rememberSaveable(d.edit?.id, d.chapterId) { mutableStateOf(e?.quote ?: d.quote) }
    var payoff by rememberSaveable(d.edit?.id, d.chapterId) { mutableStateOf(e?.payoff?.toString().orEmpty()) }
    var card by rememberSaveable(d.edit?.id, d.chapterId) { mutableStateOf(e?.cardId) }
    var status by rememberSaveable(d.edit?.id, d.chapterId) { mutableStateOf(e?.status ?: "open") }
    DetailHost(inline, if (e == null) "登记伏笔" else if (e.pending) "确认伏笔" else "伏笔", onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingTextField(content, { content = it }, label = "伏笔内容", placeholder = "埋了什么、打算怎么回收", singleLine = false, minLines = 2)
            LingTextField(quote, { quote = it }, label = "埋设原文（第 ${d.number} 章）", singleLine = false)
            LingTextField(payoff, { payoff = it.filter(Char::isDigit) }, label = "计划回收章（可空）", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            val chars = cards.filter { !it.pending }
            if (chars.isNotEmpty()) {
                Text("关联设定卡（提到它时自动带入上下文）", style = Ling.type.caption, color = c.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chars.forEach { k -> LingChip(card == k.id, { card = if (card == k.id) null else k.id }, k.name) }
                }
            }
            if (e != null && !e.pending) {
                Text("状态", style = Ling.type.caption, color = c.onSurfaceVariant)
                Segmented(listOf("未回收", "已回收", "放弃"), listOf("open", "paid", "dropped").indexOf(status)) { status = listOf("open", "paid", "dropped")[it] }
            }
            // A8 回收建议：3 种收法（在哪章、怎么收、需要提前铺垫）
            if (e != null) {
                var ideasBusy by remember { mutableStateOf(false) }
                var ideas by remember { mutableStateOf<List<PayoffIdea>>(emptyList()) }
                // A8：写章纲前先看 diff 确认，落库后可以撤销
                var applyIdea by remember { mutableStateOf<PayoffIdea?>(null) }
                var applyBefore by remember { mutableStateOf("") }
                var appliedUndo by remember { mutableStateOf<Triple<Long, String, String>?>(null) }
                var undoMessage by remember { mutableStateOf<String?>(null) }
                Text("怎么收", style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
                if (ideasBusy) com.lingwrite.app.ui.ai.AiThinking("正在想收法…", Modifier.padding(vertical = 6.dp))
                if (ideas.isEmpty()) {
                    LingButton(if (ideasBusy) "正在想收法…" else "AI 给 3 种收法", BtnKind.Tonal, icon = LingIcons.Sparkle, height = 36.dp, enabled = !ideasBusy) {
                        ideasBusy = true
                        scope.launch {
                            runCatching { PayoffIdeas.generate(e) }.onSuccess { ideas = it }.onFailure { ideas = emptyList() }
                            ideasBusy = false
                        }
                    }
                } else {
                    ideas.forEach { idea ->
                        Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("第 ${idea.chapter} 章", PillKind.Amber)
                                Spacer(Modifier.width(8.dp))
                                Text(idea.how, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                            }
                            if (idea.setup.isNotBlank()) Text("需要铺垫：${idea.setup}", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LingButton("写进第 ${idea.chapter} 章章纲备注", BtnKind.Tonal, height = 32.dp) {
                                    scope.launch {
                                        val chs = AppGraph.db.chapters().chaptersOnce(d.bookId).sortedBy { it.number }
                                        val target = chs.firstOrNull { it.number == idea.chapter }
                                        if (target != null) {
                                            applyBefore = AppGraph.db.plan().chapterOnce(target.id)?.note.orEmpty()
                                            applyIdea = idea
                                        }
                                    }
                                }
                                LingButton("改计划回收章", BtnKind.Text, height = 32.dp) {
                                    payoff = idea.chapter.toString()
                                }
                            }
                        }
                    }
                }
                // diff 确认：确认前不改库
                applyIdea?.let { idea ->
                    val added = "【伏笔回收】${idea.how}"
                    LingConfirm(
                        "写进第 ${idea.chapter} 章章纲备注", "确认后追加到该章章纲备注（可以撤销）。",
                        onDismiss = { applyIdea = null },
                        onConfirm = {
                            scope.launch {
                                val chs = AppGraph.db.chapters().chaptersOnce(d.bookId).sortedBy { it.number }
                                val target = chs.firstOrNull { it.number == idea.chapter }
                                if (target != null) {
                                    val cp = AppGraph.db.plan().chapterOnce(target.id) ?: com.lingwrite.app.data.db.ChapterPlanEntity(target.id, d.bookId)
                                    val after = cp.note + (if (cp.note.isNotBlank()) "\n" else "") + added
                                    AppGraph.db.plan().upsertChapter(cp.copy(note = after))
                                    appliedUndo = Triple(target.id, cp.note, after)
                                }
                                applyIdea = null
                            }
                        },
                        extra = {
                            if (applyBefore.isNotBlank()) Text("原备注：${applyBefore.take(80)}", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            Text("追加：$added", style = Ling.type.caption, color = Ling.colors.primary, modifier = Modifier.padding(top = 6.dp))
                        },
                    )
                }
                undoMessage?.let { Text(it, style = Ling.type.caption, color = c.error) }
                appliedUndo?.let { (chId, before, after) ->
                    Row(
                        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已写进章纲备注", style = Ling.type.caption, color = c.onSurface, modifier = Modifier.weight(1f))
                        LingButton("撤销", BtnKind.Text, height = 30.dp) {
                            scope.launch {
                                if (AppGraph.db.plan().restoreNoteIfUnchanged(chId, after, before) == 0)
                                    undoMessage = "章纲备注后来已修改，未撤销以保护你的编辑。可手动删除追加段。"
                                appliedUndo = null
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                if (e != null) LingButton("删除", BtnKind.Text) { scope.launch { AppGraph.db.checks().deleteForeshadow(e.id); onDismiss() } }
                Spacer(Modifier.weight(1f))
                LingButton(if (e?.pending == true) "确认进台账" else "保存", enabled = content.isNotBlank()) {
                    scope.launch {
                        val cur = currentChapter(AppGraph.db.chapters().chaptersOnce(d.bookId))
                        val f = (e ?: ForeshadowEntity(bookId = d.bookId, content = "", chapterId = d.chapterId, plantNumber = d.number, quoteStart = d.start)).copy(
                            content = content.trim(), quote = quote.trim(), payoff = payoff.toIntOrNull(), cardId = card, status = status, pending = false,
                            paidAt = if (status == "paid") (e?.paidAt ?: cur) else null)
                        if (e == null) AppGraph.db.checks().insertForeshadow(f) else AppGraph.db.checks().updateForeshadow(f)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
fun IssueCard(i: IssueEntity, onJump: (() -> Unit)?, onIntended: (String) -> Unit, onFix: ((String, String) -> Unit)? = null, onIgnore: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var noting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // A6 给改法：卡片内部展开改法区
    var fixing by remember { mutableStateOf(false) }
    var fixes by remember { mutableStateOf<List<IssueFix>>(emptyList()) }
    var fixErr by remember { mutableStateOf<String?>(null) }
    LingCard(Modifier.fillMaxWidth(), color = if (i.status == "open") c.surface else c.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(kindLabel(i.kind), if (i.status == "open") PillKind.Error else PillKind.Neutral)
                Text("第 ${i.number} 章", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).weight(1f))
                if (i.status == "intended") Pill("故意为之", PillKind.Neutral) else if (i.status == "ignored") Pill("已忽略", PillKind.Neutral)
                if (i.status == "fixed") Pill("已修", PillKind.Good)
            }
            Text(i.explain, style = Ling.type.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = c.onSurface)
            if (i.quote.isNotBlank()) Text("原文：「${i.quote}」", style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurface)
            if (i.basis.isNotBlank()) Text("依据：${i.basis}", style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant)
            if (i.note.isNotBlank()) Text("说明：${i.note}", style = Ling.type.caption, color = c.onSurfaceVariant)
            // A6 改法区（ui-spec §10）
            if (fixes.isNotEmpty()) {
                fixes.forEach { fx ->
                    val label = when (fx.type) { "text" -> "改正文"; "lore" -> "改设定"; else -> "补一句解释" }
                    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                        Pill(label, if (fx.type == "text") PillKind.Primary else PillKind.Amber)
                        if (fx.before.isNotBlank()) Text(fx.before, style = Ling.type.body.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        if (fx.after.isNotBlank()) Text(fx.after, style = Ling.type.body.copy(fontWeight = FontWeight.Medium), color = c.good, modifier = Modifier.padding(top = 2.dp))
                        if (fx.why.isNotBlank()) Text(fx.why, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onFix != null) LingButton(if (fx.type == "text") "去原文应用" else if (fx.type == "lore") "去改设定" else "采纳这句", BtnKind.Tonal, height = 32.dp) {
                                onFix(fx.type, fx.after)
                            }
                            LingButton("存到便签", BtnKind.Text, height = 32.dp) {
                                scope.launch {
                                    AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = i.chapterId, kind = "fix", text = "【${label}】${fx.after}（${fx.why}）"))
                                }
                            }
                        }
                    }
                }
            }
            if (fixErr != null) Text("没拿到改法：$fixErr", style = Ling.type.caption, color = c.error)
            if (i.status == "open") {
                if (noting) {
                    LingTextField(note, { note = it }, placeholder = "一句说明（可空），例如：他在说谎")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton("取消", BtnKind.Text, height = 34.dp) { noting = false }
                        LingButton("以后不再提醒", height = 34.dp) { onIntended(note.trim()) }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!fixing && fixes.isEmpty()) LingButton("给改法", BtnKind.Text, height = 34.dp, icon = LingIcons.Sparkle) {
                        fixing = true
                        scope.launch {
                            val r = runCatching { com.lingwrite.app.data.IssueFixes.generate(i) }
                            r.onSuccess { fixes = it; fixing = false }.onFailure { fixErr = it.message; fixing = false }
                        }
                    }
                    if (fixing) com.lingwrite.app.ui.ai.AiThinking("正在想改法…", Modifier.padding(vertical = 6.dp))
                    if (onJump != null) LingButton("去原文", height = 34.dp, icon = LingIcons.EditOutline) { onJump() }
                    LingButton("故意为之", BtnKind.Tonal, height = 34.dp) { noting = true }
                    LingButton("忽略", BtnKind.Text, height = 34.dp) { onIgnore() }
                }
            }
        }
    }
}

@Composable
private fun ChapterCheckSheet(chapterId: Long, onJump: (IssueEntity) -> Unit, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val list by remember(chapterId) { AppGraph.db.checks().chapterIssues(chapterId) }.collectAsState(emptyList())
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(WriteMode.Assist) }
    LaunchedEffect(chapterId) { mode = modeFor(AppGraph.db.chapters().get(chapterId)?.bookId ?: 0) }
    LingSheet(onDismiss, "一致性检查", layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("对照设定库（按章生效的状态）、关系、前情摘要和相关旧片段找矛盾。结果都是候选，由你判断。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            // 修好的问题留在组合里淡出，不直接跳掉（A6）
            list.forEachIndexed { li, i ->
                AnimatedVisibility(
                    modifier = Modifier.enterStagger(li),
                    visible = i.status == "open",
                    enter = fadeIn(tween(Motion.Short)),
                    exit = fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Short)),
                ) {
                    IssueCard(
                        i = i,
                        onJump = { onJump(i) },
                        onIntended = { n -> scope.launch { AppGraph.db.checks().updateIssue(i.copy(status = "intended", note = n)) } },
                        onFix = { type, after -> scope.launch { IssueFixes.adopt(i, type, after, mode)?.let { onJump(i) } } },
                        onIgnore = { scope.launch { AppGraph.db.checks().updateIssue(i.copy(status = "ignored")) } },
                    )
                }
            }
            msg?.let { Text(it, style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)) }
            LingButton(if (busy) "检查中…（分两步：抽取事实 → 比对）" else "检查本章", icon = LingIcons.FactCheck, enabled = !busy, height = 50.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                busy = true; msg = null
                scope.launch {
                    msg = runCatching { Consistency.check(chapterId) }.fold({ if (it == 0) "没有发现矛盾。" else "发现 $it 条可能的矛盾。" }, { it.message })
                    busy = false
                }
            }
        }
    }
}

@Composable
fun IssuesRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val list by remember(bookId) { AppGraph.db.checks().issues(bookId) }.collectAsState(emptyList())
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    var showAll by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(WriteMode.Assist) }
    LaunchedEffect(bookId) { mode = modeFor(bookId) }
    var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("一致性检查", "${list.count { it.status == "open" }} 条待处理", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("手动检查指定范围", style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingTextField(from, { from = it.filter(Char::isDigit) }, Modifier.weight(1f), placeholder = "从第几章", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        LingTextField(to, { to = it.filter(Char::isDigit) }, Modifier.weight(1f), placeholder = "到第几章", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        LingButton("检查", enabled = progress == null && from.isNotBlank()) {
                            scope.launch {
                                val a = from.toInt(); val b = to.toIntOrNull() ?: a
                                val targets = chs.filter { it.number in a..b && it.wordCount > 0 }.sortedBy { it.number }
                                targets.forEachIndexed { k, ch -> progress = "正在检查第 ${ch.number} 章（${k + 1}/${targets.size}）"; runCatching { Consistency.check(ch.id) }.onFailure { progress = it.message } }
                                progress = null
                            }
                        }
                    }
                    progress?.let { Text(it, style = Ling.type.caption, color = c.primary) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAll) "全部结果" else "待处理", style = Ling.type.label, color = c.primary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                LingButton(if (showAll) "只看待处理" else "含已处理", BtnKind.Text, height = 34.dp) { showAll = !showAll }
            }
            if (list.none { showAll || it.status == "open" }) Text("没有待处理的问题。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            // 采纳改法后问题变 fixed：待处理列表里淡出（A6）
            list.forEach { i ->
                AnimatedVisibility(
                    visible = showAll || i.status == "open",
                    enter = fadeIn(tween(Motion.Short)),
                    exit = fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Short)),
                ) {
                    IssueCard(
                        i = i,
                        onJump = { nav.navigate(Routes.editor(i.chapterId)) },
                        onIntended = { n -> scope.launch { AppGraph.db.checks().updateIssue(i.copy(status = "intended", note = n)) } },
                        onFix = { type, after ->
                            scope.launch {
                                val jump = IssueFixes.adopt(i, type, after, mode)
                                if (jump != null) nav.navigate(Routes.editor(jump))
                            }
                        },
                        onIgnore = { scope.launch { AppGraph.db.checks().updateIssue(i.copy(status = "ignored")) } },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
