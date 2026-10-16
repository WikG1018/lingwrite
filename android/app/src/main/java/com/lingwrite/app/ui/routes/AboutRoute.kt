// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

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
            tr(R.string.about_001) to SettingRow(if (p.notifyAll) LingIcons.Notify else LingIcons.NotifyOff, tr(R.string.about_002), tr(R.string.about_003), if (p.notifyAll) tr(R.string.about_004) else tr(R.string.about_005), tone = Tone.Amber, onClick = { nav.navigate(Notifications) })
        }
        SettingsRegistry.extra += { _, nav ->
            tr(R.string.about_001) to SettingRow(LingIcons.Info, tr(R.string.about_006), tr(R.string.about_007, currentVersion), tone = Tone.Neutral, onClick = { nav.navigate(Routes.About) })
        }
        UpdateHooks.onFound = { r -> AppGraph.scope.launch { Notifier.post(appCtx(), NotifyType.Update, tr(R.string.about_008, r.version), tr(R.string.about_009), UpdateModule.Route) } }
        TaskHooks.done = { chapterId, issues ->
            val c = AppGraph.db.chapters().get(chapterId)
            if (c != null) Notifier.post(appCtx(), NotifyType.Tasks, tr(R.string.about_010, ChapterLabel.short(c.number)),
                if (issues > 0) tr(R.string.about_011, issues) else tr(R.string.about_012), "issues/${c.bookId}")
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

fun deviceInfo() = tr(R.string.about_013, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

val licenses get() = listOf(
    tr(R.string.about_014) to tr(R.string.about_015),
    tr(R.string.about_016) to tr(R.string.about_017),
    "Jetpack Compose · AndroidX" to "Apache License 2.0",
    "Kotlin · kotlinx.coroutines · kotlinx.serialization" to "Apache License 2.0",
    "OkHttp · Okio" to "Apache License 2.0",
    "Room · DataStore · Navigation" to "Apache License 2.0",
    "Haze" to "Apache License 2.0",
    "Material Symbols" to "Apache License 2.0",
)

val privacy get() = listOf(
    Triple(LingIcons.Lock, tr(R.string.about_018), tr(R.string.about_019)),
    Triple(LingIcons.Key, tr(R.string.about_020), tr(R.string.about_021)),
    Triple(LingIcons.Bolt, tr(R.string.about_022), tr(R.string.about_023)),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutRoute(nav: NavHostController) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val a by Updater.available.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    SettingsListScreen(tr(R.string.about_024), listOf(
        SettingSection("", listOf(
            SettingRow(LingIcons.Download, tr(R.string.about_025), if (a != null) tr(R.string.about_026, a!!.version) else null, tone = if (a != null) Tone.Error else Tone.Primary, onClick = { nav.navigate(UpdateModule.Route) }),
            SettingRow(LingIcons.History, tr(R.string.about_027), tr(R.string.about_028), tone = Tone.Neutral, onClick = { open(ctx, "${AboutModule.Home}/releases") }),
        )),
        SettingSection("", listOf(
            SettingRow(LingIcons.Article, tr(R.string.about_029), tr(R.string.about_030), tone = Tone.Neutral, onClick = { sheet = "license" }),
            SettingRow(LingIcons.Shield, tr(R.string.about_031), tr(R.string.about_032), tone = Tone.Good, onClick = { sheet = "privacy" }),
        )),
        SettingSection("", listOf(
            SettingRow(LingIcons.Code, tr(R.string.about_033), Updater.REPO, tone = Tone.Neutral, onClick = { open(ctx, AboutModule.Home) }),
            SettingRow(LingIcons.Feedback, tr(R.string.about_034), tr(R.string.about_035), tone = Tone.Amber, onClick = {
                open(ctx, "${AboutModule.Home}/issues/new?body=" + Uri.encode("\n\n---\n" + deviceInfo()))
            }),
        )),
    ), onBack = { nav.popBackStack() }, header = {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).combinedClickable(remember { MutableInteractionSource() }, null, onClick = {}, onLongClick = {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr(R.string.about_036), deviceInfo())); copied = true
        }), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2E4870)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(120.dp))
                }
                if (a != null) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(14.dp).clip(CircleShape).background(c.background).padding(2.dp).clip(CircleShape).background(c.error))
            }
            Spacer(Modifier.height(12.dp))
            Text(tr(R.string.about_037), style = Ling.type.title, color = c.onSurface)
            Text(if (copied) tr(R.string.about_038) else tr(R.string.about_039, BuildConfig.VERSION_NAME), style = Ling.type.caption, color = if (copied) c.primary else c.onSurfaceVariant)
        }
    }, footer = tr(R.string.about_040))
    when (sheet) {
        "license" -> LingSheet({ sheet = null }, tr(R.string.about_029), layout = SheetLayout.Viewport) { LicenseList() }
        "privacy" -> LingSheet({ sheet = null }, tr(R.string.about_031)) { PrivacyCards() }
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
    val prefs = p ?: run { SettingsListScreen(tr(R.string.about_002), emptyList(), onBack = { nav.popBackStack() }, footer = tr(R.string.about_041)); return }
    var granted by remember { mutableStateOf(Notifier.permissionGranted(ctx)) }
    LifecycleResumeEffect(Unit) { granted = Notifier.permissionGranted(ctx); onPauseOrDispose {} }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; scope.launch { AppGraph.prefs.update { it.copy(notifyAsked = true) } }
    }
    fun upd(f: (AppPrefs) -> AppPrefs) = scope.launch { AppGraph.prefs.update(f) }
    val on = prefs.notifyAll
    SettingsListScreen(tr(R.string.about_002), listOf(
        SettingSection("", listOf(SettingRow(if (on) LingIcons.Notify else LingIcons.NotifyOff, tr(R.string.about_042), tr(R.string.about_043), switch = on, tone = Tone.Amber, onSwitch = { v -> upd { it.copy(notifyAll = v) } }))),
    ) + if (on) listOf(SettingSection(tr(R.string.about_044), listOf(
        SettingRow(LingIcons.CheckCircle, NotifyType.Tasks.title, NotifyType.Tasks.desc, switch = prefs.notifyTasks, tone = Tone.Good, onSwitch = { v -> upd { it.copy(notifyTasks = v) } }),
        SettingRow(LingIcons.Schedule, NotifyType.Foreshadow.title, tr(R.string.about_045), switch = prefs.notifyForeshadow, tone = Tone.Error, onSwitch = { v -> upd { it.copy(notifyForeshadow = v) } }),
        SettingRow(LingIcons.Download, NotifyType.Update.title, NotifyType.Update.desc, switch = prefs.notifyUpdate, tone = Tone.Primary, onSwitch = { v -> upd { it.copy(notifyUpdate = v) } }),
    ))) else emptyList(), onBack = { nav.popBackStack() }, header = {
        if (on && !granted) PermissionCard(denied = prefs.notifyAsked,
            onAllow = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onSettings = { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
    }, footer = tr(R.string.about_046))
}

/** Android 13+：申请前先解释；被拒后改为横幅引导去系统设置。 */
@Composable
fun PermissionCard(denied: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    val c = Ling.colors
    if (!denied) LingCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), color = c.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Notify, null, tint = c.onPrimaryContainer); Spacer(Modifier.width(10.dp))
                Text(tr(R.string.about_047), style = Ling.type.subhead, color = c.onPrimaryContainer)
            }
            Text(tr(R.string.about_048), style = Ling.type.body, color = c.onPrimaryContainer)
            LingButton(tr(R.string.about_049), modifier = Modifier.fillMaxWidth(), onClick = onAllow)
        }
    } else Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(Radius.card).background(c.errorContainer).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(LingIcons.NotifyOff, null, tint = c.error, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Text(tr(R.string.about_050), style = Ling.type.label, color = c.onErrorContainer, modifier = Modifier.weight(1f))
        LingButton(tr(R.string.about_051), BtnKind.Text, height = 36.dp, onClick = onSettings)
    }
}
