// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.VolumeLabel
import com.lingwrite.app.data.encodeMarks
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun EditorDirectory(st: EditorState, nav: NavHostController) {
    val ch = st.chapter
    val chapters by remember(ch.bookId) { AppGraph.books.chapterHeaders(ch.bookId) }.collectAsState(emptyList())
    val volumes by remember(ch.bookId) { AppGraph.books.volumes(ch.bookId) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(Ling.colors.surface), contentPadding = WindowInsets.statusBars.asPaddingValues().let { PaddingValues(top = it.calculateTopPadding() + 12.dp, start = 12.dp, end = 12.dp, bottom = 12.dp) }) {
        item { Text("作品目录", style = Ling.type.titleSmall, color = Ling.colors.onSurface, modifier = Modifier.padding(vertical = 12.dp)) }
        item {
            LingButton("章节管理", BtnKind.Text) {
                AppGraph.books.queueSave(ch.id, st.value.text, encodeMarks(st.marks)); nav.navigate(Routes.work(ch.bookId))
            }
            LingButton("写下一章", BtnKind.Tonal) { scope.launch {
                try {
                    AppGraph.books.saveContent(ch.id, st.value.text, encodeMarks(st.marks))
                    val id = AppGraph.books.nextChapter(ch.bookId, afterChapterId = ch.id)
                    withContext(Dispatchers.Main.immediate) { nav.navigate(Routes.editor(id)) { popUpTo(Routes.editor(ch.id)) { inclusive = true } } }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "操作未完成，请查看目录后重试：${e.message}" }
            } }
            error?.let { Text(it, style = Ling.type.caption, color = Ling.colors.error) }
        }
        volumes.forEachIndexed { index, v ->
            item { Text(VolumeLabel.full(index + 1, v.title), style = Ling.type.label, color = Ling.colors.primary, modifier = Modifier.padding(vertical = 12.dp)) }
            items(chapters.filter { it.volumeId == v.id }.sortedBy { it.number }, key = { it.id }) { c ->
                Text(ChapterLabel.full(c.number, c.title), style = Ling.type.body, color = Ling.colors.onSurface,
                    modifier = Modifier.fillMaxWidth().background(if (c.id == ch.id) Ling.colors.primaryContainer else Ling.colors.surface)
                        .clickable {
                            if (c.id != ch.id) {
                                AppGraph.books.queueSave(ch.id, st.value.text, encodeMarks(st.marks))
                                nav.navigate(Routes.editor(c.id)) { popUpTo(Routes.editor(ch.id)) { inclusive = true } }
                            }
                        }.padding(12.dp))
            }
        }
    }
}

@Composable
fun EditorInspector(st: EditorState, nav: NavHostController, tab: Int) {
    val paneVisible = LocalPaneVisible.current
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Box(paneVisibility(tab == 0)) { CompositionLocalProvider(LocalPaneVisible provides (paneVisible && tab == 0)) { ChapterPlanSheet(st.chapter.bookId, st.chapter.id, inline = true, returnToList = false) { } } }
        Box(paneVisibility(tab == 1)) { CompositionLocalProvider(LocalPaneVisible provides (paneVisible && tab == 1)) { LoreWorkspace(st.chapter.bookId, nav, modifier = Modifier.fillMaxSize(), atChapter = st.chapter.number) } }
        Box(paneVisibility(tab == 2)) { CompositionLocalProvider(LocalPaneVisible provides (paneVisible && tab == 2)) { ForeshadowWorkspace(st.chapter.bookId, nav, st.chapter.id, st.chapter.number) } }
    }) { children, bounds ->
        val panels = children.mapIndexed { i, child ->
            child.measure(Constraints.fixed(if (i == tab) bounds.maxWidth else 0, bounds.maxHeight))
        }
        layout(bounds.maxWidth, bounds.maxHeight) { panels.getOrNull(tab)?.placeRelative(0, 0) }
    }
}

@Composable
fun LoreWorkspace(bookId: Long, nav: NavHostController, type: String = "", modifier: Modifier = Modifier.fillMaxSize(), atChapter: Int? = null) {
    var selected by rememberSaveable(bookId, type) { mutableStateOf<Long?>(null) }
    AdaptiveMasterDetail(selected != null, modifier,
        master = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { LoreLibrary(bookId, nav, type) { selected = it } } },
        detail = {
            BackHandler(LocalNarrowDetail.current) { selected = null }
            selected?.let { id -> key(id) { LoreDetailRoute(id, nav, atChapter) { selected = null } } }
                ?: Text("选择一张设定卡，在这里查看和编辑。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        })
}

@Composable
fun ForeshadowWorkspace(bookId: Long, nav: NavHostController, chapterId: Long? = null, chapterNumber: Int? = null) {
    val fs by remember(bookId) { AppGraph.db.checks().foreshadows(bookId) }.collectAsState(emptyList())
    val chapters by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val cards by remember(bookId) { AppGraph.db.lore().cards(bookId) }.collectAsState(emptyList())
    var selected by remember { mutableStateOf<FsDraft?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val number = chapterNumber ?: currentChapter(chapters)
    AdaptiveMasterDetail(selected != null, Modifier.fillMaxSize(), master = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LingButton("一致性检查", BtnKind.Text, modifier = Modifier.padding(12.dp)) { nav.navigate("issues/$bookId") }
            ForeshadowList(fs, number, cards.associate { it.id to it.name }, filter, { filter = it },
                onOpen = { selected = FsDraft(bookId, it.chapterId, it.plantNumber, it.quote, it.quoteStart, it) },
                onAdopt = { f -> scope.launch { AppGraph.db.checks().updateForeshadow(f.copy(pending = false)) } },
                onDismiss = { f -> scope.launch { AppGraph.db.checks().deleteForeshadow(f.id) } })
            LingButton("登记伏笔", BtnKind.Tonal, modifier = Modifier.padding(16.dp)) { selected = FsDraft(bookId, chapterId, number, "", -1) }
        }
    }, detail = {
        BackHandler(LocalNarrowDetail.current) { selected = null }
        selected?.let { d -> key(d.edit?.id, d.chapterId) { ForeshadowSheet(d, inline = true) { selected = null } } }
            ?: Text("选择伏笔，编辑回收计划和关联设定。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    })
}
