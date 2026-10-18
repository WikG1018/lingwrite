// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.lingwrite.app.ui.theme.Motion
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.R
import com.lingwrite.app.data.TtsEngine
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling

/** 听稿面板：播放/停止、语速；系统 TTS 不可用时降级提示。 */
@Composable
fun TtsSheet(text: String, from: Int, initialRate: Float, bookId: Long? = null, onDismiss: () -> Unit) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val engine = remember { TtsEngine(ctx) }
    val ready by engine.ready.collectAsState()
    val speaking by engine.speaking.collectAsState()
    val error by engine.error.collectAsState()
    var rate by remember { mutableFloatStateOf(initialRate.coerceIn(0.5f, 2f)) }
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }
    // 按作品语言选语音（英文 / 日文作品不再用中文语音读）；判断不出时按简体中文，不支持再退回系统语言。
    LaunchedEffect(bookId) {
        val lang = bookId?.let { id -> runCatching { com.lingwrite.app.data.ai.OutputLang.ofBook(id) }.getOrNull() }
        engine.setLanguage(lang)
    }

    LingSheet(onDismiss, tr(R.string.tts_001)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.tts_008), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.onSurfaceVariant, modifier = Modifier.enterStagger(0))
            // 引擎状态：出现 / 消失走展开 + 淡入，不跳动（motion §6）。
            AnimatedVisibility(error != null || !ready, enter = fadeIn(tween(Motion.Medium)) + expandVertically(tween(Motion.Medium, easing = Motion.EmphasizedDecelerate)),
                exit = fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Short, easing = Motion.EmphasizedAccelerate))) {
                if (error != null) Text(tr(R.string.tts_005), style = Ling.type.body, color = c.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else Text(tr(R.string.tts_006), style = Ling.type.body, color = c.onSurfaceVariant)
            }
            Text(tr(R.string.tts_004), style = Ling.type.label, color = c.primary, modifier = Modifier.enterStagger(1))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.enterStagger(2)) {
                listOf(0.8f to "0.8×", 1.0f to "1.0×", 1.2f to "1.2×").forEach { (v, label) ->
                    LingChip(rate == v, { rate = v }, label)
                }
            }
            // 播放 / 停止互换：交叉淡入 + 轻微缩放（motion §6，Short）。
            AnimatedContent(speaking, transitionSpec = {
                (fadeIn(tween(Motion.Short)) + scaleIn(tween(Motion.Short), initialScale = 0.96f)) togetherWith fadeOut(tween(Motion.Short))
            }, label = "ttsPlay", modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).enterStagger(3)) { on ->
                if (on) {
                    LingButton(tr(R.string.tts_003), BtnKind.Filled, icon = LingIcons.Stop, height = 48.dp, enabled = ready, modifier = Modifier.fillMaxWidth()) { engine.stop() }
                } else {
                    LingButton(tr(R.string.tts_002), BtnKind.Filled, icon = LingIcons.Play, height = 48.dp, enabled = ready && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        engine.speak(text, from, rate)
                    }
                }
            }
        }
    }
}
