// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.theme.Ling

/**
 * 草稿区（C5，ui-spec §2）：生成模式“起草本章”的独立区域，在正文下方。
 * 每段可“采纳到正文 / 微调 / 换一版”；草稿区文字不计入字数统计。
 */
@Composable
fun DraftPane(
    segments: List<String>,
    total: Int,
    streamingIndex: Int,
    onAdopt: (Int) -> Unit,
    onTweak: (Int) -> Unit,
    onReroll: (Int) -> Unit,
    onAdoptAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ling.colors
    LingCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.draft_pane_001, segments.size.coerceAtMost(total), total), style = Ling.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.tertiary, modifier = Modifier.weight(1f))
                LingButton(tr(R.string.draft_pane_002), BtnKind.Text, height = 32.dp, onClick = onDismiss)
            }
            Text(tr(R.string.draft_pane_003), style = Ling.type.caption.copy(fontSize = 11.5.sp), color = c.onSurfaceVariant)
            segments.forEachIndexed { i, seg ->
                Column(Modifier.padding(top = 10.dp)) {
                    if (i == streamingIndex) Row(verticalAlignment = Alignment.Top) { Text(seg, style = Ling.type.bodyLarge.copy(lineHeight = 27.sp), color = c.onSurface, modifier = Modifier.weight(1f, fill = false)); com.lingwrite.app.ui.ai.AiStreamCursor(Modifier.padding(top = 4.dp)) }
                    else Text(seg, style = Ling.type.bodyLarge.copy(lineHeight = 27.sp), color = c.onSurface)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton(tr(R.string.draft_pane_004), BtnKind.Tonal, height = 32.dp, enabled = streamingIndex < 0, onClick = { onAdopt(i) })
                        LingButton(tr(R.string.ai_components_009), BtnKind.Outlined, height = 32.dp, onClick = { onTweak(i) })
                        LingButton(tr(R.string.draft_pane_005), BtnKind.Text, height = 32.dp, onClick = { onReroll(i) })
                    }
                }
            }
            if (streamingIndex >= segments.size) AiThinking(tr(R.string.draft_pane_006, segments.size + 1), Modifier.padding(top = 10.dp))
            if (segments.isNotEmpty() && streamingIndex < 0) LingButton(
                tr(R.string.draft_pane_007), modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), height = 44.dp, onClick = onAdoptAll,
            )
        }
    }
}
