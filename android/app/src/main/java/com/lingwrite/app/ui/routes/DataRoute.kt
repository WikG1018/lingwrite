// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

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
        SettingsRegistry.extra += { _, nav -> tr(R.string.data_001) to SettingRow(LingIcons.Lock, tr(R.string.data_002), tr(R.string.data_003), tone = Tone.Good, onClick = { nav.navigate(Routes.Backup) }) }
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
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Exporter.render(bookId, opts).toByteArray()) } }; tr(R.string.data_004, Words.grouped(Words.count(preview))) }.getOrElse { tr(R.string.data_005, it.message) }
        }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.data_006), book?.title, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf("TXT", "Markdown"), if (md) 1 else 0) { md = it == 1 }
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.data_007), style = Ling.type.label, color = c.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LingTextField(from, { from = it.filter(Char::isDigit) }, Modifier.weight(1f), label = tr(R.string.data_008), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        LingTextField(to, { to = it.filter(Char::isDigit) }, Modifier.weight(1f), label = tr(R.string.data_009), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    }
                    CheckRow(tr(R.string.data_010), titles) { titles = it }
                    CheckRow(tr(R.string.data_011), blurb) { blurb = it }
                    if (titles) {
                        Text(tr(R.string.data_012), style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
                        Segmented(ExportNumStyle.entries.map { trText(it.label) }, ExportNumStyle.entries.indexOf(numStyle)) { numStyle = ExportNumStyle.entries[it] }
                    }
                    Text(tr(R.string.data_013), style = Ling.type.caption, color = c.onSurfaceVariant)
                }
            }
            Text(tr(R.string.data_014), style = Ling.type.label, color = c.primary, modifier = Modifier.padding(start = 8.dp))
            LingCard(color = c.paper) {
                Text(preview.take(900) + if (preview.length > 900) "\n…" else "", style = Ling.type.body.copy(lineHeight = 22.sp, fontFamily = if (md) FontFamily.Monospace else Ling.type.body.fontFamily), color = c.paperInk, modifier = Modifier.padding(16.dp))
            }
            msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(start = 8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        LingButton(tr(R.string.data_015), icon = LingIcons.Share, height = 50.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            saver.launch("${book?.title?.ifBlank { tr(R.string.book_ai_010) } ?: tr(R.string.data_016)}.${if (md) "md" else "txt"}")
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
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { Backup.writeZip(it, ctx, p) } }; tr(R.string.data_017) }.getOrElse { tr(R.string.data_018, it.message) }
            busy = false
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { Backup.readZip(it) } } }.onSuccess { pending = it }.onFailure { msg = tr(R.string.data_019, it.message) } }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.data_002), onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.data_020), style = Ling.type.titleSmall, color = c.onSurface)
                    Text(tr(R.string.data_021), style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                    LingButton(if (busy) tr(R.string.data_022) else tr(R.string.data_023), icon = LingIcons.Lock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        saver.launch(tr(R.string.data_024, java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())))
                    }
                }
            }
            LingCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.data_025), style = Ling.type.titleSmall, color = c.onSurface)
                    Text(tr(R.string.data_026), style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
                    LingButton(tr(R.string.data_027), BtnKind.Tonal, icon = LingIcons.Add, modifier = Modifier.fillMaxWidth()) { opener.launch(arrayOf("application/zip", "application/octet-stream")) }
                }
            }
            msg?.let { Text(it, style = Ling.type.body, color = c.primary, modifier = Modifier.padding(horizontal = 8.dp)) }
            if (autos.isNotEmpty()) {
                SectionLabel(tr(R.string.data_028))
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
        LingSheet({ pending = null }, tr(R.string.data_029)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportSummary(a)
                Segmented(listOf(tr(R.string.data_030), tr(R.string.data_031)), if (overwrite) 1 else 0) { overwrite = it == 1 }
                Text(if (overwrite) tr(R.string.data_032) else tr(R.string.data_033),
                    style = Ling.type.caption, color = if (overwrite) c.error else c.onSurfaceVariant)
                Text(tr(R.string.data_034), style = Ling.type.caption, color = c.onSurfaceVariant)
                LingButton(if (busy) tr(R.string.data_035) else tr(R.string.data_036), enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 50.dp) { if (overwrite) confirm = true else doImport(a, false, ctx, { busy = it }) { msg = it; pending = null; autos = Backup.autoBackups(ctx) } }
            }
        }
        if (confirm) LingConfirm(tr(R.string.data_037), tr(R.string.data_038), confirm = tr(R.string.data_031), danger = true,
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
        withContext(Dispatchers.Main) { busy(false); done(r.fold({ tr(R.string.data_039, it) + if (overwrite) tr(R.string.data_040) else "" }, { tr(R.string.data_041, it.message) })) }
    }
}

@Composable
fun ImportSummary(a: Backup.Archive) {
    val c = Ling.colors
    LingCard(color = c.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(R.string.data_042, TimeFmt.clock(a.exportedAt)), style = Ling.type.caption, color = c.onSurfaceVariant)
            Text(a.bookTitles.take(4).joinToString(tr(R.string.data_043)) + if (a.bookTitles.size > 4) tr(R.string.data_044, a.bookTitles.size) else "", style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
            Text(tr(R.string.data_045, a.count("chapters"), a.count("lore_cards"), a.count("foreshadows"), a.count("ideas")), style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}
