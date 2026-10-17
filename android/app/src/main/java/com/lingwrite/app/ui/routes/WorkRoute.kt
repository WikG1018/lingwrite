// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

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
    var outline: @Composable (Long) -> Unit = { TabPlaceholder(tr(R.string.work_001)) }
    var lore: @Composable (Long) -> Unit = { TabPlaceholder(tr(R.string.ask_panel_005)) }
    var foreshadow: @Composable (Long) -> Unit = { TabPlaceholder(tr(R.string.check_024)) }
    var stats: @Composable (Long) -> Unit = { TabPlaceholder(tr(R.string.dock_002)) }
    var loreCount: (Long) -> kotlinx.coroutines.flow.Flow<Int> = { kotlinx.coroutines.flow.flowOf(0) }
    var overdueCount: (Long) -> kotlinx.coroutines.flow.Flow<Pair<Int, Int>> = { kotlinx.coroutines.flow.flowOf(0 to 0) }
}

@Composable
private fun TabPlaceholder(name: String) {
    Text(tr(R.string.work_002, name), style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(28.dp))
}

@Composable
fun WorkRoute(bookId: Long, onBack: () -> Unit, onOpenChapter: (Long) -> Unit, onSearch: () -> Unit, onExport: (Long) -> Unit, onPublishCheck: (Long) -> Unit = {}, onReadStats: (Long) -> Unit = {}, initialTab: Int = 0) {
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
            SheetAction(LingIcons.EditOutline, tr(R.string.shelf_009)) { rename = ch; chapterMenu = null }
            SheetAction(LingIcons.Check, if (ch.draft) tr(R.string.work_003) else tr(R.string.editor_027)) { scope.launch { repo.setChapterState(ch.id, if (ch.draft) "done" else "draft"); if (ch.draft) EditorHooks.onChapterDone(ch.id) }; chapterMenu = null }
            SheetAction(LingIcons.Delete, tr(R.string.work_004), danger = true) { chapterDelete = ch; chapterMenu = null }
        }
    }
    chapterDelete?.let { ch ->
        LingConfirm(tr(R.string.work_005), tr(R.string.work_006), confirm = tr(R.string.work_007), danger = true,
            onDismiss = { chapterDelete = null }, onConfirm = { scope.launch { repo.deleteChapter(ch.id) }; chapterDelete = null })
    }
    if (trash) LingSheet({ trash = false }, tr(R.string.work_008)) {
        if (deleted.isEmpty()) Text(tr(R.string.work_009), modifier = Modifier.padding(24.dp))
        deleted.forEach { ch -> SheetAction(LingIcons.History, ChapterLabel.full(ch.number, ch.title), sub = tr(R.string.work_010, ch.wordCount)) {
            scope.launch { repo.restoreChapter(ch.id) }
        } }
    }
    // 长按卷头：重命名 / 删除分卷（删除会把卷里的章移到相邻卷并重排序号）
    volumeMenu?.let { v ->
        val nChapters = v.chapters.size
        LingSheet({ volumeMenu = null }, v.title) {
            SheetAction(LingIcons.EditOutline, tr(R.string.work_011)) { volumeRename = v; volumeMenu = null }
            SheetAction(LingIcons.Delete, tr(R.string.work_012) + if (nChapters > 0) tr(R.string.work_013, nChapters) else "", danger = true) { volumeDelete = v; volumeMenu = null }
            Text(tr(R.string.work_014), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
    }
    volumeRename?.let { v ->
        val entity = vols.firstOrNull { it.id == v.id }
        var t by remember(v.id) { mutableStateOf(entity?.title.orEmpty()) }
        val index = vols.sortedBy { it.sort }.indexOfFirst { it.id == v.id }
        val hint = if (index >= 0) tr(R.string.work_016, VolumeLabel.full(index + 1, tr(R.string.work_015))) else ""
        LingConfirm(tr(R.string.work_011), hint, onDismiss = { volumeRename = null },
            onConfirm = {
                scope.launch { entity?.let { AppGraph.db.chapters().updateVolume(it.copy(title = Naming.stripVolume(t))) } }
                volumeRename = null
            }, extra = {
                LingTextField(t, { t = it }, Modifier.padding(top = 4.dp), label = tr(R.string.work_017))
            })
    }
    volumeDelete?.let { v ->
        val only = vols.size <= 1
        val hint = if (only) tr(R.string.work_018) else tr(R.string.work_019, v.chapters.size)
        LingConfirm(tr(R.string.work_012), hint, confirm = tr(R.string.check_044), danger = true,
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
        LingConfirm(tr(R.string.editor_036), "", onDismiss = { rename = null }, onConfirm = { scope.launch { repo.renameChapter(ch.id, Naming.stripChapter(t)) }; rename = null }, extra = {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LingTextField(t, { t = it }, Modifier.weight(1f), label = tr(R.string.work_020))
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
                Text(tr(R.string.work_021), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
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
        SheetAction(LingIcons.History, tr(R.string.work_022, deleted.size), sub = tr(R.string.work_023)) { trash = true; bookMenu = false }
        SheetAction(LingIcons.EditOutline, tr(R.string.work_024), sub = tr(R.string.work_025)) { editBook = true; bookMenu = false }
        SheetAction(LingIcons.Sparkle, tr(R.string.book_ai_020), sub = tr(R.string.work_026)) { bookCopy = true; bookMenu = false }
        SheetAction(LingIcons.Add, tr(R.string.work_027)) { newVolume = true; bookMenu = false }
        // A5：作品页 ⋮ →「卡文了」，直接打开最近一章的卡文急救
        SheetAction(LingIcons.Help, tr(R.string.work_028), sub = tr(R.string.work_029)) {
            val ch = chs.maxByOrNull { it.number }
            if (ch != null) { sessionFor(ch.id).wantStuck = true; onOpenChapter(ch.id) }
            bookMenu = false
        }
        SheetAction(LingIcons.Share, tr(R.string.work_030)) { onExport(bookId); bookMenu = false }
        SheetAction(LingIcons.FactCheck, tr(R.string.publish_001), sub = tr(R.string.publish_015)) { onPublishCheck(bookId); bookMenu = false }
        SheetAction(LingIcons.Graph, tr(R.string.read_001), sub = tr(R.string.read_011)) { onReadStats(bookId); bookMenu = false }
    }
    if (newVolume) {
        var t by remember { mutableStateOf("") }
        LingConfirm(tr(R.string.work_027), tr(R.string.work_031, VolumeLabel.short(vols.size + 1)), onDismiss = { newVolume = false }, onConfirm = { scope.launch { repo.addVolume(bookId, Naming.stripVolume(t)) }; newVolume = false }, extra = {
            LingTextField(t, { t = it }, Modifier.padding(top = 12.dp), label = tr(R.string.work_032), placeholder = tr(R.string.work_033))
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
        LingSheet({ editBook = false }, tr(R.string.work_024)) {
            Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LingTextField(title, { title = it }, Modifier.weight(1f), label = tr(R.string.wizard_062))
                    LingButton("✨", BtnKind.Text, height = 36.dp, onClick = { naming = !naming })
                }
                if (naming) {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(tr(R.string.lore_ai_012), style = Ling.type.label, color = Ling.colors.primary)
                            if (aiBusy) com.lingwrite.app.ui.ai.AiThinking(tr(R.string.work_034), Modifier.padding(top = 8.dp))
                            pitch?.titles?.let { titles ->
                                TitleChips(titles, onPick = { title = it; naming = false }, modifier = Modifier.padding(top = 8.dp))
                            }
                            if (pitch == null && !aiBusy) LingButton(tr(R.string.work_035), BtnKind.Tonal, height = 36.dp) {
                                aiBusy = true
                                scope.launch {
                                    runCatching { BookAi.generate(bookId, tr(R.string.book_ai_007)) }
                                        .fold({ pitch = it.getOrNull(); if (pitch == null) pitchErr = tr(R.string.book_ai_018) }, { pitchErr = it.message })
                                    aiBusy = false
                                }
                            }
                        }
                    }
                }
                LingTextField(genre, { genre = it }, label = tr(R.string.work_036))
                LingTextField(blurb, { blurb = it }, label = tr(R.string.wizard_063), singleLine = false, minLines = 3, supporting = tr(R.string.work_037))
                LingTextField(target, { target = it.filter(Char::isDigit) }, label = tr(R.string.work_038), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                // A10 上架文案
                LingButton(if (aiBusy) tr(R.string.ai_components_003) else tr(R.string.work_039), BtnKind.Tonal, icon = LingIcons.Sparkle, height = 40.dp, enabled = !aiBusy) {
                    aiBusy = true
                    scope.launch {
                        runCatching { BookAi.generate(bookId, genre.ifBlank { tr(R.string.book_ai_007) }) }
                            .fold({ pitch = it.getOrNull(); if (pitch == null) pitchErr = tr(R.string.book_ai_018) }, { pitchErr = it.message })
                        aiBusy = false
                    }
                }
                pitchErr?.let { Text(tr(R.string.ai_components_011, it), style = Ling.type.caption, color = Ling.colors.error) }
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
                                listOf(p.oneLine, p.tags.joinToString(tr(R.string.data_043)), p.recommend).filter { it.isNotBlank() }.joinToString("\n")))
                        })
                    }
                }
                LingButton(tr(R.string.check_046), modifier = Modifier.fillMaxWidth(), height = 50.dp) {
                    scope.launch { repo.updateBook(b.copy(title = title.ifBlank { b.title }, genre = genre, blurb = blurb, dailyTarget = target.toIntOrNull() ?: 0)) }
                    editBook = false
                }
            }
        }
    }
}
