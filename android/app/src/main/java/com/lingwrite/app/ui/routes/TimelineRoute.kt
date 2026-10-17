// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling

/** 章节事件轴：设定状态、关系、伏笔、章纲事件按章排列；只读，不加载正文。 */
@Composable
fun TimelineRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    var events by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("all") }
    LaunchedEffect(bookId) {
        events = runCatching { Timeline.build(bookId) }.getOrDefault(emptyList())
        loading = false
    }
    val shown = remember(events, filter) { Timeline.filter(events, filter) }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.timeline_001), book?.title, onBack = { nav.popBackStack() })
        Text(tr(R.string.timeline_009), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "all" to tr(R.string.timeline_002), "person" to tr(R.string.timeline_003), "place" to tr(R.string.timeline_004),
                "item" to tr(R.string.timeline_005), "power" to tr(R.string.timeline_006), "foreshadow" to tr(R.string.timeline_007), "plan" to tr(R.string.timeline_008),
            ).forEach { (k, label) -> LingChip(filter == k, { filter = k }, label) }
        }
        if (loading) Text("…", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        else if (shown.isEmpty()) Text(tr(R.string.timeline_010), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            itemsIndexed(shown) { i, e ->
                val prev = shown.getOrNull(i - 1)
                if (prev == null || prev.chapter != e.chapter) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ChapterLabel.short(e.chapter), style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary)
                        if (e.storyTime.isNotBlank()) Text(" · " + e.storyTime, style = Ling.type.caption, color = c.onSurfaceVariant)
                    }
                }
                LingCard(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().rowClick {
                        if (e.cardId != null) nav.navigate(Routes.lore(bookId, e.cardId))
                        else if (e.chapterId != null) nav.navigate(Routes.editor(e.chapterId))
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(kindLabel(e.kind), PillKind.Neutral)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = Ling.type.body, color = c.onSurface, maxLines = 2)
                            if (e.detail.isNotBlank()) Text(e.detail, style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "lore" -> tr(R.string.timeline_014)
    "relation" -> tr(R.string.timeline_015)
    "foreshadow" -> tr(R.string.timeline_012)
    "payoff" -> tr(R.string.timeline_013)
    "plan" -> tr(R.string.timeline_016)
    else -> kind
}
