// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

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
    fun upd(f: (AppPrefs) -> AppPrefs) = scope.launch { AppGraph.prefs.update(f) }
    val extras = SettingsRegistry.extra.mapNotNull { it(prefs, nav) }
    val base = listOf(
        "写作" to SettingRow(if (prefs.writeMode == "generate") LingIcons.SparkleFilled else LingIcons.Draw, "写作模式", "辅助：AI 只给建议；生成：AI 可起草正文",
            if (prefs.writeMode == "generate") "生成模式" else "辅助模式", onClick = { modeSheet = true }),
        "写作" to SettingRow(LingIcons.EditNote, "续写长度", value = when (prefs.continueLength) { "sentence" -> "一句"; "long" -> "多段"; else -> "一段" }, tone = Tone.Neutral, onClick = { lenSheet = true }),
        "写作" to SettingRow(LingIcons.SparkleFilled, "候选数量", "生成模式一次给几个候选（1–3，越多越费 tokens）", value = "${prefs.candidates} 个", tone = Tone.Neutral, onClick = { candSheet = true }),
        "写作" to SettingRow(LingIcons.Help, "卡住时轻提示", "光标停留 3 分钟没有新内容时，提示一次卡文急救", switch = prefs.stuckHint, tone = Tone.Neutral,
            onSwitch = { v -> upd { it.copy(stuckHint = v) } }),
        "AI" to SettingRow(LingIcons.Bolt, "模型设置", "服务商、Key、写作与自动任务模型", tone = Tone.Amber, onClick = { nav.navigate("models") }),
        "外观" to SettingRow(LingIcons.Idea, "主题", value = when (prefs.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, tone = Tone.Neutral, onClick = { themeSheet = true }),
        "外观" to SettingRow(LingIcons.VisibilityOff, "减弱动画", "转场和列表只做淡入淡出，按压不缩放", value = when (prefs.reduceMotion) { "on" -> "开"; "off" -> "关"; else -> "跟随系统" }, tone = Tone.Neutral, onClick = { motionSheet = true }),
    )
    val order = listOf("写作", "AI", "数据", "外观", "应用")
    val all = (base + extras).groupBy({ it.first }, { it.second })
    SettingsListScreen("设置", order.mapNotNull { k -> all[k]?.let { SettingSection(k, it) } }, footer = "灵写 · 数据只存在这台手机上")

    if (modeSheet) LingSheet({ modeSheet = false }, "写作模式") {
        SheetAction(LingIcons.Draw, "辅助模式", sub = "你来写，AI 查设定、提建议、改句子；建议默认不进正文") { upd { it.copy(writeMode = "assist") }; modeSheet = false }
        SheetAction(LingIcons.SparkleFilled, "生成模式", sub = "你定章纲，AI 起草整章或续写，你来改") { upd { it.copy(writeMode = "generate") }; modeSheet = false }
    }
    if (lenSheet) LingSheet({ lenSheet = false }, "续写长度") {
        listOf("sentence" to "一句", "paragraph" to "一段（默认）", "long" to "多段（约 500 字）").forEach { (k, v) ->
            SheetAction(if (prefs.continueLength == k) LingIcons.Check else LingIcons.EditNote, v) { upd { it.copy(continueLength = k) }; lenSheet = false }
        }
    }
    if (themeSheet) LingSheet({ themeSheet = false }, "主题") {
        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, v) ->
            SheetAction(if (prefs.theme == k) LingIcons.Check else LingIcons.Idea, v) { upd { it.copy(theme = k) }; themeSheet = false }
        }
    }
    if (candSheet) LingSheet({ candSheet = false }, "候选数量") {
        listOf(1 to "1 个（最省）", 2 to "2 个", 3 to "3 个（默认）").forEach { (n, v) ->
            SheetAction(if (prefs.candidates == n) LingIcons.Check else LingIcons.Sparkle, v) { upd { it.copy(candidates = n) }; candSheet = false }
        }
        Text("多候选会按次数消耗 tokens；采纳前可以左右切换和微调。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    if (motionSheet) LingSheet({ motionSheet = false }, "减弱动画") {
        listOf("system" to "跟随系统", "on" to "开", "off" to "关").forEach { (k, v) ->
            SheetAction(if (prefs.reduceMotion == k) LingIcons.Check else LingIcons.VisibilityOff, v) { upd { it.copy(reduceMotion = k) }; motionSheet = false }
        }
    }
}
