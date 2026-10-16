// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.TimeFmt
import com.lingwrite.app.data.ai.AiService
import com.lingwrite.app.data.ai.KeyVault
import com.lingwrite.app.data.ai.Preset
import com.lingwrite.app.data.ai.Presets
import com.lingwrite.app.data.db.ProviderEntity
import com.lingwrite.app.data.db.UsageRow
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.ModelsScreen
import com.lingwrite.app.ui.screens.ModelsUi
import com.lingwrite.app.ui.screens.ProviderUi
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

fun monthStart(): Long = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

val WritingTasks = setOf("continue", "rewrite", "expand", "polish", "deai", "dialog", "annotate", "wizard", "ask", "describe", "shorten")
val AutoTasks = setOf("summary", "check", "extract", "foreshadow", "style")

fun tokensLabel(n: Long) = when { n >= 1_000_000 -> "%.2fM".format(n / 1e6); n >= 1000 -> "%.1fK".format(n / 1e3); else -> "$n" }

fun estimateCost(rows: List<UsageRow>, providers: List<ProviderEntity>): Double? {
    var total = 0.0; var any = false
    rows.forEach { r ->
        val p = providers.firstOrNull { it.name == r.provider } ?: return@forEach
        AiService.prices(p.prices)[r.model]?.let { (i, o) -> total += r.inTok / 1e6 * i + r.outTok / 1e6 * o; any = true }
    }
    return if (any) total else null
}

fun ProviderEntity.ui(): ProviderUi {
    val preset = Presets.byId(presetId)
    val color = preset?.color?.let { Color(it) } ?: Color(0xFF5F6B7E)
    val letter = preset?.letter ?: name.take(1).uppercase()
    val mode = when { presetId == "custom" -> "自定义 · " + baseUrl.removePrefix("https://").substringBefore('/'); access == "sub" -> "订阅套餐"; else -> "按量付费 API" }
    val sub = mode + (if (keyHint.isNotBlank()) " · Key $keyHint" else "")
    val status = when { !enabled -> "off"; keyCipher.isBlank() -> "nokey"; access == "sub" -> "terms"; lastTest == "ok" -> "ok"; lastTest == "fail" -> "fail"; else -> "ok" }
    return ProviderUi(id, letter, color, name, sub, status, enabled)
}

@Composable
fun ModelsRoute(nav: NavHostController) {
    val ai = AppGraph.ai
    val scope = rememberCoroutineScope()
    val providers by remember { ai.providers() }.collectAsState(emptyList())
    val usage by remember { AppGraph.db.ai().usageSince(monthStart()) }.collectAsState(emptyList())
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    var pick by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }

    val calls = usage.sumOf { it.calls }
    val w = usage.filter { it.task in WritingTasks }.sumOf { it.calls }.toFloat()
    val a = usage.filter { it.task in AutoTasks }.sumOf { it.calls }.toFloat()
    val total = calls.toFloat().coerceAtLeast(1f)
    val cost = estimateCost(usage, providers)
    fun label(ref: String, fallback: String): String {
        val id = ref.substringBefore('|').toLongOrNull(); val p = providers.firstOrNull { it.id == id }
        return if (p == null) fallback else ref.substringAfter('|')
    }
    val ui = ModelsUi(
        calls = calls, cost = cost?.let { "约 ¥%.2f".format(it) },
        tokens = "输入 ${tokensLabel(usage.sumOf { it.inTok })} · 输出 ${tokensLabel(usage.sumOf { it.outTok })} tokens" + if (cost != null) " · 按你填的单价估算" else " · 填写单价后可估算费用",
        split = if (calls == 0) listOf(0f, 0f, 0f) else listOf(w / total * 100, a / total * 100, (total - w - a) / total * 100),
        writing = label(prefs?.writingModel.orEmpty(), if (providers.isEmpty()) "未设置" else "自动选择"),
        auto = label(prefs?.autoModel.orEmpty(), "跟随写作模型"),
        providers = providers.map { it.ui() },
    )
    ModelsScreen(
        ui, onBack = { nav.popBackStack() }, onStats = { nav.navigate("stats") },
        onWriting = { pick = "writing" }, onAuto = { pick = "auto" },
        onProvider = { nav.navigate("provider/$it/x") },
        onToggle = { id, on -> scope.launch { providers.firstOrNull { it.id == id }?.let { AppGraph.db.ai().updateProvider(it.copy(enabled = on)) } } },
        onAdd = { add = true }, onHelp = { help = true },
    )
    pick?.let { slot ->
        ModelPicker(providers.filter { it.enabled && it.keyCipher.isNotBlank() }, allowFollow = slot == "auto",
            current = if (slot == "auto") prefs?.autoModel.orEmpty() else prefs?.writingModel.orEmpty(), onDismiss = { pick = null }) { ref ->
            scope.launch { AppGraph.prefs.update { if (slot == "auto") it.copy(autoModel = ref) else it.copy(writingModel = ref) } }
            pick = null
        }
    }
    if (add) LingSheet({ add = false }, "添加服务商") {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(Presets.all) { p ->
                ProviderPick(p.letter, Color(p.color), p.name, p.note.ifBlank { p.baseUrl.removePrefix("https://") }) { add = false; nav.navigate("provider/0/${p.id}") }
            }
            item { ProviderPick("+", Ling.colors.outline, "自定义服务商", "OpenAI 兼容或 Anthropic 协议，例如豆包、OpenRouter") { add = false; nav.navigate("provider/0/custom") } }
        }
    }
    if (help) LingConfirm("关于模型设置", "灵写不经过任何服务器，直接用你的 Key 连接服务商。\n\n写作模型用于续写、改写等你主动发起的操作；自动任务模型用于写完一章后的摘要、一致性检查、伏笔建议，可以选更便宜的模型。\n\n各家“订阅套餐”大多只允许在编程工具中使用，用于写作 App 可能被封 Key，默认请用按量付费 API。",
        confirm = "知道了", dismiss = "", onDismiss = { help = false }, onConfirm = { help = false })
}

@Composable
private fun ProviderPick(letter: String, color: Color, name: String, sub: String, onClick: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
            Text(letter, style = Ling.type.label.copy(fontWeight = FontWeight.Bold, fontSize = if (letter.length > 1) 13.sp else 15.sp), color = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
            Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun ModelPicker(providers: List<ProviderEntity>, allowFollow: Boolean, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Ling.colors
    LingSheet(onDismiss, if (allowFollow) "自动任务模型" else "写作模型") {
        if (providers.isEmpty()) Text("还没有填好 Key 的服务商。先添加一个。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            if (allowFollow) item { PickRow("跟随写作模型", "不单独指定", current.isBlank()) { onPick("") } }
            providers.forEach { p ->
                item { Text(p.name, style = Ling.type.label, color = c.primary, modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp)) }
                items(p.models.split(',').map { it.trim() }.filter { it.isNotBlank() }) { m ->
                    val ref = "${p.id}|$m"
                    PickRow(m, null, current == ref) { onPick(ref) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Ling.type.bodyLarge, color = c.onSurface)
            if (sub != null) Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        if (selected) Icon(LingIcons.Check, null, tint = c.primary)
    }
}

/** 服务商详情 / 新建。 */
@Composable
fun ProviderEditRoute(id: Long, presetId: String, nav: NavHostController) {
    val c = Ling.colors
    val dao = AppGraph.db.ai()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf<ProviderEntity?>(null) }
    LaunchedEffect(id) {
        loaded = if (id > 0) dao.provider(id) else {
            val p = Presets.byId(presetId)
            if (p != null) ProviderEntity(presetId = p.id, name = p.name, protocol = p.protocol, baseUrl = p.baseUrl, auth = p.auth, models = p.models.joinToString(","))
            else ProviderEntity(presetId = "custom", name = "", protocol = "openai", baseUrl = "", auth = "bearer", models = "")
        }
    }
    val base = loaded ?: return
    val preset: Preset? = Presets.byId(base.presetId)
    var name by remember(base) { mutableStateOf(base.name) }
    var protocol by remember(base) { mutableStateOf(base.protocol) }
    var url by remember(base) { mutableStateOf(base.baseUrl) }
    var auth by remember(base) { mutableStateOf(base.auth) }
    var access by remember(base) { mutableStateOf(base.access) }
    var key by remember(base) { mutableStateOf("") }
    var models by remember(base) { mutableStateOf(base.models.split(',').map { it.trim() }.filter { it.isNotBlank() }) }
    var newModel by remember { mutableStateOf("") }
    var prices by remember(base) { mutableStateOf(AiService.prices(base.prices)) }
    var testMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var termsAsk by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build(): ProviderEntity {
        val cipher = if (key.isNotBlank()) KeyVault.encrypt(key.trim()) else base.keyCipher
        val hint = if (key.isNotBlank()) KeyVault.mask(key.trim()) else base.keyHint
        return base.copy(name = name.ifBlank { "自定义" }, protocol = protocol, baseUrl = url.trim(), auth = auth, access = access,
            models = models.joinToString(","), keyCipher = cipher, keyHint = hint, prices = AiService.encodePrices(prices.filterKeys { it in models }))
    }
    suspend fun save(): ProviderEntity {
        val e = build()
        return if (e.id == 0L) e.copy(id = dao.insertProvider(e)).also { loaded = it } else e.also { dao.updateProvider(it); loaded = it }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(if (id > 0) name else "添加 ${preset?.name ?: "自定义服务商"}", onBack = { nav.popBackStack() }) {
            if (id > 0) LingIconButton(LingIcons.Delete, "删除", tint = c.error) { confirmDelete = true }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (preset?.subscription != null) {
                SectionLabel("接入方式")
                Segmented(listOf("按量付费 API", "订阅套餐"), if (access == "sub") 1 else 0, Modifier.padding(horizontal = 16.dp)) { i ->
                    if (i == 1 && access != "sub") termsAsk = true
                    if (i == 0 && access == "sub") { access = "payg"; url = preset.baseUrl; models = preset.models }
                }
            }
            SectionLabel("连接")
            LingCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (preset == null) {
                        LingTextField(name, { name = it }, label = "名称", placeholder = "例如：豆包、OpenRouter")
                        Text("协议", style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
                        Segmented(listOf("OpenAI 兼容", "Anthropic"), if (protocol == "anthropic") 1 else 0) { protocol = if (it == 1) "anthropic" else "openai"; auth = if (it == 1) "x-api-key" else "bearer" }
                        Text("认证方式", style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
                        Segmented(listOf("Bearer", "x-api-key", "api-key"), listOf("bearer", "x-api-key", "api-key").indexOf(auth).coerceAtLeast(0)) { auth = listOf("bearer", "x-api-key", "api-key")[it] }
                    }
                    LingTextField(url, { url = it }, label = "接口地址", keyboardType = KeyboardType.Uri, supporting = preset?.note?.takeIf { it.isNotBlank() })
                    LingTextField(key, { key = it }, label = "API Key", placeholder = if (base.keyHint.isNotBlank()) "已保存（${base.keyHint}），留空则不修改" else (if (access == "sub") preset?.subscription?.keyHint else preset?.keyHint) ?: "粘贴 Key",
                        visualTransformation = PasswordVisualTransformation(), supporting = "Key 用 Android Keystore 加密后只存在本机，只发给这个服务商")
                }
            }
            SectionLabel("模型")
            LingCard(Modifier.padding(horizontal = 16.dp)) {
                models.forEachIndexed { i, m ->
                    if (i > 0) InsetDivider(16.dp)
                    ModelRow(m, prices[m], onPrice = { p -> prices = if (p == null) prices - m else prices + (m to p) }) { models = models - m }
                }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LingTextField(newModel, { newModel = it }, Modifier.weight(1f), placeholder = "添加模型名")
                    Spacer(Modifier.width(8.dp))
                    LingButton("添加", BtnKind.Tonal, enabled = newModel.isNotBlank()) { models = (models + newModel.trim()).distinct(); newModel = "" }
                }
                if (protocol == "openai" && (preset?.canListModels != false)) {
                    LingButton("从服务商拉取模型列表", BtnKind.Text, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp), enabled = !busy) {
                        scope.launch {
                            busy = true
                            val r = AppGraph.ai.listModels(save())
                            r.onSuccess { list -> if (list.isNotEmpty()) models = (models + list).distinct(); testMsg = true to "拉取到 ${list.size} 个模型" }
                                .onFailure { testMsg = false to (it.message ?: "拉取失败") }
                            busy = false
                        }
                    }
                }
            }
            testMsg?.let { (ok, msg) ->
                Row(Modifier.padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) LingIcons.CheckCircle else LingIcons.Info, null, Modifier.size(18.dp), tint = if (ok) c.good else c.error)
                    Spacer(Modifier.width(6.dp)); Text(msg, style = Ling.type.caption, color = if (ok) c.good else c.error)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LingButton(if (busy) "测试中…" else "测试连接", BtnKind.Outlined, modifier = Modifier.weight(1f), height = 50.dp, enabled = !busy && models.isNotEmpty() && url.isNotBlank() && (key.isNotBlank() || base.keyCipher.isNotBlank())) {
                scope.launch { busy = true; val p = save(); val r = AppGraph.ai.test(p, models.first()); testMsg = r.isSuccess to (r.getOrNull() ?: r.exceptionOrNull()?.message ?: ""); busy = false }
            }
            LingButton("保存", modifier = Modifier.weight(1f), height = 50.dp, enabled = url.isNotBlank() && models.isNotEmpty()) {
                scope.launch { save(); nav.popBackStack() }
            }
        }
    }
    if (termsAsk && preset?.subscription != null) {
        val sub = preset.subscription
        LingConfirm("订阅套餐的条款风险", sub.terms + "\n\n灵写不会伪装成编程工具，也不会修改 User-Agent。继续使用即表示你了解并自行承担被限流、封 Key 或停订阅的风险。",
            confirm = "我了解风险，切换", danger = true, onDismiss = { termsAsk = false },
            onConfirm = { access = "sub"; url = sub.baseUrl; sub.defaultModel?.let { models = listOf(it) }; termsAsk = false },
            extra = {
                sub.sources.forEach { s ->
                    Text(s, style = Ling.type.caption.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = c.primary,
                        modifier = Modifier.padding(top = 8.dp).rowClick { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s))) } })
                }
            })
    }
    if (confirmDelete) LingConfirm("删除 ${base.name}？", "Key 会一起删除。已有的调用记录会保留。", confirm = "删除", danger = true,
        onDismiss = { confirmDelete = false }, onConfirm = { scope.launch { dao.deleteProvider(base.id); nav.popBackStack() } })
}

@Composable
private fun ModelRow(model: String, price: Pair<Double, Double>?, onPrice: (Pair<Double, Double>?) -> Unit, onRemove: () -> Unit) {
    val c = Ling.colors
    var edit by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().rowClick { edit = true }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model, style = Ling.type.bodyLarge, color = c.onSurface)
            Text(price?.let { "输入 ¥${it.first} / 输出 ¥${it.second} 每百万 token" } ?: "点按填写单价（用于估算费用）", style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        LingIconButton(LingIcons.Close, "移除", tint = c.onSurfaceVariant, onClick = onRemove)
    }
    if (edit) {
        var i by remember { mutableStateOf(price?.first?.toString() ?: "") }
        var o by remember { mutableStateOf(price?.second?.toString() ?: "") }
        LingConfirm("$model 单价", "每百万 token 的价格（元），以服务商账单为准。", onDismiss = { edit = false },
            onConfirm = { val a = i.toDoubleOrNull(); val b = o.toDoubleOrNull(); onPrice(if (a != null && b != null) a to b else null); edit = false },
            extra = {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LingTextField(i, { i = it }, Modifier.weight(1f), label = "输入", keyboardType = KeyboardType.Decimal)
                    LingTextField(o, { o = it }, Modifier.weight(1f), label = "输出", keyboardType = KeyboardType.Decimal)
                }
            })
    }
}

/** 分段选择器：胶囊底 + 滑动选中块。 */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = Ling.colors
    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(3.dp)) {
        val n = options.size.coerceAtLeast(1)
        val itemW = maxWidth / n
        val x by androidx.compose.animation.core.animateDpAsState(itemW * selected, Motion.bouncy(), label = "segInd")
        Box(Modifier.offset(x = x).width(itemW).fillMaxHeight().clip(RoundedCornerShape(17.dp)).background(c.surface))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Box(Modifier.weight(1f).fillMaxHeight().rowClick { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(o, style = Ling.type.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.onSurface else c.onSurfaceVariant)
                }
            }
        }
    }
}
