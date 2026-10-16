// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Space

data class RelUi(val initial: String, val color: Color, val name: String, val desc: String, val changed: String? = null, val cardId: Long = 0)
data class FsUi(val title: String, val sub: String, val badge: String?, val overdue: Boolean)
data class LoreDetailUi(
    val typeLabel: String, val name: String, val initial: String, val avatar: Pair<Color, Color>, val pills: List<Pair<String, PillKind>>,
    val chapterLabel: String, val fields: List<com.lingwrite.app.data.ResolvedField>, val relations: List<RelUi>, val foreshadows: List<FsUi>,
    val aliases: String, val isCharacter: Boolean = true,
)
val DemoLore = LoreDetailUi("人物", "沈清禾", "沈", Color(0xFF5B79A8) to Color(0xFF2E4870),
    listOf("主要配角" to PillKind.Primary, "青云宗 · 内门" to PillKind.Neutral, "出场 64 章" to PillKind.Amber), "第 118 章时",
    listOf(com.lingwrite.app.data.ResolvedField("境界", "筑基后期", 96, "筑基中期"), com.lingwrite.app.data.ResolvedField("性格", "话少，观察细，护短；对宗门规矩有怀疑但不明说", null, null),
        com.lingwrite.app.data.ResolvedField("当前目标", "查清十年前师父失踪与断剑的关系", null, null)),
    listOf(RelUi("林", Color(0xFF3E5C8A), "林砚", "师弟 · 起疑 → 试探", "第 118 章变化"), RelUi("顾", Color(0xFFA8691A), "顾长庚", "师父 · 已失踪")),
    listOf(FsUi("左腕红绳的来历", "第 12 章埋下 · 计划第 110 章前回收", "超期 8 章", true)), "")

@Composable
fun LoreScreen(
    ui: LoreDetailUi = DemoLore, onBack: () -> Unit = {}, onHistory: () -> Unit = {}, onEdit: () -> Unit = {}, onMore: () -> Unit = {},
    onChapter: () -> Unit = {}, onField: (String) -> Unit = {}, onRelation: (RelUi) -> Unit = {}, onAddRelation: () -> Unit = {}, onAddChange: () -> Unit = {},
) {
    val c = Ling.colors
    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = Space.dockClearance)) {
        LingTopBar(ui.typeLabel, onBack = onBack, titleStyle = Ling.type.titleSmall) {
            LingIconButton(LingIcons.History, "状态变化", onClick = onHistory); LingIconButton(LingIcons.EditOutline, "编辑", onClick = onEdit); LingIconButton(LingIcons.More, "更多", onClick = onMore)
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp).enterStagger(0), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(ui.avatar.first, ui.avatar.second))), contentAlignment = Alignment.Center) {
                Text(ui.initial, style = Ling.type.headline.copy(fontSize = 26.sp), color = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(ui.name, style = Ling.type.headline.copy(fontSize = 23.sp), color = c.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { ui.pills.take(3).forEach { (t, k) -> Pill(t, k) } }
                if (ui.aliases.isNotBlank()) Text("又名 " + ui.aliases, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(1)) {
            Row(Modifier.fillMaxWidth().rowClick(onChapter).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Schedule, null, Modifier.size(20.dp), tint = c.primary)
                Spacer(Modifier.width(8.dp))
                Text("按章查看：", style = Ling.type.label.copy(fontWeight = FontWeight.Normal), color = c.onSurface)
                Text(ui.chapterLabel, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.weight(1f))
                Text("切换", style = Ling.type.label.copy(fontWeight = FontWeight.Normal), color = c.primary)
                Icon(LingIcons.Chevron, null, Modifier.size(18.dp), tint = c.primary)
            }
        }
        SectionLabel("基本设定", Modifier.enterStagger(2))
        LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(2)) {
            if (ui.fields.isEmpty()) Text("还没有填写设定。点右上角编辑。", style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            ui.fields.forEachIndexed { i, f ->
                if (i > 0) InsetDivider(16.dp, 16.dp)
                Field(f.k, f.v, change = f.changedAt?.let { at -> "第 $at 章起" + (f.previous?.let { "（原：$it）" } ?: "") }) { onField(f.k) }
            }
        }
        Row(Modifier.padding(start = 16.dp, top = 4.dp)) { LingButton("记录一次状态变化", BtnKind.Text, icon = LingIcons.TrendUp, onClick = onAddChange) }
        if (ui.isCharacter) SectionLabel("人物关系", Modifier.enterStagger(3))
        if (ui.isCharacter) LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(3)) {
            ui.relations.forEachIndexed { i, r ->
                if (i > 0) InsetDivider(64.dp)
                Relation(r.initial, r.color, r.name, r.desc, { onRelation(r) }) { if (r.changed != null) Pill(r.changed, PillKind.Neutral) else Icon(LingIcons.Chevron, null, tint = c.onSurfaceVariant) }
            }
            if (ui.relations.isNotEmpty()) InsetDivider(64.dp)
            Row(Modifier.fillMaxWidth().rowClick(onAddRelation).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Add, null, tint = c.primary); Spacer(Modifier.width(12.dp)); Text("添加关系", style = Ling.type.label, color = c.primary)
            }
        }
        if (ui.foreshadows.isNotEmpty()) SectionLabel("相关伏笔", Modifier.enterStagger(4))
        ui.foreshadows.forEach { fs -> LingCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).enterStagger(4)) {
            Row(Modifier.fillMaxWidth().rowClick {}.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Tile(LingIcons.Key, if (fs.overdue) c.errorContainer else c.tertiaryContainer, if (fs.overdue) c.onErrorContainer else c.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fs.title, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface)
                    Text(fs.sub, style = Ling.type.caption, color = c.onSurfaceVariant)
                }
                fs.badge?.let { Pill(it, if (fs.overdue) PillKind.Error else PillKind.Neutral) }
            }
        } }
    }
}

@Composable
private fun Field(label: String, value: String, change: String? = null, onClick: () -> Unit = {}) {
    val c = Ling.colors
    Column(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = Ling.type.caption, color = c.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        FlowRowCompat {
            Text(value, style = Ling.type.bodyLarge, color = c.onSurface)
            if (change != null) {
                Spacer(Modifier.width(6.dp))
                Icon(LingIcons.TrendUp, null, Modifier.size(16.dp), tint = c.tertiary)
                Spacer(Modifier.width(3.dp))
                Text(change, style = Ling.type.caption.copy(fontSize = 12.sp), color = c.tertiary)
            }
        }
    }
}

@Composable
private fun Relation(initial: String, color: Color, name: String, desc: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
            Text(initial, style = Ling.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface)
            Text(desc, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        trailing()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(verticalArrangement = Arrangement.Center) { content() }
}
