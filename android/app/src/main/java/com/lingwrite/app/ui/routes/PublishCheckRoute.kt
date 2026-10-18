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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PublishCheckRoute(bookId: Long, chapterId: Long, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Ling.colors
    val wordsFile = remember(ctx) { File(ctx.filesDir, "compliance/words.txt") }
    var words by remember { mutableStateOf(PublishCheck.parseWordList(runCatching { wordsFile.readText() }.getOrDefault(""))) }
    var report by remember { mutableStateOf<PublishReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)

    fun runCheck() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            runCatching { PublishCheck.run(bookId, chapterId.takeIf { it > 0 }, words) }
                .onSuccess { report = it }
                .onFailure { error = tr(R.string.publish_020, it.message ?: "") }
            busy = false
        }
    }
    LaunchedEffect(bookId, chapterId) { runCheck() }

    fun saveWords(text: String) {
        val list = PublishCheck.parseWordList(text)
        words = list
        runCatching { wordsFile.parentFile?.mkdirs(); wordsFile.writeText(list.joinToString("\n")) }
        editing = false
        msg = tr(R.string.publish_018)
        runCheck()
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val r = report ?: return@rememberLauncherForActivityResult
        scope.launch {
            msg = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                        out.write(reportText(book?.title.orEmpty(), chapterId > 0, r).toByteArray())
                    }
                }
                tr(R.string.publish_019)
            }.getOrElse { tr(R.string.data_005, it.message) }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.publish_001), book?.title, onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "summary") {
                LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val r = report
                        if (r != null) {
                            Text(
                                if (chapterId > 0) tr(R.string.publish_017) else tr(R.string.publish_016, r.checkedChapters),
                                style = Ling.type.titleSmall, color = c.onSurface,
                            )
                            Text(
                                tr(R.string.publish_008, r.share.genPercent, r.share.editedPercent, r.share.totalChars),
                                style = Ling.type.caption, color = c.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LingButton(tr(R.string.publish_002), BtnKind.Tonal, height = 36.dp, enabled = !busy) { runCheck() }
                                LingButton(tr(R.string.publish_014), BtnKind.Text, height = 36.dp, enabled = r.issues.isNotEmpty()) {
                                    saver.launch(book?.title.orEmpty().ifBlank { "report" } + "-publish-check.md")
                                }
                            }
                        } else {
                            Text(if (busy) tr(R.string.publish_003) else tr(R.string.publish_001), style = Ling.type.titleSmall, color = c.onSurface)
                        }
                        Text(tr(R.string.publish_015), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.tertiary)
                    }
                }
            }
            item(key = "words") {
                LingCard(Modifier.fillMaxWidth().enterStagger(1)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(R.string.publish_009), style = Ling.type.label, color = c.onSurface)
                            Text(
                                if (words.isEmpty()) tr(R.string.publish_010) else words.take(8).joinToString(tr(R.string.data_043)) + if (words.size > 8) "…" else "",
                                style = Ling.type.caption, color = c.onSurfaceVariant,
                            )
                        }
                        LingButton(tr(R.string.publish_011), BtnKind.Text, height = 34.dp) { edited = words.joinToString("\n"); editing = true }
                    }
                }
            }
            val issues = report?.issues.orEmpty()
            // 首次检查超过 150ms 才出骨架，避免闪一下（motion §15）。
            if (report == null && busy) item(key = "skeleton") { DelayedSkeleton(true, cards = 3) }
            if (report != null && issues.isEmpty()) item(key = "clean") {
                Text(tr(R.string.publish_004), style = Ling.type.body, color = c.good, modifier = Modifier.padding(12.dp).enterStagger(2).animateItem())
            }
            listOf(CheckSeverity.Must to tr(R.string.publish_005), CheckSeverity.Warn to tr(R.string.publish_006)).forEach { (sev, label) ->
                val list = issues.filter { it.severity == sev }
                if (list.isNotEmpty()) {
                    item(key = "h-$sev") { SectionLabel("$label · ${list.size}", Modifier.enterStagger(2).animateItem()) }
                    itemsIndexed(list, key = { i, it -> "$sev-${it.chapterId}-${it.offset}-$i" }) { i, issue ->
                        LingCard(Modifier.fillMaxWidth().enterStagger(3 + i.coerceAtMost(5)).animateItem()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${ChapterLabel.short(issue.chapterNumber)} · ${issue.message}", style = Ling.type.body, color = c.onSurface)
                                    if (issue.quote.isNotBlank()) Text(issue.quote, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2)
                                }
                                if (issue.offset >= 0 && issue.chapterId > 0) {
                                    LingButton(tr(R.string.publish_013), BtnKind.Text, height = 32.dp) {
                                        EditorJump.put(issue.chapterId, issue.offset)
                                        nav.navigate(Routes.editor(issue.chapterId))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "foot") {
                error?.let { Text(it, style = Ling.type.body, color = c.error, modifier = Modifier.padding(8.dp)) }
                msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(8.dp)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editing) {
        LingSheet({ editing = false }, tr(R.string.publish_011)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.publish_010), style = Ling.type.caption, color = c.onSurfaceVariant)
                LingTextField(edited, { edited = it }, label = tr(R.string.publish_009), singleLine = false, minLines = 6, maxLines = 12)
                LingButton(tr(R.string.publish_012), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 48.dp) { saveWords(edited) }
            }
        }
    }
}

/** 导出报告：按界面语言（章号用 ChapterLabel）。 */
private fun reportText(book: String, oneChapter: Boolean, r: PublishReport): String = buildString {
    append("# ").append(book.ifBlank { tr(R.string.publish_001) }).append('\n')
    append(if (oneChapter) tr(R.string.publish_017) else tr(R.string.publish_016, r.checkedChapters)).append('\n')
    append(tr(R.string.publish_008, r.share.genPercent, r.share.editedPercent, r.share.totalChars)).append('\n')
    append(tr(R.string.publish_015)).append("\n\n")
    r.issues.forEach { i ->
        append("- [").append(if (i.severity == CheckSeverity.Must) tr(R.string.publish_005) else tr(R.string.publish_006)).append("] ")
        append(ChapterLabel.short(i.chapterNumber)).append(" · ").append(i.message)
        if (i.quote.isNotBlank()) append(tr(R.string.pubchk_010)).append(i.quote.replace("\n", " "))
        append('\n')
    }
}
