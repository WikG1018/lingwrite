// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

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
            SheetAction(LingIcons.EditNote, "本章章纲", sub = "核心事件 · 开篇钩子 · 章尾悬念") { Outline.editing = st.chapter.id; close() }
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
            if (plan == null || base.contains("【本章章纲】")) base else "【本章章纲】\n$plan\n\n$base"
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
        val p = dao.chapterOnce(chapterId)?.takeIf { !it.isEmpty() } ?: return "这一章还没有章纲"
        if (c.content.isBlank()) return "正文还是空的"
        val r = AppGraph.ai.complete("check", OutlinePrompts.SYS, OutlinePrompts.landed(p.asPrompt(), c.content), c.bookId, c.id, 300)
        return r.fold({ raw ->
            val o = extractJson(raw, array = false) ?: return@fold "模型没有按格式返回"
            AppGraph.db.withTransaction {
                val latest = dao.chapterOnce(chapterId)
                if (latest != p || AppGraph.db.chapters().get(chapterId)?.content != c.content) "正文或章纲已修改，请重新检查"
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
            MiniEntry(LingIcons.Sparkle, "总纲工作台", Modifier.weight(1f)) { nav.navigate("chain/$bookId") }
            MiniEntry(LingIcons.Graph, "多线视图", Modifier.weight(1f)) { nav.navigate("threads/$bookId") }
            MiniEntry(LingIcons.FactCheck, "黄金三章", Modifier.weight(1f)) { nav.navigate("golden/$bookId") }
        }
        // 总纲
        LingCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().rowClick { editSynopsis = true; editVolume = null; editChapter = null }.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("总纲", style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                    Icon(LingIcons.EditOutline, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Text(bp.synopsis.ifBlank { "写下整本书的主线：主角要什么、挡在面前的是什么、最后会怎样。" }, style = Ling.type.body.copy(lineHeight = 22.sp),
                    color = if (bp.synopsis.isBlank()) c.onSurfaceVariant else c.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                // A7 独立入口：只有总纲也能直接拆卷纲（进总纲工作台第 ④ 步）
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton("AI 拆卷纲", BtnKind.Tonal, icon = LingIcons.Sparkle, height = 36.dp) { nav.navigate("chain/$bookId?step=4") }
                    LingButton("总纲工作台", BtnKind.Text, icon = LingIcons.Book, height = 36.dp) { nav.navigate("chain/$bookId") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton("章纲字段与线索", BtnKind.Tonal, icon = LingIcons.Settings, height = 36.dp) { config = true }
            LingButton("模板", BtnKind.Tonal, icon = LingIcons.Book, height = 36.dp) { templates = true }
        }
        vols.forEachIndexed { vi, v ->
            val vp = vplans.firstOrNull { it.volumeId == v.id }
            val list = chs.filter { it.volumeId == v.id }.sortedBy { it.number }
            val open = v.id !in collapsed
            Row(Modifier.fillMaxWidth().rowClick { collapsed = if (open) collapsed + v.id else collapsed - v.id }.padding(start = 24.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.ExpandMore, null, tint = c.primary, modifier = Modifier.size(20.dp).rotate(if (open) 0f else -90f))
                Text(VolumeLabel.full(vi + 1, v.title), style = Ling.type.volumeHeader, color = c.primary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                Text("${list.count { it.state == "done" }}/${list.size} 章", style = Ling.type.caption, color = c.onSurfaceVariant)
                if (vi > 0) Box(Modifier.rotate(180f)) { LingIconButton(LingIcons.ExpandMore, "上移卷", c.onSurfaceVariant) { scope.launch { repo.moveVolumeIn(bookId, v.id, -1) } } }
            }
            if (open) LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(vi).animateContentSize()) {
                Column(Modifier.fillMaxWidth().rowClick { editVolume = v; editSynopsis = false; editChapter = null }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("卷纲", style = Ling.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceVariant)
                    Text(vp?.outline?.ifBlank { null } ?: "点这里写本卷要完成的事", style = Ling.type.body, color = if (vp?.outline.isNullOrBlank()) c.onSurfaceVariant else c.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!vp?.goal.isNullOrBlank()) Pill("目标 · ${vp!!.goal}", PillKind.Primary)
                        if (!vp?.climax.isNullOrBlank()) Pill("高潮 · ${vp!!.climax}", PillKind.Amber)
                        if ((vp?.chapterCount ?: 0) > 0) Pill("约 ${vp!!.chapterCount} 章", PillKind.Neutral)
                    }
                }
                list.forEach { ch ->
                    InsetDivider(16.dp)
                    ChapterPlanRow(ch, cplans.firstOrNull { it.chapterId == ch.id }, onOpen = { editChapter = ch.id; editSynopsis = false; editVolume = null },
                        onUp = { scope.launch { repo.moveChapter(ch.id, -1) } }, onDown = { scope.launch { repo.moveChapter(ch.id, 1) } })
                }
                InsetDivider(16.dp)
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LingButton("加一章", BtnKind.Text, icon = LingIcons.Add, height = 36.dp) { scope.launch { editChapter = repo.addChapterTo(bookId, v.id, "") } }
                    LingButton("AI 拆章纲", BtnKind.Text, icon = LingIcons.Sparkle, height = 36.dp) { split = v }
                    LingButton("规划后续", BtnKind.Text, icon = LingIcons.Schedule, height = 36.dp) { rolling = v }
                }
            }
        }
        if (vols.isEmpty()) Text("还没有分卷。写下第一章时会自动建立第一卷。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp))
    }
    if (embedded && !editing) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { OutlineList() }
    else AdaptiveMasterDetail(editing, if (embedded) Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 170).coerceAtLeast(400).dp) else Modifier.fillMaxSize(), master = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (embedded) 12.dp else Space.dockClearance)) { OutlineList() }
    }, detail = {
        when {
            editSynopsis -> key("synopsis") { TextEditSheet("总纲", bp.synopsis, { editSynopsis = false }, inline = true) { t -> scope.launch { AppGraph.db.withTransaction { dao.upsertBook((dao.bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(synopsis = t)) } } } }
            editVolume != null -> {
                val v = editVolume!!
                val existing = vplans.firstOrNull { it.volumeId == v.id } ?: VolumePlanEntity(v.id, bookId)
                key(v.id) { TextEditSheet(v.title + " · 卷纲", existing.outline, { editVolume = null }, inline = true) { t -> scope.launch { AppGraph.db.withTransaction { dao.upsertVolume((dao.volumeOnce(v.id) ?: existing).copy(outline = t)) } } } }
            }
            editChapter != null -> key(editChapter) { ChapterPlanSheet(bookId, editChapter!!, inline = true) { editChapter = null } }
            else -> Text("选择总纲、卷纲或章纲，在这里规划故事。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
    })
    androidx.activity.compose.BackHandler((editSynopsis || editVolume != null) && editChapter == null && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 720) { editSynopsis = false; editVolume = null; editChapter = null }
    split?.let { v -> SplitSheet(bookId, v, bp, presetN) { split = null } }
    rolling?.let { v ->
        LingSheet({ rolling = null }, "AI 规划后续章纲 · ${VolumeLabel.full(vols.sortedBy { it.sort }.indexOf(v) + 1, v.title)}") {
            Text("从这一卷最后一个有章纲的章节往后接着拆。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            listOf("3" to "3 章", "5" to "5 章").forEach { (n, label) ->
                SheetAction(LingIcons.Sparkle, label, sub = "滚动规划，接着已有章纲往后") { presetN = n; rolling = null; split = v }
            }
            SheetAction(LingIcons.EditNote, "自定章数") { presetN = "5"; rolling = null; split = v }
            Text("结果是建议，采纳前都可以改。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
    }
    if (config) PlanConfigSheet(bp) { config = false }
    val navHost = LocalNav.current
    if (templates) LingSheet({ templates = false }, "网文模板") {
        Text("套用后会给章纲加上对应字段，并在 AI 拆章纲时附上写法要求。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        val on = bp.templates.split(',').filter { it.isNotBlank() }.toSet()
        OutlineTemplates.forEach { t ->
            SheetAction(if (t.id in on) LingIcons.CheckCircle else LingIcons.Book, t.name + if (t.id in on) " · 已套用" else "", sub = t.desc) {
                scope.launch {
                    val ids = if (t.id in on) on - t.id else on + t.id
                    val fields = (parseList(bp.fields) + if (t.id in on) emptyList() else t.fields).distinct()
                    dao.upsertBook(bp.copy(templates = ids.joinToString(","), fields = encodeList(fields)))
                }
            }
        }
        // A12 拆文入口：拆一本书做模板（版权提示在拆文页）
        SheetAction(LingIcons.Graph, "拆一本书做模板", sub = "只保存分析结果，不保存原文") {
            templates = false
            navHost.navigate(com.lingwrite.app.ui.Routes.breakdown(bookId))
        }
        // 已保存的拆文模板：点开可查看 / 套用到本书章纲 / 删除
        val saved by remember { AppGraph.db.breakdowns().all() }.collectAsState(initial = emptyList())
        if (saved.isNotEmpty()) {
            Text("我的拆文模板", style = Ling.type.label, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
            saved.forEach { b ->
                val n = com.lingwrite.app.data.Breakdown.parseTemplate(b.template).size
                SheetAction(LingIcons.Sparkle, b.name, sub = "$n 章结构 · 点开可套用到本书") {
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
                Pill(prog.label, when (prog) { PlanProgress.Done -> PillKind.Good; PlanProgress.Writing -> PillKind.Amber; else -> PillKind.Neutral })
            }
            if (p == null || p.isEmpty()) Text("还没写章纲", style = Ling.type.caption, color = c.onSurfaceVariant)
            else {
                if (p.event.isNotBlank()) Text(p.event, style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LandDot("事件", p.event.isNotBlank(), landed?.event); LandDot("钩子", p.hook.isNotBlank(), landed?.hook); LandDot("悬念", p.cliff.isNotBlank(), landed?.cliff)
                    if (p.fromAi) Pill("AI 拆解", PillKind.Amber)
                }
                if (landed != null && landed.note.isNotBlank() && listOf(landed.event, landed.hook, landed.cliff).any { it == false })
                    Text("偏离提醒：${landed.note}", style = Ling.type.caption, color = c.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column {
            Icon(LingIcons.ExpandMore, "上移", tint = c.onSurfaceVariant, modifier = Modifier.size(28.dp).clip(CircleShape).rowClick(onUp).padding(4.dp).graphicsRotate(180f))
            Icon(LingIcons.ExpandMore, "下移", tint = c.onSurfaceVariant, modifier = Modifier.size(28.dp).clip(CircleShape).rowClick(onDown).padding(4.dp))
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
            LingButton("保存", modifier = Modifier.fillMaxWidth(), height = 50.dp) { onSave(t.trim()); onDismiss() }
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
    val allThreads = parseList(bp?.threads ?: "[\"主线\"]")
    fun current() = p0.copy(event = event.trim(), hook = hook.trim(), cliff = cliff.trim(), note = note.trim(), custom = encodeMap(custom.toMap()), threads = threads.joinToString(","))
    suspend fun persist() {
        val draft = current()
        AppGraph.db.withTransaction {
            val latest = dao.chapterOnce(chapterId) ?: p0
            fun merge(before: String, ours: String, theirs: String): String {
                if (ours == before) return theirs
                check(theirs == before || theirs == ours) { "章纲已被其他操作修改，请返回列表后重新打开。草稿仍在此页。" }
                return ours
            }
            dao.upsertChapter(latest.copy(event = merge(p0.event, draft.event, latest.event), hook = merge(p0.hook, draft.hook, latest.hook), cliff = merge(p0.cliff, draft.cliff, latest.cliff), note = merge(p0.note, draft.note, latest.note), custom = merge(p0.custom, draft.custom, latest.custom), threads = merge(p0.threads, draft.threads, latest.threads)))
            loaded = dao.chapterOnce(chapterId)
        }
    }
    fun save(close: Boolean) { scope.launch { runCatching { persist() }.onSuccess { if (close) onDismiss() else msg = "已保存" }.onFailure { msg = it.message } } }
    androidx.activity.compose.BackHandler(inline && returnToList && LocalPaneVisible.current) { save(true) }
    DetailHost(inline, ch?.let { "${ChapterLabel.short(it.number)} · 章纲" } ?: "章纲", { save(true) }, showBack = returnToList) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingTextField(event, { event = it }, label = "核心事件", singleLine = false, minLines = 2)
            LingTextField(hook, { hook = it }, label = "开篇钩子", singleLine = false)
            LingTextField(cliff, { cliff = it }, label = "章尾悬念", singleLine = false)
            fields.forEach { f -> LingTextField(custom[f].orEmpty(), { customJson = encodeMap(custom + (f to it)) }, label = f, singleLine = false) }
            LingTextField(note, { note = it }, label = "备注（可选）", singleLine = false)
            if (allThreads.isNotEmpty()) {
                Text("本章推进的线索", style = Ling.type.caption, color = c.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allThreads.forEach { t -> LingChip(t in threads, { threadCsv = (if (t in threads) threads - t else threads + t).joinToString(",") }, t) }
                }
            }
            parseLanded(p0.landed)?.let { l ->
                LingCard(color = c.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text("落实检查", style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                        Text(listOf("核心事件" to l.event, "开篇钩子" to l.hook, "章尾悬念" to l.cliff).joinToString("  ") { (k, v) -> k + when (v) { true -> " ✓"; false -> " ✗"; else -> " –" } }, style = Ling.type.caption, color = c.onSurfaceVariant)
                        if (l.note.isNotBlank()) Text(l.note, style = Ling.type.caption, color = c.onSurface)
                        if (listOf(l.event, l.hook, l.cliff).any { it == false }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            LingButton("忽略", BtnKind.Text, height = 34.dp) { scope.launch { runCatching { persist(); AppGraph.db.withTransaction { dao.chapterOnce(chapterId)?.let { dao.upsertChapter(it.copy(landed = "")) }; loaded = dao.chapterOnce(chapterId) } }.onFailure { msg = it.message } } }
                            Text("或直接改上面的章纲，让它和正文一致", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
            msg?.let { Text(it, style = Ling.type.caption, color = if (it == "已保存") c.good else c.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                LingButton(if (checking) "检查中…" else "检查正文是否落实", BtnKind.Tonal, icon = LingIcons.FactCheck, enabled = !checking, modifier = Modifier.weight(1f)) {
                    checking = true; msg = null
                    scope.launch { runCatching { persist(); Outline.checkLanded(chapterId) }.onSuccess { msg = it }.onFailure { msg = it.message }; checking = false }
                }
                LingButton("保存", modifier = Modifier.weight(1f)) { save(!inline) }
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
    LingSheet(onDismiss, "AI 拆章纲 · ${v.title}", layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sugg.isEmpty()) {
                Text("根据总纲和本卷卷纲，接着已有章纲往后拆。结果是建议，采纳前都可以改。", style = Ling.type.body, color = c.onSurfaceVariant)
                LingTextField(n, { n = it.filter(Char::isDigit).take(2) }, label = "拆几章（建议 3–5 章滚动规划）", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                err?.let { Text(it, style = Ling.type.caption, color = c.error) }
                LingButton(if (busy) "正在拆…" else "开始", icon = LingIcons.SparkleFilled, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) {
                    busy = true; err = null
                    scope.launch {
                        val dao = AppGraph.db.plan()
                        val vo = dao.volumeOnce(v.id)?.outline.orEmpty()
                        if (vo.isBlank() && bp.synopsis.isBlank()) { err = "先写总纲或本卷卷纲"; busy = false; return@launch }
                        val chs = AppGraph.db.chapters().chaptersOnce(bookId).filter { it.volumeId == v.id }
                        val hint = OutlineTemplates.filter { it.id in bp.templates.split(',') }.joinToString(" ") { it.hint }
                        // 走 ChainRunner：带真实已有章纲 + 解析失败重发一次（与总纲工作台第 ⑤ 步同一套）
                        val drafts = com.lingwrite.app.data.ChainRunner.chapters(bookId, v.id, n.toIntOrNull()?.coerceIn(1, 20) ?: 5, hint)
                        if (drafts.isEmpty()) err = "模型两次都没有按格式返回，重试或换个模型"
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
                                Text(s.title.ifBlank { "新章 ${i + 1}" }, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.weight(1f))
                                Checkbox(take, { take = it; s.take = it })
                            }
                            LingTextField(e, { e = it; s.event = it }, label = "核心事件", singleLine = false)
                            LingTextField(h, { h = it; s.hook = it }, label = "开篇钩子", singleLine = false)
                            LingTextField(cl, { cl = it; s.cliff = it }, label = "章尾悬念", singleLine = false)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    LingButton("重拆", BtnKind.Tonal, modifier = Modifier.weight(1f)) { sugg.clear() }
                    LingButton("采纳 ${sugg.count { it.take }} 章", modifier = Modifier.weight(1f), enabled = sugg.any { it.take }) {
                        scope.launch {
                            sugg.filter { it.take }.forEach { s ->
                                val id = AppGraph.books.addChapterTo(bookId, v.id, Naming.stripChapter(s.title))
                                AppGraph.db.plan().upsertChapter(ChapterPlanEntity(id, bookId, s.event, s.hook, s.cliff, custom = encodeMap(s.custom), threads = "主线", fromAi = true))
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
    LingSheet({ scope.launch { AppGraph.db.plan().upsertBook(bp.copy(fields = encodeList(fields), threads = encodeList(threads), gapThreshold = gap.toIntOrNull() ?: 8)); onDismiss() } }, "章纲字段与线索", layout = SheetLayout.Viewport) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自定义字段（全书统一，每章填写）", style = Ling.type.label, color = c.primary)
            Text("固定字段：核心事件、开篇钩子、章尾悬念", style = Ling.type.caption, color = c.onSurfaceVariant)
            ChipEditor(fields, nf, { nf = it }, "例如：爽点、情绪、字数目标")
            Text("线索", style = Ling.type.label, color = c.primary, modifier = Modifier.padding(top = 8.dp))
            ChipEditor(threads, nt, { nt = it }, "例如：感情线、复仇线")
            LingTextField(gap, { gap = it.filter(Char::isDigit) }, label = "线索中断超过几章时提醒", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingButton("完成", modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) {
                scope.launch { AppGraph.db.plan().upsertBook(bp.copy(fields = encodeList(fields), threads = encodeList(threads), gapThreshold = gap.toIntOrNull() ?: 8)); onDismiss() }
            }
        }
    }
}

@Composable
private fun ChipEditor(list: MutableList<String>, input: String, onInput: (String) -> Unit, hint: String) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.toList().forEach { f -> InputChip(true, { list.remove(f) }, { Text(f, style = Ling.type.label) }, trailingIcon = { Icon(LingIcons.Close, "删除", Modifier.size(16.dp)) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LingTextField(input, onInput, Modifier.weight(1f), placeholder = hint)
        Spacer(Modifier.width(8.dp))
        LingButton("添加", BtnKind.Tonal, enabled = input.isNotBlank() && input.trim() !in list) { list += input.trim(); onInput("") }
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
        LingTopBar("多线视图", "每条线索在哪些章推进", onBack = { nav.popBackStack() })
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
                Text("「${g.thread}」已经 ${g.gap} 章没有推进" + (g.lastAt?.let { "（上次在第 $it 章）" } ?: "（还没开始）"), style = Ling.type.body, color = c.onErrorContainer, modifier = Modifier.padding(14.dp))
            }
        }
        if (chs.isEmpty() || threads.isEmpty()) { Text("先在大纲里给章纲关联线索。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(28.dp)); return@Column }
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
        Text("圆点表示该章推进了这条线；中断超过 $threshold 章会标红，可在“章纲字段与线索”里调整。", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp))
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
        LingTopBar("黄金三章检查", "针对第 1–3 章，每项附原文依据", onBack = { nav.popBackStack() }) { LingIconButton(LingIcons.EditOutline, "编辑检查项") { edit = true } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GoldenList(items, parseGolden(bp.goldenResult))
            err?.let { Text(it, style = Ling.type.caption, color = c.error, modifier = Modifier.padding(horizontal = 28.dp)) }
        }
        LingButton(if (busy) "AI 正在审读前三章…" else "检查前三章", icon = LingIcons.FactCheck, enabled = !busy, height = 50.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            busy = true; err = null
            scope.launch {
                val chs = AppGraph.db.chapters().chaptersOnce(bookId).filter { it.number <= 3 && it.content.isNotBlank() }
                if (chs.isEmpty()) { err = "前三章还没有正文"; busy = false; return@launch }
                AppGraph.ai.complete("check", OutlinePrompts.SYS, OutlinePrompts.golden(items, chs), bookId, null, 1500).onSuccess { raw ->
                    val arr = extractJson(raw)?.let { runCatching { it.jsonArray }.getOrNull() }
                    if (arr == null) err = "模型没有按格式返回"
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
        LingSheet({ scope.launch { dao.upsertBook(bp.copy(golden = encodeList(list))); edit = false } }, "编辑检查项") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.toList().forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                        LingIconButton(LingIcons.Close, "删除", c.onSurfaceVariant) { list.remove(s) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LingTextField(input, { input = it }, Modifier.weight(1f), placeholder = "新增检查项")
                    Spacer(Modifier.width(8.dp)); LingButton("添加", BtnKind.Tonal, enabled = input.isNotBlank()) { list += input.trim(); input = "" }
                }
                LingButton("恢复默认", BtnKind.Text) { list.clear(); list.addAll(DEFAULT_GOLDEN) }
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
                    if (r != null) Text((if (r.pass) "通过" else "待改") + (if (r.chapter > 0) " · 第 ${r.chapter} 章" else "") + if (r.evidence.isNotBlank()) "：${r.evidence}" else "",
                        style = Ling.type.caption.copy(lineHeight = 18.sp), color = if (r.pass) c.onSurfaceVariant else c.error)
                }
            }
        }
    }
}
