// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object DataModule {
    fun install() {
        ExtraDestinations.list.add { nav: NavHostController ->
            composable(Routes.Export) { e: androidx.navigation.NavBackStackEntry -> ExportRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
            composable(Routes.Backup) { BackupRoute(nav) }
        }
        SettingsRegistry.extra += { _, nav -> "数据" to SettingRow(LingIcons.Lock, "备份与恢复", "整库备份为一个文件；导入可覆盖或合并", tone = Tone.Good, onClick = { nav.navigate(Routes.Backup) }) }
    }
}

@Composable
fun ExportRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    val chs by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    var md by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("1") }
    var to by remember(chs.size) { mutableStateOf((chs.maxOfOrNull { it.number } ?: 1).toString()) }
    var titles by remember { mutableStateOf(true) }
    var blurb by remember { mutableStateOf(true) }
    var numStyle by remember { mutableStateOf(ExportNumStyle.Arabic) }
    var preview by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val opts = Exporter.Options(md, from.toIntOrNull() ?: 1, to.toIntOrNull() ?: Int.MAX_VALUE, titles, blurb, numStyle = numStyle)
    LaunchedEffect(opts) { preview = Exporter.render(bookId, opts) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (md) "text/markdown" else "text/plain")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Exporter.render(bookId, opts).toByteArray()) } }; "已导出 ${Words.grouped(Words.count(preview))} 字" }.getOrElse { "导出失败：${it.message}" }
        }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("导出", book?.title, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf("TXT", "Markdown"), if (md) 1 else 0) { md = it == 1 }
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("章节范围", style = Ling.type.label, color = c.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LingTextField(from, { from = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "从第", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        LingTextField(to, { to = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "到第", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    }
                    CheckRow("包含章节标题", titles) { titles = it }
                    CheckRow("包含作品简介", blurb) { blurb = it }
                    if (titles) {
                        Text("章节号格式", style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
                        Segmented(ExportNumStyle.entries.map { it.label }, ExportNumStyle.entries.indexOf(numStyle)) { numStyle = ExportNumStyle.entries[it] }
                    }
                    Text("AI 段落标记不会写进导出文件；是否对外披露由你决定。", style = Ling.type.caption, color = c.onSurfaceVariant)
                }
            }
            Text("预览", style = Ling.type.label, color = c.primary, modifier = Modifier.padding(start = 8.dp))
            LingCard(color = c.paper) {
                Text(preview.take(900) + if (preview.length > 900) "\n…" else "", style = Ling.type.body.copy(lineHeight = 22.sp, fontFamily = if (md) FontFamily.Monospace else Ling.type.body.fontFamily), color = c.paperInk, modifier = Modifier.padding(16.dp))
            }
            msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(start = 8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        LingButton("保存到文件", icon = LingIcons.Share, height = 50.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            saver.launch("${book?.title?.ifBlank { "未命名" } ?: "作品"}.${if (md) "md" else "txt"}")
        }
    }
}

@Composable
private fun CheckRow(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).rowClick { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, onChange); Text(text, style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
    }
}

@Composable
fun BackupRoute(nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Backup.Archive?>(null) }
    var autos by remember { mutableStateOf(Backup.autoBackups(ctx)) }
    var busy by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val p = AppGraph.prefs.flow.first()
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { Backup.writeZip(it, ctx, p) } }; "备份完成" }.getOrElse { "备份失败：${it.message}" }
            busy = false
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { Backup.readZip(it) } } }.onSuccess { pending = it }.onFailure { msg = "无法读取：${it.message}" } }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("备份与恢复", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("备份整个灵写", style = Ling.type.titleSmall, color = c.onSurface)
                    Text("作品、章节与历史版本、设定库、大纲、伏笔、灵感、AI 使用记录和地图图片打包成一个 .zip。API Key 不会写进备份，恢复后需要重新填写。", style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                    LingButton(if (busy) "正在备份…" else "立即备份", icon = LingIcons.Lock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        saver.launch("灵写备份-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())}.zip")
                    }
                }
            }
            LingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("从备份导入", style = Ling.type.titleSmall, color = c.onSurface)
                    Text("可以覆盖本机数据，或把备份里的作品合并进来。导入前会自动备份一次当前数据。", style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                    LingButton("选择备份文件", BtnKind.Tonal, icon = LingIcons.Add, modifier = Modifier.fillMaxWidth()) { opener.launch(arrayOf("application/zip", "application/octet-stream")) }
                }
            }
            msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(horizontal = 8.dp)) }
            if (autos.isNotEmpty()) {
                SectionLabel("导入前的自动备份（保留最近 5 个）")
                LingCard {
                    autos.forEachIndexed { i, f ->
                        if (i > 0) InsetDivider(16.dp)
                        Row(Modifier.fillMaxWidth().rowClick { scope.launch { runCatching { withContext(Dispatchers.IO) { f.inputStream().use { Backup.readZip(it) } } }.onSuccess { pending = it } } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name.removePrefix("auto-").removeSuffix(".zip"), style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                            Text("${f.length() / 1024} KB", style = Ling.type.caption, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    pending?.let { a ->
        var overwrite by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }
        LingSheet({ pending = null }, "导入备份") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportSummary(a)
                Segmented(listOf("合并", "覆盖"), if (overwrite) 1 else 0) { overwrite = it == 1 }
                Text(if (overwrite) "清空本机的作品数据，换成备份里的内容。模型服务商保留。" else "把备份里的作品作为新作品加进来；同一部作品（书名和创建时间都相同）会跳过。",
                    style = Ling.type.caption, color = if (overwrite) c.error else c.onSurfaceVariant)
                Text("导入前会自动备份当前数据。", style = Ling.type.caption, color = c.onSurfaceVariant)
                LingButton(if (busy) "正在导入…" else "导入", enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) { if (overwrite) confirm = true else doImport(a, false, ctx, { busy = it }) { msg = it; pending = null; autos = Backup.autoBackups(ctx) } }
            }
        }
        if (confirm) LingConfirm("覆盖本机数据？", "本机所有作品会被备份里的内容替换。导入前会先自动备份当前数据，可以在本页恢复。", confirm = "覆盖", danger = true,
            onDismiss = { confirm = false }, onConfirm = { confirm = false; doImport(a, true, ctx, { busy = it }) { msg = it; pending = null; autos = Backup.autoBackups(ctx) } })
    }
}

private fun doImport(a: Backup.Archive, overwrite: Boolean, ctx: android.content.Context, busy: (Boolean) -> Unit, done: (String) -> Unit) {
    busy(true)
    AppGraph.scope.launch {
        val p = AppGraph.prefs.flow.first()
        val r = runCatching {
            Backup.autoBackup(ctx, p)
            val n = Backup.restore(a, ctx, overwrite)
            if (overwrite) a.json["prefs"]?.let { j -> runCatching { LingJson.decodeFromJsonElement(AppPrefs.serializer(), j) }.getOrNull() }?.let { np ->
                AppGraph.prefs.update { cur -> np.copy(writingModel = cur.writingModel, autoModel = cur.autoModel, embedModel = cur.embedModel, onboarded = true) }
            }
            n
        }
        withContext(Dispatchers.Main) { busy(false); done(r.fold({ "导入完成：$it 部作品" + if (overwrite) "（已覆盖）" else "" }, { "导入未完成：${it.message}。导入前的自动备份可在本页恢复。" })) }
    }
}

@Composable
fun ImportSummary(a: Backup.Archive) {
    val c = Ling.colors
    LingCard(color = c.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("备份于 ${TimeFmt.clock(a.exportedAt)}", style = Ling.type.caption, color = c.onSurfaceVariant)
            Text(a.bookTitles.take(4).joinToString("、") + if (a.bookTitles.size > 4) " 等 ${a.bookTitles.size} 部" else "", style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
            Text("${a.count("chapters")} 章 · ${a.count("lore_cards")} 张设定卡 · ${a.count("foreshadows")} 条伏笔 · ${a.count("ideas")} 条灵感", style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}
