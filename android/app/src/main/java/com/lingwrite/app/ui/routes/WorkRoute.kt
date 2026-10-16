// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lingwrite.app.data.Naming
import com.lingwrite.app.data.VolumeLabel
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.WorkScreen
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

/** 作品主页各 tab 的扩展点：由后续模块注册（大纲 / 设定库 / 伏笔 / 统计）。 */
object WorkTabs {
    var outline: @Composable (Long) -> Unit = { TabPlaceholder("大纲") }
    var lore: @Composable (Long) -> Unit = { TabPlaceholder("设定库") }
    var foreshadow: @Composable (Long) -> Unit = { TabPlaceholder("伏笔") }
    var stats: @Composable (Long) -> Unit = { TabPlaceholder("统计") }
    var loreCount: (Long) -> kotlinx.coroutines.flow.Flow<Int> = { kotlinx.coroutines.flow.flowOf(0) }
    var overdueCount: (Long) -> kotlinx.coroutines.flow.Flow<Pair<Int, Int>> = { kotlinx.coroutines.flow.flowOf(0 to 0) }
}

@Composable
private fun TabPlaceholder(name: String) {
    Text("$name 即将开放", style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(28.dp))
}

@Composable
fun WorkRoute(bookId: Long, onBack: () -> Unit, onOpenChapter: (Long) -> Unit, onSearch: () -> Unit, onExport: (Long) -> Unit, initialTab: Int = 0) {
    val repo = AppGraph.books
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { repo.book(bookId) }.collectAsState(null)
    val vols by remember(bookId) { repo.volumes(bookId) }.collectAsState(emptyList())
    val chs by remember(bookId) { repo.chapterHeaders(bookId) }.collectAsState(emptyList())
    val cplans by remember(bookId) { AppGraph.db.plan().chapters(bookId) }.collectAsState(emptyList())
    val lore by remember(bookId) { WorkTabs.loreCount(bookId) }.collectAsState(0)
    val fs by remember(bookId) { WorkTabs.overdueCount(bookId) }.collectAsState(0 to 0)
    val b = book ?: return
    var chapterMenu by remember { mutableStateOf<Chapter?>(null) }
    var chapterDelete by remember { mutableStateOf<Chapter?>(null) }
    var trash by remember { mutableStateOf(false) }
    val deleted by remember(bookId) { repo.deletedChapters(bookId) }.collectAsState(emptyList())
    var rename by remember { mutableStateOf<Chapter?>(null) }
    var bookMenu by remember { mutableStateOf(false) }
    var bookCopy by remember { mutableStateOf(false) }
    var newVolume by remember { mutableStateOf(false) }
    var editBook by remember { mutableStateOf(false) }
    var volumeMenu by remember { mutableStateOf<Volume?>(null) }
    var volumeRename by remember { mutableStateOf<Volume?>(null) }
    var volumeDelete by remember { mutableStateOf<Volume?>(null) }

    WorkScreen(
        initialTab = initialTab,
        header = workHeader(b, chs, lore, fs.first, fs.second), volumes = volumesUi(vols, chs, cplans.associate { it.chapterId to parseList(it.titleIdeas).size }),
        onBack = onBack, onOpenChapter = { onOpenChapter(it.id) },
        onNextChapter = {
            scope.launch {
                // 跟随最新一章所在卷，而不是永远进最后一卷
                val lastId = chs.maxByOrNull { it.number }?.id
                onOpenChapter(repo.nextChapter(bookId, afterChapterId = lastId))
            }
        },
        onSearch = onSearch, onExport = { onExport(bookId) }, onMore = { bookMenu = true },
        onChapterLongPress = { chapterMenu = it },
        onVolumeLongPress = { volumeMenu = it },
        serialBoard = { SerialBoardCard(bookId) },
    ) { tab ->
        when (tab) { 1 -> WorkTabs.outline(bookId); 2 -> WorkTabs.lore(bookId); 3 -> WorkTabs.foreshadow(bookId); 4 -> WorkTabs.stats(bookId) }
    }

    chapterMenu?.let { ch ->
        LingSheet({ chapterMenu = null }, ChapterLabel.full(ch.no, ch.title)) {
            SheetAction(LingIcons.EditOutline, "重命名") { rename = ch; chapterMenu = null }
            SheetAction(LingIcons.Check, if (ch.draft) "标记为已完成" else "改回草稿") { scope.launch { repo.setChapterState(ch.id, if (ch.draft) "done" else "draft"); if (ch.draft) EditorHooks.onChapterDone(ch.id) }; chapterMenu = null }
            SheetAction(LingIcons.Delete, "删除本章", danger = true) { chapterDelete = ch; chapterMenu = null }
        }
    }
    chapterDelete?.let { ch ->
        LingConfirm("删除本章？", "章节将移入回收站，正文、章纲和历史版本保留，可从作品菜单恢复。", confirm = "移入回收站", danger = true,
            onDismiss = { chapterDelete = null }, onConfirm = { scope.launch { repo.deleteChapter(ch.id) }; chapterDelete = null })
    }
    if (trash) LingSheet({ trash = false }, "已删除章节") {
        if (deleted.isEmpty()) Text("回收站为空。", modifier = Modifier.padding(24.dp))
        deleted.forEach { ch -> SheetAction(LingIcons.History, ChapterLabel.full(ch.number, ch.title), sub = "${ch.wordCount} 字 · 恢复后保留历史版本") {
            scope.launch { repo.restoreChapter(ch.id) }
        } }
    }
    // 长按卷头：重命名 / 删除分卷（删除会把卷里的章移到相邻卷并重排序号）
    volumeMenu?.let { v ->
        val nChapters = v.chapters.size
        LingSheet({ volumeMenu = null }, v.title) {
            SheetAction(LingIcons.EditOutline, "重命名分卷") { volumeRename = v; volumeMenu = null }
            SheetAction(LingIcons.Delete, "删除分卷" + if (nChapters > 0) "（$nChapters 章移到相邻卷）" else "", danger = true) { volumeDelete = v; volumeMenu = null }
            Text("删除后其余分卷自动重排序号，章号全书连续重排。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
    }
    volumeRename?.let { v ->
        val entity = vols.firstOrNull { it.id == v.id }
        var t by remember(v.id) { mutableStateOf(entity?.title.orEmpty()) }
        val index = vols.sortedBy { it.sort }.indexOfFirst { it.id == v.id }
        val hint = if (index >= 0) "显示为 ${VolumeLabel.full(index + 1, "「新名」")}（编号自动）" else ""
        LingConfirm("重命名分卷", hint, onDismiss = { volumeRename = null },
            onConfirm = {
                scope.launch { entity?.let { AppGraph.db.chapters().updateVolume(it.copy(title = Naming.stripVolume(t))) } }
                volumeRename = null
            }, extra = {
                LingTextField(t, { t = it }, Modifier.padding(top = 4.dp), label = "卷名（不用写「第 N 卷」）")
            })
    }
    volumeDelete?.let { v ->
        val only = vols.size <= 1
        val hint = if (only) "至少要保留一个分卷，不能删除。" else "卷里的 ${v.chapters.size} 章会移到相邻卷，其余分卷和章号自动重排。此操作不可撤销。"
        LingConfirm("删除分卷", hint, confirm = "删除", danger = true,
            onDismiss = { volumeDelete = null },
            onConfirm = {
                scope.launch {
                    if (!only) repo.deleteVolume(bookId, v.id)
                    volumeDelete = null
                }
            })
    }
    rename?.let { ch ->
        var t by remember(ch) { mutableStateOf(ch.title) }
        var ideas by remember(ch.id) { mutableStateOf(emptyList<String>()) }
        var aiBusy by remember(ch.id) { mutableStateOf(false) }
        LingConfirm("章节标题", "", onDismiss = { rename = null }, onConfirm = { scope.launch { repo.renameChapter(ch.id, Naming.stripChapter(t)) }; rename = null }, extra = {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LingTextField(t, { t = it }, Modifier.weight(1f), label = "章名（不用写“第 N 章”）")
                // A13 手动入口：✨ 再生成一批候选（走 ContextEngine.titleIdeas，和完成本章自动生成同一套）
                LingButton(if (aiBusy) "…" else "✨", BtnKind.Text, height = 40.dp, enabled = !aiBusy) {
                    aiBusy = true
                    scope.launch {
                        val r = runCatching { com.lingwrite.app.data.ContextEngine.titleIdeas(bookId, ch.id) }.getOrDefault(emptyList())
                        if (r.isNotEmpty()) ideas = r
                        aiBusy = false
                    }
                }
            }
            LaunchedEffect(ch.id) {
                val p = AppGraph.db.plan().chapterOnce(ch.id)
                ideas = p?.let { parseList(it.titleIdeas) }.orEmpty()
            }
            if (ideas.isNotEmpty()) {
                Text("AI 标题建议（点一个填入）", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ideas.take(3).forEach { idea ->
                        Box(Modifier.clip(RoundedCornerShape(11.dp)).background(Ling.colors.tertiaryContainer).clickable { t = idea }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(idea, style = Ling.type.micro, color = Ling.colors.onTertiaryContainer)
                        }
                    }
                }
            }
        })
    }
    if (bookMenu) LingSheet({ bookMenu = false }, b.title) {
        SheetAction(LingIcons.History, "已删除章节（${deleted.size}）", sub = "恢复正文、章纲和历史版本") { trash = true; bookMenu = false }
        SheetAction(LingIcons.EditOutline, "作品信息", sub = "书名、类型、简介、日更目标") { editBook = true; bookMenu = false }
        SheetAction(LingIcons.Sparkle, "AI 生成书面", sub = "用现有书名做封面") { bookCopy = true; bookMenu = false }
        SheetAction(LingIcons.Add, "新建分卷") { newVolume = true; bookMenu = false }
        // A5：作品页 ⋮ →「卡文了」，直接打开最近一章的卡文急救
        SheetAction(LingIcons.Help, "卡文了", sub = "打开卡文急救：选卡点、澄清问题、给 3 个方向") {
            val ch = chs.maxByOrNull { it.number }
            if (ch != null) { sessionFor(ch.id).wantStuck = true; onOpenChapter(ch.id) }
            bookMenu = false
        }
        SheetAction(LingIcons.Share, "导出 TXT / Markdown") { onExport(bookId); bookMenu = false }
    }
    if (newVolume) {
        var t by remember { mutableStateOf("") }
        LingConfirm("新建分卷", "将创建为 ${VolumeLabel.short(vols.size + 1)}，新章节会写进最后一卷。", onDismiss = { newVolume = false }, onConfirm = { scope.launch { repo.addVolume(bookId, Naming.stripVolume(t)) }; newVolume = false }, extra = {
            LingTextField(t, { t = it }, Modifier.padding(top = 12.dp), label = "卷名（可空）", placeholder = "比如：山门初入")
        })
    }
    if (bookCopy) BookCoverSheet(b) { bookCopy = false }
    if (editBook) {
        var title by remember { mutableStateOf(b.title) }
        var genre by remember { mutableStateOf(b.genre) }
        var blurb by remember { mutableStateOf(b.blurb) }
        var target by remember { mutableStateOf(if (b.dailyTarget > 0) "${b.dailyTarget}" else "") }
        // A10：书名、简介、卖点文案（ai-features §10）
        var naming by remember { mutableStateOf(false) }
        var aiBusy by remember { mutableStateOf(false) }
        var pitch by remember { mutableStateOf<BookPitch?>(null) }
        var pitchErr by remember { mutableStateOf<String?>(null) }
        LingSheet({ editBook = false }, "作品信息") {
            Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LingTextField(title, { title = it }, Modifier.weight(1f), label = "书名")
                    LingButton("✨", BtnKind.Text, height = 36.dp, onClick = { naming = !naming })
                }
                if (naming) {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("AI 起名", style = Ling.type.label, color = Ling.colors.primary)
                            if (aiBusy) com.lingwrite.app.ui.ai.AiThinking("正在起书名…", Modifier.padding(top = 8.dp))
                            pitch?.titles?.let { titles ->
                                TitleChips(titles, onPick = { title = it; naming = false }, modifier = Modifier.padding(top = 8.dp))
                            }
                            if (pitch == null && !aiBusy) LingButton("生成 5 个书名", BtnKind.Tonal, height = 36.dp) {
                                aiBusy = true
                                scope.launch {
                                    runCatching { BookAi.generate(bookId, "起点") }
                                        .fold({ pitch = it.getOrNull(); if (pitch == null) pitchErr = "模型没有按格式返回" }, { pitchErr = it.message })
                                    aiBusy = false
                                }
                            }
                        }
                    }
                }
                LingTextField(genre, { genre = it }, label = "类型")
                LingTextField(blurb, { blurb = it }, label = "简介", singleLine = false, minLines = 3, supporting = "点 ✨ 让 AI 给 3 版候选")
                LingTextField(target, { target = it.filter(Char::isDigit) }, label = "日更目标（字）", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                // A10 上架文案
                LingButton(if (aiBusy) "生成中…" else "生成上架文案", BtnKind.Tonal, icon = LingIcons.Sparkle, height = 40.dp, enabled = !aiBusy) {
                    aiBusy = true
                    scope.launch {
                        runCatching { BookAi.generate(bookId, genre.ifBlank { "起点" }) }
                            .fold({ pitch = it.getOrNull(); if (pitch == null) pitchErr = "模型没有按格式返回" }, { pitchErr = it.message })
                        aiBusy = false
                    }
                }
                pitchErr?.let { Text("没拿到结果：$it", style = Ling.type.caption, color = Ling.colors.error) }
                pitch?.let { p ->
                    if (p.blurbs.isNotEmpty()) {
                        var blurbSel by remember { mutableStateOf(0) }
                        BlurbCandidates(
                            blurbs = p.blurbs, selected = blurbSel,
                            onSelect = { blurbSel = it },
                            onUse = { blurb = p.blurbs[blurbSel] },
                        )
                    }
                    if (p.oneLine.isNotBlank() || p.recommend.isNotBlank()) {
                        PitchCard(p, onCopy = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                                listOf(p.oneLine, p.tags.joinToString("、"), p.recommend).filter { it.isNotBlank() }.joinToString("\n")))
                        })
                    }
                }
                LingButton("保存", modifier = Modifier.fillMaxWidth(), height = 50.dp) {
                    scope.launch { repo.updateBook(b.copy(title = title.ifBlank { b.title }, genre = genre, blurb = blurb, dailyTarget = target.toIntOrNull() ?: 0)) }
                    editBook = false
                }
            }
        }
    }
}
