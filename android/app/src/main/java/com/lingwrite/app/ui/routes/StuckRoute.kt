// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.StuckPrompts
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingConfirm
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.SheetLayout
import com.lingwrite.app.ui.components.LingSheet
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.pressScale
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

/**
 * 卡文急救（A5，ui-spec §6）：3 步 Sheet。
 * ①选卡点 → ②澄清问题（可跳过）→ ③3 个方向卡（存便签 / 改进章纲 / 生成模式起草一段）。
 */
@Composable
fun StuckSheet(
    chapterId: Long,
    bookId: Long,
    isGenerate: Boolean,
    onDismiss: () -> Unit,
    onDraftAWay: ((StuckPrompts.Way) -> Unit)? = null,
) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }
    var kind by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var clarifies by remember { mutableStateOf<List<StuckPrompts.Clarify>>(emptyList()) }
    val answers = remember { mutableStateListOf<String>() }
    var ways by remember { mutableStateOf<List<StuckPrompts.Way>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    // 改进章纲（A5）：先看 diff 再确认，落库后可撤销
    var outlineFix by remember { mutableStateOf<StuckPrompts.Way?>(null) }
    var outlineBefore by remember { mutableStateOf("") }
    var outlineApplied by remember { mutableStateOf(false) }
    LaunchedEffect(outlineFix) {
        outlineFix?.let { outlineBefore = AppGraph.db.plan().chapterOnce(chapterId)?.note.orEmpty() }
    }

    fun saveWay(w: StuckPrompts.Way) {
        scope.launch {
            AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = chapterId, kind = "stuck", text = "【${w.title}】${w.how}"))
            onDismiss()
        }
    }

    LingSheet(onDismiss, "卡文急救 · 第 $step 步 / 3") {
        if (step == 3 && !busy && ways.isNotEmpty()) {
            // 第 3 步整屏给方向卡：横向翻页，卡宽 300dp（ui-spec §6）
            val chips = listOf(kind) + clarifies.mapIndexed { i, cl ->
                val q = cl.q.trim('？', '?', '。')
                "$q → ${answers.getOrNull(i)?.ifBlank { "跳过" } ?: "跳过"}"
            }
            StuckWaysPager(
                ways = ways, chips = chips.filter { it.isNotBlank() }, isGenerate = isGenerate,
                onSave = ::saveWay,
                onOutline = { w -> outlineFix = w },
                onDraftAWay = onDraftAWay,
            )
            return@LingSheet
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                1 -> {
                    listOf(
                        "不知道怎么过渡" to "上一场和下一场接不上",
                        "大纲走不通了" to "章纲写不下去或和正文冲突",
                        "没状态" to "就是不想写，或写不动",
                        "人物不知道该怎么反应" to "人物卡住了",
                        "其他" to "自己写一句",
                    ).forEachIndexed { si, (t, sub) ->
                        val on = kind == t
                        val i = remember { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().enterStagger(si).pressScale(i, 0.97f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) c.primaryContainer else c.surfaceContainer)
                                .clickable(i, indication = null) { kind = t; step = 2 }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(LingIcons.Idea, null, tint = if (on) c.onPrimaryContainer else c.onSurfaceVariant, modifier = Modifier.width(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t, style = Ling.type.bodyLarge, color = c.onSurface)
                                Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
                2 -> {
                    if (kind == "没状态") {
                        // 不调用 AI：直接给本地建议（ai-features §5.4）
                        Text("没状态的时候不用硬写。", style = Ling.type.body, color = c.onSurfaceVariant)
                        listOf("短更预案：拆一章当两章发", "跳写下一个更想写的场景", "回看上一章摘要找回感觉").forEachIndexed { qi, s ->
                            LingCard(Modifier.fillMaxWidth().enterStagger(qi)) { Text(s, style = Ling.type.body, color = c.onSurface, modifier = Modifier.padding(12.dp)) }
                        }
                        LingButton("存到本章便签", BtnKind.Tonal, height = 44.dp, modifier = Modifier.fillMaxWidth()) {
                            scope.launch {
                                AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = chapterId, kind = "stuck", text = "没状态建议：短更预案 / 跳写 / 回看摘要"))
                                onDismiss()
                            }
                        }
                        return@Column
                    }
                    StuckClarifyStep(
                        clarifies = clarifies, answers = answers, busy = busy, note = note,
                        onNote = { note = it },
                        onGenerate = {
                            busy = true
                            scope.launch {
                                runCatching { StuckPrompts.clarify(bookId, chapterId, kind, note) }
                                    .onSuccess { clarifies = it; answers.clear(); repeat(it.size) { answers += "" } }
                                    .onFailure { err = it.message }
                                busy = false
                            }
                        },
                        onSkip = {
                            busy = true
                            step = 3
                            scope.launch {
                                runCatching { StuckPrompts.ways(bookId, chapterId, kind, note, "（未回答）") }
                                    .onSuccess { ways = it }.onFailure { err = it.message }
                                busy = false
                            }
                        },
                        onPick = { i, opt -> if (i < answers.size) answers[i] = opt else answers.add(opt) },
                        onWays = {
                            busy = true
                            step = 3
                            scope.launch {
                                runCatching { StuckPrompts.ways(bookId, chapterId, kind, note, answers.joinToString("；")) }
                                    .onSuccess { ways = it }.onFailure { err = it.message }
                                busy = false
                            }
                        },
                    )
                }
                else -> {
                    if (busy) {
                        com.lingwrite.app.ui.ai.AiThinking("正在想方向…")
                    } else {
                        err?.let { Text("没拿到结果：$it", style = Ling.type.caption, color = c.error) }
                        LingButton("重试", BtnKind.Outlined, height = 40.dp) { step = 2 }
                    }
                }
            }
        }
    }

    // 改进章纲：显示要追加的内容，确认后才写，并给一次撤销
    outlineFix?.let { w ->
        val added = "【卡文改】${w.outlineChange.ifBlank { w.how }}"
        LingConfirm(
            "改进章纲", "确认后追加到本章章纲备注（可以撤销）。",
            onDismiss = { outlineFix = null },
            onConfirm = {
                scope.launch {
                    val cp = AppGraph.db.plan().chapterOnce(chapterId)
                    if (cp != null) AppGraph.db.plan().upsertChapter(cp.copy(note = cp.note + (if (cp.note.isNotBlank()) "\n" else "") + added))
                    outlineFix = null
                    outlineApplied = true
                }
            },
            extra = {
                if (outlineBefore.isNotBlank()) Text("原备注：${outlineBefore.take(80)}", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text("追加：$added", style = Ling.type.caption, color = Ling.colors.primary, modifier = Modifier.padding(top = 6.dp))
            },
        )
    }
    if (outlineApplied) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已写进本章章纲备注", style = Ling.type.caption, color = c.onSurface, modifier = Modifier.weight(1f))
            LingButton("撤销", BtnKind.Text, height = 30.dp) {
                scope.launch {
                    val cp = AppGraph.db.plan().chapterOnce(chapterId)
                    if (cp != null) AppGraph.db.plan().upsertChapter(cp.copy(note = outlineBefore))
                    outlineApplied = false
                }
            }
            LingButton("知道了", BtnKind.Text, height = 30.dp) { outlineApplied = false }
        }
    }
}

/** 第 2 步：AI 先问 2–3 个澄清问题，选项按钮回答，可以跳过（ui-spec §6）。 */
@Composable
fun StuckClarifyStep(
    clarifies: List<StuckPrompts.Clarify>,
    answers: List<String>,
    busy: Boolean,
    note: String,
    onNote: (String) -> Unit,
    onGenerate: () -> Unit,
    onSkip: () -> Unit,
    onPick: (Int, String) -> Unit,
    onWays: () -> Unit,
) {
    val c = Ling.colors
    Text("AI 想先问清楚（可以跳过）", style = Ling.type.caption, color = c.onSurfaceVariant)
    if (clarifies.isEmpty() && !busy) {
        LingTextField(note, onNote, label = "补充一句（可空）", singleLine = false, minLines = 2, placeholder = "比如：这两场之间隔了三天")
        LingButton("生成问题", icon = LingIcons.SparkleFilled, height = 46.dp, modifier = Modifier.fillMaxWidth(), onClick = onGenerate)
        LingButton("跳过，直接给方向", BtnKind.Text, height = 40.dp, onClick = onSkip)
    } else if (busy) {
        com.lingwrite.app.ui.ai.AiThinking("正在想问题…")
    } else {
        clarifies.forEachIndexed { i, cl ->
            Text(cl.q, style = Ling.type.bodyLarge, color = c.onSurface)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cl.options.forEach { opt ->
                    val on = answers.getOrNull(i) == opt
                    Box(
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(if (on) c.primaryContainer else c.surfaceContainer)
                            .clickable { onPick(i, opt) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(opt, style = Ling.type.caption, color = if (on) c.onPrimaryContainer else c.onSurface) }
                }
            }
        }
        LingButton("给 3 个方向", icon = LingIcons.SparkleFilled, height = 46.dp, modifier = Modifier.fillMaxWidth(), onClick = onWays)
    }
}

/**
 * 第 3 步：方向卡横向翻页（ui-spec §6）。
 * 每张宽 300dp、`surfaceContainer` 底、右上角页码点；「利 / 弊 / 章纲」分别用 good / error / primary 色标签。
 * 底部说明“只给方向，不写正文”。组件自带 20dp 横向内边距，调用方不要再加。
 */
@Composable
fun StuckWaysPager(
    ways: List<StuckPrompts.Way>,
    chips: List<String> = emptyList(),
    isGenerate: Boolean = false,
    onSave: (StuckPrompts.Way) -> Unit,
    onOutline: (StuckPrompts.Way) -> Unit,
    onDraftAWay: ((StuckPrompts.Way) -> Unit)? = null,
) {
    val c = Ling.colors
    if (chips.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEachIndexed { i, s -> Pill(s, if (i == 0) PillKind.Primary else PillKind.Neutral) }
        }
    }
    val pagerState = rememberPagerState(pageCount = { ways.size })
    HorizontalPager(
        pagerState,
        contentPadding = PaddingValues(horizontal = 20.dp),
        pageSpacing = 12.dp,
        beyondViewportPageCount = 1,
    ) { page ->
        val w = ways[page]
        LingCard(Modifier.width(300.dp), color = c.surfaceContainer) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.title, style = Ling.type.subhead, color = c.onSurface, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ways.indices.forEach { i ->
                            Box(Modifier.size(if (i == page) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == page) c.amber else c.outline))
                        }
                    }
                }
                Text(w.how, style = Ling.type.body, color = c.onSurface, modifier = Modifier.padding(top = 6.dp))
                if (w.pro.isNotBlank()) Row(Modifier.padding(top = 6.dp)) {
                    Text("利", style = Ling.type.label, color = c.good, modifier = Modifier.width(24.dp))
                    Text(w.pro, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (w.con.isNotBlank()) Row(Modifier.padding(top = 2.dp)) {
                    Text("弊", style = Ling.type.label, color = c.error, modifier = Modifier.width(24.dp))
                    Text(w.con, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (w.outlineChange.isNotBlank()) Row(Modifier.padding(top = 2.dp)) {
                    Text("章纲", style = Ling.type.label, color = c.primary, modifier = Modifier.width(24.dp))
                    Text(w.outlineChange, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton("存到本章便签", BtnKind.Tonal, height = 32.dp) { onSave(w) }
                    LingButton("改进章纲", BtnKind.Text, height = 32.dp) { onOutline(w) }
                    if (isGenerate && onDraftAWay != null) {
                        LingButton("按这个方向起草一段", BtnKind.Text, height = 32.dp) { onDraftAWay(w) }
                    }
                }
            }
        }
    }
    Text(
        "只给方向，不写正文 · 左右滑动看另外 ${(ways.size - 1).coerceAtLeast(0)} 个",
        style = Ling.type.caption, color = c.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
