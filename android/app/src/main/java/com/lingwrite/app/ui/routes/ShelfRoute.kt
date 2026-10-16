// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.ShelfScreen
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Genres get() = listOf(tr(R.string.shelf_001), tr(R.string.shelf_002), tr(R.string.shelf_003), tr(R.string.shelf_004), tr(R.string.shelf_005), tr(R.string.shelf_006), tr(R.string.shelf_007), tr(R.string.shelf_008), tr(R.string.lore_055))

@Composable
fun ShelfRoute(onOpenBook: (Long) -> Unit, onOpenChapter: (Long) -> Unit, onSearch: () -> Unit, onWizard: (Long) -> Unit) {
    val repo = AppGraph.books
    val scope = rememberCoroutineScope()
    val summaries by remember { repo.summaries() }.collectAsState(initial = null)
    val list = summaries ?: return
    var hero by remember { mutableStateOf<HeroInfo?>(null) }
    val today by remember { repo.todayWords() }.collectAsState(0)
    LaunchedEffect(list.firstOrNull()?.id, list.firstOrNull()?.updatedAt) { hero = list.firstOrNull()?.let { repo.hero(it.id) } }
    var newSheet by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Book?>(null) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var renameFor by remember { mutableStateOf<Book?>(null) }

    ShelfScreen(
        books = list.map { it.toUi() }, hero = hero, todayWords = today,
        onOpenBook = { id -> if (list.firstOrNull { it.id == id }?.status == "wizard") onWizard(id) else onOpenBook(id) },
        onContinue = { h -> h.chapterId?.let(onOpenChapter) ?: onOpenBook(h.bookId) },
        onSearch = onSearch, onNewBook = { newSheet = true },
        onSample = { scope.launch { repo.seedSample() } }, onBookMenu = { menuFor = it },
    )

    if (newSheet) NewBookSheet(onDismiss = { newSheet = false }, onWizard = { scope.launch { newSheet = false; onWizard(repo.createBook("", status = "wizard")) } }) { t, g ->
        scope.launch { newSheet = false; onOpenBook(repo.createBook(t, g)) }
    }
    menuFor?.let { b ->
        LingSheet({ menuFor = null }, b.title) {
            SheetAction(LingIcons.EditOutline, tr(R.string.shelf_009)) { renameFor = b; menuFor = null }
            SheetAction(LingIcons.Check, tr(R.string.shelf_010)) { scope.launch { repo.setStatus(b.id, "done") }; menuFor = null }
            SheetAction(LingIcons.Delete, tr(R.string.shelf_011), danger = true, sub = tr(R.string.shelf_012)) { confirmDelete = b; menuFor = null }
        }
    }
    renameFor?.let { b ->
        var t by remember(b) { mutableStateOf(b.title) }
        LingConfirm(tr(R.string.shelf_009), "", onDismiss = { renameFor = null }, onConfirm = { scope.launch { repo.rename(b.id, t) }; renameFor = null }, extra = {
            LingTextField(t, { t = it }, Modifier.padding(top = 4.dp))
        })
    }
    confirmDelete?.let { b ->
        LingConfirm(tr(R.string.shelf_013, b.title), tr(R.string.shelf_014), confirm = tr(R.string.check_044), danger = true,
            onDismiss = { confirmDelete = null }, onConfirm = { scope.launch { repo.deleteBook(b.id) }; confirmDelete = null })
    }
}

@Composable
fun NewBookSheet(onDismiss: () -> Unit, onWizard: () -> Unit, onCreate: (String, String) -> Unit) {
    val c = Ling.colors
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    LingSheet(onDismiss, tr(R.string.shelf_015)) {
        LingCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), color = c.primaryContainer) {
            Row(Modifier.fillMaxWidth().rowClick(onWizard).padding(16.dp)) {
                Tile(LingIcons.SparkleFilled, c.primary, c.onPrimary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(tr(R.string.shelf_016), style = Ling.type.subhead, color = c.onPrimaryContainer)
                    Text(tr(R.string.shelf_017), style = Ling.type.caption, color = c.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        Text(tr(R.string.shelf_018), style = Ling.type.label, color = c.primary, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
        LingTextField(title, { title = it }, Modifier.padding(horizontal = 20.dp), placeholder = tr(R.string.shelf_019))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(Genres) { g ->
                LingChip(g == genre, { genre = if (genre == g) "" else g }, g)
            }
        }
        LingButton(tr(R.string.shelf_020), modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(), height = 50.dp, enabled = title.isNotBlank()) { onCreate(title.trim(), genre) }
    }
}
