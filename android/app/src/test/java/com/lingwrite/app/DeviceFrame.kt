// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Ling

/** 截图用：模拟 36dp 状态栏 + 22dp 手势条（Robolectric 不提供系统栏 inset），与高保真稿几何一致。 */
@Composable
fun DeviceFrame(paper: Boolean, content: @Composable () -> Unit) {
    val c = Ling.colors
    val bg = if (paper) c.paper else c.background
    Box(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxSize().padding(top = 36.dp, bottom = if (paper) 24.dp else 22.dp)) { content() }
        Row(Modifier.fillMaxWidth().height(36.dp).background(bg).padding(start = 26.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("9:41", style = Ling.type.label.copy(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.onSurface, modifier = Modifier.weight(1f))
            listOf(Icons.Rounded.SignalCellularAlt, Icons.Rounded.Wifi, Icons.Rounded.Battery5Bar).forEach { Icon(it, null, Modifier.padding(start = 4.dp).size(17.dp), tint = c.onSurface) }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (paper) 24.dp else 22.dp).background(if (paper) c.surface else bg), contentAlignment = Alignment.Center) {
            Box(Modifier.size(108.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurface.copy(alpha = 0.4f)))
        }
    }
}
