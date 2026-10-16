// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

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
        LingTopBar("设定库", onBack = { nav.popBackStack() }) {
            LingIconButton(LingIcons.Help, "问设定", onClick = { ask = true })
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
                            bookId = bookId, type = "concept", name = qsn.take(10).ifBlank { "新设定" },
                            fields = encodeFields(listOf(Field("说明", ans.take(300)))), pending = true,
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
                Text("AI 从第 ${pending.first().number} 章整理出 ${pending.size} 条建议", style = Ling.type.label, color = c.onTertiaryContainer, modifier = Modifier.weight(1f))
                Icon(LingIcons.Chevron, null, tint = c.onTertiaryContainer, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EntryCard(LingIcons.Graph, "人物关系图", "按章查看关系变化", Modifier.weight(1f)) { nav.navigate("graph/$bookId") }
            EntryCard(LingIcons.Map, "地图与势力", "上传地图，标注地点和范围", Modifier.weight(1f)) { nav.navigate("maps/$bookId") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LingChip(type == "", { type = "" }, "全部 ${cards.size}") }
            items(LoreType.entries) { t ->
                val n = cards.count { it.type == t.id }
                LingChip(type == t.id, { type = if (type == t.id) "" else t.id }, "${t.label} $n")
            }
        }
        val shown = cards.filter { type.isEmpty() || it.type == type }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) Text("还没有${LoreType.entries.firstOrNull { it.id == type }?.label ?: "设定"}卡。名字或别名出现在正文里时，卡片会自动带入 AI 上下文。",
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
                        val sub = listOf(t.label, card.role, card.aliases.takeIf { it.isNotBlank() }?.let { "又名 $it" }).filter { !it.isNullOrBlank() }.joinToString(" · ")
                        Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        card.pending -> Pill("AI 建议", PillKind.Amber, LingIcons.SparkleFilled)
                        card.inject == "always" -> Pill("始终带入", PillKind.Primary)
                        card.inject == "never" -> Pill("不带入", PillKind.Neutral)
                    }
                }
            }
        }
        LingButton("新建设定卡", BtnKind.Tonal, icon = LingIcons.Add, modifier = Modifier.padding(16.dp)) { creating = true }
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
    LingSheet(onDismiss, if (card == null) "新建设定卡" else "编辑 ${card.name}", layout = SheetLayout.Viewport) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LoreType.entries) { t ->
                    LingChip(t == type, { type = t
                        if (fields.all { it.v.isBlank() }) { fields.clear(); fields.addAll(defaultFields(t).map { Field(it, "") }) } }, t.label)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LingTextField(name, { name = it }, Modifier.weight(1f), label = "名称")
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
                            val sameKind = AppGraph.db.lore().cardsOnce(bookId).filter { it.type == type.id }.take(30).joinToString("、") { it.name }
                            val bp = AppGraph.db.plan().bookOnce(bookId)
                            val bookTitle = AppGraph.db.books().get(bookId)?.title.orEmpty()
                            val genre = AppGraph.db.books().get(bookId)?.genre.orEmpty()
                            LoreAi.suggestNames(bookTitle, genre, bp?.style.orEmpty(), sameKind, type.label, role, fields.joinToString("；") { "${it.k}=${it.v}" }, namingAsk)
                                .onSuccess { nameIdeas = it }.onFailure { nameIdeas = emptyList() }
                            namingBusy = false
                        }
                    },
                    onPick = { name = it.name; naming = false },
                )
            }
            LingTextField(aliases, { aliases = it }, label = "别名", placeholder = "用逗号分隔，例如：沈师姐，清禾", supporting = "名字或别名出现在正文里，就会自动识别并带入 AI 上下文")
            LingTextField(role, { role = it }, label = "定位", placeholder = if (type == LoreType.Character) "主角 / 主要配角 / 反派…" else "一句话说明")
            // A4 补全入口：字段区底部的“AI 补全空字段”
            val blanks = fields.filter { it.v.isBlank() && it.k.isNotBlank() }
            if (blanks.isNotEmpty()) {
                LingButton(if (completing) "补全中…" else "AI 补全空字段（${blanks.size} 个）", BtnKind.Tonal, icon = LingIcons.Sparkle, height = 38.dp, enabled = !completing) {
                    completing = true
                    scope.launch {
                        val bp = AppGraph.db.plan().bookOnce(bookId)
                        val related = AppGraph.db.lore().cardsOnce(bookId).filter { it.name == name || it.allNames().contains(name) }.joinToString("；") { "${it.name}：${it.role}" }
                        val fieldsText = fields.joinToString("；") { "${it.k}=${it.v.ifBlank { "（空）" }}" }
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
                LingTextField(newKey, { newKey = it }, Modifier.weight(1f), placeholder = "自定义字段名")
                Spacer(Modifier.width(8.dp))
                LingButton("添加字段", BtnKind.Tonal, enabled = newKey.isNotBlank()) { fields += Field(newKey.trim(), ""); newKey = "" }
            }
            Text("带入 AI 上下文", style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
            Segmented(listOf("提及时", "始终", "从不"), listOf("auto", "always", "never").indexOf(inject)) { inject = listOf("auto", "always", "never")[it] }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                if (onDelete != null) LingButton("删除", BtnKind.Outlined, modifier = Modifier.weight(1f), height = 50.dp, onClick = onDelete)
                LingButton(if (card?.pending == true) "确认采纳" else "保存", modifier = Modifier.weight(1f), height = 50.dp, enabled = name.isNotBlank()) {
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
        val desc = history.map { it.label }.distinct().joinToString(" → ")
        RelUi(other.name.take(1), avatarColor(other.id), other.name, desc, if (r.fromChapter == chapter && history.size > 1) "第 ${r.fromChapter} 章变化" else null, other.id)
    }
    val pills = buildList {
        if (cd.role.isNotBlank()) add(cd.role to PillKind.Primary)
        resolved.firstOrNull { it.k in setOf("所属", "门派", "势力") }?.let { add(it.v to PillKind.Neutral) }
        cd.firstChapter?.let { add("第 $it 章登场" to PillKind.Amber) }
        if (cd.inject == "always") add("始终带入" to PillKind.Primary)
    }
    LoreScreen(
        LoreDetailUi(t.label, cd.name, cd.name.take(1), avatarColor(cd.id).let { it to it.copy(red = it.red * 0.7f, green = it.green * 0.7f, blue = it.blue * 0.7f) },
            pills, "第 $chapter 章时", resolved, relUi, fsList, cd.aliases, t == LoreType.Character),
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
        LingSheet({ changeField = null }, "记录状态变化") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("例如境界突破、受伤、身份暴露、死亡。之后写到这一章及以后，AI 会按新状态理解人物。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant)
                LingTextField(field, { field = it }, label = "字段", placeholder = "境界 / 伤势 / 生死…")
                LingTextField(value, { value = it }, label = "新的值", singleLine = false)
                LingTextField(from, { from = it.filter(Char::isDigit) }, label = "从第几章起生效", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                LingButton("保存", modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = field.isNotBlank() && value.isNotBlank()) {
                    scope.launch { dao.insertState(LoreStateEntity(cardId = cd.id, fromChapter = from.toIntOrNull() ?: chapter, field = field.trim(), value = value.trim())); changeField = null }
                }
            }
        }
    }
    if (history) LingSheet({ history = false }, "状态变化记录") {
        if (states.isEmpty()) Text("还没有记录过变化。", style = Ling.type.body, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        states.sortedByDescending { it.fromChapter }.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("第 ${s.fromChapter} 章起 · ${s.field}", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                    Text(s.value, style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
                }
                LingIconButton(LingIcons.Delete, "删除", tint = Ling.colors.onSurfaceVariant) { scope.launch { dao.deleteState(s.id) } }
            }
        }
    }
    if (addRel) RelationSheet(cd, cards.filter { it.type == "character" && it.id != cd.id && !it.pending }, chapter, { addRel = false }) { r ->
        scope.launch { dao.insertRelation(r); addRel = false }
    }
    relMenu?.let { r ->
        LingSheet({ relMenu = null }, "与 ${r.name} 的关系") {
            SheetAction(LingIcons.Badge, "打开 ${r.name}") { relMenu = null; nav.navigate(com.lingwrite.app.ui.Routes.lore(cd.bookId, r.cardId)) }
            SheetAction(LingIcons.TrendUp, "记录关系变化", sub = "从某一章起关系变成…") { relMenu = null; addRel = true }
        }
    }
}

/** 扩展点：伏笔模块提供与卡片相关的伏笔。 */
object LoreHooks {
    var foreshadows: (Long) -> kotlinx.coroutines.flow.Flow<List<FsUi>> = { kotlinx.coroutines.flow.flowOf(emptyList()) }
}

val RelationTones = listOf("ally" to "同伴", "mentor" to "师徒", "family" to "亲属", "love" to "感情", "enemy" to "敌对", "neutral" to "其他")

@Composable
fun RelationSheet(from: LoreCardEntity, others: List<LoreCardEntity>, chapter: Int, onDismiss: () -> Unit, onSave: (LoreRelationEntity) -> Unit) {
    val c = Ling.colors
    var to by remember { mutableStateOf(others.firstOrNull()) }
    var label by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("ally") }
    var at by remember { mutableStateOf("$chapter") }
    LingSheet(onDismiss, "${from.name} 的关系") {
        if (others.isEmpty()) { Text("先新建其他人物卡，才能添加关系。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp)); return@LingSheet }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others) { o -> LingChip(o == to, { to = o }, o.name) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RelationTones) { (k, v) -> LingChip(k == tone, { tone = k }, v) }
            }
            LingTextField(label, { label = it }, label = "关系", placeholder = "师弟、宿敌、暗恋…；填“断绝”表示从这章起不再有关系")
            LingTextField(at, { at = it.filter(Char::isDigit) }, label = "从第几章起", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            LingButton("保存", modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = to != null && label.isNotBlank()) {
                onSave(LoreRelationEntity(bookId = from.bookId, fromId = from.id, toId = to!!.id, label = label.trim(), fromChapter = at.toIntOrNull() ?: chapter, tone = tone))
            }
        }
    }
}

@Composable
fun ChapterSliderSheet(current: Int, max: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(current.toFloat()) }
    LingSheet(onDismiss, "按章查看") {
        Text("第 ${v.toInt()} 章时", style = Ling.type.headline, color = Ling.colors.primary, modifier = Modifier.padding(horizontal = 24.dp))
        Slider(v, { v = it }, valueRange = 1f..max.coerceAtLeast(2).toFloat(), steps = (max - 2).coerceIn(0, 200), modifier = Modifier.padding(horizontal = 20.dp),
            colors = SliderDefaults.colors(thumbColor = Ling.colors.primary, activeTrackColor = Ling.colors.primary))
        LingButton("查看", modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), height = 50.dp) { onPick(v.toInt()) }
    }
}
