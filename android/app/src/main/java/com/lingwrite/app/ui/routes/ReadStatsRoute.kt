// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ReadMetricEntity
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

/** 作者手动/粘贴录入平台追读数据，本地看曲线和可疑掉点；不抓取后台。 */
@Composable
fun ReadStatsRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    val headers by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val metrics by remember(bookId) { AppGraph.db.readStats().rows(bookId) }.collectAsState(emptyList())
    val plans by remember(bookId) { AppGraph.db.plan().chapters(bookId) }.collectAsState(emptyList())
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }
    var addChapter by remember { mutableStateOf("") }
    var addReads by remember { mutableStateOf("") }
    var addCompletion by remember { mutableStateOf("") }
    var addFavorites by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val latest = remember(metrics) { ReadStats.latest(metrics) }
    val chapterNumbers = remember(headers) { headers.associate { it.id to it.number } }
    val words = remember(headers) { headers.associate { it.number to it.wordCount } }
    val planMap = remember(plans) { plans.associateBy { it.chapterId } }
    val avgWords = remember(headers) { if (headers.isEmpty()) 0 else headers.sumOf { it.wordCount } / headers.size }
    val insights = remember(metrics, plans, headers) { ReadStats.insights(metrics, planMap, chapterNumbers, words, avgWords) }
    val byChapter = remember(insights) { insights.associateBy { it.chapter } }
    val biggestDrop = insights.maxByOrNull { it.dropPercent }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.read_001), book?.title, onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LingCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (latest.isEmpty()) {
                            Text(tr(R.string.read_011), style = Ling.type.body, color = c.onSurfaceVariant)
                        } else {
                            Text(tr(R.string.read_015), style = Ling.type.label, color = c.primary)
                            CompletionChart(latest, Modifier.fillMaxWidth().height(130.dp))
                            val last = latest.last()
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("${ChapterLabel.short(last.chapterNumber)} · ${last.completion}%", style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (biggestDrop != null && biggestDrop.dropPercent > 0) Text(tr(R.string.read_016, biggestDrop.dropPercent), style = Ling.type.caption, color = c.error)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LingButton(tr(R.string.read_002), BtnKind.Tonal, height = 38.dp) { importOpen = true; importText = ""; error = null }
                            LingButton(tr(R.string.read_003), BtnKind.Text, height = 38.dp) { addOpen = true; error = null }
                        }
                    }
                }
            }
            if (latest.isNotEmpty()) item { SectionLabel(tr(R.string.read_001)) }
            items(latest) { m ->
                val ins = byChapter[m.chapterNumber]
                LingCard {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ChapterLabel.short(m.chapterNumber), style = Ling.type.body, color = c.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (m.reads >= 0) Text("${tr(R.string.read_005)} ${m.reads}", style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (m.completion >= 0) Text("${tr(R.string.read_006)} ${m.completion}%", style = Ling.type.caption, color = c.onSurfaceVariant)
                                if (m.favorites >= 0) Text("${tr(R.string.read_007)} ${m.favorites}", style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (ins != null && ins.dropPercent >= 10) Pill(tr(R.string.read_016, ins.dropPercent), PillKind.Error)
                            else if (ins != null && ins.belowMedian) Pill(tr(R.string.read_017), PillKind.Amber)
                            if (ins != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (ins.noCliff) Pill(tr(R.string.read_018), PillKind.Neutral)
                                if (ins.shortChapter) Pill(tr(R.string.read_019), PillKind.Neutral)
                            }
                        }
                    }
                }
            }
            item {
                error?.let { Text(it, style = Ling.type.body, color = c.error, modifier = Modifier.padding(8.dp)) }
                msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(8.dp)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (importOpen) LingSheet({ importOpen = false }, tr(R.string.read_002)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.read_012), style = Ling.type.caption, color = c.onSurfaceVariant)
            LingTextField(importText, { importText = it }, label = tr(R.string.read_002), singleLine = false, minLines = 6, maxLines = 12)
            LingButton(tr(R.string.read_009), enabled = importText.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 48.dp) {
                runCatching {
                    val rows = ReadStats.parseTable(importText)
                    if (rows.isEmpty()) error("没有识别到数据")
                    scope.launch {
                        rows.forEach { r -> AppGraph.db.readStats().insert(ReadMetricEntity(bookId = bookId, chapterNumber = r.chapter, platform = r.platform, reads = r.reads, completion = r.completion, favorites = r.favorites, comments = r.comments)) }
                        importOpen = false
                        msg = tr(R.string.read_014, rows.size)
                    }
                }.onFailure { error = tr(R.string.read_013, it.message ?: "") }
            }
        }
    }
    if (addOpen) LingSheet({ addOpen = false }, tr(R.string.read_003)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingTextField(addChapter, { addChapter = it.filter(Char::isDigit) }, label = tr(R.string.read_004), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingTextField(addReads, { addReads = it.filter(Char::isDigit) }, label = tr(R.string.read_005), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingTextField(addCompletion, { addCompletion = it.filter(Char::isDigit) }, label = tr(R.string.read_020), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingTextField(addFavorites, { addFavorites = it.filter(Char::isDigit) }, label = tr(R.string.read_007), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingButton(tr(R.string.read_010), enabled = addChapter.isNotBlank() && addCompletion.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 48.dp) {
                scope.launch {
                    AppGraph.db.readStats().insert(ReadMetricEntity(
                        bookId = bookId, chapterNumber = addChapter.toIntOrNull() ?: 0,
                        reads = addReads.toIntOrNull() ?: -1, completion = addCompletion.toIntOrNull()?.coerceIn(0, 100) ?: -1,
                        favorites = addFavorites.toIntOrNull() ?: -1,
                    ))
                    addOpen = false
                    msg = tr(R.string.read_014, 1)
                }
            }
        }
    }
}

@Composable
private fun CompletionChart(rows: List<ReadMetricEntity>, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val pts = rows.filter { it.completion >= 0 }
    Canvas(modifier) {
        if (pts.size < 2) return@Canvas
        val pad = 12.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val path = Path()
        pts.forEachIndexed { i, m ->
            val x = pad + w * i / (pts.size - 1)
            val y = pad + h * (1f - m.completion / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(c.primary, radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
        drawPath(path, c.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}
