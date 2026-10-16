// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.lazy.itemsIndexed
import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.Breakdown
import com.lingwrite.app.data.BreakdownChapter
import com.lingwrite.app.data.BreakdownResult
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIconButton
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

/**
 * A12 拆文：本地分章后，经作者确认发给所选模型分析；仅保存结构化结果。
 * 原文只在内存里处理，不落库、不联网保存。
 */
@Composable
fun BreakdownRoute(bookId: Long, viewId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BreakdownResult?>(null) }
    var saved by remember { mutableStateOf(false) }
    var recognized by remember { mutableStateOf(0) }
    var applyAsk by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(-1) }
    var viewing by remember { mutableStateOf(viewId > 0) }

    // viewId > 0：直接打开已保存的分析（从「网文模板」Sheet 进来）
    LaunchedEffect(viewId) {
        if (viewId > 0) {
            runCatching { Breakdown.load(viewId) }.getOrNull()?.let { result = it; saved = true }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("拆文", "只保存分析结果，不保存原文", onBack = { nav.popBackStack() })
        if (result == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 版权提示横幅（ui-spec §10：errorContainer 底）
                Row(Modifier.fillMaxWidth().background(c.errorContainer).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "请只使用你有权使用的文本。点击分析会将样章发给你配置的 AI 服务商，并可能产生费用。灵写本机只保存分析结果；服务商的数据政策请自行确认。",
                        style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onErrorContainer,
                    )
                }
                Text("第 1 步 · 贴入样章", style = Ling.type.label, color = c.primary)
                LingTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = false, minLines = 8, maxLines = 14,
                    placeholder = "粘贴 1–10 章样章，章节之间用「第X章」或空行分隔")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(if (busy) "分析中…" else "开始分析", height = 46.dp, enabled = text.isNotBlank() && !busy, icon = LingIcons.Sparkle) {
                        busy = true; err = null
                        scope.launch {
                            val chapters = splitChapters(text)
                            // A12：先把样章里的人名列出来，输出里统一换成「主角 / 对手 / 配角N」
                            val names = Breakdown.extractNames(text)
                            recognized = names.size
                            val res = runCatching { Breakdown.analyze("样章", chapters, names) }.getOrNull()
                            res?.let { result = it.getOrNull() } ?: run { err = "模型没有按格式返回" }
                            if (result == null && err == null) err = "没有拿到分析结果"
                            busy = false
                        }
                    }
                    LingButton("重置", BtnKind.Text, height = 46.dp, enabled = !busy) { text = ""; result = null; err = null }
                }
                err?.let { Text("没拿到结果：$it", style = Ling.type.caption, color = c.error) }
                // MO2：分析要跑一会儿，首屏先给骨架（150ms 阈值，避免闪一下）
                if (com.lingwrite.app.ui.components.rememberDelayedVisible(busy)) {
                    com.lingwrite.app.ui.components.SkeletonCard(4)
                }
                Text(
                    if (recognized > 0) "已识别 $recognized 个疑似专名，结果会进行本地脱名处理；这不改变样章会发给所选服务商的事实。"
                    else "在本机分章，再由所选 AI 分析；本机只保存结构结果，不保存样章。",
                    style = Ling.type.caption, color = c.onSurfaceVariant,
                )
            }
        } else {
            val r = result!!
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("节奏曲线（冲突强度 1–5）", style = Ling.type.label, color = c.primary)
                            CurveChart(r.curve, Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp))
                            Text("强度最高：第 ${(r.curve.indices.maxByOrNull { r.curve[it] } ?: 0) + 1} 章", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                itemsIndexed(r.chapters) { idx, ch ->
                    LingCard(Modifier.fillMaxWidth().enterStagger(idx)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("第 ${ch.n} 章", PillKind.Neutral)
                                Spacer(Modifier.width(8.dp))
                                Pill("强度 ${ch.intensity}", if (ch.intensity >= 4) PillKind.Amber else PillKind.Neutral)
                            }
                            if (ch.event.isNotBlank()) Text("事件：${ch.event}", style = Ling.type.body, color = c.onSurface)
                            if (ch.hook.isNotBlank()) Text("钩子：${ch.hook}", style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.cool.isNotBlank()) Text("爽点：${ch.cool}", style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.cliff.isNotBlank()) Text("章尾：${ch.cliff}", style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.pattern.isNotBlank()) Text("结构：${ch.pattern}", style = Ling.type.caption.copy(color = c.primary))
                        }
                    }
                }
                item {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("结构模板（已抹去人名设定）", style = Ling.type.label, color = c.primary)
                            r.template.forEach { t ->
                                Text("第 ${t.chapter} 章：${t.pattern}", style = Ling.type.body, color = c.onSurface)
                            }
                            r.notes.forEach { Text("· $it", style = Ling.type.caption, color = c.onSurfaceVariant) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (applied >= 0) Text("✓ 已把模板填进 $applied 个还没章纲的章节，去大纲页可以再改。", style = Ling.type.body, color = c.good)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (bookId > 0 && r.template.isNotEmpty()) {
                                LingButton("套用到本书章纲", height = 42.dp, enabled = applied < 0) { applyAsk = true }
                            }
                            if (!saved) {
                                LingButton("存为我的模板", BtnKind.Tonal, height = 42.dp) {
                                    scope.launch { Breakdown.save(r, bookId.takeIf { it > 0 }); saved = true }
                                }
                            }
                            if (viewing) {
                                LingButton("删除此模板", BtnKind.Text, height = 42.dp) {
                                    scope.launch {
                                        AppGraph.db.breakdowns().delete(viewId)
                                        nav.popBackStack()
                                    }
                                }
                            }
                            if (!viewing) LingButton("再拆一本", BtnKind.Text, height = 42.dp) { text = ""; result = null; saved = false }
                        }
                        if (bookId > 0) Text("套用只填还没有章纲的章节，已有的不动；填入的都是建议，可在大纲页改。", style = Ling.type.caption, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (applyAsk) {
        com.lingwrite.app.ui.components.LingConfirm(
            title = "套用结构模板",
            text = "把这份模板按顺序填进还没有章纲的章节（已有的章纲不动），填入的都是建议。",
            onDismiss = { applyAsk = false },
            onConfirm = {
                applyAsk = false
                scope.launch {
                    applied = Breakdown.applyToOutline(bookId, result?.template.orEmpty())
                }
            },
        )
    }
}

/** 按章节标题或空行切章；识别不了就整段当 1 章。 */
fun splitChapters(text: String): List<String> {
    val byTitle = Regex("(?m)^\\s*第[一二三四五六七八九十百千0-9]+[章节卷][^\\n]{0,30}$").findAll(text).toList()
    if (byTitle.size >= 2) {
        val out = mutableListOf<String>()
        for (i in byTitle.indices) {
            val start = byTitle[i].range.first
            val end = byTitle.getOrNull(i + 1)?.range?.first ?: text.length
            out += text.substring(start, end).trim()
        }
        return out.take(10)
    }
    val paras = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    return if (paras.size > 1) listOf(paras.joinToString("\n\n")) else listOf(text)
}

/** 节奏折线图：primary 色 2dp 线 + 5dp 点（ui-spec §10）。 */
@Composable
fun CurveChart(values: List<Int>, modifier: Modifier = Modifier) {
    val c = Ling.colors
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val minV = 0f; val maxV = 5f
        val stepX = size.width / (values.size - 1)
        val yOf = { v: Int -> size.height * (1f - (v - minV) / (maxV - minV)) }
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * stepX, yOf(v))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, c.primary, style = Stroke(width = 2.dp.toPx()))
        values.forEachIndexed { i, v -> drawCircle(c.primary, radius = 2.5.dp.toPx(), center = Offset(i * stepX, yOf(v))) }
    }
}
