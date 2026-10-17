// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.delay

/** 编辑器内的「速查」：设定、伏笔、前情、搜索；只读，不写正文。 */
@Composable
fun EditorQuickRef(
    bookId: Long,
    chapterId: Long,
    chapterNumber: Int,
    text: String,
    onDismiss: () -> Unit,
    onOpenHit: (Hit) -> Unit,
) {
    val c = Ling.colors
    var tab by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<List<QuickRef.CardHit>>(emptyList()) }
    var foreshadows by remember { mutableStateOf<List<com.lingwrite.app.data.db.ForeshadowEntity>>(emptyList()) }
    var summaries by remember { mutableStateOf<List<QuickRef.SummaryLine>>(emptyList()) }
    var volumeSummary by remember { mutableStateOf<com.lingwrite.app.data.db.VolumeSummaryEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, chapterId) {
        cards = runCatching { QuickRef.cards(bookId, chapterId, chapterNumber, text) }.getOrDefault(emptyList())
        foreshadows = runCatching { QuickRef.foreshadows(bookId, chapterNumber, text) }.getOrDefault(emptyList())
        val pair = runCatching { QuickRef.summaries(bookId, chapterId, chapterNumber) }.getOrNull()
        summaries = pair?.first.orEmpty()
        volumeSummary = pair?.second
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        hits = runCatching { QuickRef.search(bookId, query) }.getOrDefault(emptyList())
        searching = false
    }

    LingSheet(onDismiss, tr(R.string.quick_001), layout = SheetLayout.Viewport) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr(R.string.quick_002), tr(R.string.quick_003), tr(R.string.quick_004), tr(R.string.quick_005)).forEachIndexed { i, label ->
                LingChip(tab == i, { tab = i }, label)
            }
        }
        when (tab) {
            0 -> if (cards.isEmpty()) Empty(tr(R.string.quick_007)) else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 20.dp)) {
                items(cards) { hit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hit.card.name, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Pill(LoreType.of(hit.card.type).label, PillKind.Neutral)
                        }
                        if (hit.fields.isNotEmpty()) {
                            Text(
                                hit.fields.joinToString(" · ") { "${it.k}：${it.v}" },
                                style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            1 -> if (foreshadows.isEmpty()) Empty(tr(R.string.quick_007)) else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 20.dp)) {
                items(foreshadows) { f ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(f.content, style = Ling.type.body, color = c.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (f.payoff != null) tr(R.string.quick_013, f.plantNumber, f.payoff!!) else tr(R.string.quick_014),
                            style = Ling.type.caption, color = c.onSurfaceVariant,
                        )
                    }
                }
            }
            2 -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 20.dp)) {
                volumeSummary?.text?.takeIf { it.isNotBlank() }?.let { v ->
                    item {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(tr(R.string.quick_009), style = Ling.type.label, color = c.primary)
                            Text(v, style = Ling.type.body, color = c.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (summaries.isEmpty()) item { Empty(tr(R.string.quick_008)) }
                items(summaries) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(ChapterLabel.short(s.number), style = Ling.type.caption, color = c.primary)
                        Text(s.text, style = Ling.type.body, color = c.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                LingTextField(query, { query = it }, label = tr(R.string.quick_005), placeholder = tr(R.string.quick_006))
                if (searching) Text(tr(R.string.quick_010), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                else if (query.isNotBlank() && hits.isEmpty()) Text(tr(R.string.quick_011), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(hits) { hit ->
                        Column(Modifier.fillMaxWidth().rowClick { onOpenHit(hit) }.padding(vertical = 10.dp)) {
                            Text(ChapterLabel.full(hit.number, ""), style = Ling.type.caption, color = c.primary)
                            Text(hit.text, style = Ling.type.body, color = c.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
    }
}
