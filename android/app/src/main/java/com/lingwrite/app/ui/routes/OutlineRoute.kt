// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.room.withTransaction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.VolumeLabel
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.PromptHooks
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Space
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 三级大纲模块。 */
object OutlineModule {
    fun install() {
        WorkTabs.outline = { bookId -> OutlineTab(bookId, LocalNav.current, embedded = true) }
        ExtraDestinations.list.add { nav: NavHostController ->
            composable("threads/{bookId}") { e: androidx.navigation.NavBackStackEntry -> ThreadsRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
            composable("golden/{bookId}") { e: androidx.navigation.NavBackStackEntry -> GoldenRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
            composable(
                "chain/{bookId}?step={step}",
                listOf(
                    androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("step") { type = androidx.navigation.NavType.IntType; defaultValue = 0 },
                ),
            ) { e: androidx.navigation.NavBackStackEntry ->
                ChainRoute(e.arguments!!.getLong("bookId"), nav, e.arguments!!.getInt("step"))
            }
        }
        val prevMore = EditorHooks.moreItems
        EditorHooks.moreItems = { st, close, nav ->
            SheetAction(LingIcons.EditNote, tr(R.string.outline_001), sub = tr(R.string.outline_002)) { Outline.editing = st.chapter.id; close() }
            prevMore(this, st, close, nav)
        }
        val prevOverlay = EditorHooks.overlays
        EditorHooks.overlays = { st, nav ->
            prevOverlay(st, nav)
            if (Outline.editing == st.chapter.id) ChapterPlanSheet(st.chapter.bookId, st.chapter.id) { Outline.editing = null }
        }
        val prevDone = EditorHooks.onChapterDone
        EditorHooks.onChapterDone = { id -> prevDone(id); AppGraph.scope.launch { if (AppGraph.prefs.flow.first().autoCheck) Outline.checkLanded(id) } }
        val prevCtx = PromptHooks.context
        PromptHooks.context = { bookId, chapterId, cursor, text ->
            val base = prevCtx(bookId, chapterId, cursor, text)
            val plan = AppGraph.db.plan().chapterOnce(chapterId)?.takeIf { !it.isEmpty() }?.asPrompt()
            if (plan == null || base.contains("【本章章纲】")) base else trZh(R.string.outline_003, plan, base)
        }
    }
}

object Outline {
    var editing by mutableStateOf<Long?>(null)
    val dao get() = AppGraph.db.plan()
    suspend fun plan(bookId: Long) = dao.bookOnce(bookId) ?: BookPlanEntity(bookId)
    suspend fun chapter(c: ChapterEntity) = dao.chapterOnce(c.id) ?: ChapterPlanEntity(c.id, c.bookId)

    /** 正文是否落实章纲（章纲为空或无模型时静默跳过）。 */
    suspend fun checkLanded(chapterId: Long): String? {
        val c = AppGraph.db.chapters().get(chapterId) ?: return null
        val p = dao.chapterOnce(chapterId)?.takeIf { !it.isEmpty() } ?: return tr(R.string.outline_004)
        if (c.content.isBlank()) return tr(R.string.outline_005)
        val r = AppGraph.ai.complete("check", OutlinePrompts.SYS, OutlinePrompts.landed(p.asPrompt(), c.content), c.bookId, c.id, 300)
        return r.fold({ raw ->
            val o = extractJson(raw, array = false) ?: return@fold tr(R.string.book_ai_018)
            AppGraph.db.withTransaction {
                val latest = dao.chapterOnce(chapterId)
                if (latest != p || AppGraph.db.chapters().get(chapterId)?.content != c.content) tr(R.string.outline_006)
                else { dao.upsertChapter(latest.copy(landed = o.toString())); null }
            }
        }, { it.message })
    }
}

data class PlanSuggestion(val title: String, var event: String, var hook: String, var cliff: String, val custom: Map<String, String>, var take: Boolean = true)

@Composable
fun OutlineTab(bookId: Long, nav: NavHostController, embedded: Boolean = false) {
    val c = Ling.colors
    val repo = AppGraph.books
    val dao = AppGraph.db.plan()
    val scope = rememberCoroutineScope()
    val plan by remember(bookId) { dao.book(bookId) }.collectAsState(null)
    val vols by remember(bookId) { repo.volumes(bookId) }.collectAsState(emptyList())
    val vplans by remember(bookId) { dao.volumes(bookId) }.collectAsState(emptyList())
    val chs by remember(bookId) { repo.chapterHeaders(bookId) }.collectAsState(emptyList())
    val cplans by remember(bookId) { dao.chapters(bookId) }.collectAsState(emptyList())
    val bp = plan ?: BookPlanEntity(bookId)
    var editSynopsis by remember { mutableStateOf(false) }
    var editVolume by remember { mutableStateOf<VolumeEntity?>(null) }
    var editChapter by remember { mutableStateOf<Long?>(null) }
    var split by remember { mutableStateOf<VolumeEntity?>(null) }
    var rolling by remember { mutableStateOf<VolumeEntity?>(null) }
    var presetN by remember { mutableStateOf("5") }
    var config by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<Long>()) }

    val editing = editSynopsis || editVolume != null || editChapter != null
    @Composable
    fun OutlineList() {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniEntry(LingIcons.Sparkle, tr(R.string.chain_001), Modifier.weight(1f)) { nav.navigate("chain/$bookId") }
            MiniEntry(LingIcons.Graph, tr(R.string.outline_007), Modifier.weight(1f)) { nav.navigate("threads/$bookId") }
            MiniEntry(LingIcons.FactCheck, tr(R.string.outline_008), Modifier.weight(1f)) { nav.navigate("golden/$bookId") }
        }
        // 总纲
        LingCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().rowClick { editSynopsis = true; editVolume = null; editChapter = null }.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.outline_009), style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                    Icon(LingIcons.EditOutline, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Text(bp.synopsis.ifBlank { tr(R.string.outline_010) }, style = Ling.type.body.copy(lineHeight = 22.sp),
                    color = if (bp.synopsis.isBlank()) c.onSurfaceVariant else c.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                // A7 独立入口：只有总纲也能直接拆卷纲（进总纲工作台第 ④ 步）
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(tr(R.string.chain_051), BtnKind.Tonal, icon = LingIcons.Sparkle, height = 36.dp) { nav.navigate("chain/$bookId?step=4") }
                    LingButton(tr(R.string.chain_001), BtnKind.Text, icon = LingIcons.Book, height = 36.dp) { nav.navigate("chain/$bookId") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(tr(R.string.outline_011), BtnKind.Tonal, icon = LingIcons.Settings, height = 36.dp) { config = true }
            LingButton(tr(R.string.outline_012), BtnKind.Tonal, icon = LingIcons.Book, height = 36.dp) { templates = true }
        }
        vols.forEachIndexed { vi, v ->
            val vp = vplans.firstOrNull { it.volumeId == v.id }
            val list = chs.filter { it.volumeId == v.id }.sortedBy { it.number }
            val open = v.id !in collapsed
            Row(Modifier.fillMaxWidth().rowClick { collapsed = if (open) collapsed + v.id else collapsed - v.id }.padding(start = 24.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.ExpandMore, null, tint = c.primary, modifier = Modifier.size(20.dp).rotate(if (open) 0f else -90f))
                Text(VolumeLabel.full(vi + 1, v.title), style = Ling.type.volumeHeader, color = c.primary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                Text(tr(R.string.outline_013, list.count { it.state == "done" }, list.size), style = Ling.type.caption, color = c.onSurfaceVariant)
                if (vi > 0) Box(Modifier.rotate(180f)) { LingIconButton(LingIcons.ExpandMore, tr(R.string.outline_014), c.onSurfaceVariant) { scope.launch { repo.moveVolumeIn(bookId, v.id, -1) } } }
            }
            if (open) LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(vi).animateContentSize()) {
                Column(Modifier.fillMaxWidth().rowClick { editVolume = v; editSynopsis = false; editChapter = null }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr(R.string.outline_015), style = Ling.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceVariant)
                    Text(vp?.outline?.ifBlank { null } ?: tr(R.string.outline_016), style = Ling.type.body, color = if (vp?.outline.isNullOrBlank()) c.onSurfaceVariant else c.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!vp?.goal.isNullOrBlank()) Pill(tr(R.string.outline_017, vp!!.goal), PillKind.Primary)
                        if (!vp?.climax.isNullOrBlank()) Pill(tr(R.string.outline_018, vp!!.climax), PillKind.Amber)
                        if ((vp?.chapterCount ?: 0) > 0) Pill(tr(R.string.outline_019, vp!!.chapterCount), PillKind.Neutral)
                    }
                }
                list.forEach { ch ->
                    InsetDivider(16.dp)
                    ChapterPlanRow(ch, cplans.firstOrNull { it.chapterId == ch.id }, onOpen = { editChapter = ch.id; editSynopsis = false; editVolume = null },
                        onUp = { scope.launch { repo.moveChapter(ch.id, -1) } }, onDown = { scope.launch { repo.moveChapter(ch.id, 1) } })
                }
                InsetDivider(16.dp)
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LingButton(tr(R.string.outline_020), BtnKind.Text, icon = LingIcons.Add, height = 36.dp) { scope.launch { editChapter = repo.addChapterTo(bookId, v.id, "") } }
                    LingButton(tr(R.string.chain_061), BtnKind.Text, icon = LingIcons.Sparkle, height = 36.dp) { split = v }
                    LingButton(tr(R.string.outline_021), BtnKind.Text, icon = LingIcons.Schedule, height = 36.dp) { rolling = v }
                }
            }
        }
        if (vols.isEmpty()) Text(tr(R.string.outline_022), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp))
    }
    if (embedded && !editing) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { OutlineList() }
    else AdaptiveMasterDetail(editing, if (embedded) Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 170).coerceAtLeast(400).dp) else Modifier.fillMaxSize(), master = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (embedded) 12.dp else Space.dockClearance)) { OutlineList() }
    }, detail = {
        when {
            editSynopsis -> key("synopsis") { TextEditSheet(tr(R.string.outline_009), bp.synopsis, { editSynopsis = false }, inline = true) { t -> scope.launch { AppGraph.db.withTransaction { dao.upsertBook((dao.bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(synopsis = t)) } } } }
            editVolume != null -> {
                val v = editVolume!!
                val existing = vplans.firstOrNull { it.volumeId == v.id } ?: VolumePlanEntity(v.id, bookId)
                key(v.id) { TextEditSheet(v.title + tr(R.string.outline_023), existing.outline, { editVolume = null }, inline = true) { t -> scope.launch { AppGraph.db.withTransaction { dao.upsertVolume((dao.volumeOnce(v.id) ?: existing).copy(outline = t)) } } } }
            }
            editChapter != null -> key(editChapter) { ChapterPlanSheet(bookId, editChapter!!, inline = true) { editChapter = null } }
            else -> Text(tr(R.string.outline_024), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
    })
    androidx.activity.compose.BackHandler((editSynopsis || editVolume != null) && editChapter == null && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 720) { editSynopsis = false; editVolume = null; editChapter = null }
    split?.let { v -> SplitSheet(bookId, v, bp, presetN) { split = null } }
    rolling?.let { v ->
        LingSheet({ rolling = null }, tr(R.string.outline_025, VolumeLabel.full(vols.sortedBy { it.sort }.indexOf(v) + 1, v.title))) {
            Text(tr(R.string.outline_026), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            listOf("3" to tr(R.string.outline_027), "5" to tr(R.string.outline_028)).forEach { (n, label) ->
                SheetAction(LingIcons.Sparkle, label, sub = tr(R.string.outline_029)) { presetN = n; rolling = null; split = v }
            }
            SheetAction(LingIcons.EditNote, tr(R.string.outline_030)) { presetN = "5"; rolling = null; split = v }
            Text(tr(R.string.outline_031), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
    }
    if (config) PlanConfigSheet(bp) { config = false }
    val navHost = LocalNav.current
    if (templates) LingSheet({ templates = false }, tr(R.string.outline_032)) {
        Text(tr(R.string.outline_033), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        val on = bp.templates.split(',').filter { it.isNotBlank() }.toSet()
        OutlineTemplates.forEach { t ->
            SheetAction(if (t.id in on) LingIcons.CheckCircle else LingIcons.Book, t.name + if (t.id in on) tr(R.string.outline_034) else "", sub = t.desc) {
                scope.launch {
                    val ids = if (t.id in on) on - t.id else on + t.id
                    val fields = (parseList(bp.fields) + if (t.id in on) emptyList() else t.fields).distinct()
                    dao.upsertBook(bp.copy(templates = ids.joinToString(","), fields = encodeList(fields)))
                }
            }
        }
        // A12 拆文入口：拆一本书做模板（版权提示在拆文页）
        SheetAction(LingIcons.Graph, tr(R.string.outline_035), sub = tr(R.string.breakdown_002)) {
            templates = false
            navHost.navigate(com.lingwrite.app.ui.Routes.breakdown(bookId))
        }
        // 已保存的拆文模板：点开可查看 / 套用到本书章纲 / 删除
        val saved by remember { AppGraph.db.breakdowns().all() }.collectAsState(initial = emptyList())
        if (saved.isNotEmpty()) {
            Text(tr(R.string.outline_036), style = Ling.type.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
            saved.forEach { b ->
                val n = com.lingwrite.app.data.Breakdown.parseTemplate(b.template).size
                SheetAction(LingIcons.Sparkle, b.name, sub = tr(R.string.outline_037, n)) {
                    templates = false
                    navHost.navigate(com.lingwrite.app.ui.Routes.breakdown(bookId, b.id))
                }
            }
        }
    }
}

@Composable
private fun MiniEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Ling.colors
    LingCard(modifier) {
        Column(Modifier.fillMaxWidth().rowClick(onClick).padding(14.dp)) {
            Tile(icon, c.primaryContainer, c.onPrimaryContainer, 36.dp)
            Text(title, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ChapterPlanRow(ch: ChapterEntity, p: ChapterPlanEntity?, onOpen: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val c = Ling.colors
    val prog = progressOf(ch)
    val landed = p?.landed?.let(::parseLanded)
    Row(Modifier.fillMaxWidth().rowClick(onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape).background(when (prog) { PlanProgress.Done -> c.goodContainer; PlanProgress.Writing -> c.tertiaryContainer; else -> c.surfaceContainer }), contentAlignment = Alignment.Center) {
            Text("${ch.number}", style = Ling.type.micro.copy(fontWeight = FontWeight.SemiBold), color = when (prog) { PlanProgress.Done -> c.good; PlanProgress.Writing -> c.onTertiaryContainer; else -> c.onSurfaceVariant })
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.title, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                Pill(trText(prog.label), when (prog) { PlanProgress.Done -> PillKind.Good; PlanProgress.Writing -> PillKind.Amber; else -> PillKind.Neutral })
            }
            if (p == null || p.isEmpty()) Text(tr(R.string.outline_038), style = Ling.type.caption, color = c.onSurfaceVariant)
            else {
                if (p.event.isNotBlank()) Text(p.event, style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LandDot(tr(R.string.outline_039), p.event.isNotBlank(), landed?.event); LandDot(tr(R.string.outline_040), p.hook.isNotBlank(), landed?.hook); LandDot(tr(R.string.outline_041), p.cliff.isNotBlank(), landed?.cliff)
                    if (p.fromAi) Pill(tr(R.string.outline_042), PillKind.Amber)
                }
                if (landed != null && landed.note.isNotBlank() && listOf(landed.event, landed.hook, landed.cliff).any { it == false })
                    Text(tr(R.string.outline_043, landed.note), style = Ling.type.caption, color = c.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column {
            Icon(LingIcons.ExpandMore, tr(R.string.outline_044), tint = c.onSurfaceVariant, modifier = Modifier.size(28.dp).clip(CircleShape).rowClick(onUp).padding(4.dp).graphicsRotate(180f))
            Icon(LingIcons.ExpandMore, tr(R.string.outline_045), tint = c.onSurfaceVariant, modifier = Modifier.size(28.dp).clip(CircleShape).rowClick(onDown).padding(4.dp))
        }
    }
}

private fun Modifier.graphicsRotate(d: Float) = this.rotate(d)

@Composable
private fun LandDot(label: String, filled: Boolean, landed: Boolean?) {
    val c = Ling.colors
    val (bg, fg) = when {
        !filled -> c.surfaceContainer to c.outline
        landed == true -> c.goodContainer to c.good
        landed == false -> c.errorContainer to c.onErrorContainer
        else -> c.primaryContainer to c.onPrimaryContainer
    }
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (landed == true) Icon(LingIcons.Check, null, tint = fg, modifier = Modifier.size(11.dp).padding(end = 2.dp))
        Text(label, style = Ling.type.micro, color = fg)
    }
}

@Composable
fun TextEditSheet(title: String, initial: String, onDismiss: () -> Unit, inline: Boolean = false, onSave: (String) -> Unit) {
    var t by rememberSaveable { mutableStateOf(initial) }
    DetailHost(inline, title, onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingTextField(t, { t = it }, singleLine = false, minLines = 5, maxLines = 14)
            LingButton(tr(R.string.check_046), modifier = Modifier.fillMaxWidth(), height = 50.dp) { onSave(t.trim()); onDismiss() }
        }
    }
}

/** 章纲编辑：固定字段 + 作品级自定义字段 + 线索 + 落实检查。与编辑器“本章章纲”共用。 */
@Composable
fun ChapterPlanSheet(bookId: Long, chapterId: Long, inline: Boolean = false, returnToList: Boolean = true, onDismiss: () -> Unit) {
    val c = Ling.colors
    val dao = AppGraph.db.plan()
    val scope = rememberCoroutineScope()
    val bp by remember(bookId) { dao.book(bookId) }.collectAsState(null)
    val ch by remember(chapterId) { AppGraph.db.chapters().chapter(chapterId) }.collectAsState(null)
    var loaded by remember(chapterId) { mutableStateOf<ChapterPlanEntity?>(null) }
    LaunchedEffect(chapterId) { loaded = dao.chapterOnce(chapterId) ?: ChapterPlanEntity(chapterId, bookId) }
    val p0 = loaded ?: return
    var event by rememberSaveable(chapterId) { mutableStateOf(p0.event) }
    var hook by rememberSaveable(chapterId) { mutableStateOf(p0.hook) }
    var cliff by rememberSaveable(chapterId) { mutableStateOf(p0.cliff) }
    var note by rememberSaveable(chapterId) { mutableStateOf(p0.note) }
    var customJson by rememberSaveable(chapterId) { mutableStateOf(p0.custom) }
    val custom = parseMap(customJson)
    var threadCsv by rememberSaveable(chapterId) { mutableStateOf(p0.threads) }
    val threads = threadCsv.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    var checking by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val fields = parseList(bp?.threads?.let { bp!!.fields } ?: "[]")
    val allThreads = parseList(bp?.threads ?: tr(R.string.outline_046))
    fun current() = p0.copy(event = event.trim(), hook = hook.trim(), cliff = cliff.trim(), note = note.trim(), custom = encodeMap(custom.toMap()), threads = threads.joinToString(","))
    suspend fun persist() {
        val draft = current()
        AppGraph.db.withTransaction {
            val latest = dao.chapterOnce(chapterId) ?: p0
            fun merge(before: String, ours: String, theirs: String): String {
                if (ours == before) return theirs
                check(theirs == before || theirs == ours) { tr(R.string.outline_047) }
                return ours
            }
            dao.upsertChapter(latest.copy(event = merge(p0.event, draft.event, latest.event), hook = merge(p0.hook, draft.hook, latest.hook), cliff = merge(p0.cliff, draft.cliff, latest.cliff), note = merge(p0.note, draft.note, latest.note), custom = merge(p0.custom, draft.custom, latest.custom), threads = merge(p0.threads, draft.threads, latest.threads)))
            loaded = dao.chapterOnce(chapterId)
        }
    }
    fun save(close: Boolean) { scope.launch { runCatching { persist() }.onSuccess { if (close) onDismiss() else msg = tr(R.string.outline_048) }.onFailure { msg = it.message } } }
    androidx.activity.compose.BackHandler(inline && returnToList && LocalPaneVisible.current) { save(true) }
    DetailHost(inline, ch?.let { tr(R.string.outline_049, ChapterLabel.short(it.number)) } ?: tr(R.string.outline_050), { save(true) }, showBack = returnToList) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingTextField(event, { event = it }, label = tr(R.string.outline_051), singleLine = false, minLines = 2)
            LingTextField(hook, { hook = it }, label = tr(R.string.outline_052), singleLine = false)
            LingTextField(cliff, { cliff = it }, label = tr(R.string.outline_053), singleLine = false)
            fields.forEach { f -> LingTextField(custom[f].orEmpty(), { customJson = encodeMap(custom + (f to it)) }, label = f, singleLine = false) }
            LingTextField(note, { note = it }, label = tr(R.string.outline_054), singleLine = false)
            if (allThreads.isNotEmpty()) {
                Text(tr(R.string.outline_055), style = Ling.type.caption, color = c.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allThreads.forEach { t -> LingChip(t in threads, { threadCsv = (if (t in threads) threads - t else threads + t).joinToString(",") }, t) }
                }
            }
            parseLanded(p0.landed)?.let { l ->
                LingCard(color = c.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tr(R.string.outline_056), style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                        Text(listOf(tr(R.string.outline_051) to l.event, tr(R.string.outline_052) to l.hook, tr(R.string.outline_053) to l.cliff).joinToString("  ") { (k, v) -> k + when (v) { true -> " ✓"; false -> " ✗"; else -> " –" } }, style = Ling.type.caption, color = c.onSurfaceVariant)
                        if (l.note.isNotBlank()) Text(l.note, style = Ling.type.caption, color = c.onSurface)
                        if (listOf(l.event, l.hook, l.cliff).any { it == false }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            LingButton(tr(R.string.check_066), BtnKind.Text, height = 34.dp) { scope.launch { runCatching { persist(); AppGraph.db.withTransaction { dao.chapterOnce(chapterId)?.let { dao.upsertChapter(it.copy(landed = "")) }; loaded = dao.chapterOnce(chapterId) } }.onFailure { msg = it.message } } }
                            Text(tr(R.string.outline_057), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
            msg?.let { Text(it, style = Ling.type.caption, color = if (it == "已保存") c.good else c.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                LingButton(if (checking) tr(R.string.outline_058) else tr(R.string.outline_059), BtnKind.Tonal, icon = LingIcons.FactCheck, enabled = !checking, modifier = Modifier.weight(1f)) {
                    checking = true; msg = null
                    scope.launch { runCatching { persist(); Outline.checkLanded(chapterId) }.onSuccess { msg = it }.onFailure { msg = it.message }; checking = false }
                }
                LingButton(tr(R.string.check_046), modifier = Modifier.weight(1f)) { save(!inline) }
            }
        }
    }
}

/** AI 拆章纲：结果先作为建议，可微调、逐条或全部采纳。 */
@Composable
private fun SplitSheet(bookId: Long, v: VolumeEntity, bp: BookPlanEntity, presetN: String = "5", onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var n by remember { mutableStateOf(presetN) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val sugg = remember { mutableStateListOf<PlanSuggestion>() }
    val fields = parseList(bp.fields)
    LingSheet(onDismiss, tr(R.string.outline_060, v.title), layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sugg.isEmpty()) {
                Text(tr(R.string.outline_061), style = Ling.type.body, color = c.onSurfaceVariant)
                LingTextField(n, { n = it.filter(Char::isDigit).take(2) }, label = tr(R.string.outline_062), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                err?.let { Text(it, style = Ling.type.caption, color = c.error) }
                LingButton(if (busy) tr(R.string.outline_063) else tr(R.string.outline_064), icon = LingIcons.SparkleFilled, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) {
                    busy = true; err = null
                    scope.launch {
                        val dao = AppGraph.db.plan()
                        val vo = dao.volumeOnce(v.id)?.outline.orEmpty()
                        if (vo.isBlank() && bp.synopsis.isBlank()) { err = tr(R.string.outline_065); busy = false; return@launch }
                        val chs = AppGraph.db.chapters().chaptersOnce(bookId).filter { it.volumeId == v.id }
                        val hint = OutlineTemplates.filter { it.id in bp.templates.split(',') }.joinToString(" ") { it.hint }
                        // 走 ChainRunner：带真实已有章纲 + 解析失败重发一次（与总纲工作台第 ⑤ 步同一套）
                        val drafts = com.lingwrite.app.data.ChainRunner.chapters(bookId, v.id, n.toIntOrNull()?.coerceIn(1, 20) ?: 5, hint)
                        if (drafts.isEmpty()) err = tr(R.string.outline_066)
                        else sugg += drafts.map { PlanSuggestion(it.title, it.event, it.hook, it.cliff, it.custom) }
                        busy = false
                    }
                }
            } else {
                sugg.forEachIndexed { i, s ->
                    var e by remember(s) { mutableStateOf(s.event) }; var h by remember(s) { mutableStateOf(s.hook) }; var cl by remember(s) { mutableStateOf(s.cliff) }
                    var take by remember(s) { mutableStateOf(s.take) }
                    LingCard(color = if (take) c.surface else c.surfaceContainer, modifier = Modifier.border(1.dp, if (take) c.primary.copy(alpha = .4f) else Color.Transparent, RoundedCornerShape(14.dp))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title.ifBlank { tr(R.string.chain_059, i + 1) }, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.weight(1f))
                                Checkbox(take, { take = it; s.take = it })
                            }
                            LingTextField(e, { e = it; s.event = it }, label = tr(R.string.outline_051), singleLine = false)
                            LingTextField(h, { h = it; s.hook = it }, label = tr(R.string.outline_052), singleLine = false)
                            LingTextField(cl, { cl = it; s.cliff = it }, label = tr(R.string.outline_053), singleLine = false)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    LingButton(tr(R.string.outline_067), BtnKind.Tonal, modifier = Modifier.weight(1f)) { sugg.clear() }
                    LingButton(tr(R.string.outline_068, sugg.count { it.take }), modifier = Modifier.weight(1f), enabled = sugg.any { it.take }) {
                        scope.launch {
                            sugg.filter { it.take }.forEach { s ->
                                val id = AppGraph.books.addChapterTo(bookId, v.id, Naming.stripChapter(s.title))
                                AppGraph.db.plan().upsertChapter(ChapterPlanEntity(id, bookId, s.event, s.hook, s.cliff, custom = encodeMap(s.custom), threads = tr(R.string.outline_069), fromAi = true))
                            }
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanConfigSheet(bp: BookPlanEntity, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val fields = remember { mutableStateListOf<String>().apply { addAll(parseList(bp.fields)) } }
    val threads = remember { mutableStateListOf<String>().apply { addAll(parseList(bp.threads)) } }
    var nf by remember { mutableStateOf("") }; var nt by remember { mutableStateOf("") }
    var gap by remember { mutableStateOf(bp.gapThreshold.toString()) }
    LingSheet({ scope.launch { AppGraph.db.plan().upsertBook(bp.copy(fields = encodeList(fields), threads = encodeList(threads), gapThreshold = gap.toIntOrNull() ?: 8)); onDismiss() } }, tr(R.string.outline_011), layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.outline_070), style = Ling.type.label, color = c.primary)
            Text(tr(R.string.outline_071), style = Ling.type.caption, color = c.onSurfaceVariant)
            ChipEditor(fields, nf, { nf = it }, tr(R.string.outline_072))
            Text(tr(R.string.outline_073), style = Ling.type.label, color = c.primary, modifier = Modifier.padding(top = 8.dp))
            ChipEditor(threads, nt, { nt = it }, tr(R.string.outline_074))
            LingTextField(gap, { gap = it.filter(Char::isDigit) }, label = tr(R.string.outline_075), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingButton(tr(R.string.lore_visual_012), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) {
                scope.launch { AppGraph.db.plan().upsertBook(bp.copy(fields = encodeList(fields), threads = encodeList(threads), gapThreshold = gap.toIntOrNull() ?: 8)); onDismiss() }
            }
        }
    }
}

@Composable
private fun ChipEditor(list: MutableList<String>, input: String, onInput: (String) -> Unit, hint: String) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.toList().forEach { f -> InputChip(true, { list.remove(f) }, { Text(f, style = Ling.type.label) }, trailingIcon = { Icon(LingIcons.Close, tr(R.string.check_044), Modifier.size(16.dp)) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LingTextField(input, onInput, Modifier.weight(1f), placeholder = hint)
        Spacer(Modifier.width(8.dp))
        LingButton(tr(R.string.models_034), BtnKind.Tonal, enabled = input.isNotBlank() && input.trim() !in list) { list += input.trim(); onInput("") }
    }
}

/** 主线 / 副线多线视图。 */
@Composable
fun ThreadsRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val dao = AppGraph.db.plan()
    val bp by remember(bookId) { dao.book(bookId) }.collectAsState(null)
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val plans by remember(bookId) { dao.chapters(bookId) }.collectAsState(emptyList())
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.outline_007), tr(R.string.outline_076), onBack = { nav.popBackStack() })
        ThreadsView(parseList((bp ?: BookPlanEntity(bookId)).threads), chs.sortedBy { it.number }, plans, bp?.gapThreshold ?: 8)
    }
}

@Composable
fun ThreadsView(threads: List<String>, chs: List<ChapterEntity>, plans: List<ChapterPlanEntity>, threshold: Int) {
    val c = Ling.colors
    val byId = plans.associateBy { it.chapterId }
    val current = chs.lastOrNull { it.wordCount > 0 }?.number ?: chs.firstOrNull()?.number ?: 0
    val gaps = threadGaps(threads, chs.mapNotNull { ch -> byId[ch.id]?.let { ch.number to it } }, current)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        gaps.filter { it.gap > threshold }.forEach { g ->
            LingCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = c.errorContainer) {
                Text(tr(R.string.outline_077, g.thread, g.gap) + (g.lastAt?.let { tr(R.string.outline_078, it) } ?: tr(R.string.outline_079)), style = Ling.type.body, color = c.onErrorContainer, modifier = Modifier.padding(14.dp))
            }
        }
        if (chs.isEmpty() || threads.isEmpty()) { Text(tr(R.string.outline_080), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp)); return@Column }
        LingCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.padding(vertical = 12.dp)) {
                Column(Modifier.padding(start = 14.dp, top = 22.dp)) {
                    threads.forEach { t -> Box(Modifier.height(36.dp), contentAlignment = Alignment.CenterStart) { Text(t, style = Ling.type.label, color = c.onSurface, maxLines = 1, modifier = Modifier.widthIn(max = 72.dp)) } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 14.dp)) {
                    chs.forEach { ch ->
                        val p = byId[ch.id]
                        Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${ch.number}", style = Ling.type.micro, color = if (ch.number == current) c.primary else c.onSurfaceVariant, modifier = Modifier.height(22.dp))
                            threads.forEachIndexed { ti, t ->
                                val on = p?.threads?.split(',')?.map(String::trim)?.contains(t) == true
                                val col = AvatarColors[ti % AvatarColors.size]
                                Box(Modifier.height(36.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(c.outlineVariant))
                                    if (on) Box(Modifier.size(14.dp).clip(CircleShape).background(col))
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(tr(R.string.outline_081, threshold), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp))
    }
}

/** 黄金三章检查表。 */
@Composable
fun GoldenRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val dao = AppGraph.db.plan()
    val scope = rememberCoroutineScope()
    val bp0 by remember(bookId) { dao.book(bookId) }.collectAsState(null)
    val bp = bp0 ?: BookPlanEntity(bookId)
    val items = parseList(bp.golden).ifEmpty { DEFAULT_GOLDEN }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.outline_082), tr(R.string.outline_083), onBack = { nav.popBackStack() }) { LingIconButton(LingIcons.EditOutline, tr(R.string.outline_084)) { edit = true } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GoldenList(items, parseGolden(bp.goldenResult))
            err?.let { Text(it, style = Ling.type.caption, color = c.error, modifier = Modifier.padding(horizontal = 28.dp)) }
        }
        LingButton(if (busy) tr(R.string.outline_085) else tr(R.string.outline_086), icon = LingIcons.FactCheck, enabled = !busy, height = 50.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            busy = true; err = null
            scope.launch {
                val chs = AppGraph.db.chapters().chaptersOnce(bookId).filter { it.number <= 3 && it.content.isNotBlank() }
                if (chs.isEmpty()) { err = tr(R.string.outline_087); busy = false; return@launch }
                AppGraph.ai.complete("check", OutlinePrompts.SYS, OutlinePrompts.golden(items, chs), bookId, null, 1500).onSuccess { raw ->
                    val arr = extractJson(raw)?.let { runCatching { it.jsonArray }.getOrNull() }
                    if (arr == null) err = tr(R.string.book_ai_018)
                    else dao.upsertBook(bp.copy(goldenAt = System.currentTimeMillis(), goldenResult = encodeGolden(arr.map { e ->
                        val o = e.jsonObject
                        GoldenResult(o.str("item"), o.str("pass") == "true", o.str("evidence"), o.str("chapter").toIntOrNull() ?: 0)
                    })))
                }.onFailure { err = it.message }
                busy = false
            }
        }
    }
    if (edit) {
        val list = remember { mutableStateListOf<String>().apply { addAll(items) } }
        var input by remember { mutableStateOf("") }
        LingSheet({ scope.launch { dao.upsertBook(bp.copy(golden = encodeList(list))); edit = false } }, tr(R.string.outline_084)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.toList().forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                        LingIconButton(LingIcons.Close, tr(R.string.check_044), c.onSurfaceVariant) { list.remove(s) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LingTextField(input, { input = it }, Modifier.weight(1f), placeholder = tr(R.string.outline_088))
                    Spacer(Modifier.width(8.dp)); LingButton(tr(R.string.models_034), BtnKind.Tonal, enabled = input.isNotBlank()) { list += input.trim(); input = "" }
                }
                LingButton(tr(R.string.outline_089), BtnKind.Text) { list.clear(); list.addAll(DEFAULT_GOLDEN) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun GoldenList(items: List<String>, results: List<GoldenResult>) {
    val c = Ling.colors
    LingCard(Modifier.padding(16.dp)) {
        items.forEachIndexed { i, it ->
            if (i > 0) InsetDivider(56.dp)
            val r = results.firstOrNull { r -> r.item == it } ?: results.getOrNull(i)?.takeIf { results.size == items.size }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(when (r?.pass) { true -> c.goodContainer; false -> c.errorContainer; else -> c.surfaceContainer }), contentAlignment = Alignment.Center) {
                    if (r?.pass == true) Icon(LingIcons.Check, null, tint = c.good, modifier = Modifier.size(16.dp))
                    else Text(if (r == null) "${i + 1}" else "!", style = Ling.type.micro.copy(fontWeight = FontWeight.Bold), color = if (r == null) c.onSurfaceVariant else c.onErrorContainer)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(it, style = Ling.type.bodyLarge.copy(fontSize = 15.sp), color = c.onSurface)
                    if (r != null) Text((if (r.pass) tr(R.string.outline_090) else tr(R.string.outline_091)) + (if (r.chapter > 0) tr(R.string.outline_092, r.chapter) else "") + if (r.evidence.isNotBlank()) tr(R.string.outline_093, r.evidence) else "",
                        style = Ling.type.caption.copy(lineHeight = 18.sp), color = if (r.pass) c.onSurfaceVariant else c.error)
                }
            }
        }
    }
}
