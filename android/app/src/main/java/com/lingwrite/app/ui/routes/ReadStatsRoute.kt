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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.lingwrite.app.ui.theme.Motion
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
            item(key = "chart") {
                LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
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
            if (latest.isNotEmpty()) item(key = "rowsHeader") { SectionLabel(tr(R.string.read_001), Modifier.enterStagger(1)) }
            items(latest, key = { "m" + it.chapterNumber }) { m ->
                val ins = byChapter[m.chapterNumber]
                LingCard(Modifier.fillMaxWidth().enterStagger(2).animateItem()) {
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
            item(key = "foot") {
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
                    if (rows.isEmpty()) error(tr(R.string.read_021))
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

/**
 * 完读率曲线：0 / 50 / 100 三条参考线 + 刻度；数据变化时路径从左向右重新生长
 * （motion §13，Long · EmphasizedDecelerate；减弱动效时直接画完）。
 */
@Composable
private fun CompletionChart(rows: List<ReadMetricEntity>, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val pts = rows.filter { it.completion >= 0 }
    val reduced = Motion.reduced()
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(pts.map { it.chapterNumber to it.completion }) {
        if (reduced) grow.snapTo(1f) else { grow.snapTo(0f); grow.animateTo(1f, tween(Motion.Long * 2, easing = Motion.EmphasizedDecelerate)) }
    }
    val measurer = rememberTextMeasurer()
    val tick = Ling.type.caption.copy(fontSize = 10.sp, color = c.onSurfaceVariant)
    val grid = c.outlineVariant
    Canvas(modifier.semantics { contentDescription = pts.joinToString { "${ChapterLabel.short(it.chapterNumber)} ${it.completion}%" } }) {
        val left = 28.dp.toPx(); val pad = 8.dp.toPx()
        val w = size.width - left - pad
        val h = size.height - pad * 2
        listOf(0, 50, 100).forEach { v ->
            val y = pad + h * (1f - v / 100f)
            drawLine(grid, Offset(left, y), Offset(size.width - pad, y), strokeWidth = 1.dp.toPx(),
                pathEffect = if (v == 50) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null)
            val t = measurer.measure("$v", tick)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
        }
        if (pts.isEmpty()) return@Canvas
        val xs = pts.indices.map { i -> if (pts.size == 1) left + w / 2 else left + w * i / (pts.size - 1) }
        val ys = pts.map { pad + h * (1f - it.completion / 100f) }
        val path = Path()
        xs.indices.forEach { i -> if (i == 0) path.moveTo(xs[i], ys[i]) else path.lineTo(xs[i], ys[i]) }
        val p = grow.value
        // 只画到当前进度对应的 x，点在曲线经过后再出现。
        clipRect(right = left + (w + pad) * p) {
            drawPath(path, c.primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        xs.indices.forEach { i ->
            val shown = xs[i] <= left + (w + pad) * p
            if (shown) drawCircle(c.primary, radius = 2.5.dp.toPx(), center = Offset(xs[i], ys[i]))
        }
    }
}
