// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lingwrite.app.R
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.enterStagger
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion

/** 升级后首次启动：后台做升级前快照时的等待页（UpgradeGuard）。 */
@Composable
fun UpgradeBackupScreen() {
    val c = Ling.colors
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 40.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.enterStagger(0).size(56.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(LingIcons.Shield, null, tint = c.primary, modifier = Modifier.size(26.dp))
            }
            Text(tr(R.string.upgrade_001), style = Ling.type.title, color = c.onSurface, modifier = Modifier.enterStagger(1))
            Text(tr(R.string.upgrade_002), style = Ling.type.body, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.enterStagger(2))
            // 减弱动画时不放循环进度条（motion §16），文字本身已说明状态。
            if (!Motion.reduced()) LinearProgressIndicator(
                Modifier.enterStagger(3).padding(top = 6.dp).width(120.dp).clip(RoundedCornerShape(2.dp)),
                color = c.primary, trackColor = c.surfaceContainer,
            )
        }
    }
}
