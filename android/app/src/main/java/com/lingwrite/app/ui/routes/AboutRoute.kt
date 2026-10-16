// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.AppGraph
import com.lingwrite.app.BuildConfig
import com.lingwrite.app.R
import com.lingwrite.app.data.*
import com.lingwrite.app.ui.AppOverlays
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.SettingSection
import com.lingwrite.app.ui.screens.SettingsListScreen
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Radius
import kotlinx.coroutines.launch

object AboutModule {
    const val Notifications = "notifications"
    const val Home = "https://github.com/${Updater.REPO}"

    fun install() {
        ExtraDestinations.list.add { nav: NavHostController ->
            composable(Routes.About) { AboutRoute(nav) }
            composable(Notifications) { NotificationsRoute(nav) }
        }
        SettingsRegistry.extra += { p, nav ->
            "应用" to SettingRow(if (p.notifyAll) LingIcons.Notify else LingIcons.NotifyOff, "通知", "后台任务、伏笔提醒、新版本", if (p.notifyAll) "已开启" else "已关闭", tone = Tone.Amber, onClick = { nav.navigate(Notifications) })
        }
        SettingsRegistry.extra += { _, nav ->
            "应用" to SettingRow(LingIcons.Info, "关于灵写", "版本 v$currentVersion", tone = Tone.Neutral, onClick = { nav.navigate(Routes.About) })
        }
        UpdateHooks.onFound = { r -> AppGraph.scope.launch { Notifier.post(appCtx(), NotifyType.Update, "灵写 v${r.version} 可以更新了", "打开查看更新说明，下载后会先校验签名再安装。", UpdateModule.Route) } }
        TaskHooks.done = { chapterId, issues ->
            val c = AppGraph.db.chapters().get(chapterId)
            if (c != null) Notifier.post(appCtx(), NotifyType.Tasks, "${ChapterLabel.short(c.number)}检查完成",
                if (issues > 0) "发现 $issues 处可能与设定不一致，点开查看。" else "摘要已更新，没有发现与设定冲突的地方。", "issues/${c.bookId}")
        }
        AppOverlays.list += { nav ->
            val ctx = LocalContext.current
            val route by Notifier.pending.collectAsState()
            LaunchedEffect(route) { route?.let { Notifier.pending.value = null; runCatching { nav.navigate(it) } } }
            LaunchedEffect(Unit) { Notifier.channels(ctx); runCatching { Notifier.foreshadowDigest(ctx) } }
        }
    }

    private lateinit var ctxRef: android.content.Context
    private fun appCtx() = ctxRef
    fun bind(ctx: android.content.Context) { ctxRef = ctx.applicationContext }
}

private fun open(ctx: android.content.Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

fun deviceInfo() = "灵写 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

val licenses = listOf(
    "灵写 LingWrite" to "GNU AGPL-3.0 · 源代码 github.com/WikG1018/lingwrite",
    "界面字体：MiSans" to "© 小米科技有限责任公司，依《MiSans 字体知识产权许可协议》使用，未修改；字体不适用 AGPL · hyperos.mi.com/font",
    "Jetpack Compose · AndroidX" to "Apache License 2.0",
    "Kotlin · kotlinx.coroutines · kotlinx.serialization" to "Apache License 2.0",
    "OkHttp · Okio" to "Apache License 2.0",
    "Room · DataStore · Navigation" to "Apache License 2.0",
    "Haze" to "Apache License 2.0",
    "Material Symbols" to "Apache License 2.0",
)

val privacy = listOf(
    Triple(LingIcons.Lock, "数据只在这台手机上", "作品、设定、大纲和备份都存在本机，灵写没有服务器，也不收集统计数据。"),
    Triple(LingIcons.Key, "Key 加密保存", "API Key 用系统密钥库加密，不会写进备份文件，也不会离开这台设备。"),
    Triple(LingIcons.Bolt, "只发给你选的模型", "调用 AI 时，相关正文和设定只发给你配置的服务商；检查更新只访问 GitHub 或你选的镜像。"),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutRoute(nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val a by Updater.available.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    SettingsListScreen("关于", listOf(
        SettingSection("", listOf(
            SettingRow(LingIcons.Download, "检查更新", if (a != null) "有新版本 v${a!!.version}" else null, tone = if (a != null) Tone.Error else Tone.Primary, onClick = { nav.navigate(UpdateModule.Route) }),
            SettingRow(LingIcons.History, "更新日志", "每个版本改了什么", tone = Tone.Neutral, onClick = { open(ctx, "${AboutModule.Home}/releases") }),
        )),
        SettingSection("", listOf(
            SettingRow(LingIcons.Article, "开源许可", "界面字体：MiSans，© 小米", tone = Tone.Neutral, onClick = { sheet = "license" }),
            SettingRow(LingIcons.Shield, "隐私说明", "数据存在哪里、发给谁", tone = Tone.Good, onClick = { sheet = "privacy" }),
        )),
        SettingSection("", listOf(
            SettingRow(LingIcons.Code, "GitHub 主页", Updater.REPO, tone = Tone.Neutral, onClick = { open(ctx, AboutModule.Home) }),
            SettingRow(LingIcons.Feedback, "问题反馈", "提交 Issue，会附上版本与设备信息", tone = Tone.Amber, onClick = {
                open(ctx, "${AboutModule.Home}/issues/new?body=" + Uri.encode("\n\n---\n" + deviceInfo()))
            }),
        )),
    ), onBack = { nav.popBackStack() }, header = {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).combinedClickable(remember { MutableInteractionSource() }, null, onClick = {}, onLongClick = {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("灵写版本", deviceInfo())); copied = true
        }), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2E4870)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(120.dp))
                }
                if (a != null) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(14.dp).clip(CircleShape).background(c.background).padding(2.dp).clip(CircleShape).background(c.error))
            }
            Spacer(Modifier.height(12.dp))
            Text("灵写", style = Ling.type.title, color = c.onSurface)
            Text(if (copied) "已复制版本与设备信息" else "v${BuildConfig.VERSION_NAME} · 长按复制版本信息", style = Ling.type.caption, color = if (copied) c.primary else c.onSurfaceVariant)
        }
    }, footer = "灵写 · 给写长篇的人")
    when (sheet) {
        "license" -> LingSheet({ sheet = null }, "开源许可", layout = SheetLayout.Viewport) { LicenseList() }
        "privacy" -> LingSheet({ sheet = null }, "隐私说明") { PrivacyCards() }
    }
}

@Composable
fun LicenseList() {
    val c = Ling.colors
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        licenses.forEachIndexed { i, (n, l) ->
            if (i > 0) InsetDivider(0.dp)
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(n, style = Ling.type.bodyLarge, color = c.onSurface)
                Text(l, style = Ling.type.caption, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PrivacyCards() {
    val c = Ling.colors
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        privacy.forEach { (ic, t, d) ->
            LingCard(Modifier.fillMaxWidth(), color = c.surfaceContainer) {
                Row(Modifier.padding(14.dp)) {
                    Tile(ic, c.goodContainer, c.good)
                    Spacer(Modifier.width(14.dp))
                    Column { Text(t, style = Ling.type.subhead, color = c.onSurface); Spacer(Modifier.height(2.dp)); Text(d, style = Ling.type.body, color = c.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun NotificationsRoute(nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val prefs = p ?: run { SettingsListScreen("通知", emptyList(), onBack = { nav.popBackStack() }, footer = "正在加载…"); return }
    var granted by remember { mutableStateOf(Notifier.permissionGranted(ctx)) }
    LifecycleResumeEffect(Unit) { granted = Notifier.permissionGranted(ctx); onPauseOrDispose {} }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; scope.launch { AppGraph.prefs.update { it.copy(notifyAsked = true) } }
    }
    fun upd(f: (AppPrefs) -> AppPrefs) = scope.launch { AppGraph.prefs.update(f) }
    val on = prefs.notifyAll
    SettingsListScreen("通知", listOf(
        SettingSection("", listOf(SettingRow(if (on) LingIcons.Notify else LingIcons.NotifyOff, "允许通知", "关闭后灵写不会发出任何通知", switch = on, tone = Tone.Amber, onSwitch = { v -> upd { it.copy(notifyAll = v) } }))),
    ) + if (on) listOf(SettingSection("分类", listOf(
        SettingRow(LingIcons.CheckCircle, NotifyType.Tasks.title, NotifyType.Tasks.desc, switch = prefs.notifyTasks, tone = Tone.Good, onSwitch = { v -> upd { it.copy(notifyTasks = v) } }),
        SettingRow(LingIcons.Schedule, NotifyType.Foreshadow.title, "每天最多汇总提醒一次", switch = prefs.notifyForeshadow, tone = Tone.Error, onSwitch = { v -> upd { it.copy(notifyForeshadow = v) } }),
        SettingRow(LingIcons.Download, NotifyType.Update.title, NotifyType.Update.desc, switch = prefs.notifyUpdate, tone = Tone.Primary, onSwitch = { v -> upd { it.copy(notifyUpdate = v) } }),
    ))) else emptyList(), onBack = { nav.popBackStack() }, header = {
        if (on && !granted) PermissionCard(denied = prefs.notifyAsked,
            onAllow = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onSettings = { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
    }, footer = "点通知会直接打开对应页面：检查结果、作品伏笔或更新说明。")
}

/** Android 13+：申请前先解释；被拒后改为横幅引导去系统设置。 */
@Composable
fun PermissionCard(denied: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    val c = Ling.colors
    if (!denied) LingCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), color = c.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Notify, null, tint = c.onPrimaryContainer); Spacer(Modifier.width(10.dp))
                Text("需要通知权限", style = Ling.type.subhead, color = c.onPrimaryContainer)
            }
            Text("灵写只在三种情况下提醒你：后台检查跑完了、伏笔快到或已过回收章节、有新版本。不会推送营销消息，每类都可以单独关掉。", style = Ling.type.body, color = c.onPrimaryContainer)
            LingButton("开启通知", modifier = Modifier.fillMaxWidth(), onClick = onAllow)
        }
    } else Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(Radius.card).background(c.errorContainer).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(LingIcons.NotifyOff, null, tint = c.error, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Text("系统已关闭灵写的通知，下面的开关暂时不起作用", style = Ling.type.label, color = c.onErrorContainer, modifier = Modifier.weight(1f))
        LingButton("去设置", BtnKind.Text, height = 36.dp, onClick = onSettings)
    }
}
