// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.ui.ai.AiCandidateCard
import com.lingwrite.app.ui.ai.AiErrorCard
import com.lingwrite.app.ui.ai.AiMainButton
import com.lingwrite.app.ui.ai.AiNoteCard
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.ai.Candidate
import com.lingwrite.app.ui.ai.adoptHighlight
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.Skeleton
import com.lingwrite.app.ui.components.SkeletonBlock
import com.lingwrite.app.ui.components.SkeletonCard
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.LingTheme
import org.junit.Test

/** v0.2 AI 共用组件的截图（ui-spec §1）：状态页与骨架。 */
class AiShotTest : ShotBase() {
    @Test
    fun aiStates() = shoot("60-ai-states") {
        Column(
            Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI 状态 · 共用组件（Idle / Thinking / Streaming / Ready / Error / Adopted）", fontSize = 20.sp, color = Ling.colors.onSurface)
            Text("① Idle · 空态（入口按钮 + 一句说明）", style = Ling.type.label, color = Ling.colors.primary)
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiMainButton(WriteMode.Assist) {}
                    Text("辅助模式：AI 给方向、提问题，不会替你写正文。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                }
            }
            Text("② Thinking · 思考中", style = Ling.type.label, color = Ling.colors.primary)
            LingCard { AiThinking("正在读本章和设定…", Modifier.padding(14.dp)) }
            Text("③ Streaming · 流式输出中", style = Ling.type.label, color = Ling.colors.primary)
            LingCard {
                Text(
                    "林砚停住脚。袖中的断剑忽然轻轻一震，像是认出了什么人。他没有转身，只说：“捡的。”▍",
                    style = Ling.type.bodyLarge.copy(lineHeight = 26.sp), color = Ling.colors.onSurface, modifier = Modifier.padding(14.dp),
                )
            }
            Text("④ Ready · 候选（生成模式）", style = Ling.type.label, color = Ling.colors.primary)
            var selected by remember { mutableIntStateOf(0) }
            AiCandidateCard(
                "续写候选",
                listOf(
                    Candidate("袖中的断剑又轻轻一震。林砚垂下眼，指腹在剑脊上按了按，像是安抚。"),
                    Candidate("台下还在争他出了几剑。没人注意他袖口那道新裂的口子。"),
                    Candidate("“捡的。”他说完这两个字，廊柱阴影里的人影动了一下。"),
                ),
                selected, onSelect = { selected = it },
            )
            Text("⑤ Error · 错误卡", style = Ling.type.label, color = Ling.colors.primary)
            AiErrorCard("网络超时", retries = 1)
            Text("⑥ Adopted · 采纳后高亮（amber 30% 已淡到常规底色）", style = Ling.type.label, color = Ling.colors.primary)
            Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .then(Modifier.adoptHighlight("gen", "shot")).padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(LingIcons.Check, null, tint = Ling.colors.onSurface, modifier = Modifier.padding(end = 8.dp))
                Text("已采纳到正文", style = Ling.type.caption, color = Ling.colors.onSurface)
            }
            Text("辅助模式结果永远是便签卡，没有「采纳到正文」按钮", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
        }
    }

    @Test
    fun skeleton() = shoot("61-skeleton") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("骨架屏", fontSize = 20.sp, color = Ling.colors.onSurface)
            Skeleton(0.9f)
            SkeletonBlock(3)
            SkeletonCard(4)
        }
    }

    @Test
    fun candidates() = shoot("43-candidates") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("多候选 · 生成模式", fontSize = 20.sp, color = Ling.colors.onSurface)
            var selected by remember { mutableIntStateOf(1) }
            AiCandidateCard(
                "续写 · 3 个候选",
                listOf(
                    Candidate("袖中的断剑又轻轻一震。林砚垂下眼，指腹在剑脊上按了按，像是安抚。"),
                    Candidate("台下还在争他出了几剑。没人注意他袖口那道新裂的口子。"),
                    Candidate("“捡的。”他说完这两个字，廊柱阴影里的人影动了一下。"),
                ),
                selected,
                onSelect = { selected = it },
            )
            AiCandidateCard("改写 · 3 个候选", listOf(Candidate("第一版写法。"), Candidate("第二版写法。")), 0, ready = { it == 0 })
        }
    }
}
