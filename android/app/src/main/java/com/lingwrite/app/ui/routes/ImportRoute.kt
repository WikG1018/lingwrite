// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.lingwrite.app.ui.theme.Motion
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavHostController
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** 旧稿导入：选择文件 → 预览/拆分 → 确认写入。预览阶段不写库。 */
@Composable
fun ImportRoute(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var title by remember { mutableStateOf("") }
    var split by remember { mutableStateOf(ImportSplit.Heading) }
    var length by remember { mutableStateOf("3000") }
    var encoding by remember { mutableStateOf<Charset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(uri: Uri, mode: ImportSplit = split, charset: Charset? = encoding) {
        source = uri
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Importer.parse(ctx, uri, ImportOptions(mode, length.toIntOrNull()?.coerceIn(500, 20_000) ?: 3000, charset))
                }
            }
            result.onSuccess { p ->
                preview = p
                title = p.bookTitle
                if (p.chapters.isEmpty()) error = tr(R.string.import_022)
            }.onFailure { error = tr(R.string.import_005, it.message ?: "") }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    fun doImport() {
        val p = preview ?: return
        if (writing || busy) return
        writing = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Importer.commit(p, title) } }
                .onSuccess { id ->
                    writing = false
                    nav.navigate(Routes.work(id)) { popUpTo(Routes.Shelf) }
                }
                .onFailure { error = tr(R.string.import_017, it.message ?: ""); writing = false }
        }
    }

    val c = Ling.colors
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.import_001), preview?.bookTitle, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val p = preview
            if (p == null) {
                LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr(R.string.import_021), style = Ling.type.titleSmall, color = c.onSurface)
                        Text(tr(R.string.import_002), style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                        LingButton(tr(R.string.import_003), icon = LingIcons.Add, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            picker.launch(arrayOf(
                                "text/plain",
                                "text/markdown",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/octet-stream",
                            ))
                        }
                    }
                }
                // 解析大文件：超过 150ms 显示骨架（motion §15）。
                if (busy) { Text(tr(R.string.import_004), style = Ling.type.body, color = c.primary, modifier = Modifier.padding(horizontal = 8.dp)); DelayedSkeleton(true, cards = 2) }
            } else {
                LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr(R.string.import_013, p.volumes.size, p.chapters.size, Words.grouped(p.totalWords)), style = Ling.type.titleSmall, color = c.onSurface)
                        Text(tr(R.string.import_019, p.encoding), style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                        if (p.warnings.isNotEmpty()) {
                            p.warnings.forEach { w -> Text("· $w", style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.tertiary) }
                        }
                        if (p.encoding != "UTF-8") {
                            LingButton(tr(R.string.import_020), BtnKind.Text, height = 34.dp, enabled = !busy) {
                                val next = if (p.encoding == "GB18030") Charsets.UTF_8 else Charset.forName("GB18030")
                                encoding = next
                                source?.let { load(it, split, next) }
                            }
                        }
                    }
                }
                Text(tr(R.string.import_007), style = Ling.type.label, color = c.primary, modifier = Modifier.padding(start = 8.dp).enterStagger(1))
                Segmented(
                    listOf(tr(R.string.import_008), tr(R.string.import_009), tr(R.string.import_010)),
                    when (split) { ImportSplit.Heading -> 0; ImportSplit.Length -> 1; ImportSplit.Single -> 2 },
                ) { idx ->
                    val mode = when (idx) { 1 -> ImportSplit.Length; 2 -> ImportSplit.Single; else -> ImportSplit.Heading }
                    split = mode
                    source?.let { load(it, mode, encoding) }
                }
                AnimatedVisibility(split == ImportSplit.Length, enter = fadeIn(tween(Motion.Medium)) + expandVertically(tween(Motion.Medium, easing = Motion.EmphasizedDecelerate)),
                    exit = fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Short, easing = Motion.EmphasizedAccelerate))) {
                    LingTextField(
                        length, { length = it.filter(Char::isDigit) },
                        label = tr(R.string.import_011, length.toIntOrNull()?.coerceIn(500, 20_000) ?: 3000),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                }
                LingTextField(title, { title = it }, label = tr(R.string.import_014), modifier = Modifier.enterStagger(2))
                SectionLabel(tr(R.string.import_012, minOf(20, p.chapters.size)), Modifier.enterStagger(3))
                LingCard(Modifier.fillMaxWidth().enterStagger(4)) {
                    // 重新拆分时预览整体淡入淡出，不跳变。
                    Crossfade(p, animationSpec = tween(Motion.Medium), label = "importPreview") { pp ->
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(pp.chapters.take(20), key = { i, _ -> i }) { i, ch ->
                            if (i > 0) InsetDivider(16.dp)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", Modifier.width(28.dp), style = Ling.type.caption, color = c.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ch.title.ifBlank { tr(R.string.book_ai_010) },
                                        style = Ling.type.body.copy(fontWeight = FontWeight.Medium),
                                        color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(Words.grouped(Words.count(ch.content)) + " " + tr(R.string.work_040), style = Ling.type.caption, color = c.onSurfaceVariant)
                                }
                                if (pp.volumes.size > 1) Pill(VolumeLabel.short(ch.volume + 1), PillKind.Neutral)
                            }
                        }
                    }
                    }
                }
                if (busy) Text(tr(R.string.import_004), style = Ling.type.body, color = c.primary)
            }
            error?.let { Text(it, style = Ling.type.body, color = c.error, modifier = Modifier.padding(horizontal = 8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        if (preview != null) {
            LingButton(if (writing) tr(R.string.import_016) else tr(R.string.import_015), enabled = !writing && !busy && title.isNotBlank(), height = 50.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), onClick = { doImport() })
        }
    }
}
