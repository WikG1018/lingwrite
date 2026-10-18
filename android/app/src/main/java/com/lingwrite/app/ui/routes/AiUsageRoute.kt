// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.AiUsage
import com.lingwrite.app.data.AiUsageReport
import com.lingwrite.app.data.ChapterUsage
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiUsageRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    var report by remember { mutableStateOf<AiUsageReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        // 逐章读正文 + 统计标记：放到后台线程，首屏走 150ms 骨架（motion §15）。
        report = runCatching { withContext(Dispatchers.Default) { AiUsage.report(bookId) } }.getOrNull()
        loading = false
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        val r = report ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(reportText(book?.title.orEmpty(), r).toByteArray()) } }
                tr(R.string.usage_012)
            }.getOrElse { tr(R.string.data_005, it.message) }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.usage_001), book?.title, onBack = { nav.popBackStack() })
        if (loading) {
            Column(Modifier.padding(horizontal = 16.dp)) { DelayedSkeleton(true, cards = 3) }
        } else {
            val r = report
            if (r == null) {
                Text(tr(R.string.usage_011), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp).enterStagger(0))
            } else if (r.totalCalls == 0 && r.chapters.all { it.share.genChars == 0 && it.share.editedChars == 0 }) {
                Text(tr(R.string.usage_011), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp).enterStagger(0))
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "summary") {
                        LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr(R.string.usage_002), style = Ling.type.label, color = c.primary)
                                Text(tr(R.string.usage_008, r.totalShare.genPercent, r.totalShare.editedPercent), style = Ling.type.headline, color = c.onSurface)
                                Text(tr(R.string.usage_009, r.totalShare.totalChars) + " · " + tr(R.string.usage_003) + " ${r.adoptedPercent}%", style = Ling.type.caption, color = c.onSurfaceVariant)
                                Text(tr(R.string.usage_010, r.totalCalls, r.totalAccepted + r.totalEdited), style = Ling.type.caption, color = c.onSurfaceVariant)
                                ShareBar(r.totalShare.genPercent, r.totalShare.editedPercent)
                                if (r.totalCalls >= AiUsage.MAX_LOGS) Text(tr(R.string.usage_015, AiUsage.MAX_LOGS), style = Ling.type.caption, color = c.onSurfaceVariant)
                                LingButton(tr(R.string.usage_006), BtnKind.Text, height = 34.dp) { saver.launch(book?.title.orEmpty().ifBlank { "ai-usage" } + "-ai-usage.md") }
                                Text(tr(R.string.usage_007), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.tertiary)
                            }
                        }
                    }
                    item(key = "chHeader") { SectionLabel(tr(R.string.usage_004), Modifier.enterStagger(1)) }
                    items(r.chapters.filter { it.share.genChars > 0 || it.share.editedChars > 0 || it.calls > 0 }, key = { "c" + it.chapterId }) { u ->
                        ChapterRow(u, Modifier.enterStagger(2).animateItem())
                    }
                    if (r.tasks.isNotEmpty()) {
                        item(key = "taskHeader") { SectionLabel(tr(R.string.usage_005), Modifier.enterStagger(3)) }
                        items(r.tasks, key = { "t" + it.task }) { t ->
                            LingCard(Modifier.fillMaxWidth().enterStagger(4).animateItem()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(com.lingwrite.app.data.taskName(t.task), style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                    Text(tr(R.string.usage_010, t.calls, t.accepted), style = Ling.type.caption, color = c.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "foot") { msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(8.dp)) }; Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(u: ChapterUsage, modifier: Modifier = Modifier) {
    val c = Ling.colors
    LingCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(com.lingwrite.app.data.ChapterLabel.full(u.number, u.title), style = Ling.type.body, color = c.onSurface, maxLines = 1)
                Text(tr(R.string.usage_008, u.share.genPercent, u.share.editedPercent), style = Ling.type.caption, color = c.onSurfaceVariant)
            }
            if (u.share.genPercent + u.share.editedPercent >= 50) Pill("AI ${u.share.genPercent + u.share.editedPercent}%", PillKind.Amber)
            else Text(tr(R.string.usage_010, u.calls, u.accepted + u.edited), style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}

/** 生成 / 修改占比条：从左向右生长（motion §13），只改绘制参数。 */
@Composable
private fun ShareBar(gen: Int, edited: Int) {
    val c = Ling.colors
    val reduced = com.lingwrite.app.ui.theme.Motion.reduced()
    val grow = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(gen, edited) {
        if (!reduced) { grow.snapTo(0f); grow.animateTo(1f, androidx.compose.animation.core.tween(com.lingwrite.app.ui.theme.Motion.Long * 2, 120, com.lingwrite.app.ui.theme.Motion.EmphasizedDecelerate)) }
    }
    val amber = c.amber; val soft = c.amber.copy(alpha = 0.4f)
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(c.surfaceContainer)
        .drawBehind {
            val w = size.width * grow.value
            val g = w * gen / 100f; val e = w * edited / 100f
            drawRect(amber, size = size.copy(width = g))
            drawRect(soft, topLeft = androidx.compose.ui.geometry.Offset(g, 0f), size = size.copy(width = e))
        })
}

/** 导出报告：按界面语言，章号用 ChapterLabel。 */
private fun reportText(book: String, r: AiUsageReport): String = buildString {
    append("# ").append(tr(R.string.usage_001)).append(if (book.isNotBlank()) " · $book" else "").append('\n')
    append(tr(R.string.usage_002)).append(tr(R.string.pubchk_010)).append(tr(R.string.usage_008, r.totalShare.genPercent, r.totalShare.editedPercent)).append('\n')
    append(tr(R.string.usage_003)).append(tr(R.string.pubchk_010)).append(r.adoptedPercent).append("%\n")
    append(tr(R.string.usage_010, r.totalCalls, r.totalAccepted + r.totalEdited)).append("\n\n")
    append("> ").append(tr(R.string.usage_007)).append("\n\n")
    r.chapters.forEach { u ->
        if (u.share.genChars > 0 || u.share.editedChars > 0 || u.calls > 0) {
            append("- ").append(com.lingwrite.app.data.ChapterLabel.short(u.number)).append(tr(R.string.pubchk_010))
            append(tr(R.string.usage_008, u.share.genPercent, u.share.editedPercent))
            if (u.calls > 0) append(" · ").append(tr(R.string.usage_010, u.calls, u.accepted + u.edited))
            append('\n')
        }
    }
}
