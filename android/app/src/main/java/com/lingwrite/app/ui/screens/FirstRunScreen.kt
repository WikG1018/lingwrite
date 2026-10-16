// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.ModeCopy
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion

/** 首次使用：两种模式平等，默认都不选；选中前“继续”不可用。 */
@Composable
fun FirstRunScreen(onContinue: (WriteMode) -> Unit = {}) {
    val c = Ling.colors
    var mode by remember { mutableStateOf<WriteMode?>(null) }
    Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
            Row(Modifier.padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(24.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(c.primary))
                repeat(2) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c.outlineVariant)) }
            }
            Text("你想让 AI\n怎么帮你写？", style = Ling.type.display, color = c.onSurface, modifier = Modifier.enterStagger(0))
            Text("两种方式都能用好灵写，之后可在设置里随时切换。", style = Ling.type.bodyLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).enterStagger(1))
            ModeOption(WriteMode.Assist, mode, LingIcons.Draw, c.primaryContainer, c.onPrimaryContainer, "辅助模式", ModeCopy.ASSIST_DESC + "。",
                listOf("正文每个字出自你手", "产出是方向和问题，进本章便签"), Modifier.enterStagger(2)) { mode = it }
            Spacer(Modifier.height(14.dp))
            ModeOption(WriteMode.Generate, mode, LingIcons.Sparkle, c.tertiaryContainer, c.onTertiaryContainer, "生成模式", ModeCopy.GENERATE_DESC + "。",
                listOf("按章纲起草，自动带入设定", "多候选挑选，段落带 AI 标记"), Modifier.enterStagger(3)) { mode = it }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 22.dp)) {
            LingButton(
                text = when (mode) { null -> "选择一种方式继续"; WriteMode.Assist -> "用辅助模式开始"; WriteMode.Generate -> "用生成模式开始" },
                enabled = mode != null, height = 52.dp, modifier = Modifier.fillMaxWidth(),
                textStyle = Ling.type.subhead.copy(fontSize = 16.sp),
            ) { mode?.let(onContinue) }
            Text("可随时切换 · 稿件保存在本机；使用 AI 会将选定内容发给所选服务商", style = Ling.type.caption.copy(fontSize = 13.sp), color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun ModeOption(
    value: WriteMode, current: WriteMode?, icon: ImageVector, tileBg: Color, tileFg: Color,
    title: String, desc: String, bullets: List<String>, modifier: Modifier, onPick: (WriteMode) -> Unit,
) {
    val c = Ling.colors
    val on = value == current
    val i = remember { MutableInteractionSource() }
    val border by animateColorAsState(if (on) c.primary else Color.Transparent, tween(Motion.Medium), label = "optBorder")
    val bg by animateColorAsState(if (on) lerp(c.surface, c.primaryContainer, 0.45f) else c.surface, tween(Motion.Medium), label = "optBg")
    val ring by animateDpAsState(if (on) 7.dp else 2.dp, Motion.bouncy(), label = "radio")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().pressScale(i, 0.98f).clip(shape).background(bg).border(2.dp, border, shape)
            .clickable(i, ripple(color = c.primary), role = Role.RadioButton) { onPick(value) }.padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(icon, tileBg, tileFg)
            Spacer(Modifier.width(12.dp))
            Text(title, style = Ling.type.titleSmall.copy(fontSize = 18.sp), color = c.onSurface, modifier = Modifier.weight(1f))
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).border(ring, if (on) c.primary else c.outline, RoundedCornerShape(11.dp)))
        }
        Text(desc, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
        bullets.forEach { b ->
            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Check, null, Modifier.size(16.dp), tint = c.primary)
                Spacer(Modifier.width(6.dp))
                Text(b, style = Ling.type.label.copy(fontWeight = FontWeight.Normal), color = c.onSurface)
            }
        }
    }
}
