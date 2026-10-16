// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.*
import com.lingwrite.app.ui.theme.CoverPalette
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Space
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val AvatarColors = listOf(Color(0xFF3E5C8A), Color(0xFFA8691A), Color(0xFF2E7D5B), Color(0xFF8A5A7A), Color(0xFF5F6B7E), Color(0xFFB0624A))
fun avatarColor(id: Long) = AvatarColors[(id % AvatarColors.size).toInt()]

fun typeIcon(t: LoreType): ImageVector = when (t) {
    LoreType.Character -> LingIcons.Badge; LoreType.Faction -> LingIcons.Shelf; LoreType.Place -> LingIcons.Place
    LoreType.Item -> LingIcons.Key; LoreType.Power -> LingIcons.Bolt; LoreType.Term -> LingIcons.Book
}

/** 设定库模块：注册作品主页 tab、编辑器人名高亮、导航目的地。 */
object LoreModule {
    fun install() {
        WorkTabs.lore = { bookId -> LoreWorkspace(bookId, LocalNav.current) }
        WorkTabs.loreCount = { AppGraph.db.lore().count(it) }
        EditorHooks.names = { bookId -> AppGraph.db.lore().cards(bookId).map { l -> l.filter { it.type == "character" && !it.pending }.flatMap { it.allNames() } } }
        com.lingwrite.app.ui.ExtraDestinations.list.add { nav: NavHostController ->
            composable("lorelib/{bookId}/{type}") { e ->
                LoreLibraryPage(e.arguments!!.getString("bookId")!!.toLong(), e.arguments!!.getString("type") ?: "", nav)
            }
            composable("graph/{bookId}") { e: androidx.navigation.NavBackStackEntry -> RelationGraphRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
            composable("maps/{bookId}") { e: androidx.navigation.NavBackStackEntry -> MapRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
            composable("lore-suggest/{bookId}") { e: androidx.navigation.NavBackStackEntry -> LoreSuggestRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
        }
    }
}

val LocalNav = staticCompositionLocalOf<NavHostController> { error("no nav") }

/** lore/{bookId}/{cardId}：>0 详情；0 设定库；-1 只看人物。 */
@Composable
fun LoreRoute(bookId: Long, cardId: Long, nav: NavHostController) {
    if (cardId > 0) LoreDetailRoute(cardId, nav) else LoreLibraryPage(bookId, if (cardId == -1L) "character" else "", nav)
}

@Composable
fun LoreLibraryPage(bookId: Long, type: String, nav: NavHostController) {
    val c = Ling.colors
    // A15：设定库「问」入口
    var ask by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.ask_panel_005), onBack = { nav.popBackStack() }) {
            LingIconButton(LingIcons.Help, tr(R.string.ask_panel_017), onClick = { ask = true })
        }
        LoreWorkspace(bookId, nav, type, Modifier.weight(1f))
    }
    if (ask) {
        val last by androidx.compose.runtime.produceState<com.lingwrite.app.data.db.ChapterEntity?>(null, bookId) {
            value = AppGraph.db.chapters().chaptersOnce(bookId).maxByOrNull { it.number }
        }
        last?.let { ch ->
            val scope = rememberCoroutineScope()
            AskPanel(
                chapterId = ch.id, bookId = bookId, chapterNo = ch.number, quote = null,
                onDismiss = { ask = false },
                onToLoreCard = { qsn, ans ->
                    scope.launch {
                        AppGraph.db.lore().insertCard(LoreCardEntity(
                            bookId = bookId, type = "concept", name = qsn.take(10).ifBlank { tr(R.string.context_031) },
                            fields = encodeFields(listOf(Field(tr(R.string.context_032), ans.take(300)))), pending = true,
                        ))
                    }
                },
                loreScope = true,
            )
        }
    }
}

@Composable
fun LoreLibrary(bookId: Long, nav: NavHostController, initialType: String = "", onSelect: ((Long) -> Unit)? = null) {
    val c = Ling.colors
    val dao = AppGraph.db.lore()
    val scope = rememberCoroutineScope()
    val cards by remember(bookId) { dao.cards(bookId) }.collectAsState(emptyList())
    var type by remember { mutableStateOf(initialType) }
    var creating by remember { mutableStateOf(false) }
    Column {
        // A2：AI 整理建议横幅（ui-spec §5）
        val pending by remember(bookId) { AppGraph.db.loreSuggest().pending(bookId) }.collectAsState(emptyList())
        if (pending.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(c.tertiaryContainer)
                    .rowClick { nav.navigate("lore-suggest/$bookId") }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LingIcons.SparkleFilled, null, tint = c.onTertiaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.lore_001, pending.first().number, pending.size), style = Ling.type.label, color = c.onTertiaryContainer, modifier = Modifier.weight(1f))
                Icon(LingIcons.Chevron, null, tint = c.onTertiaryContainer, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EntryCard(LingIcons.Graph, tr(R.string.lore_002), tr(R.string.lore_003), Modifier.weight(1f)) { nav.navigate("graph/$bookId") }
            EntryCard(LingIcons.Map, tr(R.string.lore_004), tr(R.string.lore_005), Modifier.weight(1f)) { nav.navigate("maps/$bookId") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LingChip(type == "", { type = "" }, tr(R.string.lore_006, cards.size)) }
            items(LoreType.entries) { t ->
                val n = cards.count { it.type == t.id }
                LingChip(type == t.id, { type = if (type == t.id) "" else t.id }, "${trText(t.label)} $n")
            }
        }
        val shown = cards.filter { type.isEmpty() || it.type == type }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) Text(tr(R.string.lore_008, LoreType.entries.firstOrNull { it.id == type }?.label ?: tr(R.string.lore_007)),
            style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
        else LingCard(Modifier.padding(horizontal = 16.dp)) {
            shown.forEachIndexed { i, card ->
                if (i > 0) InsetDivider(70.dp)
                val t = LoreType.of(card.type)
                Row(Modifier.fillMaxWidth().rowClick { if (onSelect != null) onSelect(card.id) else nav.navigate(com.lingwrite.app.ui.Routes.lore(bookId, card.id)) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t == LoreType.Character) Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(avatarColor(card.id)), contentAlignment = Alignment.Center) {
                        Text(card.name.take(1), style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = Color.White)
                    } else Tile(typeIcon(t), c.surfaceContainer, c.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.name, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
                        val sub = listOf(trText(t.label), card.role, card.aliases.takeIf { it.isNotBlank() }?.let { tr(R.string.lore_009, it) }).filter { !it.isNullOrBlank() }.joinToString(" · ")
                        Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        card.pending -> Pill(tr(R.string.lore_010), PillKind.Amber, LingIcons.SparkleFilled)
                        card.inject == "always" -> Pill(tr(R.string.lore_011), PillKind.Primary)
                        card.inject == "never" -> Pill(tr(R.string.lore_012), PillKind.Neutral)
                    }
                }
            }
        }
        LingButton(tr(R.string.lore_013), BtnKind.Tonal, icon = LingIcons.Add, modifier = Modifier.padding(16.dp)) { creating = true }
    }
    if (creating) CardEditSheet(null, bookId, defaultType = type.ifEmpty { "character" }, onDismiss = { creating = false }) { e ->
        scope.launch { val id = dao.insertCard(e); creating = false; if (onSelect != null) onSelect(id) else nav.navigate(com.lingwrite.app.ui.Routes.lore(bookId, id)) }
    }
}

@Composable
private fun EntryCard(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Ling.colors
    LingCard(modifier) {
        Column(Modifier.fillMaxWidth().rowClick(onClick).padding(14.dp)) {
            Tile(icon, c.primaryContainer, c.onPrimaryContainer, 36.dp)
            Text(title, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.padding(top = 10.dp))
            Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** 新建 / 编辑卡片。 */
@Composable
fun CardEditSheet(card: LoreCardEntity?, bookId: Long, defaultType: String = "character", onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (LoreCardEntity) -> Unit) {
    val c = Ling.colors
    var type by remember { mutableStateOf(LoreType.of(card?.type ?: defaultType)) }
    var name by remember { mutableStateOf(card?.name ?: "") }
    var aliases by remember { mutableStateOf(card?.aliases ?: "") }
    var role by remember { mutableStateOf(card?.role ?: "") }
    var inject by remember { mutableStateOf(card?.inject ?: "auto") }
    val fields = remember {
        val existing = card?.let { parseFields(it.fields) } ?: emptyList()
        mutableStateListOf<Field>().apply { addAll(existing.ifEmpty { defaultFields(type).map { Field(it, "") } }) }
    }
    var newKey by remember { mutableStateOf("") }
    // A4：AI 起名与空字段补全
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var namingBusy by remember { mutableStateOf(false) }
    var namingAsk by remember { mutableStateOf("") }
    var nameIdeas by remember { mutableStateOf<List<NameIdea>>(emptyList()) }
    var completing by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LingSheet(onDismiss, if (card == null) tr(R.string.lore_013) else tr(R.string.lore_014, card.name), layout = SheetLayout.Viewport) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LoreType.entries) { t ->
                    LingChip(t == type, { type = t
                        if (fields.all { it.v.isBlank() }) { fields.clear(); fields.addAll(defaultFields(t).map { Field(it, "") }) } }, trText(t.label))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LingTextField(name, { name = it }, Modifier.weight(1f), label = tr(R.string.ideas_027))
                // A4 起名入口：名字框右侧的 ✨
                LingButton("✨", BtnKind.Text, height = 36.dp, onClick = { naming = !naming })
            }
            if (naming) {
                LoreNamingCard(
                    loading = namingBusy,
                    names = nameIdeas,
                    ask = namingAsk,
                    onAskChange = { namingAsk = it },
                    onGenerate = {
                        namingBusy = true
                        scope.launch {
                            val sameKind = AppGraph.db.lore().cardsOnce(bookId).filter { it.type == type.id }.take(30).joinToString(tr(R.string.data_043)) { it.name }
                            val bp = AppGraph.db.plan().bookOnce(bookId)
                            val bookTitle = AppGraph.db.books().get(bookId)?.title.orEmpty()
                            val genre = AppGraph.db.books().get(bookId)?.genre.orEmpty()
                            LoreAi.suggestNames(bookTitle, genre, bp?.style.orEmpty(), sameKind, type.label, role, fields.joinToString(tr(R.string.ask_panel_008)) { "${it.k}=${it.v}" }, namingAsk)
                                .onSuccess { nameIdeas = it }.onFailure { nameIdeas = emptyList() }
                            namingBusy = false
                        }
                    },
                    onPick = { name = it.name; naming = false },
                )
            }
            LingTextField(aliases, { aliases = it }, label = tr(R.string.lore_015), placeholder = tr(R.string.lore_016), supporting = tr(R.string.lore_017))
            LingTextField(role, { role = it }, label = tr(R.string.lore_018), placeholder = if (type == LoreType.Character) tr(R.string.lore_019) else tr(R.string.lore_020))
            // A4 补全入口：字段区底部的“AI 补全空字段”
            val blanks = fields.filter { it.v.isBlank() && it.k.isNotBlank() }
            if (blanks.isNotEmpty()) {
                LingButton(if (completing) tr(R.string.lore_021) else tr(R.string.lore_022, blanks.size), BtnKind.Tonal, icon = LingIcons.Sparkle, height = 38.dp, enabled = !completing) {
                    completing = true
                    scope.launch {
                        val bp = AppGraph.db.plan().bookOnce(bookId)
                        val related = AppGraph.db.lore().cardsOnce(bookId).filter { it.name == name || it.allNames().contains(name) }.joinToString(tr(R.string.ask_panel_008)) { tr(R.string.lore_023, it.name, it.role) }
                        val fieldsText = fields.joinToString(tr(R.string.ask_panel_008)) { "${it.k}=${it.v.ifBlank { tr(R.string.lore_024) }}" }
                        LoreAi.completeFields(bp?.synopsis.orEmpty(), related, "", fieldsText)
                            .onSuccess { m -> drafts = m.filterKeys { k -> blanks.any { it.k == k } } }
                        completing = false
                    }
                }
                drafts.forEach { (k, v) ->
                    FieldDraftRow(k, v, onAccept = {
                        val idx = fields.indexOfFirst { it.k == k }
                        if (idx >= 0) fields[idx] = fields[idx].copy(v = it)
                        drafts = drafts - k
                    }, onReject = { drafts = drafts - k })
                }
            }
            fields.forEachIndexed { i, f ->
                LingTextField(f.v, { fields[i] = f.copy(v = it) }, label = f.k, singleLine = false, maxLines = 5)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LingTextField(newKey, { newKey = it }, Modifier.weight(1f), placeholder = tr(R.string.lore_025))
                Spacer(Modifier.width(8.dp))
                LingButton(tr(R.string.lore_026), BtnKind.Tonal, enabled = newKey.isNotBlank()) { fields += Field(newKey.trim(), ""); newKey = "" }
            }
            Text(tr(R.string.lore_027), style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
            Segmented(listOf(tr(R.string.lore_028), tr(R.string.lore_029), tr(R.string.lore_030)), listOf("auto", "always", "never").indexOf(inject)) { inject = listOf("auto", "always", "never")[it] }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                if (onDelete != null) LingButton(tr(R.string.check_044), BtnKind.Outlined, modifier = Modifier.weight(1f), height = 50.dp, onClick = onDelete)
                LingButton(if (card?.pending == true) tr(R.string.lore_031) else tr(R.string.check_046), modifier = Modifier.weight(1f), height = 50.dp, enabled = name.isNotBlank()) {
                    val base = card ?: LoreCardEntity(bookId = bookId, type = type.id, name = "")
                    onSave(base.copy(type = type.id, name = name.trim(), aliases = aliases.trim(), role = role.trim(), inject = inject,
                        fields = encodeFields(fields.filter { it.k.isNotBlank() }), pending = false, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
}

/** 设定卡详情：按章查看、字段变化、关系、相关伏笔。 */
@Composable
fun LoreDetailRoute(cardId: Long, nav: NavHostController, atChapter: Int? = null, onBack: (() -> Unit)? = null) {
    val dao = AppGraph.db.lore()
    val scope = rememberCoroutineScope()
    val card by remember(cardId) { dao.card(cardId) }.collectAsState(null)
    val cd = card ?: return
    val states by remember(cardId) { dao.states(cardId) }.collectAsState(emptyList())
    val rels by remember(cd.bookId) { dao.relations(cd.bookId) }.collectAsState(emptyList())
    val cards by remember(cd.bookId) { dao.cards(cd.bookId) }.collectAsState(emptyList())
    val maxCh by remember(cd.bookId) { AppGraph.db.chapters().chapterHeaders(cd.bookId).map { l -> l.maxOfOrNull { it.number } ?: 1 } }.collectAsState(1)
    val fsList by remember(cardId) { LoreHooks.foreshadows(cardId) }.collectAsState(emptyList())
    var chapter by remember(cardId, maxCh) { mutableIntStateOf(atChapter ?: maxCh) }
    var edit by remember { mutableStateOf(false) }
    var pickChapter by remember { mutableStateOf(false) }
    var changeField by remember { mutableStateOf<String?>(null) }
    var addRel by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var relMenu by remember { mutableStateOf<RelUi?>(null) }
    val t = LoreType.of(cd.type)
    val resolved = resolveAt(cd, states, chapter)
    val relUi = relationsAt(rels.filter { it.fromId == cd.id || it.toId == cd.id }, chapter).mapNotNull { r ->
        val other = cards.firstOrNull { it.id == (if (r.fromId == cd.id) r.toId else r.fromId) } ?: return@mapNotNull null
        val history = rels.filter { (it.fromId == r.fromId && it.toId == r.toId) || (it.fromId == r.toId && it.toId == r.fromId) }.filter { it.fromChapter <= chapter }.sortedBy { it.fromChapter }
        val desc = history.map { trText(it.label) }.distinct().joinToString(" → ")
        RelUi(other.name.take(1), avatarColor(other.id), other.name, desc, if (r.fromChapter == chapter && history.size > 1) tr(R.string.lore_032, r.fromChapter) else null, other.id)
    }
    val pills = buildList {
        if (cd.role.isNotBlank()) add(cd.role to PillKind.Primary)
        resolved.firstOrNull { it.k in setOf("所属", tr(R.string.lore_033), tr(R.string.lore_034)) }?.let { add(it.v to PillKind.Neutral) }
        cd.firstChapter?.let { add(tr(R.string.lore_035, it) to PillKind.Amber) }
        if (cd.inject == "always") add(tr(R.string.lore_011) to PillKind.Primary)
    }
    LoreScreen(
        LoreDetailUi(trText(t.label), cd.name, cd.name.take(1), avatarColor(cd.id).let { it to it.copy(red = it.red * 0.7f, green = it.green * 0.7f, blue = it.blue * 0.7f) },
            pills, tr(R.string.lore_036, chapter), resolved, relUi, fsList, cd.aliases, t == LoreType.Character),
        onBack = { if (onBack != null) onBack() else nav.popBackStack() }, onHistory = { history = true }, onEdit = { edit = true }, onMore = { edit = true },
        onChapter = { pickChapter = true }, onField = { changeField = it }, onRelation = { relMenu = it },
        onAddRelation = { addRel = true }, onAddChange = { changeField = resolved.firstOrNull()?.k ?: "" },
    )
    if (edit) CardEditSheet(cd, cd.bookId, onDismiss = { edit = false }, onDelete = { scope.launch { dao.deleteCard(cd.id); edit = false; if (onBack != null) onBack() else nav.popBackStack() } }) {
        scope.launch { dao.updateCard(it); edit = false }
    }
    if (pickChapter) ChapterSliderSheet(chapter, maxCh, { pickChapter = false }) { chapter = it; pickChapter = false }
    changeField?.let { f ->
        var field by remember(f) { mutableStateOf(f) }
        var value by remember(f) { mutableStateOf("") }
        var from by remember(f) { mutableStateOf("$chapter") }
        LingSheet({ changeField = null }, tr(R.string.lore_037)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(R.string.lore_038), style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
                LingTextField(field, { field = it }, label = tr(R.string.lore_039), placeholder = tr(R.string.lore_040))
                LingTextField(value, { value = it }, label = tr(R.string.lore_041), singleLine = false)
                LingTextField(from, { from = it.filter(Char::isDigit) }, label = tr(R.string.lore_042), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                LingButton(tr(R.string.check_046), modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = field.isNotBlank() && value.isNotBlank()) {
                    scope.launch { dao.insertState(LoreStateEntity(cardId = cd.id, fromChapter = from.toIntOrNull() ?: chapter, field = field.trim(), value = value.trim())); changeField = null }
                }
            }
        }
    }
    if (history) LingSheet({ history = false }, tr(R.string.lore_043)) {
        if (states.isEmpty()) Text(tr(R.string.lore_044), style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        states.sortedByDescending { it.fromChapter }.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.lore_045, s.fromChapter, s.field), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                    Text(s.value, style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
                }
                LingIconButton(LingIcons.Delete, tr(R.string.check_044), tint = Ling.colors.onSurfaceVariant) { scope.launch { dao.deleteState(s.id) } }
            }
        }
    }
    if (addRel) RelationSheet(cd, cards.filter { it.type == "character" && it.id != cd.id && !it.pending }, chapter, { addRel = false }) { r ->
        scope.launch { dao.insertRelation(r); addRel = false }
    }
    relMenu?.let { r ->
        LingSheet({ relMenu = null }, tr(R.string.lore_046, r.name)) {
            SheetAction(LingIcons.Badge, tr(R.string.lore_047, r.name)) { relMenu = null; nav.navigate(com.lingwrite.app.ui.Routes.lore(cd.bookId, r.cardId)) }
            SheetAction(LingIcons.TrendUp, tr(R.string.lore_048), sub = tr(R.string.lore_049)) { relMenu = null; addRel = true }
        }
    }
}

/** 扩展点：伏笔模块提供与卡片相关的伏笔。 */
object LoreHooks {
    var foreshadows: (Long) -> kotlinx.coroutines.flow.Flow<List<FsUi>> = { kotlinx.coroutines.flow.flowOf(emptyList()) }
}

val RelationTones get() = listOf("ally" to tr(R.string.lore_050), "mentor" to tr(R.string.lore_051), "family" to tr(R.string.lore_052), "love" to tr(R.string.lore_053), "enemy" to tr(R.string.lore_054), "neutral" to tr(R.string.lore_055))

@Composable
fun RelationSheet(from: LoreCardEntity, others: List<LoreCardEntity>, chapter: Int, onDismiss: () -> Unit, onSave: (LoreRelationEntity) -> Unit) {
    val c = Ling.colors
    var to by remember { mutableStateOf(others.firstOrNull()) }
    var label by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("ally") }
    var at by remember { mutableStateOf("$chapter") }
    LingSheet(onDismiss, tr(R.string.lore_056, from.name)) {
        if (others.isEmpty()) { Text(tr(R.string.lore_057), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp)); return@LingSheet }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others) { o -> LingChip(o == to, { to = o }, o.name) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RelationTones) { (k, v) -> LingChip(k == tone, { tone = k }, v) }
            }
            LingTextField(label, { label = it }, label = tr(R.string.ask_panel_002), placeholder = tr(R.string.lore_058))
            LingTextField(at, { at = it.filter(Char::isDigit) }, label = tr(R.string.lore_059), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingButton(tr(R.string.check_046), modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = to != null && label.isNotBlank()) {
                onSave(LoreRelationEntity(bookId = from.bookId, fromId = from.id, toId = to!!.id, label = label.trim(), fromChapter = at.toIntOrNull() ?: chapter, tone = tone))
            }
        }
    }
}

@Composable
fun ChapterSliderSheet(current: Int, max: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(current.toFloat()) }
    LingSheet(onDismiss, tr(R.string.lore_060)) {
        Text(tr(R.string.lore_036, v.toInt()), style = Ling.type.headline, color = Ling.colors.primary, modifier = Modifier.padding(horizontal = 24.dp))
        Slider(v, { v = it }, valueRange = 1f..max.coerceAtLeast(2).toFloat(), steps = (max - 2).coerceIn(0, 200), modifier = Modifier.padding(horizontal = 20.dp),
            colors = SliderDefaults.colors(thumbColor = Ling.colors.primary, activeTrackColor = Ling.colors.primary))
        LingButton(tr(R.string.lore_061), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), height = 50.dp) { onPick(v.toInt()) }
    }
}
