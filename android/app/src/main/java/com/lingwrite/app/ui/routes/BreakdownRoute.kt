// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

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
        LingTopBar(tr(R.string.breakdown_001), tr(R.string.breakdown_002), onBack = { nav.popBackStack() })
        if (result == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 版权提示横幅（ui-spec §10：errorContainer 底）
                Row(Modifier.fillMaxWidth().background(c.errorContainer).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        tr(R.string.breakdown_003),
                        style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onErrorContainer,
                    )
                }
                Text(tr(R.string.breakdown_004), style = Ling.type.label, color = c.primary)
                LingTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = false, minLines = 8, maxLines = 14,
                    placeholder = tr(R.string.breakdown_005))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(if (busy) tr(R.string.breakdown_006) else tr(R.string.breakdown_007), height = 46.dp, enabled = text.isNotBlank() && !busy, icon = LingIcons.Sparkle) {
                        busy = true; err = null
                        scope.launch {
                            val chapters = splitChapters(text)
                            // A12：先把样章里的人名列出来，输出里统一换成「主角 / 对手 / 配角N」
                            val names = Breakdown.extractNames(text)
                            recognized = names.size
                            val res = runCatching { Breakdown.analyze(tr(R.string.breakdown_008), chapters, names) }.getOrNull()
                            res?.let { result = it.getOrNull() } ?: run { err = tr(R.string.book_ai_018) }
                            if (result == null && err == null) err = tr(R.string.breakdown_009)
                            busy = false
                        }
                    }
                    LingButton(tr(R.string.breakdown_010), BtnKind.Text, height = 46.dp, enabled = !busy) { text = ""; result = null; err = null }
                }
                err?.let { Text(tr(R.string.ai_components_011, it), style = Ling.type.caption, color = c.error) }
                // MO2：分析要跑一会儿，首屏先给骨架（150ms 阈值，避免闪一下）
                if (com.lingwrite.app.ui.components.rememberDelayedVisible(busy)) {
                    com.lingwrite.app.ui.components.SkeletonCard(4)
                }
                Text(
                    if (recognized > 0) tr(R.string.breakdown_011, recognized)
                    else tr(R.string.breakdown_012),
                    style = Ling.type.caption, color = c.onSurfaceVariant,
                )
            }
        } else {
            val r = result!!
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(tr(R.string.breakdown_013), style = Ling.type.label, color = c.primary)
                            CurveChart(r.curve, Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp))
                            Text(tr(R.string.breakdown_014, (r.curve.indices.maxByOrNull { r.curve[it] } ?: 0) + 1), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                itemsIndexed(r.chapters) { idx, ch ->
                    LingCard(Modifier.fillMaxWidth().enterStagger(idx)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(tr(R.string.breakdown_015, ch.n), PillKind.Neutral)
                                Spacer(Modifier.width(8.dp))
                                Pill(tr(R.string.breakdown_016, ch.intensity), if (ch.intensity >= 4) PillKind.Amber else PillKind.Neutral)
                            }
                            if (ch.event.isNotBlank()) Text(tr(R.string.breakdown_017, ch.event), style = Ling.type.body, color = c.onSurface)
                            if (ch.hook.isNotBlank()) Text(tr(R.string.breakdown_018, ch.hook), style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.cool.isNotBlank()) Text(tr(R.string.breakdown_019, ch.cool), style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.cliff.isNotBlank()) Text(tr(R.string.breakdown_020, ch.cliff), style = Ling.type.caption, color = c.onSurfaceVariant)
                            if (ch.pattern.isNotBlank()) Text(tr(R.string.breakdown_021, ch.pattern), style = Ling.type.caption.copy(color = c.primary))
                        }
                    }
                }
                item {
                    LingCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.breakdown_022), style = Ling.type.label, color = c.primary)
                            r.template.forEach { t ->
                                Text(tr(R.string.book_ai_017, t.chapter, t.pattern), style = Ling.type.body, color = c.onSurface)
                            }
                            r.notes.forEach { Text("· $it", style = Ling.type.caption, color = c.onSurfaceVariant) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (applied >= 0) Text(tr(R.string.breakdown_023, applied), style = Ling.type.body, color = c.good)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (bookId > 0 && r.template.isNotEmpty()) {
                                LingButton(tr(R.string.breakdown_024), height = 42.dp, enabled = applied < 0) { applyAsk = true }
                            }
                            if (!saved) {
                                LingButton(tr(R.string.breakdown_025), BtnKind.Tonal, height = 42.dp) {
                                    scope.launch { Breakdown.save(r, bookId.takeIf { it > 0 }); saved = true }
                                }
                            }
                            if (viewing) {
                                LingButton(tr(R.string.breakdown_026), BtnKind.Text, height = 42.dp) {
                                    scope.launch {
                                        AppGraph.db.breakdowns().delete(viewId)
                                        nav.popBackStack()
                                    }
                                }
                            }
                            if (!viewing) LingButton(tr(R.string.breakdown_027), BtnKind.Text, height = 42.dp) { text = ""; result = null; saved = false }
                        }
                        if (bookId > 0) Text(tr(R.string.breakdown_028), style = Ling.type.caption, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (applyAsk) {
        com.lingwrite.app.ui.components.LingConfirm(
            title = tr(R.string.breakdown_029),
            text = tr(R.string.breakdown_030),
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
