// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.CallLogEntity
import com.lingwrite.app.data.db.WordDayEntity
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Space
import java.time.LocalDate

object StatsModule {
    fun install() { WorkTabs.stats = { bookId -> BookStatsTab(bookId) } }
}

@Composable
fun StatsRoute(nav: NavHostController) {
    var period by remember { mutableStateOf(Period.Month) }
    var offset by remember { mutableIntStateOf(0) }
    val range = remember(period, offset) { periodRange(period, offset) }
    val logs by remember(range) { AppGraph.db.stats().logsBetween(range.from, range.to) }.collectAsState(emptyList())
    val providers by remember { AppGraph.ai.providers() }.collectAsState(emptyList())
    val since = remember { TimeFmt.day(System.currentTimeMillis() - 29L * 86_400_000) }
    val words by remember { AppGraph.db.stats().wordDays(since) }.collectAsState(emptyList())
    var detail by remember { mutableStateOf<Pair<String, List<CallLogEntity>>?>(null) }
    val data = remember(logs, providers, period) { aggregate(logs, period, range, providers) }
    StatsScreen(period, trText(range.label), data, words, onPeriod = { period = it; offset = 0 }, onShift = { offset = (offset + it).coerceAtMost(0) }, canNext = offset < 0,
        onPrices = { nav.navigate("models") },
        onGroup = { byModel, g -> detail = trText(g.label) to logs.filter { if (byModel) it.provider + "|" + it.model == g.key else it.task == g.key } })
    detail?.let { (t, l) ->
        LingSheet({ detail = null }, t) {
            Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                l.sortedByDescending { it.time }.take(200).forEach { e ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${taskName(e.task)} · ${e.model}", style = Ling.type.body, color = Ling.colors.onSurface)
                            Text(TimeFmt.clock(e.time) + (if (e.scope.isNotBlank()) " · ${e.scope}" else ""), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                        }
                        Text("${tokensLabel(e.inputTokens.toLong())} / ${tokensLabel(e.outputTokens.toLong())}" + if (e.estimated) tr(R.string.stats_001) else "", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun StatsScreen(period: Period, label: String, d: StatsData, words: List<WordDayEntity>, onPeriod: (Period) -> Unit = {}, onShift: (Int) -> Unit = {}, canNext: Boolean = false,
                onPrices: () -> Unit = {}, onGroup: (Boolean, Group) -> Unit = { _, _ -> }) {
    val c = Ling.colors
    var tokensMode by remember { mutableStateOf(false) }
    var byModel by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = Space.dockClearance)) {
        Text(tr(R.string.dock_002), style = Ling.type.headline, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 10.dp).enterStagger(0))
        WritingCard(words, Modifier.padding(horizontal = 16.dp).enterStagger(1))
        SectionLabel(tr(R.string.stats_002))
        Segmented(Period.entries.map { p -> when (p) { Period.Day -> tr(R.string.stats_029); Period.Week -> tr(R.string.stats_030); Period.Month -> tr(R.string.stats_031) } }, period.ordinal, Modifier.padding(horizontal = 16.dp)) { onPeriod(Period.entries[it]) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.rotateIcon(90f)) { LingIconButton(LingIcons.ExpandMore, tr(R.string.stats_003), c.onSurfaceVariant) { onShift(-1) } }
            Text(label, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.rotateIcon(-90f)) { LingIconButton(LingIcons.ExpandMore, tr(R.string.stats_004), if (canNext) c.onSurfaceVariant else c.outlineVariant) { if (canNext) onShift(1) } }
        }
        if (d.calls == 0) {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Tile(LingIcons.Stats, c.surfaceContainer, c.onSurfaceVariant, 56.dp)
                Text(tr(R.string.stats_005), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            LingCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Metric(tr(R.string.stats_006), Words.grouped(d.calls), Modifier.weight(1f)); Metric(tr(R.string.stats_007), tokensLabel(d.inTok), Modifier.weight(1f)); Metric(tr(R.string.stats_008), tokensLabel(d.outTok), Modifier.weight(1f))
                    }
                    InsetDivider(0.dp)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text(d.cost?.let { "¥ %.2f".format(it) } ?: "—", style = Ling.type.headline.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.onSurface)
                        Text(tr(R.string.stats_009), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 5.dp))
                    }
                    Text(tr(R.string.stats_010), style = Ling.type.caption, color = c.onSurfaceVariant)
                    if (d.unpriced.isNotEmpty()) Text(tr(R.string.stats_012, d.unpriced.take(3).joinToString(tr(R.string.data_043)), if (d.unpriced.size > 3) tr(R.string.stats_011) else ""), style = Ling.type.caption, color = c.primary, modifier = Modifier.padding(top = 4.dp).rowClick(onPrices))
                }
            }
            LingCard(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (period == Period.Day) tr(R.string.stats_013) else tr(R.string.stats_014), style = Ling.type.label, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Segmented(listOf(tr(R.string.stats_015), "tokens"), if (tokensMode) 1 else 0, Modifier.width(150.dp)) { tokensMode = it == 1 }
                    }
                    Bars(d.buckets.map { if (tokensMode) it.tokens.toFloat() else it.calls.toFloat() }, d.buckets.map { trText(it.label) }, Modifier.padding(top = 12.dp).fillMaxWidth().height(140.dp))
                }
            }
            Segmented(listOf(tr(R.string.stats_016), tr(R.string.stats_017)), if (byModel) 0 else 1, Modifier.padding(horizontal = 16.dp)) { byModel = it == 0 }
            val groups = if (byModel) d.byModel else d.byTask
            val max = groups.maxOfOrNull { it.calls }?.coerceAtLeast(1) ?: 1
            LingCard(Modifier.padding(16.dp)) {
                groups.forEachIndexed { i, g ->
                    if (i > 0) InsetDivider(16.dp)
                    Column(Modifier.fillMaxWidth().rowClick { onGroup(byModel, g) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(AvatarColors[i % AvatarColors.size]))
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(trText(g.label), style = Ling.type.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = c.onSurface)
                                Text(tr(R.string.stats_018, g.sub, tokensLabel(g.inTok), tokensLabel(g.outTok)), style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(tr(R.string.stats_019, g.calls), style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                                Text(g.cost?.let { "¥ %.2f".format(it) } ?: tr(R.string.stats_020), style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                        }
                        Box(Modifier.padding(top = 8.dp, start = 20.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceContainer)) {
                            Box(Modifier.fillMaxWidth(g.calls / max.toFloat()).fillMaxHeight().background(AvatarColors[i % AvatarColors.size].copy(alpha = .8f)))
                        }
                    }
                }
            }
        }
        Text(tr(R.string.stats_021), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun Modifier.rotateIcon(d: Float) = this.rotate(d)

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(bottom = 12.dp)) {
        Text(value, style = Ling.type.titleSmall.copy(fontSize = 19.sp), color = Ling.colors.onSurface)
        Text(label, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
    }
}

@Composable
fun Bars(values: List<Float>, labels: List<String>, modifier: Modifier, color: Color = Ling.colors.primary) {
    val c = Ling.colors
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(Motion.Long * 2, easing = Motion.EmphasizedDecelerate)) }
    val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = Ling.type.micro.copy(color = c.onSurfaceVariant)
    Canvas(modifier) {
        val n = values.size.coerceAtLeast(1)
        val gap = size.width / n
        val bw = (gap * 0.62f).coerceAtMost(18.dp.toPx())
        val bottom = size.height - 16.dp.toPx()
        values.forEachIndexed { i, v ->
            val h = (v / max) * bottom * grow.value
            val x = gap * i + (gap - bw) / 2
            drawRoundRect(c.surfaceContainer, Offset(x, 0f), Size(bw, bottom), CornerRadius(bw / 3))
            if (v > 0) drawRoundRect(color, Offset(x, bottom - h), Size(bw, h), CornerRadius(bw / 3))
            val step = when { n > 24 -> 5; n > 12 -> 3; else -> 1 }
            if (i % step == 0) {
                val t = measurer.measure(labels.getOrElse(i) { "" }, style)
                drawText(t, topLeft = Offset(x + bw / 2 - t.size.width / 2, size.height - t.size.height))
            }
        }
    }
}

@Composable
fun WritingCard(words: List<WordDayEntity>, modifier: Modifier) {
    val c = Ling.colors
    val today = LocalDate.now()
    val byDay = words.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.words } }
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { (byDay[it.toString()] ?: 0).toFloat() }
    var streak = 0
    for (d in generateSequence(today) { it.minusDays(1) }) { if ((byDay[d.toString()] ?: 0) > 0) streak++ else if (d != today) break; if (streak > 400) break }
    LingCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Words.grouped(byDay[today.toString()] ?: 0), style = Ling.type.headline.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.onSurface)
                Text(tr(R.string.serial_board_card_003), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 5.dp).weight(1f))
                if (streak > 0) Pill(tr(R.string.stats_022, streak), PillKind.Good)
            }
            Text(tr(R.string.stats_023, Words.grouped(byDay.values.sum())), style = Ling.type.caption, color = c.onSurfaceVariant)
            Bars(values, days.map { "${it.dayOfMonth}" }, Modifier.padding(top = 12.dp).fillMaxWidth().height(96.dp), c.amber)
        }
    }
}

@Composable
fun BookStatsTab(bookId: Long) {
    val c = Ling.colors
    val words by remember(bookId) { AppGraph.db.stats().bookWordDays(bookId) }.collectAsState(emptyList())
    val logs by remember(bookId) { AppGraph.db.stats().bookLogs(bookId) }.collectAsState(emptyList())
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    Column(Modifier.padding(vertical = 12.dp)) {
        WritingCard(words, Modifier.padding(horizontal = 16.dp).enterStagger(1))
        // A16 读者反馈历史（同章改稿前后星级对比）
        com.lingwrite.app.ui.routes.ReaderHistoryList(bookId) {}
        SectionLabel(tr(R.string.stats_024))
        LingCard(Modifier.padding(horizontal = 16.dp)) {
            val sorted = chs.sortedBy { it.number }.takeLast(30)
            Column(Modifier.padding(16.dp)) {
                Text(tr(R.string.stats_025, Words.grouped(if (chs.isEmpty()) 0 else chs.sumOf { it.wordCount } / chs.size)), style = Ling.type.caption, color = c.onSurfaceVariant)
                Bars(sorted.map { it.wordCount.toFloat() }, sorted.map { "${it.number}" }, Modifier.padding(top = 12.dp).fillMaxWidth().height(110.dp))
            }
        }
        SectionLabel(tr(R.string.stats_026))
        LingCard(Modifier.padding(horizontal = 16.dp)) {
            val g = logs.groupBy { it.task }.entries.sortedByDescending { it.value.size }
            if (g.isEmpty()) Text(tr(R.string.stats_005), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            g.forEachIndexed { i, (t, l) ->
                if (i > 0) InsetDivider(16.dp)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(taskName(t), style = Ling.type.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f))
                    val adopt = l.count { it.outcome == "accepted" || it.outcome == "edited" || it.outcome == "noted" }
                    Text(tr(R.string.stats_027, l.size, adopt) + if (l.any { it.candidates > 1 }) tr(R.string.stats_028, if (l.isEmpty()) 0 else l.count { it.chosen >= 0 } * 100 / l.count { it.candidates > 1 }) else "", style = Ling.type.caption, color = c.onSurfaceVariant)
                }
            }
        }
    }
}
