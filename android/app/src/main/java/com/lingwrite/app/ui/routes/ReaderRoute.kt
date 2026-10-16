// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import com.lingwrite.app.ui.components.rowClick
import androidx.compose.foundation.border
import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.READER_PERSONAS
import com.lingwrite.app.data.ReaderFeedback
import com.lingwrite.app.data.ReaderPersona
import com.lingwrite.app.data.ReaderPrompts
import com.lingwrite.app.data.ReaderReport
import com.lingwrite.app.data.TimeFmt
import com.lingwrite.app.data.db.ReaderReportEntity
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A16 AI 读者反馈（ai-features §16）：模拟读者试读，给追读意愿和弃书点。
 * 不评判 AI 率，也不做“降 AI 检测”；结果标注“模拟读者反馈，仅供参考”。
 */
@Composable
fun ReaderRoute(bookId: Long, startChapter: Int, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val personas = remember { mutableStateListOf<String>() }
    var customDesc by remember { mutableStateOf("") }
    var scopeCh by remember { mutableStateOf("${startChapter.coerceAtLeast(1)}") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<Pair<ReaderPersona, Result<ReaderReport>>>() }
    val history by remember { AppGraph.db.readerReports().ofBook(bookId) }.collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    var estTokens by remember { mutableStateOf(0) }
    var ranFrom by remember { mutableStateOf(startChapter) }
    var ranTo by remember { mutableStateOf(startChapter) }
    var retryingId by remember { mutableStateOf<String?>(null) }

    // A16：单个画像失败可以单独重试，不影响其它画像
    fun retryPersona(p: ReaderPersona, from: Int, to: Int) {
        scope.launch {
            val idx = results.indexOfFirst { it.first.id == p.id }
            if (idx < 0) return@launch
            retryingId = p.id
            results[idx] = p to ReaderFeedback.run(bookId, p, customDesc, from, to, text)
            retryingId = null
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.editor_034), tr(R.string.reader_001), onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (results.isEmpty()) {
                Text(tr(R.string.reader_002), style = Ling.type.label, color = c.primary)
                READER_PERSONAS.forEachIndexed { pi, p ->
                    val on = p.id in personas
                    ReaderPersonaRow(p, on, Modifier.enterStagger(pi)) {
                        if (on) personas.remove(p.id) else if (personas.size < 3) personas.add(p.id)
                    }
                }
                if ("custom" in personas) {
                    LingTextField(customDesc, { customDesc = it }, label = tr(R.string.reader_003), singleLine = false, minLines = 2, placeholder = tr(R.string.reader_004))
                }
                Text(tr(R.string.reader_005), style = Ling.type.label, color = c.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LingTextField(scopeCh, { scopeCh = it.filter(Char::isDigit) }, Modifier.weight(1f), label = tr(R.string.reader_006))
                    Text(tr(R.string.reader_007), style = Ling.type.body, color = c.onSurfaceVariant)
                }
                Text(
                    if (estTokens > 0) tr(R.string.reader_008, estTokens) else tr(R.string.reader_009),
                    style = Ling.type.caption, color = c.onSurfaceVariant,
                )
                err?.let { Text(tr(R.string.ai_components_011, it), style = Ling.type.caption, color = c.error) }
                LingButton(
                    if (busy) tr(R.string.reader_010) else tr(R.string.reader_011), height = 46.dp,
                    enabled = personas.isNotEmpty() && !busy,
                ) {
                    busy = true; err = null; results.clear()
                    scope.launch {
                        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
                        val from = scopeCh.toIntOrNull() ?: startChapter
                        val to = startChapter.coerceAtLeast(from)
                        val range = chs.filter { it.number in from..to }.take(5)
                        text = range.joinToString("\n\n") { it.content }
                        ranFrom = from; ranTo = to
                        estTokens = ReaderPrompts.estTokens(text, personas.size)
                        val jobs = personas.map { pid ->
                            async {
                                val p = READER_PERSONAS.first { it.id == pid }
                                p to ReaderFeedback.run(bookId, p, customDesc, from, to, text)
                            }
                        }
                        jobs.awaitAll().forEach { results.add(it) }
                        if (results.all { it.second.isFailure }) err = results.firstOrNull()?.second?.exceptionOrNull()?.message
                        busy = false
                    }
                }
                // MO2：等模型返回时先给骨架（150ms 阈值），避免空白停顿
                if (busy && com.lingwrite.app.ui.components.rememberDelayedVisible(true)) {
                    com.lingwrite.app.ui.ai.AiThinking(tr(R.string.reader_012))
                    repeat(personas.size.coerceAtLeast(1)) { com.lingwrite.app.ui.components.SkeletonCard(4) }
                }
            } else {
                // 结果卡依次出现；页脚标注模拟反馈
                results.forEachIndexed { ri, (p, res) ->
                    ReaderResultCard(p, res, modifier = Modifier.enterStagger(ri),
                        retrying = retryingId == p.id,
                        onRetry = { retryPersona(p, ranFrom, ranTo) },
                        onSave = { advice ->
                            scope.launch {
                                val ch = AppGraph.db.chapters().chaptersOnce(bookId).firstOrNull { it.number == scopeCh.toIntOrNull() } ?: AppGraph.db.chapters().chaptersOnce(bookId).last()
                                AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = ch.id, kind = "fix", text = tr(R.string.reader_013, p, advice)))
                            }
                        },
                        onJump = { quote ->
                            scope.launch {
                                val ch = AppGraph.db.chapters().chaptersOnce(bookId).firstOrNull { it.content.contains(quote) }
                                ch?.let { nav.navigate(com.lingwrite.app.ui.Routes.editor(it.id)) }
                            }
                        },
                    )
                }
                Text(
                    tr(R.string.reader_014, estTokens),
                    style = Ling.type.caption, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                LingButton(tr(R.string.reader_015), BtnKind.Text, height = 40.dp) { results.clear(); estTokens = 0 }
            }
        }
    }
}

/** 单个画像的结果卡：星级 + 一句话 + 弃书点 + 爽点/期待 + 想看的下一章 + 建议。 */
@Composable
fun ReaderResultCard(
    p: ReaderPersona,
    res: Result<ReaderReport>,
    onSave: (String) -> Unit,
    onJump: (String) -> Unit,
    retrying: Boolean = false,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Ling.colors
    LingCard(Modifier.fillMaxWidth().then(modifier)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = Ling.type.subhead, color = c.onSurface, modifier = Modifier.weight(1f))
                Text(res.getOrNull()?.starsLabel ?: "…", style = Ling.type.titleSmall.copy(fontSize = 15.sp), color = c.amber)
            }
            val r = res.getOrNull()
            if (r == null) {
                Text(tr(R.string.reader_017, res.exceptionOrNull()?.message ?: tr(R.string.reader_016)), style = Ling.type.caption, color = c.error)
                LingButton(if (retrying) tr(R.string.reader_018) else tr(R.string.ai_components_014), BtnKind.Text, height = 32.dp, enabled = !retrying, onClick = onRetry)
            } else {
                if (r.oneLine.isNotBlank()) Text("“${r.oneLine}”", style = Ling.type.bodyLarge, color = c.onSurface)
                if (r.drops.isNotEmpty()) {
                    Text(tr(R.string.reader_019), style = Ling.type.label, color = c.error)
                    r.drops.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            Pill(d.quote.take(12) + "…", PillKind.Error, modifier = Modifier.padding(end = 8.dp))
                            Text(d.why, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                        LingButton(tr(R.string.reader_020), BtnKind.Text, height = 30.dp) { onJump(d.quote) }
                    }
                }
                if (r.hooks.isNotEmpty()) {
                    Text(tr(R.string.reader_021), style = Ling.type.label, color = c.primary)
                    r.hooks.forEach { Text("· $it", style = Ling.type.caption, color = c.onSurfaceVariant) }
                }
                if (r.wantNext.isNotBlank()) {
                    Text(tr(R.string.reader_022), style = Ling.type.label, color = c.primary)
                    Text(r.wantNext, style = Ling.type.body, color = c.onSurface)
                }
                if (r.advice.isNotEmpty()) {
                    Text(tr(R.string.reader_023), style = Ling.type.label, color = c.primary)
                    r.advice.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("· $a", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                            LingButton(tr(R.string.editor_ai_008), BtnKind.Text, height = 30.dp) { onSave(a) }
                        }
                    }
                }
                // A16：每张卡都写明是模拟反馈，不评判 AI 率
                Text(tr(R.string.reader_024), style = Ling.type.micro, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 历史报告列表（统计 tab）：同章改稿前后星级对比。 */
@Composable
fun ReaderHistoryList(bookId: Long, onOpen: (ReaderReportEntity) -> Unit) {
    val reports by remember { AppGraph.db.readerReports().ofBook(bookId) }.collectAsState(emptyList())
    if (reports.isEmpty()) return
    LingCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.reader_025), style = Ling.type.label, color = Ling.colors.primary)
            val byKey = reports.groupBy { "${it.fromChapter}-${it.toChapter}-${it.persona}" }
            byKey.forEach { (_, list) ->
                val latest = list.first()
                val r = ReaderFeedback.parse(latest.result)
                val prev = list.getOrNull(1)?.let { ReaderFeedback.parse(it.result) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(latest.persona.let { pid -> READER_PERSONAS.firstOrNull { it.id == pid }?.name ?: pid }, PillKind.Neutral)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(R.string.reader_026, latest.fromChapter, latest.toChapter), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (r != null && prev != null && r.stars != prev.stars) {
                        val up = r.stars > prev.stars
                        Text((if (up) "↑ " else "↓ ") + prev.stars + "→" + r.stars, style = Ling.type.caption, color = if (up) Ling.colors.good else Ling.colors.error)
                    } else if (r != null) {
                        Text(r.starsLabel, style = Ling.type.caption, color = Ling.colors.amber)
                    }
                    Text(TimeFmt.relative(latest.createdAt), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(tr(R.string.reader_027), style = Ling.type.micro, color = Ling.colors.onSurfaceVariant)
        }
    }
}


/** 读者画像选择行：选中态为浅主色底 + 1dp 主色描边（与 ToneCard 一致），整卡可点；不再额外放「已选」胶囊。 */
@Composable
fun ReaderPersonaRow(p: ReaderPersona, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val c = Ling.colors
    val selShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    LingCard(
        modifier.fillMaxWidth()
            .border(1.dp, if (on) c.primary.copy(alpha = .35f) else androidx.compose.ui.graphics.Color.Transparent, selShape),
        shape = selShape,
        color = if (on) androidx.compose.ui.graphics.lerp(c.surface, c.primary, if (c.isDark) .10f else .06f) else c.surface,
    ) {
        Row(Modifier.fillMaxWidth().rowClick(onToggle).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(on, { onToggle() })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(p.name, style = Ling.type.bodyLarge, color = c.onSurface)
                Text(p.desc, style = Ling.type.caption, color = c.onSurfaceVariant)
            }
        }
    }
}
