// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.lingwrite.app.ui.components.LocalEntrancePlayed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.Chapter
import com.lingwrite.app.data.FakeData
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.CoverPalette
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Space

@Composable
fun WorkScreen(
    header: com.lingwrite.app.data.WorkHeader = FakeData.header,
    volumes: List<com.lingwrite.app.data.Volume> = FakeData.volumes,
    initialTab: Int = 0,
    onBack: () -> Unit = {},
    onOpenChapter: (Chapter) -> Unit = {},
    onNextChapter: () -> Unit = {},
    onSearch: () -> Unit = {},
    onExport: () -> Unit = {},
    onMore: () -> Unit = {},
    onChapterLongPress: (Chapter) -> Unit = {},
    /** 长按卷头：删除/重命名分卷入口。 */
    onVolumeLongPress: (com.lingwrite.app.data.Volume) -> Unit = {},
    /** A11 连载看板卡（ui-spec §9），放在概览卡下方。 */
    serialBoard: (@Composable () -> Unit)? = null,
    tabContent: @Composable (Int) -> Unit = {},
) {
    val c = Ling.colors
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var entrancePlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab != 0) entrancePlayed = true }
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalEntrancePlayed provides entrancePlayed) {
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = WindowInsets.statusBars.asPaddingValues().let { PaddingValues(top = it.calculateTopPadding(), bottom = Space.dockClearance + 40.dp) },
        ) {
            item { WorkMasthead(header, tab, { tab = it }, onBack, onSearch, onExport, onMore, serialBoard) }
            if (tab != 0) item {
                if (tab in 2..3) Box(Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 170).coerceAtLeast(400).dp)) { tabContent(tab) }
                else tabContent(tab)
            }
            if (tab == 0) volumes.forEachIndexed { vi, v ->
                item { VolumeHeader(v, vi, onVolumeLongPress) }
                item {
                    LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(3 + vi * 3)) {
                        v.chapters.forEachIndexed { i, ch ->
                            if (i > 0) InsetDivider(62.dp)
                            ChapterRow(ch, { onChapterLongPress(ch) }) { onOpenChapter(ch) }
                        }
                    }
                }
            }
        }
        if (tab == 0) LingFab("写下一章", LingIcons.EditOutline, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 88.dp), onClick = onNextChapter)
    }
    }
}

@Composable
private fun WorkMasthead(
    header: com.lingwrite.app.data.WorkHeader,
    tab: Int,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onExport: () -> Unit,
    onMore: () -> Unit,
    serialBoard: (@Composable () -> Unit)?,
) {
    val c = Ling.colors
    LingTopBar(onBack = onBack) {
        LingIconButton(LingIcons.Search, "搜索", onClick = onSearch)
        LingIconButton(LingIcons.Share, "导出", onClick = onExport)
        LingIconButton(LingIcons.More, "更多", onClick = onMore)
    }
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp).enterStagger(0), verticalAlignment = Alignment.CenterVertically) {
        BookCover(header.short, header.cover, 58.dp, 78.dp, Modifier.shadow(10.dp, RoundedCornerShape(9.dp), ambientColor = Color(0x401E3250), spotColor = Color(0x401E3250)), header.id)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(header.title, style = Ling.type.headline, color = c.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { if (header.genre.isNotBlank()) Pill(header.genre); Pill(header.statusLabel, PillKind.Neutral); header.target?.let { Pill(it, PillKind.Neutral) } }
        }
    }
    LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(1)) {
        Row(Modifier.padding(vertical = 12.dp).height(IntrinsicSize.Min)) {
            listOf(Triple(header.words, "总字数", c.onSurface), Triple("${header.chapters}", "章节", c.onSurface), Triple("${header.lore}", "设定卡", c.onSurface), Triple("${header.overdue}", "伏笔超期", if (header.overdue > 0) c.error else c.onSurface))
                .forEachIndexed { i, (n, l, col) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n, style = Ling.type.titleSmall.copy(fontSize = 18.sp), color = col)
                        Text(l, style = Ling.type.caption.copy(fontSize = 12.sp), color = c.onSurfaceVariant)
                    }
                }
        }
    }
    if (serialBoard != null) Box(Modifier.padding(top = 12.dp)) { serialBoard() }
    WorkTabs(tab, header.foreshadowOpen, onTab, Modifier.padding(top = 10.dp))
}

/** 卷头：长按呼出分卷操作（重命名 / 删除）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VolumeHeader(v: com.lingwrite.app.data.Volume, index: Int, onLongPress: (com.lingwrite.app.data.Volume) -> Unit) {
    val c = Ling.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp).enterStagger(2 + index * 3)
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(v) })
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(v.title, style = Ling.type.volumeHeader, color = c.primary, modifier = Modifier.weight(1f))
        Text(v.range, style = Ling.type.caption, color = c.onSurfaceVariant)
    }
}

@Composable
private fun WorkTabs(selected: Int, foreshadow: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val tabs = listOf("章节" to null, "大纲" to null, "设定库" to null, "伏笔" to (if (foreshadow > 0) "$foreshadow" else null), "统计" to null)
    val density = LocalDensity.current
    val centers = remember { mutableStateMapOf<Int, Dp>() }
    Box(modifier.fillMaxWidth().height(46.dp)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(c.outlineVariant))
        Row(Modifier.fillMaxHeight().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
            tabs.forEachIndexed { i, (t, badge) ->
                val on = i == selected
                val col by animateColorAsState(if (on) c.onSurface else c.onSurfaceVariant, tween(Motion.Medium), label = "tab")
                Row(
                    Modifier.clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                        .onGloballyPositioned { with(density) { centers[i] = (it.positionInParent().x + it.size.width / 2f).toDp() } }
                        .padding(bottom = 11.dp),
                ) {
                    Text(t, style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = col)
                    if (badge != null) Text(badge, style = Ling.type.dock.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 1.dp))
                }
            }
        }
        val x by animateDpAsState(((centers[selected] ?: 31.dp) + 20.dp - 11.dp), Motion.bouncy(), label = "tabInd")
        Box(Modifier.align(Alignment.BottomStart).offset(x = x).size(22.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(c.primary))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(ch: Chapter, onLong: () -> Unit, onClick: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).combinedClickable(onLongClick = onLong, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ChapterLabel.short(ch.no), Modifier.width(64.dp), style = Ling.type.label.copy(fontFeatureSettings = "tnum"), color = c.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            if (ch.title.isBlank()) Text("未命名", style = Ling.type.bodyLarge.copy(lineHeight = 21.sp), color = c.onSurfaceVariant, maxLines = 1)
            else Text(ch.title, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ch.state, style = Ling.type.caption.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.onSurfaceVariant)
        }
        if (ch.titleIdeas > 0) Pill("${ch.titleIdeas} 个标题建议", PillKind.Amber)
        if (ch.draft) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c.amber))
        if (ch.aiShare != null) Pill("AI ${ch.aiShare}%", PillKind.Amber, LingIcons.SparkleFilled)
        if (ch.checked) Pill("一致性", PillKind.Good, LingIcons.Check)
        Text(ch.words, Modifier.width(40.dp), style = Ling.type.caption, color = c.onSurfaceVariant, textAlign = TextAlign.End)
    }
}
