// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.components.enterStagger
import com.lingwrite.app.ui.theme.Ling

/** 灵感 / 统计：本阶段尚无高保真稿，先放空状态。 */
@Composable
fun PlaceholderScreen(title: String, icon: ImageVector, hint: String) {
    val c = Ling.colors
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Text(title, style = Ling.type.headline, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, top = 18.dp))
        Column(Modifier.fillMaxSize().padding(bottom = 120.dp).enterStagger(0), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(32.dp), tint = c.onPrimaryContainer)
            }
            Text(hint, style = Ling.type.body, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, start = 48.dp, end = 48.dp))
        }
    }
}
