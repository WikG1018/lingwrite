// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object WizardModule {
    fun install() {
        ExtraDestinations.list.add { nav: NavHostController ->
            composable(Routes.Wizard) { e: androidx.navigation.NavBackStackEntry -> WizardRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
        }
    }
}

private data class StepInfo(val title: String, val desc: String, val example: String)
private val Steps get() = listOf(
    StepInfo(tr(R.string.ai_components_001), tr(R.string.wizard_001), tr(R.string.wizard_002)),
    StepInfo(tr(R.string.wizard_003), tr(R.string.wizard_004), tr(R.string.wizard_005)),
    StepInfo(tr(R.string.wizard_006), tr(R.string.wizard_007), tr(R.string.wizard_008)),
    StepInfo(tr(R.string.wizard_009), tr(R.string.wizard_010), tr(R.string.wizard_011)),
    StepInfo(tr(R.string.wizard_012), tr(R.string.wizard_013), tr(R.string.wizard_014)),
    StepInfo(tr(R.string.wizard_015), tr(R.string.wizard_016), tr(R.string.wizard_017)),
    StepInfo(tr(R.string.wizard_018), tr(R.string.wizard_019), tr(R.string.wizard_020)),
    StepInfo(tr(R.string.wizard_021), tr(R.string.wizard_022), tr(R.string.wizard_023)),
)

@Composable
fun WizardRoute(bookId: Long, nav: NavHostController) {
    val dao = AppGraph.db.plan()
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<WizardData?>(null) }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(bookId) {
        val b = AppGraph.db.books().get(bookId)
        data = WizardData.parse(dao.bookOnce(bookId)?.wizard.orEmpty())
        step = (b?.wizardStep ?: 0).coerceIn(0, 7)
    }
    val d = data ?: return
    fun save(nd: WizardData, ns: Int = step) {
        data = nd; step = ns
        scope.launch {
            dao.upsertBook((dao.bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(wizard = nd.encode()))
            AppGraph.db.books().get(bookId)?.let { AppGraph.books.updateBook(it.copy(wizardStep = ns)) }
        }
    }
    WizardScreen(step, d, onChange = { save(it) }, onBack = { if (step == 0) nav.popBackStack() else save(d, step - 1) },
        onClose = { nav.popBackStack() }, onSkip = { if (step == 7) scope.launch { finish(bookId, d, false); nav.navigate(Routes.editor(AppGraph.db.chapters().chaptersOnce(bookId).first().id)) { popUpTo(Routes.Shelf) } } else save(d, step + 1) },
        onAdopt = {
            scope.launch {
                adopt(bookId, step, d)
                if (step == 7) { finish(bookId, d, true); nav.navigate(Routes.editor(AppGraph.db.chapters().chaptersOnce(bookId).first().id)) { popUpTo(Routes.Shelf) } } else save(d, step + 1)
            }
        }, bookId = bookId)
}

/** 每步“采纳并继续”时把结果写入对应模块。 */
internal suspend fun adopt(bookId: Long, step: Int, d: WizardData) {
    val plan = AppGraph.db.plan()
    val lore = AppGraph.db.lore()
    val bp = plan.bookOnce(bookId) ?: BookPlanEntity(bookId)
    when (step) {
        0 -> plan.upsertBook(bp.copy(idea = d.idea.ifBlank { bp.idea }, logline = d.premise.ifBlank { bp.logline }, chainStep = 1))
        1 -> {
            val fields = (parseList(bp.fields) + OutlineTemplates.filter { it.id in d.templates }.flatMap { it.fields }).distinct()
            plan.upsertBook(bp.copy(templates = d.templates.joinToString(","), fields = encodeList(fields), style = d.style, chainStep = maxOf(bp.chainStep, 1)))
            AppGraph.db.books().get(bookId)?.let { if (d.genre.isNotBlank()) AppGraph.books.updateBook(it.copy(genre = d.genre)) }
        }
        2 -> {
            val have = lore.cardsOnce(bookId).map { it.name }.toSet()
            d.chars.filter { it.take && it.name.isNotBlank() && it.name !in have }.forEach { ch ->
                lore.insertCard(LoreCardEntity(bookId = bookId, type = "character", name = ch.name, aliases = ch.aliases, role = ch.role,
                    fields = encodeFields(listOf(Field(tr(R.string.wizard_024), ch.identity), Field(tr(R.string.wizard_025), ch.personality), Field(tr(R.string.wizard_026), ch.motive), Field(tr(R.string.wizard_027), ch.cheat)).filter { it.v.isNotBlank() }),
                    inject = if (ch.role.contains("主角")) "always" else "auto"))
            }
            // 核心设定（②）完成 → chainStep 记到 2
            plan.upsertBook(bp.copy(chainStep = maxOf(bp.chainStep, 2)))
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 2)
        }
        3 -> {
            val have = lore.cardsOnce(bookId).map { it.name }.toSet()
            d.world.filter { it.take && it.name.isNotBlank() && it.name !in have }.forEach { w ->
                val t = LoreType.of(w.type)
                lore.insertCard(LoreCardEntity(bookId = bookId, type = t.id, name = w.name, fields = encodeFields(listOf(Field(defaultFields(t).last().let { if (t == LoreType.Power) tr(R.string.wizard_028) else tr(R.string.wizard_029) }, w.desc))), inject = if (t == LoreType.Power) "always" else "auto"))
            }
            plan.upsertBook(bp.copy(chainStep = maxOf(bp.chainStep, 2)))
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 2)
        }
        4 -> {
            plan.upsertBook(bp.copy(synopsis = d.synopsis, chainStep = maxOf(bp.chainStep, 4)))
            val vols = AppGraph.db.chapters().volumesOnce(bookId)
            val v = vols.firstOrNull()?.id ?: AppGraph.books.addVolume(bookId, Naming.stripVolume(d.volumeTitle))
            vols.firstOrNull()?.let { AppGraph.db.chapters().updateVolume(it.copy(title = Naming.stripVolume(d.volumeTitle).ifBlank { it.title })) }
            plan.upsertVolume(VolumePlanEntity(v, bookId, d.volumeOutline))
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 3)
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 4, plan.volumes(bookId).first())
        }
        5 -> AppGraph.db.books().get(bookId)?.let { AppGraph.books.updateBook(it.copy(title = d.title.ifBlank { it.title }, blurb = d.blurb)) }
        6 -> {
            val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
            val vol = AppGraph.db.chapters().volumesOnce(bookId).firstOrNull()?.id ?: AppGraph.books.addVolume(bookId, Naming.stripVolume(d.volumeTitle))
            d.chapters.take(3).forEachIndexed { i, cd ->
                val id = chs.getOrNull(i)?.also { AppGraph.books.renameChapter(it.id, cd.title.ifBlank { it.title }) }?.id ?: AppGraph.books.addChapterTo(bookId, vol, cd.title)
                plan.upsertChapter(ChapterPlanEntity(id, bookId, cd.event, cd.hook, cd.cliff, threads = tr(R.string.outline_069), fromAi = true))
            }
            plan.upsertBook(bp.copy(chainStep = maxOf(bp.chainStep, 5)))
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 5, plan.volumes(bookId).first())
        }
        7 -> {
            // 第一章：节拍写进 chapter_plans.beats，链路标到 6（outline-chain §4）
            val ch = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }.firstOrNull() ?: return
            val cp = plan.chapterOnce(ch.id) ?: ChapterPlanEntity(ch.id, bookId)
            // 第 8 步里已经生成过节拍就保留生成的（和总纲工作台第 ⑥ 步同一套 ChainRunner）
            if (parseBeats(cp.beats).isEmpty()) {
                val beatLines = d.chapters.firstOrNull()?.let { listOfNotNull(it.event, it.hook, it.cliff) } ?: emptyList()
                if (beatLines.isNotEmpty()) plan.upsertChapter(cp.copy(beats = encodeBeats(beatLines.map { com.lingwrite.app.data.Beat(it) })))
            }
            val bp2 = plan.bookOnce(bookId) ?: BookPlanEntity(bookId)
            plan.upsertBook(bp2.copy(chainStep = 6, idea = bp2.idea.ifBlank { d.idea }, logline = bp2.logline.ifBlank { d.premise }))
            com.lingwrite.app.data.ChainStore.markAdopted(bookId, 6, plan.volumes(bookId).first())
        }
    }
}

internal suspend fun finish(bookId: Long, d: WizardData, adopted: Boolean) {
    AppGraph.db.books().get(bookId)?.let { AppGraph.books.updateBook(it.copy(status = "outline", wizardStep = 8, title = it.title.ifBlank { d.title.ifBlank { d.pitches.firstOrNull()?.title ?: tr(R.string.wizard_030) } })) }
    if (AppGraph.db.chapters().chaptersOnce(bookId).isEmpty()) AppGraph.books.nextChapter(bookId)
}

@Composable
fun WizardScreen(step: Int, d: WizardData, onChange: (WizardData) -> Unit, onBack: () -> Unit, onClose: () -> Unit, onSkip: () -> Unit, onAdopt: () -> Unit, bookId: Long = 0) {
    val c = Ling.colors
    val info = Steps[step]
    var busy by remember { mutableStateOf(false) }
    var err by remember(step) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun ask(prompt: String, array: Boolean = true, max: Int = 2000, apply: (JsonElement) -> WizardData?) {
        busy = true; err = null
        scope.launch {
            AppGraph.ai.complete("wizard", WizardPrompts.SYS, prompt, bookId, null, max, Slot.Writing).onSuccess { raw ->
                val j = extractJson(raw, array = array)
                val nd = j?.let { runCatching { apply(it) }.getOrNull() }
                if (nd == null) err = tr(R.string.wizard_031) else onChange(nd)
            }.onFailure { err = it.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.editor_ai_024), tr(R.string.wizard_032, step + 1), onBack = onBack) { LingIconButton(LingIcons.Close, tr(R.string.wizard_033), onClick = onClose) }
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(8) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) c.primary else c.surfaceContainer)) }
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { (slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 8 } + fadeIn()) togetherWith fadeOut(tween(Motion.Short)) }, label = "step") { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Steps[s].title, style = Ling.type.headline, color = c.onSurface)
                Text(Steps[s].desc, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant)
                Text(Steps[s].example, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp))
                when (s) {
                    0 -> StepIdea(d, onChange, busy) { ask(WizardPrompts.pitches(d.idea)) { j -> d.copy(pitches = j.jsonArray.map { val o = it.jsonObject; Pitch(o.str("title"), o.str("logline")) }) } }
                    1 -> StepStyle(d, onChange, busy, bookId) { e -> err = e }
                    2 -> StepChars(d, onChange, busy) { ask(WizardPrompts.chars(d), max = 2500) { j -> d.copy(chars = j.jsonArray.map { val o = it.jsonObject; CharDraft(o.str("name"), o.str("role"), o.str("aliases"), o.str("identity"), o.str("personality"), o.str("motive"), o.str("cheat")) }) } }
                    3 -> StepWorld(d, onChange, busy) { ask(WizardPrompts.world(d)) { j -> d.copy(world = j.jsonArray.map { val o = it.jsonObject; WorldDraft(o.str("type").ifBlank { "term" }, o.str("name"), o.str("desc")) }) } }
                    4 -> StepSynopsis(d, onChange, busy) { ask(WizardPrompts.synopsis(d), array = false) { j -> val o = j.jsonObject; d.copy(synopsis = o.str("synopsis"), volumeTitle = Naming.stripVolume(o.str("volumeTitle")), volumeOutline = o.str("volumeOutline")) } }
                    5 -> StepTitles(d, onChange, busy) { ask(WizardPrompts.titles(d), array = false) { j -> val o = j.jsonObject; d.copy(titles = o["titles"]!!.jsonArray.map { it.jsonPrimitive.content }, blurbs = o["blurbs"]!!.jsonArray.map { it.jsonPrimitive.content }) } }
                    6 -> StepChapters(d, onChange, busy) { ask(WizardPrompts.chapters(d, DEFAULT_GOLDEN), max = 2000) { j -> d.copy(chapters = j.jsonArray.map { val o = it.jsonObject; ChapterDraft(o.str("title"), o.str("event"), o.str("hook"), o.str("cliff")) }) } }
                    else -> StepFirstChapter(bookId, d)
                }
                err?.let { Text(it, style = Ling.type.caption, color = c.error) }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LingButton(tr(R.string.stuck_002), BtnKind.Outlined, height = 50.dp, modifier = Modifier.weight(1f)) { onSkip() }
            LingButton(if (step == 7) tr(R.string.wizard_034) else tr(R.string.chain_022), height = 50.dp, enabled = !busy && canAdopt(step, d), modifier = Modifier.weight(2f)) { onAdopt() }
        }
    }
}

private fun canAdopt(step: Int, d: WizardData) = when (step) {
    0 -> d.premise.isNotBlank(); 2 -> d.chars.any { it.take }; 3 -> d.world.any { it.take }; 4 -> d.synopsis.isNotBlank()
    5 -> d.title.isNotBlank(); 6 -> d.chapters.isNotEmpty(); else -> true
}

/**
 * 第 8 步（第一章）：真的生成场景节拍与开篇示意。
 * 复用数据层的 [com.lingwrite.app.data.ChainRunner]（和总纲工作台第 ⑥ 步同一套提示词与落库逻辑）：
 * 辅助模式的产出只进本章便签，绝不进正文。
 */
@Composable
private fun StepFirstChapter(bookId: Long, d: WizardData) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var chapterId by remember { mutableStateOf(0L) }
    var beats by remember { mutableStateOf<List<String>>(emptyList()) }
    var openings by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val mode = remember(bookId) { mutableStateOf(WriteMode.Assist) }
    LaunchedEffect(bookId) {
        mode.value = com.lingwrite.app.data.modeFor(bookId)
        chapterId = AppGraph.db.chapters().chaptersOnce(bookId).minByOrNull { it.number }?.id
            ?: AppGraph.books.nextChapter(bookId, d.chapters.firstOrNull()?.title.orEmpty())
    }
    LingCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.wizard_035, trText(mode.value.label)), style = Ling.type.label, color = c.primary)
            Text(
                if (mode.value == WriteMode.Assist) tr(R.string.wizard_036)
                else tr(R.string.wizard_037),
                style = Ling.type.caption, color = c.onSurfaceVariant,
            )
            if (busy) com.lingwrite.app.ui.ai.AiThinking(tr(R.string.chain_067))
            beats.forEachIndexed { i, b -> Text("${i + 1}. $b", style = Ling.type.bodyLarge.copy(lineHeight = 24.sp), color = c.onSurface) }
            openings.forEach { (t, e) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer).padding(10.dp)) {
                    Text(t, style = Ling.type.body, color = c.onSurface)
                    Text(e, style = Ling.type.caption, color = c.primary)
                }
            }
            err?.let { Text(it, style = Ling.type.caption, color = c.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton(if (busy) tr(R.string.ai_components_003) else if (beats.isEmpty()) tr(R.string.chain_068) else tr(R.string.wizard_038), BtnKind.Tonal, icon = LingIcons.SparkleFilled, enabled = !busy && chapterId != 0L, height = 40.dp) {
                    busy = true; err = null
                    scope.launch {
                        val r = com.lingwrite.app.data.ChainRunner.beats(bookId, chapterId)
                        if (r.isEmpty()) err = tr(R.string.chain_020) else {
                            beats = r
                            // 立刻写进 chapter_plans.beats（生成模式进编辑器就能按节拍起草）
                            com.lingwrite.app.data.ChainRunner.adoptBeats(bookId, chapterId, beats, emptyList(), assistMode = false)
                        }
                        busy = false
                    }
                }
                if (mode.value == WriteMode.Assist && beats.isNotEmpty()) LingButton(tr(R.string.chain_069), BtnKind.Text, height = 40.dp, enabled = !busy) {
                    busy = true; err = null
                    scope.launch {
                        openings = com.lingwrite.app.data.ChainRunner.openings(bookId, beats)
                        // 开篇示意只进便签（辅助模式红线）
                        com.lingwrite.app.data.ChainRunner.adoptBeats(bookId, chapterId, beats, openings, assistMode = true)
                        busy = false
                    }
                }
            }
            Text(tr(R.string.wizard_039), style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiButton(text: String, busy: Boolean, again: Boolean, onClick: () -> Unit) =
    LingButton(if (busy) tr(R.string.wizard_040) else if (again) tr(R.string.ai_components_008) else text, if (again) BtnKind.Tonal else BtnKind.Filled, icon = LingIcons.SparkleFilled, enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = onClick)

@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Ling.colors
    LingCard(Modifier.fillMaxWidth().border(1.5.dp, if (selected) c.primary else Color.Transparent, RoundedCornerShape(14.dp)), color = if (selected) c.primaryContainer.copy(alpha = .35f) else c.surface) {
        Column(Modifier.fillMaxWidth().rowClick(onClick).padding(14.dp), content = content)
    }
}

@Composable
private fun StepIdea(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    val c = Ling.colors
    LingTextField(d.idea, { onChange(d.copy(idea = it)) }, label = tr(R.string.wizard_041), singleLine = false, minLines = 2)
    AiButton(tr(R.string.wizard_042), busy || d.idea.isBlank(), d.pitches.isNotEmpty(), gen)
    d.pitches.forEach { p ->
        Choice(d.premise == p.logline, { onChange(d.copy(premise = p.logline)) }) {
            Text(p.title, style = Ling.type.titleSmall, color = c.onSurface)
            Text(p.logline, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (d.pitches.isNotEmpty() || d.premise.isNotBlank()) LingTextField(d.premise, { onChange(d.copy(premise = it)) }, label = tr(R.string.wizard_043), singleLine = false, minLines = 3)
}

@Composable
private fun StepStyle(d: WizardData, onChange: (WizardData) -> Unit, busy0: Boolean, bookId: Long, onErr: (String?) -> Unit) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; onErr(null)
        scope.launch {
            val text = withContext(Dispatchers.IO) { uris.joinToString("\n\n") { u -> ctx.contentResolver.openInputStream(u)?.bufferedReader()?.readText().orEmpty() } }
            AppGraph.ai.complete("style", trZh(R.string.style_009), WizardPrompts.style(text), bookId, null, 600, Slot.Writing)
                .onSuccess { onChange(d.copy(style = it.trim())) }.onFailure { onErr(it.message) }
            busy = false
        }
    }
    Text(tr(R.string.wizard_044), style = Ling.type.label, color = c.primary)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Genres.forEach { g -> LingChip(d.genre == g, { onChange(d.copy(genre = if (d.genre == g) "" else g)) }, g) }
    }
    Text(tr(R.string.outline_032), style = Ling.type.label, color = c.primary)
    OutlineTemplates.forEach { t ->
        val on = t.id in d.templates
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).rowClick { onChange(d.copy(templates = if (on) d.templates - t.id else d.templates + t.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, null)
            Column { Text(t.name, style = Ling.type.bodyLarge, color = c.onSurface); Text(t.desc, style = Ling.type.caption, color = c.onSurfaceVariant) }
        }
    }
    Text(tr(R.string.wizard_045), style = Ling.type.label, color = c.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(tr(R.string.wizard_046), tr(R.string.wizard_047), tr(R.string.wizard_048)).forEach { r -> LingChip(d.reader == r, { onChange(d.copy(reader = if (d.reader == r) "" else r)) }, r) }
    }
    Text(tr(R.string.wizard_049), style = Ling.type.label, color = c.primary)
    LingButton(if (busy) tr(R.string.wizard_050) else tr(R.string.wizard_051), BtnKind.Tonal, icon = LingIcons.Add, enabled = !busy && !busy0, modifier = Modifier.fillMaxWidth()) { picker.launch("text/*") }
    if (d.style.isNotBlank()) LingTextField(d.style, { onChange(d.copy(style = it)) }, label = tr(R.string.wizard_052), singleLine = false, minLines = 4)
    // A12 入口（ai-features §12）：从样章学结构 → 拆文页
    val navHost = LocalNav.current
    LingButton(tr(R.string.wizard_053), BtnKind.Outlined, icon = LingIcons.Graph, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        navHost.navigate(com.lingwrite.app.ui.Routes.breakdown(bookId))
    }
}

@Composable
private fun StepChars(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    val c = Ling.colors
    AiButton(tr(R.string.wizard_054), busy, d.chars.isNotEmpty(), gen)
    d.chars.forEachIndexed { i, ch ->
        fun up(n: CharDraft) = onChange(d.copy(chars = d.chars.toMutableList().also { it[i] = n }))
        Choice(ch.take, { up(ch.copy(take = !ch.take)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(avatarColor(i.toLong() + 1)), contentAlignment = Alignment.Center) { Text(ch.name.take(1), color = Color.White, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold)) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(ch.name, style = Ling.type.titleSmall, color = c.onSurface)
                    Text(listOf(ch.role, ch.identity).filter { it.isNotBlank() }.joinToString(" · "), style = Ling.type.caption, color = c.onSurfaceVariant)
                }
                Checkbox(ch.take, { up(ch.copy(take = it)) })
            }
            if (ch.take) {
                Spacer(Modifier.height(8.dp))
                LingTextField(ch.personality, { up(ch.copy(personality = it)) }, label = tr(R.string.wizard_025), singleLine = false)
                LingTextField(ch.motive, { up(ch.copy(motive = it)) }, label = tr(R.string.wizard_055), singleLine = false)
                if (ch.cheat.isNotBlank() || ch.role.contains("主角")) LingTextField(ch.cheat, { up(ch.copy(cheat = it)) }, label = tr(R.string.wizard_027), singleLine = false)
            }
        }
    }
}

@Composable
private fun StepWorld(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    val c = Ling.colors
    AiButton(tr(R.string.wizard_056), busy, d.world.isNotEmpty(), gen)
    d.world.forEachIndexed { i, w ->
        fun up(n: WorldDraft) = onChange(d.copy(world = d.world.toMutableList().also { it[i] = n }))
        Choice(w.take, { up(w.copy(take = !w.take)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tile(typeIcon(LoreType.of(w.type)), c.surfaceContainer, c.onSurfaceVariant, 34.dp)
                Text(w.name, style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.padding(start = 12.dp).weight(1f))
                Pill(trText(LoreType.of(w.type).label), PillKind.Neutral)
                Checkbox(w.take, { up(w.copy(take = it)) })
            }
            if (w.take) LingTextField(w.desc, { up(w.copy(desc = it)) }, Modifier.padding(top = 8.dp), singleLine = false)
        }
    }
}

@Composable
private fun StepSynopsis(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    AiButton(tr(R.string.wizard_057), busy, d.synopsis.isNotBlank(), gen)
    if (d.synopsis.isNotBlank()) {
        LingTextField(d.synopsis, { onChange(d.copy(synopsis = it)) }, label = tr(R.string.outline_009), singleLine = false, minLines = 5)
        LingTextField(d.volumeTitle, { onChange(d.copy(volumeTitle = it)) }, label = tr(R.string.wizard_058), placeholder = tr(R.string.wizard_059))
        LingTextField(d.volumeOutline, { onChange(d.copy(volumeOutline = it)) }, label = tr(R.string.wizard_060), singleLine = false, minLines = 4)
    }
}

@Composable
private fun StepTitles(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    val c = Ling.colors
    AiButton(tr(R.string.wizard_061), busy, d.titles.isNotEmpty(), gen)
    if (d.titles.isNotEmpty()) {
        Text(tr(R.string.wizard_062), style = Ling.type.label, color = c.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.titles.forEach { t -> LingChip(d.title == t, { onChange(d.copy(title = t)) }, t) }
        }
    }
    LingTextField(d.title, { onChange(d.copy(title = it)) }, label = tr(R.string.wizard_062))
    if (d.blurbs.isNotEmpty()) Text(tr(R.string.wizard_063), style = Ling.type.label, color = c.primary)
    d.blurbs.forEachIndexed { i, b ->
        Choice(d.blurb == b, { onChange(d.copy(blurb = b)) }) {
            Text(tr(R.string.wizard_064, i + 1), style = Ling.type.caption, color = c.primary)
            Text(b, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurface)
        }
    }
    if (d.blurb.isNotBlank() || d.blurbs.isNotEmpty()) LingTextField(d.blurb, { onChange(d.copy(blurb = it)) }, label = tr(R.string.wizard_065), singleLine = false, minLines = 3)
}

@Composable
private fun StepChapters(d: WizardData, onChange: (WizardData) -> Unit, busy: Boolean, gen: () -> Unit) {
    val c = Ling.colors
    AiButton(tr(R.string.wizard_066), busy, d.chapters.isNotEmpty(), gen)
    d.chapters.forEachIndexed { i, cd ->
        fun up(n: ChapterDraft) = onChange(d.copy(chapters = d.chapters.toMutableList().also { it[i] = n }))
        LingCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.breakdown_015, i + 1), style = Ling.type.caption, color = c.primary)
                LingTextField(cd.title, { up(cd.copy(title = it)) }, label = tr(R.string.wizard_067))
                LingTextField(cd.event, { up(cd.copy(event = it)) }, label = tr(R.string.outline_051), singleLine = false)
                LingTextField(cd.hook, { up(cd.copy(hook = it)) }, label = tr(R.string.outline_052), singleLine = false)
                LingTextField(cd.cliff, { up(cd.copy(cliff = it)) }, label = tr(R.string.outline_053), singleLine = false)
            }
        }
    }
    if (d.chapters.isNotEmpty()) {
        Text(tr(R.string.wizard_068), style = Ling.type.label, color = c.primary)
        LingCard(color = c.surfaceContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val all = d.chapters.joinToString(" ") { it.event + it.hook + it.cliff }
                val protagonist = d.chars.firstOrNull { it.role.contains("主角") }?.name
                DEFAULT_GOLDEN.forEachIndexed { i, g ->
                    val hint = when (i) {
                        0 -> protagonist?.let { if (d.chapters.firstOrNull()?.let { c1 -> (c1.event + c1.hook).contains(it) } == true) true else null }
                        4 -> d.chapters.getOrNull(2)?.cliff?.isNotBlank()
                        else -> null
                    }
                    Text((when (hint) { true -> "✓ "; false -> "✗ "; else -> "· " }) + g, style = Ling.type.caption, color = if (hint == false) c.error else c.onSurfaceVariant)
                }
                Text(tr(R.string.wizard_069), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (all.isBlank()) Spacer(Modifier)
            }
        }
    }
}
