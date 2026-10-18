// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.SeriesEntity
import com.lingwrite.app.data.db.SeriesLoreEntity
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

object SeriesModule {
    fun install() {
        ExtraDestinations.list.add { nav: NavHostController ->
            composable("series") { SeriesListRoute(nav) }
            composable("series/{seriesId}", listOf(navArgument("seriesId") { type = NavType.LongType })) { e ->
                SeriesDetailRoute(e.arguments!!.getLong("seriesId"), nav)
            }
        }
        SettingsRegistry.extra += { _, nav ->
            tr(R.string.series_001) to SettingRow(LingIcons.Book, tr(R.string.series_002), tr(R.string.series_006), tone = Tone.Neutral, onClick = { nav.navigate("series") })
        }
    }
}

@Composable
fun SeriesListRoute(nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val list by remember { AppGraph.db.series().all() }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.series_001), onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LingButton(tr(R.string.series_003), icon = LingIcons.Add, modifier = Modifier.fillMaxWidth()) { name = ""; creating = true }
            }
            if (list.isEmpty()) item { Text(tr(R.string.series_006), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
            items(list) { s ->
                LingCard {
                    Row(Modifier.fillMaxWidth().rowClick { nav.navigate("series/${s.id}") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tile(LingIcons.Book, c.primaryContainer, c.onPrimaryContainer)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(s.name, style = Ling.type.titleSmall, color = c.onSurface)
                            if (s.note.isNotBlank()) Text(s.note, style = Ling.type.caption, color = c.onSurfaceVariant)
                        }
                        Icon(LingIcons.Chevron, null, tint = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (creating) LingConfirm(tr(R.string.series_003), "", onDismiss = { creating = false }, onConfirm = {
        scope.launch { val id = SeriesStore.create(name); creating = false; nav.navigate("series/$id") }
    }, extra = { LingTextField(name, { name = it }, label = tr(R.string.series_004)) })
}

@Composable
fun SeriesDetailRoute(seriesId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val all by remember { AppGraph.db.series().all() }.collectAsState(emptyList())
    val s = all.firstOrNull { it.id == seriesId }
    val lore by remember(seriesId) { AppGraph.db.series().lore(seriesId) }.collectAsState(emptyList())
    var books by remember { mutableStateOf<List<BookEntity>>(emptyList()) }
    var unlinked by remember { mutableStateOf<List<BookEntity>>(emptyList()) }
    var addBook by remember { mutableStateOf(false) }
    var addLore by remember { mutableStateOf(false) }
    var copyItem by remember { mutableStateOf<SeriesLoreEntity?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId, books.size) {
        books = AppGraph.db.books().all().filter { it.seriesId == seriesId }
        unlinked = AppGraph.db.books().all().filter { it.seriesId == null }
    }
    if (s == null) { Text("…", modifier = Modifier.padding(24.dp)); return }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(s.name, tr(R.string.series_002), onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LingCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr(R.string.series_007), style = Ling.type.label, color = c.primary)
                        if (books.isEmpty()) Text(tr(R.string.series_009), style = Ling.type.caption, color = c.onSurfaceVariant)
                        books.forEach { b -> Text("· ${b.title}", style = Ling.type.body, color = c.onSurface) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LingButton(tr(R.string.series_008), BtnKind.Text, height = 34.dp) { addBook = true }
                            LingButton(tr(R.string.series_021), BtnKind.Text, height = 34.dp) { deleteConfirm = true }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.series_010), style = Ling.type.label, color = c.primary, modifier = Modifier.weight(1f))
                    LingButton(tr(R.string.series_011), BtnKind.Text, height = 34.dp) { addLore = true }
                }
            }
            if (lore.isEmpty()) item { Text(tr(R.string.series_023), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
            items(lore) { item ->
                LingCard {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, style = Ling.type.bodyLarge, color = c.onSurface)
                                Spacer(Modifier.width(8.dp))
                                Pill(LoreType.of(item.type).label, PillKind.Neutral)
                            }
                            val fs = parseFields(item.fields)
                            if (fs.isNotEmpty()) Text(fs.joinToString(" · ") { "${it.k}：${it.v}" }, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2)
                        }
                        LingButton(tr(R.string.series_017), BtnKind.Text, height = 32.dp) { copyItem = item }
                    }
                }
            }
            item {
                Text(tr(R.string.series_020), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                msg?.let { Text(it, style = Ling.type.caption, color = c.primary, modifier = Modifier.padding(8.dp)) }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (addBook) LingSheet({ addBook = false }, tr(R.string.series_008)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (unlinked.isEmpty()) Text(tr(R.string.series_009), style = Ling.type.body, color = c.onSurfaceVariant)
            unlinked.forEach { b ->
                SheetAction(LingIcons.Book, b.title) { scope.launch { SeriesStore.addBook(seriesId, b.id); addBook = false } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (addLore) AddSeriesLoreSheet(seriesId, onDismiss = { addLore = false })
    copyItem?.let { item ->
        LingSheet({ copyItem = null }, tr(R.string.series_018)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (books.isEmpty()) Text(tr(R.string.series_009), style = Ling.type.body, color = c.onSurfaceVariant)
                books.forEach { b ->
                    SheetAction(LingIcons.Book, b.title) {
                        scope.launch { runCatching { SeriesStore.copyToBook(item, b.id) }.onSuccess { msg = tr(R.string.series_019) }; copyItem = null }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (deleteConfirm) LingConfirm(tr(R.string.series_021), "", confirm = tr(R.string.series_021), danger = true,
        onDismiss = { deleteConfirm = false }, onConfirm = { scope.launch { SeriesStore.delete(seriesId); nav.popBackStack() } })
}

@Composable
private fun AddSeriesLoreSheet(seriesId: Long, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("character") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var fieldsText by remember { mutableStateOf("") }
    LingSheet(onDismiss, tr(R.string.series_011)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.series_013), style = Ling.type.label, color = c.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LoreType.entries.take(6).forEach { t -> LingChip(type == t.id, { type = t.id }, t.label) }
            }
            LingTextField(name, { name = it }, label = tr(R.string.series_012))
            LingTextField(role, { role = it }, label = tr(R.string.series_014))
            LingTextField(fieldsText, { fieldsText = it }, label = tr(R.string.series_015), singleLine = false, minLines = 3)
            LingButton(tr(R.string.series_016), enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), height = 48.dp) {
                val fields = fieldsText.lines().mapNotNull { line ->
                    val i = line.indexOfFirst { it == '=' || it == '：' || it == ':' }
                    if (i <= 0) null else Field(line.substring(0, i).trim(), line.substring(i + 1).trim())
                }
                scope.launch {
                    SeriesStore.insertLore(SeriesLoreEntity(seriesId = seriesId, type = type, name = name.trim(), role = role.trim(), fields = encodeFields(fields)))
                    onDismiss()
                }
            }
        }
    }
}
