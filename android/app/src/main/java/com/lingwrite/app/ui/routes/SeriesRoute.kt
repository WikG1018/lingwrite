// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.lingwrite.app.i18n.trText
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import androidx.compose.animation.animateContentSize
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
    val list by remember { AppGraph.db.series().all() }.collectAsState(null)
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.series_001), onBack = { nav.popBackStack() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "new") {
                LingButton(tr(R.string.series_003), icon = LingIcons.Add, modifier = Modifier.fillMaxWidth().enterStagger(0)) { name = ""; creating = true }
            }
            item(key = "loading") { DelayedSkeleton(list == null) }
            if (list?.isEmpty() == true) item(key = "empty") {
                Text(tr(R.string.series_006), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp).enterStagger(1))
            }
            itemsIndexed(list.orEmpty(), key = { _, s -> s.id }) { i, s ->
                LingCard(Modifier.fillMaxWidth().enterStagger(1 + i).animateItem()) {
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
    val all by remember { AppGraph.db.series().all() }.collectAsState(null)
    // 删除系列后返回的途中 s 会变成 null：保留最后一次的值，不闪占位。
    var last by remember { mutableStateOf<SeriesEntity?>(null) }
    all?.firstOrNull { it.id == seriesId }?.let { last = it }
    val s = last
    val lore by remember(seriesId) { AppGraph.db.series().lore(seriesId) }.collectAsState(emptyList())
    // Flow 查询：加入 / 移出系列后列表立即刷新（审查 #21）。
    val books by remember(seriesId) { AppGraph.db.books().inSeries(seriesId) }.collectAsState(emptyList())
    val unlinked by remember { AppGraph.db.books().unlinked() }.collectAsState(emptyList())
    var addBook by remember { mutableStateOf(false) }
    var editLore by remember { mutableStateOf<SeriesLoreEntity?>(null) }
    var addLore by remember { mutableStateOf(false) }
    var copyItem by remember { mutableStateOf<SeriesLoreEntity?>(null) }
    var removeBook by remember { mutableStateOf<BookEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(s?.name.orEmpty(), tr(R.string.series_002), onBack = { nav.popBackStack() }) {
            if (s != null) LingIconButton(LingIcons.More, tr(R.string.series_026)) { menu = true }
        }
        if (s == null) {
            Column(Modifier.padding(horizontal = 16.dp)) { DelayedSkeleton(all == null) }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "books") {
                LingCard(Modifier.fillMaxWidth().enterStagger(0)) {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(R.string.series_007), style = Ling.type.label, color = c.primary, modifier = Modifier.weight(1f))
                            LingButton(tr(R.string.series_008), BtnKind.Text, icon = LingIcons.Add, height = 34.dp) { addBook = true }
                        }
                        if (books.isEmpty()) Text(tr(R.string.series_027), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Column(Modifier.fillMaxWidth().animateContentSize(Motion.grow())) {
                            books.forEachIndexed { i, b ->
                                if (i > 0) InsetDivider(16.dp)
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(b.title.ifBlank { tr(R.string.book_ai_010) }, style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                                    LingButton(tr(R.string.series_028), BtnKind.Text, height = 32.dp) { removeBook = b }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "loreHeader") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).enterStagger(1), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.series_010), style = Ling.type.label, color = c.primary, modifier = Modifier.weight(1f))
                    LingButton(tr(R.string.series_011), BtnKind.Text, icon = LingIcons.Add, height = 34.dp) { addLore = true }
                }
            }
            if (lore.isEmpty()) item(key = "loreEmpty") { Text(tr(R.string.series_023), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp).enterStagger(2)) }
            itemsIndexed(lore, key = { _, it -> it.id }) { i, item ->
                LingCard(Modifier.fillMaxWidth().enterStagger(2 + i).animateItem()) {
                    Row(Modifier.fillMaxWidth().rowClick { editLore = item }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, style = Ling.type.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                Pill(trText(LoreType.of(item.type).label), PillKind.Neutral)
                            }
                            val fs = parseFields(item.fields)
                            if (fs.isNotEmpty()) Text(fs.joinToString(" · ") { "${it.k}：${it.v}" }, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2)
                        }
                        LingButton(tr(R.string.series_017), BtnKind.Text, height = 32.dp) { copyItem = item }
                    }
                }
            }
            item(key = "foot") {
                Text(tr(R.string.series_020), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                androidx.compose.animation.AnimatedVisibility(msg != null,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Medium)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Short))) {
                    Text(msg.orEmpty(), style = Ling.type.caption, color = c.primary, modifier = Modifier.padding(8.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (menu) LingSheet({ menu = false }) {
        SheetAction(LingIcons.Edit, tr(R.string.series_029)) { menu = false; renaming = true }
        SheetAction(LingIcons.Delete, tr(R.string.series_021), danger = true) { menu = false; deleteConfirm = true }
        Spacer(Modifier.height(12.dp))
    }
    if (renaming) {
        var name by remember { mutableStateOf(s?.name.orEmpty()) }
        LingConfirm(tr(R.string.series_029), "", onDismiss = { renaming = false }, onConfirm = {
            scope.launch { SeriesStore.rename(seriesId, name); renaming = false }
        }, extra = { LingTextField(name, { name = it }, label = tr(R.string.series_004)) })
    }
    if (addBook) LingSheet({ addBook = false }, tr(R.string.series_008)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (unlinked.isEmpty()) Text(tr(R.string.series_030), style = Ling.type.body, color = c.onSurfaceVariant)
        }
        unlinked.forEach { b ->
            SheetAction(LingIcons.Book, b.title.ifBlank { tr(R.string.book_ai_010) }) { scope.launch { SeriesStore.addBook(seriesId, b.id); addBook = false } }
        }
        Spacer(Modifier.height(12.dp))
    }
    removeBook?.let { b ->
        LingConfirm(tr(R.string.series_031), tr(R.string.series_032, b.title), confirm = tr(R.string.series_028),
            onDismiss = { removeBook = null }, onConfirm = { scope.launch { SeriesStore.removeBook(b.id); removeBook = null } })
    }
    if (addLore) SeriesLoreSheet(seriesId, null, onDismiss = { addLore = false })
    editLore?.let { item -> SeriesLoreSheet(seriesId, item, onDismiss = { editLore = null }) }
    copyItem?.let { item ->
        LingSheet({ copyItem = null }, tr(R.string.series_018)) {
            if (books.isEmpty()) Text(tr(R.string.series_027), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            books.forEach { b ->
                SheetAction(LingIcons.Book, b.title.ifBlank { tr(R.string.book_ai_010) }) {
                    scope.launch {
                        msg = runCatching { SeriesStore.copyToBook(item, b.id) }.fold(
                            { r -> if (r is CopyResult.Duplicate) tr(R.string.series_033, item.name, b.title) else tr(R.string.series_019) },
                            { tr(R.string.series_034, it.message) })
                        copyItem = null
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (deleteConfirm) LingConfirm(tr(R.string.series_021), tr(R.string.series_035), confirm = tr(R.string.series_021), danger = true,
        onDismiss = { deleteConfirm = false }, onConfirm = { deleteConfirm = false; scope.launch { SeriesStore.delete(seriesId); nav.popBackStack() } })
}

/** 新增或编辑共享卡；编辑时可删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesLoreSheet(seriesId: Long, editing: SeriesLoreEntity?, onDismiss: () -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(editing?.type ?: "character") }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var role by remember { mutableStateOf(editing?.role.orEmpty()) }
    var fieldsText by remember { mutableStateOf(editing?.let { e -> parseFields(e.fields).joinToString("\n") { "${it.k}=${it.v}" } }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LingSheet(onDismiss, if (editing == null) tr(R.string.series_011) else tr(R.string.series_036)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.series_013), style = Ling.type.label, color = c.primary)
            // FlowRow：6 个类型在 360dp 或英文 / 日文下会换行而不是溢出。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LoreType.entries.forEach { t -> LingChip(type == t.id, { type = t.id }, trText(t.label)) }
            }
            LingTextField(name, { name = it }, label = tr(R.string.series_012))
            LingTextField(role, { role = it }, label = tr(R.string.series_014))
            LingTextField(fieldsText, { fieldsText = it }, label = tr(R.string.series_015), singleLine = false, minLines = 3)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editing != null) LingButton(tr(R.string.series_037), BtnKind.Outlined, icon = LingIcons.Delete, height = 48.dp) { confirmDelete = true }
                LingButton(tr(R.string.series_016), enabled = name.isNotBlank(), modifier = Modifier.weight(1f), height = 48.dp) {
                    val fields = fieldsText.lines().mapNotNull { line ->
                        val i = line.indexOfFirst { it == '=' || it == '：' || it == ':' }
                        if (i <= 0) null else Field(line.substring(0, i).trim(), line.substring(i + 1).trim())
                    }
                    scope.launch {
                        if (editing == null) SeriesStore.insertLore(SeriesLoreEntity(seriesId = seriesId, type = type, name = name.trim(), role = role.trim(), fields = encodeFields(fields)))
                        else SeriesStore.updateLore(editing.copy(type = type, name = name.trim(), role = role.trim(), fields = encodeFields(fields), updatedAt = System.currentTimeMillis()))
                        onDismiss()
                    }
                }
            }
        }
    }
    if (confirmDelete && editing != null) LingConfirm(tr(R.string.series_037), tr(R.string.series_038, editing.name), confirm = tr(R.string.series_037), danger = true,
        onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; scope.launch { SeriesStore.deleteLore(editing.id); onDismiss() } })
}
