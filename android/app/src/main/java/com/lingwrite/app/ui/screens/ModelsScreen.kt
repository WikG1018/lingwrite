// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Space

data class ProviderUi(val id: Long, val letter: String, val color: Color, val name: String, val sub: String, val status: String, val enabled: Boolean, val isSwitch: Boolean = false)
data class ModelsUi(
    val calls: Int, val cost: String?, val tokens: String, val split: List<Float>, val writing: String, val auto: String,
    val providers: List<ProviderUi>,
)
val DemoModels get() = ModelsUi(1284, tr(R.string.models_052), tr(R.string.models_053), listOf(58f, 27f, 15f), "DeepSeek V3", "GLM-4.5 Air", listOf(
    ProviderUi(1, "D", Color(0xFF4D6BFE), "DeepSeek", tr(R.string.models_054), "ok", true),
    ProviderUi(2, tr(R.string.models_055), Color(0xFF2F5BEA), tr(R.string.models_056), tr(R.string.models_057), "terms", true),
    ProviderUi(3, "A", Color(0xFFC96442), tr(R.string.models_058), tr(R.string.models_059), "", true, isSwitch = true),
))

@Composable
fun ModelsScreen(
    ui: ModelsUi = DemoModels,
    onBack: () -> Unit = {}, onStats: () -> Unit = {}, onWriting: () -> Unit = {}, onAuto: () -> Unit = {},
    onProvider: (Long) -> Unit = {}, onToggle: (Long, Boolean) -> Unit = { _, _ -> }, onAdd: () -> Unit = {}, onHelp: () -> Unit = {},
) {
    val c = Ling.colors
    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = Space.dockClearance)) {
        LingTopBar(tr(R.string.settings_014), onBack = onBack) { LingIconButton(LingIcons.Help, tr(R.string.models_060), onClick = onHelp) }
        UsageCard(ui, Modifier.padding(horizontal = 16.dp).enterStagger(0).rowClick(onStats))
        SectionLabel(tr(R.string.models_061), Modifier.enterStagger(1))
        LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(1)) {
            Assign(LingIcons.EditNote, c.primaryContainer, c.onPrimaryContainer, tr(R.string.models_017), tr(R.string.models_062), ui.writing, onWriting)
            InsetDivider(70.dp)
            Assign(LingIcons.Bolt, c.tertiaryContainer, c.onTertiaryContainer, tr(R.string.models_016), tr(R.string.models_063), ui.auto, onAuto)
        }
        SectionLabel(tr(R.string.models_064), Modifier.enterStagger(2))
        LingCard(Modifier.padding(horizontal = 16.dp).enterStagger(2)) {
            ui.providers.forEachIndexed { i, p ->
                if (i > 0) InsetDivider(70.dp)
                Provider(p.letter, p.color, p.name, p.sub, { onProvider(p.id) }) {
                    when {
                        p.isSwitch || p.status == "" -> Switch(p.enabled, { onToggle(p.id, it) }, colors = SwitchDefaults.colors(
                            checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary,
                            uncheckedTrackColor = c.surfaceContainer, uncheckedBorderColor = c.outline, uncheckedThumbColor = c.outline,
                        ))
                        p.status == "ok" -> Pill(tr(R.string.models_065), PillKind.Good, LingIcons.CheckCircle)
                        p.status == "terms" -> Pill(tr(R.string.models_066), PillKind.Amber, LingIcons.Info)
                        p.status == "fail" -> Pill(tr(R.string.models_067), PillKind.Error)
                        p.status == "nokey" -> Pill(tr(R.string.models_068), PillKind.Neutral)
                        p.status == "off" -> Pill(tr(R.string.models_069), PillKind.Neutral)
                    }
                }
            }
            if (ui.providers.isNotEmpty()) InsetDivider(70.dp)
            Row(Modifier.fillMaxWidth().rowClick(onAdd).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).drawBehind {
                        drawRoundRect(c.outline, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(LingIcons.Add, null, tint = c.primary) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(tr(R.string.models_011), style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.primary)
                    Text(tr(R.string.models_070), style = Ling.type.caption, color = c.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Icon(LingIcons.Lock, null, Modifier.padding(top = 2.dp).size(16.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(tr(R.string.models_071), style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageCard(ui: ModelsUi, modifier: Modifier) {
    val c = Ling.colors
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(Motion.Long * 2, 120, Motion.EmphasizedDecelerate)) }
    LingCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.models_072), style = Ling.type.caption.copy(fontSize = 13.sp), color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(tr(R.string.models_073), style = Ling.type.caption.copy(fontSize = 13.sp), color = c.primary)
                Icon(LingIcons.Chevron, null, Modifier.size(18.dp), tint = c.primary)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(com.lingwrite.app.data.Words.grouped(ui.calls), style = Ling.type.headline.copy(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp), color = c.onSurface)
                Text(tr(R.string.models_074), style = Ling.type.caption.copy(fontSize = 13.sp), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp).weight(1f))
                if (ui.cost != null) Text(ui.cost, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(ui.tokens, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(c.primary, c.amber, c.outline).zip(ui.split).filter { it.second > 0f }.forEach { (col, w) ->
                    Box(Modifier.weight(w).fillMaxHeight().drawBehind { drawRect(col, size = size.copy(width = size.width * grow.value)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(c.primary, tr(R.string.models_075, ui.split[0].toInt())); Legend(c.amber, tr(R.string.models_076, ui.split[1].toInt())); Legend(c.outline, tr(R.string.lore_055))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, style = Ling.type.caption.copy(fontSize = 12.sp), color = Ling.colors.onSurfaceVariant)
    }
}

@Composable
private fun Assign(icon: ImageVector, bg: Color, fg: Color, title: String, sub: String, value: String, onClick: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile(icon, bg, fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface)
            Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        Text(value, style = Ling.type.label.copy(fontWeight = FontWeight.Normal), color = c.onSurfaceVariant, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
        Icon(LingIcons.Chevron, null, tint = c.onSurfaceVariant)
    }
}

@Composable
private fun Provider(letter: String, color: Color, name: String, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
            Text(letter, style = Ling.type.label.copy(fontSize = if (letter.length > 1) 13.sp else 15.sp, fontWeight = FontWeight.Bold), color = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface)
            Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        trailing()
    }
}
