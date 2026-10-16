// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.BuildConfig
import com.lingwrite.app.data.Release
import com.lingwrite.app.data.Updater
import com.lingwrite.app.ui.AppOverlays
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.SettingSection
import com.lingwrite.app.ui.screens.SettingsListScreen
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Radius
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object UpdateHooks {
    /** 启动检查发现新版本时调用（通知模块挂载）。 */
    var onFound: (Release) -> Unit = {}
}

val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

object UpdateModule {
    const val Route = "update"
    fun install() {
        ExtraDestinations.list.add { nav: NavHostController -> composable(Route) { UpdateRoute(nav) } }
        SettingsRegistry.extra += { _, nav ->
            val a by Updater.available.collectAsState()
            "应用" to SettingRow(LingIcons.Download, "应用更新", if (a != null) "有新版本 v${a!!.version}" else "当前 v$currentVersion", tone = if (a != null) Tone.Error else Tone.Primary, onClick = { nav.navigate(Route) })
        }
        AppOverlays.list += { _ ->
            var found by remember { mutableStateOf<Release?>(null) }
            LaunchedEffect(Unit) { found = Updater.autoCheck(current = currentVersion)?.also { UpdateHooks.onFound(it) } }
            found?.let { r -> UpdateSheet(r, onDismiss = { found = null }) }
        }
    }
}

private fun mirrorName(m: String) = when (m) { "auto" -> "自动"; "github" -> "GitHub"; "custom" -> "自定义"; else -> Updater.mirrors.firstOrNull { it.id == m }?.name ?: "镜像" }

@Composable
fun UpdateRoute(nav: NavHostController) {
    val p by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val prefs = p ?: run { SettingsListScreen("应用更新", emptyList(), onBack = { nav.popBackStack() }, footer = "正在加载…"); return }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Release?>(null) }
    var srcSheet by remember { mutableStateOf(false) }
    var customSheet by remember { mutableStateOf(false) }
    fun check() {
        if (checking) return
        checking = true; status = null
        scope.launch {
            runCatching { Updater.latest(prefs.mirror, prefs.customMirror) }
                .onSuccess { (r, src) ->
                    AppGraph.prefs.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                    if (r != null && Updater.newer(r.version, currentVersion)) { Updater.available.value = r; found = r }
                    else { Updater.available.value = null; status = "已是最新版本 v$currentVersion" }
                    if (src != "GitHub") status = status?.let { "$it（经 $src）" }
                }
                .onFailure {
                    if (snack.showSnackbar("检查更新失败：网络不可用或更新源无响应", "重试", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) { checking = false; check() }
                }
            checking = false
        }
    }
    val a by Updater.available.collectAsState()
    Box(Modifier.fillMaxSize()) {
        SettingsListScreen("应用更新", listOf(
            SettingSection("", listOf(
                SettingRow(LingIcons.Download, if (checking) "正在检查…" else "检查更新", status ?: (a?.let { "有新版本 v${it.version}" } ?: "当前 v$currentVersion"),
                    tone = if (a != null) Tone.Error else Tone.Primary, onClick = { a?.let { found = it } ?: check() }),
            )),
            SettingSection("设置", listOf(
                SettingRow(LingIcons.Schedule, "启动时自动检查", "每天最多一次", switch = prefs.autoUpdateCheck, tone = Tone.Neutral,
                    onSwitch = { v -> scope.launch { AppGraph.prefs.update { it.copy(autoUpdateCheck = v) } } }),
                SettingRow(LingIcons.Graph, "更新源", "GitHub 不通时自动改走镜像", mirrorName(prefs.mirror), tone = Tone.Neutral, onClick = { srcSheet = true }),
            )),
        ), onBack = { nav.popBackStack() }, footer = "安装包下载后会先校验 SHA-256 和签名证书，与当前版本不一致时不会安装。" + if (prefs.skippedVersion.isNotEmpty()) "\n已跳过 v${prefs.skippedVersion}。" else "")
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
    if (srcSheet) LingSheet({ srcSheet = false }, "更新源") {
        (listOf("auto" to "自动（GitHub 优先，失败回退镜像）", "github" to "仅 GitHub") + Updater.mirrors.map { it.id to "镜像 · ${it.name}" }).forEach { (k, v) ->
            SheetAction(if (prefs.mirror == k) LingIcons.Check else LingIcons.Graph, v) { scope.launch { AppGraph.prefs.update { it.copy(mirror = k) } }; srcSheet = false }
        }
        SheetAction(if (prefs.mirror == "custom") LingIcons.Check else LingIcons.EditOutline, "自定义镜像", sub = prefs.customMirror.ifBlank { "填写一个 GitHub 加速前缀" }) { srcSheet = false; customSheet = true }
    }
    if (customSheet) LingSheet({ customSheet = false }, "自定义镜像") {
        var v by remember { mutableStateOf(prefs.customMirror) }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LingTextField(v, { v = it }, Modifier.fillMaxWidth(), label = "前缀地址", placeholder = "https://example.com/")
            Text("请求会变成「前缀 + GitHub 原地址」。签名与 SHA-256 校验照常进行。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
            LingButton("保存", enabled = v.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                scope.launch { AppGraph.prefs.update { it.copy(mirror = "custom", customMirror = v.trim()) } }; customSheet = false
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    found?.let { UpdateSheet(it) { found = null } }
}

/** 极简 Markdown：标题、列表、**粗体**、`代码`。 */
@Composable
fun MdText(md: String) {
    val c = Ling.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.contains(Regex("SHA-?256", RegexOption.IGNORE_CASE)) }.forEach { l ->
            val h = l.takeWhile { it == '#' }.length
            val bullet = l.trimStart().let { it.startsWith("- ") || it.startsWith("* ") }
            val raw = when { h > 0 -> l.drop(h).trim(); bullet -> l.trimStart().drop(2); else -> l }
            val txt = buildAnnotatedString {
                Regex("""\*\*(.+?)\*\*|`(.+?)`""").let { re ->
                    var i = 0
                    re.findAll(raw).forEach { m ->
                        append(raw.substring(i, m.range.first))
                        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
                        else withStyle(SpanStyle(background = c.surfaceContainer)) { append(m.groupValues[2]) }
                        i = m.range.last + 1
                    }
                    append(raw.substring(i))
                }
            }
            if (h > 0) Text(txt, style = Ling.type.subhead, color = c.onSurface, modifier = Modifier.padding(top = 6.dp))
            else Row {
                if (bullet) Box(Modifier.padding(top = 9.dp, end = 10.dp, start = 4.dp).size(5.dp).clip(CircleShape).background(c.primary))
                Text(txt, style = Ling.type.body, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun UpdateSheet(r: Release, onDismiss: () -> Unit) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askPerm by remember { mutableStateOf<java.io.File?>(null) }
    fun installOrAsk(f: java.io.File) { if (Updater.canInstall(ctx)) { Updater.install(ctx, f); onDismiss() } else askPerm = f }
    LingSheet({ job?.cancel(); onDismiss() }, null, layout = SheetLayout.Viewport) {
        UpdateContent(r, progress, error,
            onDownload = {
                error = null; progress = 0f
                job = scope.launch {
                    runCatching { Updater.download(ctx, r) { progress = it } }
                        .onSuccess { f ->
                            when (val v = Updater.verify(ctx, f, r)) {
                                Updater.Verdict.Ok -> installOrAsk(f)
                                is Updater.Verdict.Blocked -> error = v.reason
                            }
                        }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = "下载失败：${it.message ?: "网络错误"}" }
                    progress = null; job = null
                }
            },
            onCancel = { job?.cancel(); job = null; progress = null },
            onLater = onDismiss,
            onSkip = { AppGraph.scope.launch { AppGraph.prefs.update { it.copy(skippedVersion = r.version) } }; onDismiss() })
    }
    askPerm?.let { f ->
        LingConfirm("允许灵写安装更新", "系统要求为「安装未知应用」单独授权，灵写只用它安装自己的更新包。授权后回到这里再点一次「下载并安装」。",
            confirm = "去授权", onDismiss = { askPerm = null }, onConfirm = {
                askPerm = null
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
    }
}

@Composable
fun UpdateContent(r: Release, progress: Float?, error: String?, onDownload: () -> Unit, onCancel: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val c = Ling.colors
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(LingIcons.Download, c.primaryContainer, c.onPrimaryContainer, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("新版本 v${r.version}", style = Ling.type.title, color = c.onSurface)
                Text("${r.date} · ${Updater.sizeText(r.size)} · 当前 v$currentVersion", style = Ling.type.caption, color = c.onSurfaceVariant)
            }
        }
        LingCard(Modifier.fillMaxWidth().heightIn(max = 300.dp), color = c.surfaceContainer) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) { MdText(r.body.ifBlank { "这个版本没有写更新说明。" }) }
        }
        if (error != null) Row(Modifier.fillMaxWidth().clip(Radius.sm).background(c.errorContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LingIcons.Lock, null, tint = c.error, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(error, style = Ling.type.label, color = c.error)
        }
        if (progress != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().height(6.dp).clip(Radius.pill), color = c.primary, trackColor = c.surfaceContainerHigh, drawStopIndicator = {}, gapSize = 0.dp)
                Text("正在下载 ${(progress * 100).toInt()}%", style = Ling.type.caption, color = c.onSurfaceVariant)
            }
            LingButton("取消下载", BtnKind.Outlined, modifier = Modifier.fillMaxWidth(), onClick = onCancel)
        } else {
            LingButton("下载并安装", icon = LingIcons.Download, modifier = Modifier.fillMaxWidth(), onClick = onDownload)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton("稍后", BtnKind.Tonal, modifier = Modifier.weight(1f), onClick = onLater)
                LingButton("跳过此版本", BtnKind.Text, modifier = Modifier.weight(1f), onClick = onSkip)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
