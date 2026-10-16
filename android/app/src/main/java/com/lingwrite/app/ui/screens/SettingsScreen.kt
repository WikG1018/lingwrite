// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Space

enum class Tone { Primary, Amber, Good, Neutral, Error }

data class SettingRow(
    val icon: ImageVector, val title: String, val sub: String? = null, val value: String? = null, val tone: Tone = Tone.Primary,
    val switch: Boolean? = null, val onSwitch: (Boolean) -> Unit = {}, val onClick: (() -> Unit)? = null,
)
data class SettingSection(val title: String, val rows: List<SettingRow>)

/** 通用设置列表页（设置首页、通知、更新等共用）。 */
@Composable
fun SettingsListScreen(title: String, sections: List<SettingSection>, onBack: (() -> Unit)? = null, header: @Composable () -> Unit = {}, footer: String? = null) {
    val c = Ling.colors
    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = Space.dockClearance)) {
        if (onBack != null) LingTopBar(title, onBack = onBack)
        else Text(title, style = Ling.type.headline, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp))
        header()
        sections.forEachIndexed { si, s ->
            if (s.title.isNotEmpty()) SectionLabel(s.title)
            else Spacer(Modifier.height(12.dp))
            LingCard(Modifier.padding(horizontal = 16.dp)) {
                s.rows.forEachIndexed { i, r ->
                    if (i > 0) InsetDivider(70.dp)
                    SettingRowView(r)
                }
            }
        }
        if (footer != null) Text(footer, style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
    }
}

@Composable
private fun SettingRowView(r: SettingRow) {
    val c = Ling.colors
    val (bg, fg) = when (r.tone) {
        Tone.Primary -> c.primaryContainer to c.onPrimaryContainer; Tone.Amber -> c.tertiaryContainer to c.onTertiaryContainer
        Tone.Good -> c.goodContainer to c.good; Tone.Neutral -> c.surfaceContainer to c.onSurfaceVariant; Tone.Error -> c.errorContainer to c.onErrorContainer
    }
    val click = r.onClick ?: r.switch?.let { { r.onSwitch(!it) } }
    var m = Modifier.fillMaxWidth()
    if (click != null) m = m.rowClick(click)
    BoxWithConstraints(m) {
    val stackedValue = maxWidth < 480.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile(r.icon, bg, fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = Ling.type.bodyLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp), color = c.onSurface)
            if (stackedValue && r.value != null) Text(r.value, style = Ling.type.caption, color = c.primary, modifier = Modifier.padding(top = 2.dp))
            if (r.sub != null) Text(r.sub, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        if (!stackedValue && r.value != null) Text(r.value, style = Ling.type.label.copy(fontWeight = FontWeight.Normal), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp), maxLines = 1)
        if (r.switch != null) Switch(r.switch, r.onSwitch, Modifier.padding(start = 8.dp), colors = SwitchDefaults.colors(
            checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.surfaceContainer, uncheckedBorderColor = c.outline, uncheckedThumbColor = c.outline))
        else if (r.onClick != null) Icon(LingIcons.Chevron, null, tint = c.onSurfaceVariant)
    }
    }
}
