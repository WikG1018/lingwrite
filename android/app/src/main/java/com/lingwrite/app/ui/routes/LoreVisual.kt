// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.LingJson
import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.LoreRelationEntity
import com.lingwrite.app.data.db.MapEntity
import com.lingwrite.app.data.db.MapMarkEntity
import com.lingwrite.app.data.relationsAt
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*

fun toneColor(t: String, primary: Color) = when (t) {
    "enemy" -> Color(0xFFC2413B); "love" -> Color(0xFFD06A8A); "family" -> Color(0xFFA8691A); "mentor" -> Color(0xFF2E7D5B); else -> primary
}

/** 力导向布局（确定性初始圆环 + 若干次迭代），坐标归一化到 0..1。 */
fun layoutGraph(ids: List<Long>, edges: List<Pair<Long, Long>>, iterations: Int = 160): Map<Long, Offset> {
    if (ids.isEmpty()) return emptyMap()
    val n = ids.size
    val pos = ids.mapIndexed { i, id -> id to Offset(0.5f + 0.35f * cos(2 * PI * i / n).toFloat(), 0.5f + 0.35f * sin(2 * PI * i / n).toFloat()) }.toMap().toMutableMap()
    val k = sqrt(1f / n) * 0.9f
    repeat(iterations) { step ->
        val disp = ids.associateWith { Offset.Zero }.toMutableMap()
        for (a in ids) for (b in ids) if (a != b) {
            val d = pos[a]!! - pos[b]!!; val dist = max(d.getDistance(), 0.01f)
            disp[a] = disp[a]!! + d / dist * (k * k / dist)
        }
        for ((a, b) in edges) {
            val d = pos[a]!! - pos[b]!!; val dist = max(d.getDistance(), 0.01f); val f = d / dist * (dist * dist / k)
            disp[a] = disp[a]!! - f; disp[b] = disp[b]!! + f
        }
        val temp = 0.08f * (1 - step / iterations.toFloat()) + 0.005f
        for (id in ids) {
            val d = disp[id]!!; val len = max(d.getDistance(), 0.0001f)
            val p = pos[id]!! + d / len * min(len, temp) + (Offset(0.5f, 0.5f) - pos[id]!!) * 0.02f
            pos[id] = Offset(p.x.coerceIn(0.08f, 0.92f), p.y.coerceIn(0.08f, 0.92f))
        }
    }
    return pos
}

@Composable
fun RelationGraphRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val dao = AppGraph.db.lore()
    val cards by remember(bookId) { dao.cards(bookId).map { l -> l.filter { it.type == "character" && !it.pending } } }.collectAsState(emptyList())
    val rels by remember(bookId) { dao.relations(bookId) }.collectAsState(emptyList())
    val maxCh by remember(bookId) { AppGraph.db.chapters().chapterHeaders(bookId).map { l -> l.maxOfOrNull { it.number } ?: 1 } }.collectAsState(1)
    var chapter by remember(maxCh) { mutableFloatStateOf(maxCh.toFloat()) }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("人物关系图", "第 ${chapter.toInt()} 章时 · 点人物打开卡片，拖动可调整位置", onBack = { nav.popBackStack() })
        RelationGraph(cards, relationsAt(rels, chapter.toInt()), Modifier.weight(1f).fillMaxWidth().padding(12.dp)) { nav.navigate(Routes.lore(bookId, it)) }
        if (maxCh > 1) Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("拖动查看不同章节时的关系", style = Ling.type.caption, color = c.onSurfaceVariant)
            Slider(chapter, { chapter = it }, valueRange = 1f..maxCh.toFloat(), colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary))
        }
    }
}

@Composable
fun RelationGraph(cards: List<LoreCardEntity>, rels: List<LoreRelationEntity>, modifier: Modifier, onOpen: (Long) -> Unit) {
    val c = Ling.colors
    val measurer = rememberTextMeasurer()
    val ids = cards.map { it.id }
    val base = remember(ids, rels.map { it.fromId to it.toId }) { layoutGraph(ids, rels.map { it.fromId to it.toId }) }
    val moved = remember(ids) { mutableStateMapOf<Long, Offset>() }
    var dragging by remember { mutableStateOf<Long?>(null) }
    if (cards.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("先在设定库里建几张人物卡，再添加关系。", style = Ling.type.body, color = c.onSurfaceVariant) }
        return
    }
    LingCard(modifier) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(cards) {
                detectTapGestures { tap ->
                    val hit = cards.minByOrNull { ((moved[it.id] ?: base[it.id]!!).let { p -> Offset(p.x * size.width, p.y * size.height) } - tap).getDistance() }
                    if (hit != null && ((moved[hit.id] ?: base[hit.id]!!).let { p -> Offset(p.x * size.width, p.y * size.height) } - tap).getDistance() < 34.dp.toPx()) onOpen(hit.id)
                }
            }
            .pointerInput(cards) {
                detectDragGestures(onDragStart = { s ->
                    dragging = cards.minByOrNull { ((moved[it.id] ?: base[it.id]!!).let { p -> Offset(p.x * size.width, p.y * size.height) } - s).getDistance() }?.id
                }, onDragEnd = { dragging = null }) { ch, d ->
                    ch.consume(); val id = dragging ?: return@detectDragGestures
                    val p = moved[id] ?: base[id]!!
                    moved[id] = Offset((p.x + d.x / size.width).coerceIn(0.05f, 0.95f), (p.y + d.y / size.height).coerceIn(0.05f, 0.95f))
                }
            }) {
            fun at(id: Long) = (moved[id] ?: base[id] ?: Offset(0.5f, 0.5f)).let { Offset(it.x * size.width, it.y * size.height) }
            val labelStyle = TextStyle(fontFamily = com.lingwrite.app.ui.theme.MiSans, fontSize = 11.sp, color = c.onSurfaceVariant)
            rels.forEach { r ->
                if (base[r.fromId] == null || base[r.toId] == null) return@forEach
                val a = at(r.fromId); val b = at(r.toId); val col = toneColor(r.tone, c.primary)
                drawLine(col.copy(alpha = 0.55f), a, b, 2.dp.toPx(), pathEffect = if (r.tone == "enemy") PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
                val mid = (a + b) / 2f
                val tl = measurer.measure(r.label, labelStyle)
                drawRoundRect(c.surface, Offset(mid.x - tl.size.width / 2f - 6.dp.toPx(), mid.y - tl.size.height / 2f - 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(tl.size.width + 12.dp.toPx(), tl.size.height + 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawText(tl, topLeft = Offset(mid.x - tl.size.width / 2f, mid.y - tl.size.height / 2f))
            }
            cards.forEach { card ->
                val p = at(card.id); val r = 24.dp.toPx()
                drawCircle(Color.Black.copy(alpha = if (c.isDark) 0.3f else 0.08f), r + 2.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
                drawCircle(avatarColor(card.id), r, p)
                val init = measurer.measure(card.name.take(1), TextStyle(fontFamily = com.lingwrite.app.ui.theme.MiSans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                drawText(init, topLeft = p - Offset(init.size.width / 2f, init.size.height / 2f))
                val nm = measurer.measure(card.name, TextStyle(fontFamily = com.lingwrite.app.ui.theme.MiSans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onSurface))
                drawText(nm, topLeft = Offset(p.x - nm.size.width / 2f, p.y + r + 4.dp.toPx()))
            }
        }
    }
}

// ---------------- 地图 ----------------

fun parsePoints(s: String): List<Offset> = runCatching { LingJson.parseToJsonElement(s).jsonArray.map { val a = it.jsonArray; Offset(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float) } }.getOrDefault(emptyList())
fun encodePoints(p: List<Offset>) = buildJsonArray { p.forEach { o -> addJsonArray { add(o.x); add(o.y) } } }.toString()

val MarkColors = listOf(0xFF3E5C8A, 0xFFC2413B, 0xFF2E7D5B, 0xFFA8691A, 0xFF8A5A7A)

@Composable
fun MapRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val dao = AppGraph.db.lore()
    val scope = rememberCoroutineScope()
    val maps by remember(bookId) { dao.maps(bookId) }.collectAsState(emptyList())
    var current by remember { mutableStateOf<Long?>(null) }
    val map = maps.firstOrNull { it.id == current } ?: maps.firstOrNull()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
                val f = File(dir, "map_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                "maps/${f.name}"
            }
            current = dao.insertMap(MapEntity(bookId = bookId, name = "地图 ${maps.size + 1}", image = name))
        }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar("地图与势力", onBack = { nav.popBackStack() }) { LingIconButton(LingIcons.Add, "上传地图") { picker.launch("image/*") } }
        if (map == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Tile(LingIcons.Map, c.primaryContainer, c.onPrimaryContainer, 64.dp)
                Text("上传一张你自己的地图", style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.padding(top = 16.dp))
                Text("手绘拍照或其他工具画的都可以，只存在本机。之后可以在图上标地点、画势力范围，并关联到设定卡。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                LingButton("选择图片", icon = LingIcons.Add) { picker.launch("image/*") }
            }
        } else {
            if (maps.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(maps) { m -> LingChip(m.id == map.id, { current = m.id }, m.name) }
            }
            MapEditor(map, nav)
        }
    }
}

@Composable
private fun ColumnScope.MapEditor(map: MapEntity, nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val dao = AppGraph.db.lore()
    val scope = rememberCoroutineScope()
    val marks by remember(map.id) { dao.marks(map.id) }.collectAsState(emptyList())
    val cards by remember(map.bookId) { dao.cards(map.bookId) }.collectAsState(emptyList())
    val maxCh by remember(map.bookId) { AppGraph.db.chapters().chapterHeaders(map.bookId).map { l -> l.maxOfOrNull { it.number } ?: 1 } }.collectAsState(1)
    var chapter by remember(maxCh) { mutableFloatStateOf(maxCh.toFloat()) }
    val bmp by produceState<ImageBitmap?>(null, map.image) {
        value = withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, map.image)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }
    }
    var mode by remember { mutableStateOf("view") } // view / point / region
    val draft = remember { mutableStateListOf<Offset>() }
    var naming by remember { mutableStateOf<Pair<String, List<Offset>>?>(null) }
    var open by remember { mutableStateOf<MapMarkEntity?>(null) }
    val visible = marks.filter { it.fromChapter <= chapter.toInt() && (it.untilChapter == null || it.untilChapter >= chapter.toInt()) }
    val measurer = rememberTextMeasurer()

    Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = c.primary)
        else Box(Modifier.aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(14.dp))) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(mode, visible) {
                detectTapGestures { t ->
                    val n = Offset(t.x / size.width, t.y / size.height)
                    when (mode) {
                        "point" -> naming = "point" to listOf(n)
                        "region" -> draft += n
                        else -> {
                            val hit = visible.firstOrNull { m ->
                                val pts = parsePoints(m.points)
                                if (m.kind == "point") pts.firstOrNull()?.let { (Offset(it.x * size.width, it.y * size.height) - t).getDistance() < 28.dp.toPx() } == true
                                else inPolygon(n, pts)
                            }
                            open = hit
                        }
                    }
                }
            }) {
                fun px(o: Offset) = Offset(o.x * size.width, o.y * size.height)
                visible.filter { it.kind == "region" }.forEach { m ->
                    val pts = parsePoints(m.points).map(::px); if (pts.size < 3) return@forEach
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    drawPath(path, Color(m.color).copy(alpha = 0.28f)); drawPath(path, Color(m.color), style = Stroke(2.dp.toPx()))
                    val ctr = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                    val tl = measurer.measure(m.label, TextStyle(fontFamily = com.lingwrite.app.ui.theme.MiSans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black, blurRadius = 4f)))
                    drawText(tl, topLeft = ctr - Offset(tl.size.width / 2f, tl.size.height / 2f))
                }
                visible.filter { it.kind == "point" }.forEach { m ->
                    val p = parsePoints(m.points).firstOrNull()?.let(::px) ?: return@forEach
                    drawCircle(Color.White, 9.dp.toPx(), p); drawCircle(Color(m.color), 6.dp.toPx(), p)
                    val tl = measurer.measure(m.label, TextStyle(fontFamily = com.lingwrite.app.ui.theme.MiSans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, shadow = Shadow(Color.Black, blurRadius = 4f)))
                    drawText(tl, topLeft = Offset(p.x - tl.size.width / 2f, p.y + 10.dp.toPx()))
                }
                if (draft.isNotEmpty()) {
                    val pts = draft.map(::px)
                    pts.zipWithNext { a, b2 -> drawLine(c.amber, a, b2, 2.dp.toPx()) }
                    pts.forEach { drawCircle(c.amber, 5.dp.toPx(), it) }
                }
            }
        }
    }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Segmented(listOf("查看", "标地点", "画势力范围"), listOf("view", "point", "region").indexOf(mode)) { mode = listOf("view", "point", "region")[it]; draft.clear() }
        if (mode == "region") Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("依次点出范围的边界（已点 ${draft.size} 个）", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
            LingButton("撤销", BtnKind.Text, height = 36.dp, enabled = draft.isNotEmpty()) { draft.removeAt(draft.lastIndex) }
            LingButton("完成", height = 36.dp, enabled = draft.size >= 3) { naming = "region" to draft.toList() }
        }
        if (maxCh > 1) {
            Text("第 ${chapter.toInt()} 章时的势力分布", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Slider(chapter, { chapter = it }, valueRange = 1f..maxCh.toFloat(), colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary))
        }
    }
    naming?.let { (kind, pts) ->
        var label by remember { mutableStateOf("") }
        var link by remember { mutableStateOf<LoreCardEntity?>(null) }
        var color by remember { mutableLongStateOf(MarkColors[0]) }
        var from by remember { mutableStateOf("${chapter.toInt()}") }
        val candidates = cards.filter { it.type == (if (kind == "point") "place" else "faction") }
        LingSheet({ naming = null }, if (kind == "point") "标注地点" else "势力范围") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LingTextField(label, { label = it }, label = "名称")
                if (candidates.isNotEmpty()) {
                    Text("关联到设定卡", style = Ling.type.caption, color = c.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(candidates) { cd -> LingChip(cd == link, { link = if (link == cd) null else cd; if (label.isBlank()) label = cd.name }, cd.name) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MarkColors.forEach { col -> Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(col)).rowClick { color = col }) { if (col == color) androidx.compose.material3.Icon(LingIcons.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center)) } }
                }
                LingTextField(from, { from = it.filter(Char::isDigit) }, label = "从第几章起", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                LingButton("保存", modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = label.isNotBlank()) {
                    scope.launch {
                        dao.insertMark(MapMarkEntity(mapId = map.id, cardId = link?.id, kind = kind, label = label.trim(), points = encodePoints(pts), color = color, fromChapter = from.toIntOrNull() ?: 1))
                        naming = null; draft.clear(); mode = "view"
                    }
                }
            }
        }
    }
    open?.let { m ->
        var until by remember(m) { mutableStateOf(m.untilChapter?.toString() ?: "") }
        LingSheet({ open = null }, m.label) {
            Text(if (m.kind == "point") "地点 · 第 ${m.fromChapter} 章起" else "势力范围 · 第 ${m.fromChapter} 章起" + (m.untilChapter?.let { "，到第 $it 章" } ?: ""), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            m.cardId?.let { id -> SheetAction(LingIcons.Book, "打开设定卡") { open = null; nav.navigate(Routes.lore(map.bookId, id)) } }
            if (m.kind == "region") Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LingTextField(until, { until = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "到第几章为止（留空表示一直存在）", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                Spacer(Modifier.width(8.dp))
                LingButton("保存", BtnKind.Tonal) { scope.launch { dao.updateMark(m.copy(untilChapter = until.toIntOrNull())); open = null } }
            }
            SheetAction(LingIcons.Delete, "删除标注", danger = true) { scope.launch { dao.deleteMark(m.id); open = null } }
        }
    }
}

fun inPolygon(p: Offset, poly: List<Offset>): Boolean {
    var inside = false; var j = poly.lastIndex
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
