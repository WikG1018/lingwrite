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
        report = runCatching { AiUsage.report(bookId) }.getOrNull()
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
            Text("…", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            val r = report
            if (r == null) {
                Text(tr(R.string.usage_011), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            } else if (r.totalCalls == 0 && r.chapters.all { it.share.genChars == 0 && it.share.editedChars == 0 }) {
                Text(tr(R.string.usage_011), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        LingCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr(R.string.usage_002), style = Ling.type.label, color = c.primary)
                                Text(tr(R.string.usage_008, r.totalShare.genPercent, r.totalShare.editedPercent), style = Ling.type.headline, color = c.onSurface)
                                Text(tr(R.string.usage_009, r.totalShare.totalChars) + " · " + tr(R.string.usage_003) + " ${r.adoptedPercent}%", style = Ling.type.caption, color = c.onSurfaceVariant)
                                Text(tr(R.string.usage_010, r.totalCalls, r.totalAccepted + r.totalEdited), style = Ling.type.caption, color = c.onSurfaceVariant)
                                LingButton(tr(R.string.usage_006), BtnKind.Text, height = 34.dp) { saver.launch(book?.title.orEmpty().ifBlank { "ai-usage" } + "-ai-usage.md") }
                                Text(tr(R.string.usage_007), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.tertiary)
                            }
                        }
                    }
                    item { SectionLabel(tr(R.string.usage_004)) }
                    items(r.chapters.filter { it.share.genChars > 0 || it.share.editedChars > 0 || it.calls > 0 }) { u ->
                        ChapterRow(u)
                    }
                    if (r.tasks.isNotEmpty()) {
                        item { SectionLabel(tr(R.string.usage_005)) }
                        items(r.tasks) { t ->
                            LingCard {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(com.lingwrite.app.data.taskName(t.task), style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                    Text(tr(R.string.usage_010, t.calls, t.accepted), style = Ling.type.caption, color = c.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item { msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(8.dp)) }; Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(u: ChapterUsage) {
    val c = Ling.colors
    LingCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.usage_013, u.number) + if (u.title.isNotBlank()) " " + u.title else "", style = Ling.type.body, color = c.onSurface, maxLines = 1)
                Text(tr(R.string.usage_008, u.share.genPercent, u.share.editedPercent), style = Ling.type.caption, color = c.onSurfaceVariant)
            }
            if (u.share.genPercent + u.share.editedPercent >= 50) Pill("AI ${u.share.genPercent + u.share.editedPercent}%", PillKind.Amber)
            else Text(tr(R.string.usage_010, u.calls, u.accepted + u.edited), style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}

private fun reportText(book: String, r: AiUsageReport): String = buildString {
    append("# AI 使用体检 · ").append(book.ifBlank { "" }).append('\n')
    append("AI 标记字符占比：生成 ").append(r.totalShare.genPercent).append("% · 人工修改 ").append(r.totalShare.editedPercent).append("%\n")
    append("采纳率：").append(r.adoptedPercent).append('%').append('\n')
    append("调用 ").append(r.totalCalls).append(" 次，采纳 ").append(r.totalAccepted + r.totalEdited).append(" 次\n\n")
    append("> 仅统计 App 内标记的 AI 段落；手工修改/外部粘贴不计入。不能作为平台判定依据，也不用于规避检测。\n\n")
    r.chapters.forEach { u ->
        if (u.share.genChars > 0 || u.share.editedChars > 0 || u.calls > 0) {
            append("- 第 ").append(u.number).append(" 章：AI ").append(u.share.genPercent).append("% / 修改 ").append(u.share.editedPercent).append("%")
            if (u.calls > 0) append(" · 调用 ").append(u.calls).append(" 次，采纳 ").append(u.accepted + u.edited)
            append('\n')
        }
    }
}
