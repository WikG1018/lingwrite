// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lingwrite.app.R
import com.lingwrite.app.data.TtsEngine
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling

/** 听稿面板：播放/停止、语速；系统 TTS 不可用时降级提示。 */
@Composable
fun TtsSheet(text: String, from: Int, initialRate: Float, onDismiss: () -> Unit) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val engine = remember { TtsEngine(ctx) }
    val ready by engine.ready.collectAsState()
    val speaking by engine.speaking.collectAsState()
    val error by engine.error.collectAsState()
    var rate by remember { mutableFloatStateOf(initialRate.coerceIn(0.5f, 2f)) }
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }

    LingSheet(onDismiss, tr(R.string.tts_001)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.tts_008), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant)
            if (error != null) Text(tr(R.string.tts_005), style = Ling.type.body, color = c.error)
            else if (!ready) Text(tr(R.string.tts_006), style = Ling.type.body, color = c.onSurfaceVariant)
            Text(tr(R.string.tts_004), style = Ling.type.label, color = c.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f to "0.8×", 1.0f to "1.0×", 1.2f to "1.2×").forEach { (v, label) ->
                    LingChip(rate == v, { rate = v }, label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (speaking) {
                    LingButton(tr(R.string.tts_003), BtnKind.Filled, icon = LingIcons.Stop, height = 48.dp, enabled = ready, modifier = Modifier.weight(1f)) { engine.stop() }
                } else {
                    LingButton(tr(R.string.tts_002), BtnKind.Filled, icon = LingIcons.Play, height = 48.dp, enabled = ready && text.isNotBlank(), modifier = Modifier.weight(1f)) {
                        engine.speak(text, from, rate)
                    }
                }
            }
        }
    }
}
