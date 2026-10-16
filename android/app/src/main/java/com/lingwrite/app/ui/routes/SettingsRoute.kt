// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.AppPrefs
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingSheet
import com.lingwrite.app.ui.components.SheetAction
import com.lingwrite.app.ui.screens.*
import kotlinx.coroutines.launch

/** 设置首页条目由各模块追加（分区标题 → 条目）。 */
object SettingsRegistry {
    val extra = mutableListOf<@Composable (AppPrefs, NavHostController) -> Pair<String, SettingRow>?>()
}

@Composable
fun SettingsRoute(nav: NavHostController) {
    val p by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val prefs = p ?: return
    val scope = rememberCoroutineScope()
    var modeSheet by remember { mutableStateOf(false) }
    var lenSheet by remember { mutableStateOf(false) }
    var themeSheet by remember { mutableStateOf(false) }
    var motionSheet by remember { mutableStateOf(false) }
    var candSheet by remember { mutableStateOf(false) }
    var langSheet by remember { mutableStateOf(false) }
    var aiLangSheet by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val uiTag = remember(langSheet) { activity?.let { com.lingwrite.app.i18n.AppLocale.current(it) }.orEmpty() }
    fun upd(f: (AppPrefs) -> AppPrefs) = scope.launch { AppGraph.prefs.update(f) }
    val extras = SettingsRegistry.extra.mapNotNull { it(prefs, nav) }
    val base = listOf(
        tr(R.string.author_guide_017) to SettingRow(if (prefs.writeMode == "generate") LingIcons.SparkleFilled else LingIcons.Draw, tr(R.string.settings_001), tr(R.string.settings_002),
            if (prefs.writeMode == "generate") tr(R.string.settings_003) else tr(R.string.settings_004), onClick = { modeSheet = true }),
        tr(R.string.author_guide_017) to SettingRow(LingIcons.EditNote, tr(R.string.settings_005), value = when (prefs.continueLength) { "sentence" -> tr(R.string.settings_006); "long" -> tr(R.string.settings_007); else -> tr(R.string.settings_008) }, tone = Tone.Neutral, onClick = { lenSheet = true }),
        tr(R.string.author_guide_017) to SettingRow(LingIcons.SparkleFilled, tr(R.string.settings_009), tr(R.string.settings_010), value = tr(R.string.settings_011, prefs.candidates), tone = Tone.Neutral, onClick = { candSheet = true }),
        tr(R.string.author_guide_017) to SettingRow(LingIcons.Help, tr(R.string.settings_012), tr(R.string.settings_013), switch = prefs.stuckHint, tone = Tone.Neutral,
            onSwitch = { v -> upd { it.copy(stuckHint = v) } }),
        "AI" to SettingRow(LingIcons.Bolt, tr(R.string.settings_014), tr(R.string.settings_015), tone = Tone.Amber, onClick = { nav.navigate("models") }),
        tr(R.string.settings_016) to SettingRow(LingIcons.Idea, tr(R.string.settings_017), value = when (prefs.theme) { "light" -> tr(R.string.settings_018); "dark" -> tr(R.string.settings_019); else -> tr(R.string.settings_020) }, tone = Tone.Neutral, onClick = { themeSheet = true }),
        tr(R.string.settings_016) to SettingRow(LingIcons.VisibilityOff, tr(R.string.settings_021), tr(R.string.settings_022), value = when (prefs.reduceMotion) { "on" -> tr(R.string.settings_023); "off" -> tr(R.string.settings_024); else -> tr(R.string.settings_020) }, tone = Tone.Neutral, onClick = { motionSheet = true }),
        tr(R.string.settings_016) to SettingRow(LingIcons.Graph, tr(R.string.i18n_002), value = if (uiTag.isBlank()) tr(R.string.settings_020) else langName(uiTag), tone = Tone.Neutral, onClick = { langSheet = true }),
        "AI" to SettingRow(LingIcons.Sparkle, tr(R.string.i18n_004), tr(R.string.i18n_005), value = if (prefs.aiOutputLang == "auto") tr(R.string.i18n_006) else langName(prefs.aiOutputLang), tone = Tone.Neutral, onClick = { aiLangSheet = true }),
    )
    val order = listOf(tr(R.string.author_guide_017), "AI", tr(R.string.data_001), tr(R.string.settings_016), tr(R.string.about_001))
    val all = (base + extras).groupBy({ it.first }, { it.second })
    SettingsListScreen(tr(R.string.dock_003), order.mapNotNull { k -> all[k]?.let { SettingSection(k, it) } }, footer = tr(R.string.settings_025))

    if (modeSheet) LingSheet({ modeSheet = false }, tr(R.string.settings_001)) {
        SheetAction(LingIcons.Draw, tr(R.string.settings_004), sub = tr(R.string.settings_026)) { upd { it.copy(writeMode = "assist") }; modeSheet = false }
        SheetAction(LingIcons.SparkleFilled, tr(R.string.settings_003), sub = tr(R.string.settings_027)) { upd { it.copy(writeMode = "generate") }; modeSheet = false }
    }
    if (lenSheet) LingSheet({ lenSheet = false }, tr(R.string.settings_005)) {
        listOf("sentence" to tr(R.string.settings_006), "paragraph" to tr(R.string.settings_028), "long" to tr(R.string.settings_029)).forEach { (k, v) ->
            SheetAction(if (prefs.continueLength == k) LingIcons.Check else LingIcons.EditNote, v) { upd { it.copy(continueLength = k) }; lenSheet = false }
        }
    }
    if (themeSheet) LingSheet({ themeSheet = false }, tr(R.string.settings_017)) {
        listOf("system" to tr(R.string.settings_020), "light" to tr(R.string.settings_018), "dark" to tr(R.string.settings_019)).forEach { (k, v) ->
            SheetAction(if (prefs.theme == k) LingIcons.Check else LingIcons.Idea, v) { upd { it.copy(theme = k) }; themeSheet = false }
        }
    }
    if (candSheet) LingSheet({ candSheet = false }, tr(R.string.settings_009)) {
        listOf(1 to tr(R.string.settings_030), 2 to tr(R.string.settings_031), 3 to tr(R.string.settings_032)).forEach { (n, v) ->
            SheetAction(if (prefs.candidates == n) LingIcons.Check else LingIcons.Sparkle, v) { upd { it.copy(candidates = n) }; candSheet = false }
        }
        Text(tr(R.string.settings_033), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    if (motionSheet) LingSheet({ motionSheet = false }, tr(R.string.settings_021)) {
        listOf("system" to tr(R.string.settings_020), "on" to tr(R.string.settings_023), "off" to tr(R.string.settings_024)).forEach { (k, v) ->
            SheetAction(if (prefs.reduceMotion == k) LingIcons.Check else LingIcons.VisibilityOff, v) { upd { it.copy(reduceMotion = k) }; motionSheet = false }
        }
    }
    if (langSheet) LingSheet({ langSheet = false }, tr(R.string.i18n_002)) {
        (listOf("") + com.lingwrite.app.i18n.AppLocale.supported).forEach { tag ->
            SheetAction(if (uiTag == tag) LingIcons.Check else LingIcons.Graph, if (tag.isBlank()) tr(R.string.settings_020) else langName(tag)) {
                langSheet = false; activity?.let { com.lingwrite.app.i18n.AppLocale.set(it, tag) }
            }
        }
    }
    if (aiLangSheet) LingSheet({ aiLangSheet = false }, tr(R.string.i18n_004)) {
        com.lingwrite.app.data.ai.OutputLang.choices.forEach { k ->
            SheetAction(if (prefs.aiOutputLang == k) LingIcons.Check else LingIcons.Sparkle, if (k == "auto") tr(R.string.i18n_006) else langName(k)) { upd { it.copy(aiOutputLang = k) }; aiLangSheet = false }
        }
        Text(tr(R.string.i18n_007), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** 语言名用各自的本名显示（不随界面语言翻译），方便看不懂当前语言的人找回来。 */
private fun langName(tag: String): String = when (tag) {
    "zh-CN" -> "简体中文"; "zh-TW" -> "繁體中文"; "en" -> "English"; "ja" -> "日本語"; else -> tag
}
