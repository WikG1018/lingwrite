// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.AiService
import com.lingwrite.app.data.ai.PromptHooks
import com.lingwrite.app.data.db.Hit
import com.lingwrite.app.data.db.SummaryEntity
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val contextBudgets = listOf(8_000, 16_000, 24_000, 48_000, 96_000, 128_000, 200_000, 256_000, 512_000, 1_000_000)

private fun budgetAmount(n: Int) = if (n >= 1_000_000 && n % 1_000_000 == 0) "${n / 1_000_000}M" else "${n / 1000}K"

private fun budgetLabel(n: Int) = "${budgetAmount(n)} tokens"

private fun budgetShort(n: Int) = budgetAmount(n)

object ContextModule {
    var viewer by mutableStateOf<Long?>(null)
    var summaryFor by mutableStateOf<Long?>(null)

    fun install() {
        PromptHooks.context = { bookId, chapterId, cursor, text -> ContextEngine.build(bookId, chapterId, cursor, text) }
        val prevDone = EditorHooks.onChapterDone
        EditorHooks.onChapterDone = { id ->
            prevDone(id)
            AppGraph.scope.launch {
                if (AppGraph.prefs.flow.first().autoCheck) runCatching { ContextEngine.afterChapterDone(id) }
                else AppGraph.db.chapters().get(id)?.let { ContextEngine.reindex(it) }
            }
        }
        val prevMore = EditorHooks.moreItems
        EditorHooks.moreItems = { st, close, nav ->
            prevMore(this, st, close, nav)
            SheetAction(LingIcons.Book, "本章摘要", sub = "完成本章后自动生成，可改后确认") { summaryFor = st.chapter.id; close() }
            SheetAction(LingIcons.Info, "上下文查看器", sub = "最近一次实际发给模型的内容和占比") { viewer = st.chapter.id; close() }
        }
        val prevOv = EditorHooks.overlays
        EditorHooks.overlays = { st, nav ->
            prevOv(st, nav)
            if (viewer == st.chapter.id) ContextViewerSheet { viewer = null }
            if (summaryFor == st.chapter.id) SummarySheet(st.chapter.bookId, st.chapter.id) { summaryFor = null }
        }
        ExtraDestinations.list.add { nav: NavHostController ->
            composable(Routes.Search) { e: androidx.navigation.NavBackStackEntry -> SearchRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
        }
        SettingsRegistry.extra += { p, _ ->
            var open by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            if (open) LingSheet({ open = false }, "上下文预算") {
                Text("每次发给模型的背景资料上限。超出时按优先级从低往高裁剪，章纲和设定始终优先。按所选模型的上下文长度设置。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                contextBudgets.forEach { n ->
                    SheetAction(if (p.contextBudget == n) LingIcons.Check else LingIcons.Bolt, budgetLabel(n) + if (n == 128_000) "（默认）" else "") { scope.launch { AppGraph.prefs.update { it.copy(contextBudget = n) } }; open = false }
                }
            }
            "AI" to SettingRow(LingIcons.Info, "上下文预算", "长篇的前情、设定、旧片段带入上限", budgetShort(p.contextBudget), Tone.Neutral, onClick = { open = true })
        }
        SettingsRegistry.extra += { p, _ ->
            var confirm by remember { mutableStateOf(false) }
            var pick by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            if (confirm) LingConfirm("开启语义检索？", "开启后，正文会切成小段发给你选的服务商的向量接口，用来找“意思相近”的旧片段。这会产生额外费用，正文会离开这台手机。关键词检索不受影响，始终在本机进行。",
                confirm = "开启", onDismiss = { confirm = false }, onConfirm = { confirm = false; pick = true })
            if (pick) EmbedPicker(p.embedModel, { pick = false }) { ref -> scope.launch { AppGraph.prefs.update { it.copy(semanticSearch = true, embedModel = ref) } }; pick = false }
            "AI" to SettingRow(LingIcons.Search, "语义检索", if (p.semanticSearch) "向量模型：${p.embedModel.substringAfter('|')}" else "默认关闭；开启会把正文切片发给服务商",
                switch = p.semanticSearch, tone = Tone.Neutral, onSwitch = { on -> if (on) confirm = true else scope.launch { AppGraph.prefs.update { it.copy(semanticSearch = false) } } })
        }
    }
}

@Composable
private fun EmbedPicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Ling.colors
    val providers by remember { AppGraph.ai.providers() }.collectAsState(emptyList())
    val usable = providers.filter { it.protocol == "openai" && it.keyCipher.isNotBlank() }
    var prov by remember { mutableStateOf(current.substringBefore('|').toLongOrNull() ?: usable.firstOrNull()?.id) }
    var model by remember { mutableStateOf(current.substringAfter('|', "")) }
    LingSheet(onDismiss, "选择向量模型") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (usable.isEmpty()) Text("需要一个 OpenAI 兼容协议、已填 Key 的服务商（Claude 没有向量接口）。", style = Ling.type.body, color = c.onSurfaceVariant)
            usable.forEach { pv ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).rowClick { prov = pv.id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(prov == pv.id, { prov = pv.id })
                    Text(pv.name, style = Ling.type.bodyLarge, color = c.onSurface)
                }
            }
            LingTextField(model, { model = it }, label = "向量模型名", placeholder = "例如 text-embedding-3-small、text-embedding-v3")
            LingButton("开启", enabled = prov != null && model.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) { onPick("$prov|${model.trim()}") }
        }
    }
}

@Composable
fun ContextViewerSheet(onDismiss: () -> Unit) {
    val snap = ContextEngine.last
    LingSheet(onDismiss, "上下文查看器", layout = SheetLayout.Viewport) { ContextViewer(snap) }
}

private val partColors = listOf(0xFF3E5C8A, 0xFF2E7D5B, 0xFFA8691A, 0xFF8A5A7A, 0xFF5F6B7E, 0xFFB0624A, 0xFF4F8A9C, 0xFF9C7A4F, 0xFF6E6E9C)

@Composable
fun ContextViewer(snap: CtxSnapshot?) {
    val c = Ling.colors
    if (snap == null) { Text("还没有发送过。用一次 AI 工具后，这里会显示实际发给模型的全部内容。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp)); return }
    val sysTok = AiService.estimateTokens(snap.system.length)
    val rows = listOf(Triple("系统指令 + 文风卡", sysTok, snap.system)) + snap.parts.map { Triple(it.label, it.tokens, it.text) }
    val total = rows.sumOf { it.second }.coerceAtLeast(1)
    var open by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Text("约 $total tokens · 预算 ${snap.budget + sysTok + 1500}（含回复预留 1500）· ${TimeFmt.relative(snap.at)}", style = Ling.type.caption, color = c.onSurfaceVariant)
        Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            rows.forEachIndexed { i, r -> Box(Modifier.weight(r.second.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(androidx.compose.ui.graphics.Color(partColors[i % partColors.size]))) }
        }
        rows.forEachIndexed { i, (label, tok, text) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).rowClick { open = if (open == i) null else i }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(androidx.compose.ui.graphics.Color(partColors[i % partColors.size])))
                    Text(label, style = Ling.type.bodyLarge.copy(fontSize = 15.sp), color = c.onSurface, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Text("$tok · ${tok * 100 / total}%", style = Ling.type.caption, color = c.onSurfaceVariant)
                }
                Text(text, style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant, maxLines = if (open == i) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SummarySheet(bookId: Long, chapterId: Long, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val s by remember(chapterId) { AppGraph.db.context().summary(chapterId) }.collectAsState(null)
    var text by remember(s?.updatedAt) { mutableStateOf(s?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    LingSheet(onDismiss, "本章摘要", layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when { s == null -> "还没有摘要。完成本章时会自动生成，也可以现在生成或自己写。"; s!!.confirmed -> "已确认：后续写作优先使用，不会被自动覆盖。"; else -> "AI 生成，待确认。" },
                style = Ling.type.caption, color = if (s?.confirmed == true) c.good else c.onSurfaceVariant)
            LingTextField(text, { text = it }, singleLine = false, minLines = 5, maxLines = 12, placeholder = "200 字左右：发生了什么、人物有什么变化、留下了什么悬念")
            if (busy) com.lingwrite.app.ui.ai.AiThinking("正在写本章摘要…")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                LingButton(if (busy) "生成中…" else "AI 生成", BtnKind.Tonal, icon = LingIcons.Sparkle, enabled = !busy, modifier = Modifier.weight(1f)) {
                    busy = true
                    scope.launch {
                        if (s?.confirmed == true) AppGraph.db.context().upsertSummary(s!!.copy(confirmed = false))
                        ContextEngine.afterChapterDone(chapterId); busy = false
                    }
                }
                LingButton("确认", modifier = Modifier.weight(1f), enabled = text.isNotBlank()) {
                    scope.launch { AppGraph.db.context().upsertSummary(SummaryEntity(chapterId, bookId, text.trim(), confirmed = true)); onDismiss() }
                }
            }
        }
    }
}

/** 全文搜索：bookId = 0 搜全部作品。 */
@Composable
fun SearchRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    val books by remember { AppGraph.books.summaries() }.collectAsState(emptyList())
    val titles = remember(hits, bookId) { mutableStateMapOf<Long, String>() }
    LaunchedEffect(bookId) { if (bookId > 0) ContextEngine.refresh(bookId) else AppGraph.db.books().all().forEach { ContextEngine.refresh(it.id) } }
    LaunchedEffect(q) {
        delay(250)
        hits = if (q.isBlank()) null else ContextEngine.search(q, bookId, limit = 100).sortedWith(compareBy({ it.bookId }, { it.number }))
        hits?.map { it.chapterId }?.distinct()?.forEach { id -> AppGraph.db.chapters().get(id)?.let { titles[id] = it.title } }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A15：搜索无结果时可以“问问 AI”
    var askLore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LingIconButton(LingIcons.Back, "返回") { nav.popBackStack() }
            LingTextField(q, { q = it }, Modifier.weight(1f).focusRequester(focus), placeholder = if (bookId > 0) "搜本书正文：人名、地名、一句台词…" else "搜全部作品")
        }
        SearchResults(q, hits, bookId == 0L, { id -> books.firstOrNull { it.id == id }?.title.orEmpty() }, { titles[it].orEmpty() }) { nav.navigate(Routes.editor(it.chapterId)) }
        if (q.isNotBlank() && hits?.isEmpty() == true) {
            LingButton("问问 AI：$q", BtnKind.Tonal, icon = LingIcons.Help, height = 44.dp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                askLore = true
            }
        }
    }
    if (askLore && bookId > 0) {
        val last by produceState<com.lingwrite.app.data.db.ChapterEntity?>(null, bookId) {
            value = AppGraph.db.chapters().chaptersOnce(bookId).maxByOrNull { it.number }
        }
        last?.let { ch ->
            val scope = rememberCoroutineScope()
            AskPanel(
                chapterId = ch.id, bookId = bookId, chapterNo = ch.number, quote = null,
                onDismiss = { askLore = false },
                onToLoreCard = { qsn, ans ->
                    scope.launch {
                        AppGraph.db.lore().insertCard(com.lingwrite.app.data.db.LoreCardEntity(
                            bookId = bookId, type = "term", name = qsn.take(10).ifBlank { "新设定" },
                            fields = encodeFields(listOf(Field("说明", ans.take(300)))), pending = true,
                        ))
                    }
                },
                loreScope = true,
            )
        }
    }
}

@Composable
fun SearchResults(q: String, hits: List<Hit>?, showBook: Boolean, bookTitle: (Long) -> String, chapterTitle: (Long) -> String, onOpen: (Hit) -> Unit) {
    val c = Ling.colors
    when {
        hits == null -> Text("关键词检索在本机完成，不联网、不花钱。多个词用空格隔开表示同时出现。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp))
        hits.isEmpty() -> Text("没有找到“$q”", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp))
        else -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${hits.size} 处", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp)) }
            items(hits, key = { it.id }) { h ->
                LingCard {
                    Column(Modifier.fillMaxWidth().rowClick { onOpen(h) }.padding(14.dp)) {
                        Text((if (showBook) bookTitle(h.bookId) + " · " else "") + ChapterLabel.full(h.number, chapterTitle(h.chapterId)), style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary)
                        Text(highlight(snippet(h.text, q), q, c.tertiaryContainer, c.onTertiaryContainer), style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

fun snippet(text: String, q: String): String {
    val t = q.split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: return text.take(120)
    val i = text.indexOf(t)
    return if (i < 0) text.take(120) else (if (i > 40) "…" else "") + text.substring((i - 40).coerceAtLeast(0), (i + t.length + 80).coerceAtMost(text.length))
}

fun highlight(s: String, q: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    var i = 0
    while (i < s.length) {
        val t = terms.firstOrNull { s.startsWith(it, i) }
        if (t != null) { withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold)) { append(t) }; i += t.length }
        else { append(s[i]); i++ }
    }
}
