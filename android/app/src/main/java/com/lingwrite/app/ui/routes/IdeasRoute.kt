// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.ai.AskPrompts
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Space
import kotlinx.coroutines.launch

@Composable
fun IdeasRoute(nav: NavHostController) {
    val dao = AppGraph.db.stats()
    val scope = rememberCoroutineScope()
    val ideas by remember { dao.ideas() }.collectAsState(emptyList())
    val books by remember { AppGraph.books.summaries() }.collectAsState(emptyList())
    var asking by remember { mutableStateOf(false) }
    var convert by remember { mutableStateOf<Pair<IdeaEntity, String>?>(null) }
    var menu by remember { mutableStateOf<IdeaEntity?>(null) }
    var expandItem by remember { mutableStateOf<IdeaEntity?>(null) }
    var classifyItem by remember { mutableStateOf<IdeaEntity?>(null) }
    val clip = LocalClipboardManager.current
    IdeasScreen(ideas, asking, onNote = { t -> scope.launch { dao.insertIdea(IdeaEntity(text = t)) } }, onAsk = { q ->
        asking = true
        scope.launch {
            val id = dao.insertIdea(IdeaEntity(text = q, kind = "ask"))
            val a = AppGraph.ai.complete("ask", AskPrompts.SYS, AskPrompts.user("（跨作品）", 0, "", "", null, emptyList(), q), null, null, 800, Slot.Writing).getOrElse { "（失败：${it.message}）" }
            dao.updateIdea(IdeaEntity(id, q, "ask", a.trim()))
            asking = false
        }
    }, onMore = { menu = it })
    menu?.let { i ->
        LingSheet({ menu = null }, if (i.kind == "ask") "随手问" else "灵感") {
            // A14 灵感扩展：展开成 3 个方向 / AI 帮我归类
            SheetAction(LingIcons.SparkleFilled, "展开成 3 个方向", sub = "每个方向可存为新灵感，或转设定卡 / 伏笔") { expandItem = i; menu = null }
            SheetAction(LingIcons.Help, "AI 帮我归类", sub = "判断适合转设定卡、伏笔还是章纲，并给出预填内容") { classifyItem = i; menu = null }
            SheetAction(LingIcons.Badge, "转成设定卡", sub = "选一部作品，存进设定库") { convert = i to "card"; menu = null }
            SheetAction(LingIcons.BookmarkAdd, "转成伏笔", sub = "登记到某部作品的伏笔台账") { convert = i to "foreshadow"; menu = null }
            SheetAction(LingIcons.Share, "复制") { clip.setText(AnnotatedString(i.text + if (i.answer.isNotBlank()) "\n\n" + i.answer else "")); menu = null }
            SheetAction(LingIcons.Check, if (i.pinned) "取消置顶" else "置顶") { scope.launch { dao.updateIdea(i.copy(pinned = !i.pinned)) }; menu = null }
            SheetAction(LingIcons.Delete, "删除", danger = true) { scope.launch { dao.deleteIdea(i.id) }; menu = null }
        }
    }
    // A14：展开 3 个方向（每张卡：存为新灵感 / 转设定卡 / 转伏笔）
    expandItem?.let { item ->
        var busy by remember { mutableStateOf(false) }
        var list by remember { mutableStateOf(com.lingwrite.app.data.IdeaExpand.parseExpansions(item.expansions)) }
        LingSheet({ expandItem = null }, "展开成 3 个方向") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty() && !busy) {
                    Text("「${item.text.take(30)}…」", style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
                    LingButton("生成 3 个方向", icon = LingIcons.SparkleFilled, height = 46.dp, modifier = Modifier.fillMaxWidth()) {
                        busy = true
                        scope.launch {
                            runCatching { com.lingwrite.app.data.IdeaExpand.expand(item) }.onSuccess { list = it }
                            busy = false
                        }
                    }
                } else if (busy) {
                    com.lingwrite.app.ui.ai.AiThinking("正在展开…")
                } else list.forEach { e ->
                    IdeaExpandCard(e,
                        onSave = { scope.launch { dao.insertIdea(IdeaEntity(text = e.idea, bookId = item.bookId)) } },
                        onToCard = { convert = IdeaEntity(text = e.idea, bookId = item.bookId) to "card"; expandItem = null },
                        onToForeshadow = { convert = IdeaEntity(text = e.idea, bookId = item.bookId) to "foreshadow"; expandItem = null },
                    )
                }
            }
        }
    }
    // A14：AI 归类（预填内容，作者确认后才转换）
    classifyItem?.let { item ->
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<com.lingwrite.app.data.IdeaClassify?>(null) }
        LingSheet({ classifyItem = null }, "AI 帮我归类") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result == null && !busy) {
                    Text("让 AI 判断这条灵感适合转成设定卡、伏笔还是章纲，并给出预填内容。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
                    LingButton("开始归类", icon = LingIcons.SparkleFilled, height = 46.dp, modifier = Modifier.fillMaxWidth()) {
                        busy = true
                        scope.launch {
                            runCatching { com.lingwrite.app.data.IdeaExpand.classify(item) }.onSuccess { result = it }
                            busy = false
                        }
                    }
                } else if (busy) {
                    com.lingwrite.app.ui.ai.AiThinking("正在判断…")
                } else result?.let { r ->
                    val label = when (r.kind) { "foreshadow" -> "伏笔"; "outline" -> "章纲备注"; else -> "设定卡" }
                    Pill("建议转成 $label", PillKind.Primary)
                    when (r.kind) {
                        "foreshadow" -> {
                            Text(r.content, style = Ling.type.body, color = Ling.colors.onSurface)
                            Text("${r.payoffIn} 章后回收", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                        }
                        "outline" -> {
                            Text("第 ${r.chapter} 章：${r.note}", style = Ling.type.body, color = Ling.colors.onSurface)
                        }
                        else -> {
                            Text("${r.name}（${r.type}）", style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
                            Text(r.fields, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton("确认转换", height = 40.dp, modifier = Modifier.weight(1f)) {
                            scope.launch {
                                val bookId = item.bookId ?: books.firstOrNull()?.id
                                when (r.kind) {
                                    "foreshadow" -> bookId?.let { AppGraph.db.checks().insertForeshadow(com.lingwrite.app.data.db.ForeshadowEntity(bookId = it, content = r.content, pending = false)) }
                                    "outline" -> bookId?.let { bid ->
                                        // 找到目标章（按号；越界就落最后一章），把 AI 建议追加进章纲备注，作者可再改
                                        val chs = AppGraph.db.chapters().chaptersOnce(bid).sortedBy { it.number }
                                        val ch = chs.firstOrNull { it.number == r.chapter } ?: chs.lastOrNull()
                                        if (ch != null) {
                                            val cp = AppGraph.db.plan().chapterOnce(ch.id) ?: ChapterPlanEntity(ch.id, bid)
                                            AppGraph.db.plan().upsertChapter(cp.copy(note = cp.note + (if (cp.note.isNotBlank()) "\n" else "") + "【灵感归类】${r.note}"))
                                        }
                                    }
                                    else -> bookId?.let {
                                        AppGraph.db.lore().insertCard(com.lingwrite.app.data.db.LoreCardEntity(bookId = it, type = r.type, name = r.name, pending = false))
                                    }
                                }
                                dao.updateIdea(item.copy(usedAs = r.kind))
                                classifyItem = null
                            }
                        }
                        LingButton("取消", BtnKind.Text, height = 40.dp) { classifyItem = null }
                    }
                }
            }
        }
    }
    convert?.let { (i, kind) ->
        var book by remember { mutableStateOf(books.firstOrNull()?.id) }
        var name by remember { mutableStateOf(i.text.take(12)) }
        var type by remember { mutableStateOf("character") }
        LingSheet({ convert = null }, if (kind == "card") "转成设定卡" else "转成伏笔") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (books.isEmpty()) Text("先在书架新建一部作品。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    books.forEach { b -> LingChip(book == b.id, { book = b.id }, b.title.ifBlank { "未命名" }) }
                }
                if (kind == "card") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoreType.entries.forEach { t -> LingChip(type == t.id, { type = t.id }, t.label) }
                    }
                    LingTextField(name, { name = it }, label = "名称")
                }
                LingButton("保存", enabled = book != null && (kind != "card" || name.isNotBlank()), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) {
                    scope.launch {
                        val b = book!!
                        val body = i.text + if (i.answer.isNotBlank()) "\n" + i.answer else ""
                        if (kind == "card") AppGraph.db.lore().insertCard(LoreCardEntity(bookId = b, type = type, name = name.trim(), fields = encodeFields(listOf(Field(defaultFields(LoreType.of(type)).first(), body)))))
                        else AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = b, content = body.take(200), plantNumber = currentChapter(AppGraph.db.chapters().chaptersOnce(b))))
                        if (i.id == 0L) dao.insertIdea(i.copy(usedAs = kind, bookId = b)) else dao.updateIdea(i.copy(usedAs = kind, bookId = b)); convert = null
                    }
                }
            }
        }
    }
}

@Composable
fun IdeasScreen(ideas: List<IdeaEntity>, asking: Boolean, onNote: (String) -> Unit, onAsk: (String) -> Unit, onMore: (IdeaEntity) -> Unit) {
    val c = Ling.colors
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Text("灵感", style = Ling.type.headline, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 10.dp).enterStagger(0))
        LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(1)) {
            Column(Modifier.padding(12.dp)) {
                LingTextField(text, { text = it }, singleLine = false, minLines = 2, maxLines = 6, placeholder = "随手记下一个点子，或者问一个写作问题")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("跨作品保存，可转成设定卡或伏笔", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                    LingButton(if (asking) "思考中…" else "随手问", BtnKind.Tonal, icon = LingIcons.Sparkle, height = 38.dp, enabled = text.isNotBlank() && !asking) { onAsk(text.trim()); text = "" }
                    LingButton("记下", height = 38.dp, enabled = text.isNotBlank()) { onNote(text.trim()); text = "" }
                }
            }
        }
        if (ideas.isEmpty()) Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tile(LingIcons.IdeaFilled, c.tertiaryContainer, c.onTertiaryContainer, 56.dp)
            Text("灵感不等人。洗澡时想到的反转、路上听到的一句话，先记在这里。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, Space.dockClearance), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ideas, key = { it.id }) { i ->
                LingCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().rowClick { onMore(i) }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i.kind == "ask") Pill("随手问", PillKind.Primary, LingIcons.Sparkle) else Pill("灵感", PillKind.Amber, LingIcons.Idea)
                            if (i.pinned) Pill("置顶", PillKind.Neutral, modifier = Modifier.padding(start = 6.dp))
                            if (i.usedAs.isNotBlank()) Pill(if (i.usedAs == "card") "已转设定卡" else "已转伏笔", PillKind.Good, modifier = Modifier.padding(start = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Text(TimeFmt.relative(i.createdAt), style = Ling.type.caption, color = c.onSurfaceVariant)
                        }
                        Text(i.text, style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 24.sp, fontWeight = if (i.kind == "ask") FontWeight.Medium else FontWeight.Normal), color = c.onSurface, modifier = Modifier.padding(top = 8.dp))
                        if (i.kind == "ask") {
                            if (i.answer.isBlank()) com.lingwrite.app.ui.ai.AiThinking("正在回答…", Modifier.padding(top = 10.dp))
                            else Text(i.answer, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant, maxLines = 8, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer).padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 灵感扩展方向卡（A14，ui-spec §10）：每张有“存为灵感 / 转设定卡 / 转伏笔”。 */
@Composable
fun IdeaExpandCard(e: IdeaExpansion, onSave: () -> Unit, onToCard: () -> Unit, onToForeshadow: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ling.colors
    LingCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(e.title, style = Ling.type.label, color = c.primary)
            Text(e.idea, style = Ling.type.body, color = c.onSurface, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton("存为灵感", BtnKind.Tonal, height = 32.dp, onClick = onSave)
                LingButton("转设定卡", BtnKind.Text, height = 32.dp, onClick = onToCard)
                LingButton("转伏笔", BtnKind.Text, height = 32.dp, onClick = onToForeshadow)
            }
        }
    }
}
