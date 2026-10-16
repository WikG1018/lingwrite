// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.BookPlanEntity
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.VolumePlanEntity
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.ai.AiErrorCard
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.ai.adoptFlash
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * 总纲工作台（C3，docs/v0.2/outline-chain.md §5）：
 * ①创意 → ②核心设定 → ③总纲 → ④卷纲 → ⑤章纲 → ⑥第一章。
 * 每一步 AI 给候选、作者微调后采纳；候选与上游指纹存在 book_plans.chain，
 * 改了上游后下游节点显示「上游已改，可重新生成」（ChainStore.changed）。
 */
@Composable
fun ChainRoute(bookId: Long, nav: NavHostController, startStep: Int = 0) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.plan()
    val plan by remember(bookId) { dao.book(bookId) }.collectAsState(null)
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    val vols by remember(bookId) { AppGraph.books.volumes(bookId) }.collectAsState(emptyList())
    val vplans by remember(bookId) { dao.volumes(bookId) }.collectAsState(emptyList())
    val bp = plan ?: BookPlanEntity(bookId)
    var step by remember { mutableStateOf(startStep.coerceIn(0, 6).takeIf { it > 0 } ?: bp.chainStep.coerceIn(0, 6).let { if (it == 0) 1 else it }) }
    LaunchedEffect(startStep) { if (startStep > 0) step = startStep.coerceIn(0, 6) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var justAdopted by remember { mutableStateOf(0) }

    fun done(adoptedStep: Int) { justAdopted = adoptedStep; step = (adoptedStep + 1).coerceAtMost(6) }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.chain_001), book?.title, onBack = { nav.popBackStack() }) {
            LingIconButton(LingIcons.Book, tr(R.string.chain_002), onClick = { nav.navigate(Routes.work(bookId)) })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            (1..6).forEach { s ->
                TimelineRow(
                    step = s, current = step, done = bp.chainStep >= s, label = ChainSteps.labels[s].orEmpty(),
                    summary = stepSummary(bp, s, vols.size), changed = ChainStore.changed(bp, vplans, s),
                    flash = justAdopted == s,
                    onOpen = { step = s; error = null },
                )
                if (step == s) {
                    when (s) {
                        1 -> StepIdea(bookId, bp, book?.genre.orEmpty(), busy, error, { busy = it }, { error = it }) { done(1) }
                        2 -> StepCore(bookId, bp, busy, error, { busy = it }, { error = it }) { done(2) }
                        3 -> StepSynopsis(bookId, bp, busy, error, { busy = it }, { error = it }) { done(3) }
                        4 -> StepVolumes(bookId, bp, vplans, busy, error, { busy = it }, { error = it }) { done(4) }
                        5 -> StepChapters(bookId, bp, vplans, vols, busy, error, { busy = it }, { error = it }) { done(5) }
                        6 -> StepFirst(bookId, bp, vplans, busy, error, { busy = it }, { error = it }, onOpen = { ch -> nav.navigate(Routes.editor(ch)) })
                    }
                }
            }
        }
        // 底部固定条：链路进度与用量
        Box(Modifier.fillMaxWidth().height(44.dp).border(1.dp, c.outlineVariant)) {
            Text(
                if (bp.chainStep >= 6) tr(R.string.chain_003) else tr(R.string.chain_004, bp.chainStep),
                style = Ling.type.caption.copy(fontSize = 12.sp), color = c.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            )
        }
    }
}

private fun stepSummary(bp: BookPlanEntity, s: Int, volumeCount: Int): String = when (s) {
    1 -> bp.logline.ifBlank { bp.idea.ifBlank { tr(R.string.chain_005) } }
    2 -> parseList(bp.sellingPoints).firstOrNull() ?: tr(R.string.chain_006)
    3 -> bp.synopsis.take(40).ifBlank { tr(R.string.chain_007) }
    4 -> if (volumeCount > 0) tr(R.string.chain_008, volumeCount) else tr(R.string.chain_009)
    5 -> tr(R.string.chain_010)
    else -> tr(R.string.chain_011)
}

/** 时间线节点（ui-spec §4）。[flash] = 刚采纳完成，用一次 amber 描边闪一下（motion §22）。 */
@Composable
private fun TimelineRow(step: Int, current: Int, done: Boolean, label: String, summary: String, changed: Boolean, flash: Boolean, onOpen: () -> Unit) {
    val c = Ling.colors
    val glow by animateColorAsState(if (current == step) c.primary else Color.Transparent, tween(Motion.Medium), label = "chainGlow")
    val flashMod = (if (flash) Modifier.adoptFlash(step) else Modifier).enterStagger(step - 1)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).let { if (current == step) it.background(c.surface) else it }
            .then(flashMod).clickable(onClick = onOpen).padding(vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
            // 当前节点：6dp / 18% primary 光晕（ui-spec §4）
            Box(contentAlignment = Alignment.Center) {
                if (current == step) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.18f)))
                }
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .then(if (done || current == step) Modifier.background(c.primary) else Modifier.border(2.dp, c.outline, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(LingIcons.Check, null, tint = c.onPrimary, modifier = Modifier.size(14.dp))
                    else Text("$step", style = Ling.type.micro.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = if (current == step) c.onPrimary else c.onSurfaceVariant)
                }
            }
            if (step < 6) Box(Modifier.width(2.dp).height(18.dp).background(if (done) c.primary else c.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Ling.type.label, color = if (done || current == step) c.onSurface else c.onSurfaceVariant)
                if (changed) Text(tr(R.string.chain_012), style = Ling.type.caption.copy(fontSize = 12.sp), color = c.tertiary, modifier = Modifier.padding(start = 8.dp))
            }
            Text(summary, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---------- ① 创意 → 梗概 ----------

@Composable
private fun StepIdea(bookId: Long, bp: BookPlanEntity, bookGenre: String, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onDone: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var idea by remember(bp.idea) { mutableStateOf(bp.idea) }
    var genre by remember(bookGenre) { mutableStateOf(bookGenre) }
    var platform by remember { mutableStateOf(tr(R.string.book_ai_007)) }
    // 未采纳的候选不从内存里丢：重新进来时从 book_plans.chain 恢复
    var cands by remember(bp.chain) {
        mutableStateOf(ChainState.parse(bp.chain).cands[1]?.let { ChainPrompts.parsePitches(it) } ?: emptyList())
    }
    var pick by remember { mutableStateOf(-1) }
    StepCard(tr(R.string.chain_013)) {
        LingTextField(idea, { idea = it }, label = tr(R.string.chain_014), placeholder = tr(R.string.chain_015))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingTextField(genre, { genre = it }, Modifier.weight(1f), label = tr(R.string.chain_016))
            LingTextField(platform, { platform = it }, Modifier.weight(1f), label = tr(R.string.chain_017))
        }
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_018)) }
        cands.forEachIndexed { i, (title, logline, hook) ->
            val on = i == pick
            LingCard(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .let { m -> if (on) m.border(2.dp, c.primary, RoundedCornerShape(14.dp)) else m }
                    .clickable { pick = i },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. $title", style = Ling.type.subhead, color = c.onSurface, modifier = Modifier.weight(1f))
                        if (hook.isNotBlank()) Pill(hook, PillKind.Amber)
                    }
                    Text(logline, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(
                if (busy) tr(R.string.ai_components_003) else if (cands.isEmpty()) tr(R.string.chain_019) else tr(R.string.ai_components_008), icon = LingIcons.Sparkle,
                enabled = !busy && idea.isNotBlank(), onClick = {
                    scope.launch {
                        onBusy(true); onError(null)
                        val r = ChainRunner.pitches(bookId, idea, genre, platform)
                        if (r.isEmpty()) onError(tr(R.string.chain_020)) else { cands = r; pick = -1 }
                        onBusy(false)
                    }
                },
            )
            if (cands.isNotEmpty()) LingButton(tr(R.string.chain_021), BtnKind.Text, enabled = idea.isNotBlank(), onClick = {
                scope.launch { save(bookId, idea = idea, genre = genre, chainStep = 1); ChainStore.markAdopted(bookId, 1); onDone() }
            })
        }
        if (pick >= 0) LingButton(
            tr(R.string.chain_022), modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp),
        ) {
            val t = cands[pick]
            scope.launch {
                save(bookId, idea = idea, logline = t.second.ifBlank { t.third }, genre = genre, chainStep = 1)
                ChainStore.markAdopted(bookId, 1)
                ChainStore.clearCands(bookId, 1)
                onDone()
            }
        }
    }
}

// ---------- ② 核心设定（卖点；人物/世界观复用向导与设定库） ----------

@Composable
private fun StepCore(bookId: Long, bp: BookPlanEntity, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onDone: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var cands by remember(bp.chain) {
        mutableStateOf(ChainState.parse(bp.chain).cands[2]?.let { ChainPrompts.parseListReply(it) } ?: emptyList())
    }
    val taken = remember(cands) { cands.map { mutableStateOf(true) } }
    StepCard(tr(R.string.chain_023)) {
        Text(tr(R.string.chain_024), style = Ling.type.caption, color = c.onSurfaceVariant)
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_025)) }
        cands.forEachIndexed { i, s ->
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(taken[i].value, { taken[i].value = it })
                Text(s, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.chain_026), icon = LingIcons.Sparkle, enabled = !busy, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    val r = ChainRunner.sellingPoints(bookId)
                    if (r.isEmpty()) onError(tr(R.string.chain_020)) else cands = r
                    onBusy(false)
                }
            })
            LingButton(tr(R.string.chain_022), enabled = cands.any { it.isNotBlank() }, onClick = {
                val chosen = cands.filterIndexed { i, _ -> taken.getOrNull(i)?.value ?: true }
                scope.launch { save(bookId, sellingPoints = chosen, chainStep = 2); ChainStore.markAdopted(bookId, 2); onDone() }
            })
        }
    }
}

// ---------- ③ 总纲（三段，可逐段重写） ----------

@Composable
private fun StepSynopsis(bookId: Long, bp: BookPlanEntity, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onDone: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val parsed = remember(bp.synopsis) { SynopsisFormat.parse(bp.synopsis) }
    var spine by remember(bp.synopsis) { mutableStateOf(parsed.first) }
    var stages by remember(bp.synopsis) { mutableStateOf(parsed.second) }
    var ending by remember(bp.synopsis) { mutableStateOf(parsed.third) }
    StepCard(tr(R.string.chain_027)) {
        LingTextField(spine, { spine = it }, label = tr(R.string.chain_028), singleLine = false)
        stages.forEachIndexed { i, s ->
            Column(Modifier.padding(top = 10.dp)) {
                Text(tr(R.string.chain_029, i + 1), style = Ling.type.caption, color = c.onSurfaceVariant)
                LingTextField(s.name, { st -> stages = stages.toMutableList().also { it[i] = s.copy(name = st) } }, label = tr(R.string.chain_030))
                LingTextField(s.goal, { st -> stages = stages.toMutableList().also { it[i] = s.copy(goal = st) } }, label = tr(R.string.chain_031))
                LingTextField(s.conflict, { st -> stages = stages.toMutableList().also { it[i] = s.copy(conflict = st) } }, label = tr(R.string.chain_032))
                LingTextField(s.end, { st -> stages = stages.toMutableList().also { it[i] = s.copy(end = st) } }, label = tr(R.string.chain_033))
            }
        }
        LingTextField(ending, { ending = it }, Modifier.padding(top = 10.dp), label = tr(R.string.chain_034), singleLine = false, minLines = 3)
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_035)) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.chain_036), icon = LingIcons.Sparkle, enabled = !busy, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    val r = ChainRunner.synopsis(bookId)
                    if (r == null) onError(tr(R.string.chain_020)) else { spine = r.first; stages = r.second; ending = r.third }
                    onBusy(false)
                }
            })
            LingButton(tr(R.string.chain_022), enabled = spine.isNotBlank() || stages.isNotEmpty(), onClick = {
                scope.launch {
                    save(bookId, synopsis = SynopsisFormat.build(spine, stages, ending), chainStep = 3)
                    ChainStore.markAdopted(bookId, 3)
                    onDone()
                }
            })
        }
    }
}

// ---------- ④ 卷纲（拆卷，A7） ----------

@Composable
private fun StepVolumes(bookId: Long, bp: BookPlanEntity, vplans: List<VolumePlanEntity>, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onDone: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var n by remember { mutableStateOf(SynopsisFormat.parse(bp.synopsis).second.size.coerceIn(1, 12).takeIf { it > 0 } ?: 4) }
    var perVolume by remember { mutableStateOf("60") }
    var cands by remember(bp.chain) {
        mutableStateOf(ChainState.parse(bp.chain).cands[4]?.let { ChainPrompts.parseVolumes(it) } ?: emptyList())
    }
    val taken = remember(cands) { cands.map { mutableStateOf(true) } }
    var overwrite by remember { mutableStateOf(false) }
    StepCard(tr(R.string.chain_037)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingTextField("$n", { n = it.filter(Char::isDigit).toIntOrNull() ?: 4 }, Modifier.weight(1f), label = tr(R.string.chain_038))
            LingTextField(perVolume, { perVolume = it.filter(Char::isDigit) }, Modifier.weight(1f), label = tr(R.string.chain_039))
        }
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_040)) }
        cands.forEachIndexed { i, v ->
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(taken[i].value, { taken[i].value = it })
                    Text(VolumeLabel.full(i + 1, v.name), style = Ling.type.subhead, color = c.onSurface, modifier = Modifier.weight(1f))
                    Pill(tr(R.string.chain_041, v.chapters), PillKind.Neutral)
                }
                if (v.goal.isNotBlank()) Text(tr(R.string.chain_042, v.goal), style = Ling.type.body, color = c.onSurface, modifier = Modifier.padding(start = 12.dp))
                if (v.climax.isNotBlank()) Text(tr(R.string.chain_043, v.climax), style = Ling.type.body, color = c.onSurface, modifier = Modifier.padding(start = 12.dp))
                if (v.outline.isNotBlank()) Text(v.outline, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
            }
        }
        // 已有卷时的落法：覆盖同序号（保留卷内章节）或追加在后面
        if (cands.isNotEmpty()) {
            Text(
                if (vplans.isEmpty()) tr(R.string.chain_044) else tr(R.string.chain_045, vplans.size),
                style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton(if (overwrite) tr(R.string.chain_046) else tr(R.string.chain_047), if (overwrite) BtnKind.Tonal else BtnKind.Outlined, height = 34.dp) { overwrite = true }
                LingButton(if (!overwrite) tr(R.string.chain_048) else tr(R.string.chain_049), if (!overwrite) BtnKind.Tonal else BtnKind.Outlined, height = 34.dp) { overwrite = false }
            }
            if (overwrite) Text(tr(R.string.chain_050), style = Ling.type.caption.copy(fontSize = 11.5.sp), color = c.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.chain_051), icon = LingIcons.Sparkle, enabled = !busy, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    val r = ChainRunner.volumes(bookId, n.coerceIn(1, 12))
                    if (r.isEmpty()) onError(tr(R.string.chain_020)) else cands = r
                    onBusy(false)
                }
            })
            if (cands.isNotEmpty()) LingButton(tr(R.string.chain_052), enabled = taken.any { it.value }, onClick = {
                scope.launch {
                    val chosen = cands.filterIndexed { i, _ -> taken[i].value }
                    ChainRunner.adoptVolumes(bookId, chosen, overwrite, cands.indices.filter { taken[it].value })
                    save(bookId, chainStep = 4)
                    ChainStore.markAdopted(bookId, 4, vplans)
                    ChainStore.clearCands(bookId, 4)
                    onDone()
                }
            })
        }
    }
}

// ---------- ⑤ 章纲（含 A7 滚动规划） ----------

@Composable
private fun StepChapters(bookId: Long, bp: BookPlanEntity, vplans: List<VolumePlanEntity>, vols: List<com.lingwrite.app.data.db.VolumeEntity>, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onDone: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var volIdx by remember { mutableStateOf(vols.indexOfFirst { it.id == ChainState.parse(bp.chain).chapterVolumeId }.coerceAtLeast(0)) }
    var n by remember { mutableStateOf("10") }
    var cands by remember(bp.chain) {
        mutableStateOf(ChainState.parse(bp.chain).takeIf { it.chapterVolumeId == vols.getOrNull(volIdx)?.id }?.cands?.get(5)?.let { ChainPrompts.parseChapterPlans(it) } ?: emptyList())
    }
    val taken = remember(cands) { cands.map { mutableStateOf(true) } }
    val vol = vols.getOrNull(volIdx)
    StepCard(tr(R.string.chain_053)) {
        Text(tr(R.string.chain_054), style = Ling.type.caption, color = c.onSurfaceVariant)
        if (vols.isEmpty()) Text(tr(R.string.chain_055), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                vols.forEachIndexed { i, v -> LingButton(VolumeLabel.short(i + 1), if (i == volIdx) BtnKind.Tonal else BtnKind.Text, enabled = !busy, height = 36.dp) {
                    volIdx = i; cands = emptyList(); onError(null)
                } }
            }
            LingTextField(n, { n = it.filter(Char::isDigit) }, Modifier.padding(top = 10.dp), label = tr(R.string.chain_056))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("3", "5", "10").forEach { k ->
                    LingButton(if (n == k) tr(R.string.chain_057, k) else tr(R.string.chain_041, k), if (n == k) BtnKind.Tonal else BtnKind.Outlined, height = 32.dp) { n = k }
                }
            }
        }
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_058)) }
        cands.forEachIndexed { i, s ->
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(taken[i].value, { taken[i].value = it })
                    Text(s.title.ifBlank { tr(R.string.chain_059, i + 1) }, style = Ling.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
                }
                Text(tr(R.string.chain_060, s.event, s.hook, s.cliff), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.chain_061), icon = LingIcons.Sparkle, enabled = !busy && vol != null, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    val v = vol ?: return@launch
                    val hint = OutlineTemplates.filter { it.id in bp.templates.split(',') }.joinToString(" ") { it.hint }
                    val r = ChainRunner.chapters(bookId, v.id, n.toIntOrNull() ?: 10, hint)
                    if (r.isEmpty()) onError(tr(R.string.chain_020)) else cands = r
                    onBusy(false)
                }
            })
            if (cands.isNotEmpty()) LingButton(tr(R.string.chain_062), enabled = taken.any { it.value }, onClick = {
                scope.launch {
                    val v = vol ?: return@launch
                    if (ChainState.parse(AppGraph.db.plan().bookOnce(bookId)?.chain.orEmpty()).chapterVolumeId != v.id) { onError(tr(R.string.chain_063)); return@launch }
                    val chosen = cands.filterIndexed { i, _ -> taken[i].value }
                    ChainRunner.adoptChapters(bookId, v.id, chosen)
                    save(bookId, chainStep = 5)
                    ChainStore.markAdopted(bookId, 5, vplans)
                    ChainStore.clearCands(bookId, 5)
                    onDone()
                }
            })
        }
    }
}

private fun volIdxOf(vols: List<com.lingwrite.app.data.db.VolumeEntity>, v: com.lingwrite.app.data.db.VolumeEntity): Int = vols.sortedBy { it.sort }.indexOfFirst { it.id == v.id }.let { if (it < 0) 0 else it }

// ---------- ⑥ 第一章（按模式区分，modes.md §5） ----------

@Composable
private fun StepFirst(bookId: Long, bp: BookPlanEntity, vplans: List<VolumePlanEntity>, busy: Boolean, error: String?, onBusy: (Boolean) -> Unit, onError: (String?) -> Unit, onOpen: (Long) -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val mode = remember(bookId) { mutableStateOf(WriteMode.Assist) }
    LaunchedEffect(bookId) { mode.value = com.lingwrite.app.data.modeFor(bookId) }
    var beats by remember { mutableStateOf<List<String>>(emptyList()) }
    var openings by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val first = chs.firstOrNull()
    StepCard(tr(R.string.chain_064, trText(mode.value.label))) {
        Text(
            if (mode.value == WriteMode.Assist) tr(R.string.chain_065) else tr(R.string.chain_066),
            style = Ling.type.caption, color = c.onSurfaceVariant,
        )
        if (error != null) AiErrorCard(error, modifier = Modifier.padding(top = 10.dp), onRetry = { onError(null) })
        if (busy) Box(Modifier.padding(top = 12.dp)) { AiThinking(tr(R.string.chain_067)) }
        beats.forEachIndexed { i, b -> Text("${i + 1}. $b", style = Ling.type.bodyLarge.copy(lineHeight = 26.sp), color = c.onSurface, modifier = Modifier.padding(top = 6.dp)) }
        openings.forEach { (t, e) ->
            Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                Text(t, style = Ling.type.body, color = c.onSurface)
                Text(e, style = Ling.type.caption, color = c.primary)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.chain_068), icon = LingIcons.Sparkle, enabled = !busy && first != null, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    val ch = first ?: return@launch
                    val r = ChainRunner.beats(bookId, ch.id)
                    if (r.isEmpty()) onError(tr(R.string.chain_020)) else beats = r
                    onBusy(false)
                }
            })
            if (mode.value == WriteMode.Assist && beats.isNotEmpty()) LingButton(tr(R.string.chain_069), BtnKind.Text, enabled = !busy, onClick = {
                scope.launch {
                    onBusy(true); onError(null)
                    openings = ChainRunner.openings(bookId, beats)
                    onBusy(false)
                }
            })
        }
        if (beats.isNotEmpty()) LingButton(
            if (mode.value == WriteMode.Assist) tr(R.string.chain_070) else tr(R.string.chain_071),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp),
        ) {
            scope.launch {
                val chId = first?.id ?: return@launch
                ChainRunner.adoptBeats(bookId, chId, beats, openings, mode.value == WriteMode.Assist)
                save(bookId, chainStep = 6)
                ChainStore.markAdopted(bookId, 6, vplans)
                onOpen(chId)
            }
        }
        if (first != null && mode.value == WriteMode.Generate) LingButton(tr(R.string.chain_072), BtnKind.Text, modifier = Modifier.padding(top = 6.dp), onClick = { onOpen(first.id) })
    }
}

@Composable
private fun StepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    LingCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, style = Ling.type.label, color = Ling.colors.primary); Spacer(Modifier.height(8.dp)); content() } }
}

/** 保存链路进度与字段（写 book_plans）。 */
private suspend fun save(bookId: Long, idea: String? = null, logline: String? = null, genre: String? = null, sellingPoints: List<String>? = null, synopsis: String? = null, chainStep: Int? = null) {
    val dao = AppGraph.db.plan()
    val bp = dao.bookOnce(bookId) ?: BookPlanEntity(bookId)
    dao.upsertBook(
        bp.copy(
            idea = idea ?: bp.idea, logline = logline ?: bp.logline,
            sellingPoints = sellingPoints?.let { encodeList(it) } ?: bp.sellingPoints,
            synopsis = synopsis ?: bp.synopsis, chainStep = chainStep?.coerceAtLeast(bp.chainStep) ?: bp.chainStep,
            chain = bp.chain,
        )
    )
    if (genre != null) AppGraph.db.books().get(bookId)?.let { AppGraph.books.updateBook(it.copy(genre = genre)) }
}
