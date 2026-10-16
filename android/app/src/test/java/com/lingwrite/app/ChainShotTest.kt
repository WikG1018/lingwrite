// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.lingwrite.app.data.Beat
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.encodeBeats
import com.lingwrite.app.ui.ai.AiNoteCard
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.SkeletonBlock
import com.lingwrite.app.ui.routes.DraftPane
import com.lingwrite.app.ui.screens.BeatsChecklist
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.screens.ToolChips
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/** v0.2 链路相关截图（ui-spec §11：56/57/58/59）。 */
class ChainShotTest : RouteShotBase() {
    override suspend fun extraSeed(bookId: Long) {
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        chs.firstOrNull()?.let {
            AppGraph.db.plan().upsertChapter(
                ChapterPlanEntity(it.id, bookId, event = "外门比试", hook = "断剑共鸣", cliff = "有人认出剑")
                    .copy(beats = encodeBeats(listOf(Beat("300 字内抛出钩子：断剑共鸣"), Beat("比试中藏拙，留一手"), Beat("沈清禾旁观不语"))))
            )
        }
    }

    /** 56：总纲工作台第 ③ 步（总纲三段 + 时间线）。 */
    @Test fun chainSynopsis() = shoot("56-chain-synopsis") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("总纲工作台 · 青云问道", fontSize = 20.sp, color = Ling.colors.onSurface)
            // 时间线：①✓ ②✓ ③当前 ④⑤⑥未开始
            val steps = listOf(
                Triple("创意", "断剑少年入青云查旧案", "done"),
                Triple("核心设定", "5 条卖点 · 主角卡 · 世界观", "done"),
                Triple("总纲", "主线：断剑少年查旧案", "now"),
                Triple("卷纲", "拆卷、目标与高潮", "todo"),
                Triple("章纲", "本卷前 N 章章纲", "todo"),
                Triple("第一章", "节拍与第一章", "todo"),
            )
            steps.forEach { (title, sub, state) ->
                val done = state == "done"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title + if (done) " ✓" else "", style = Ling.type.label, color = if (state == "todo") Ling.colors.onSurfaceVariant else Ling.colors.onSurface)
                            Text(sub, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                        }
                    }
                }
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("③ 总纲", style = Ling.type.label, color = Ling.colors.primary)
                    Text("主线：断剑少年查旧案", style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
                    Text("阶段外门 站稳脚跟；旧案线索；被卷入内门之争", style = Ling.type.body, color = Ling.colors.onSurface)
                    Text("结局：旧案大白，宗门重建", style = Ling.type.body, color = Ling.colors.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.lingwrite.app.ui.components.LingButton("AI 写总纲", com.lingwrite.app.ui.components.BtnKind.Tonal, height = 36.dp) {}
                        com.lingwrite.app.ui.components.LingButton("采纳并继续", height = 36.dp) {}
                    }
                }
            }
        }
    }

    /** 57：总纲工作台第 ④ 步（拆卷候选）。 */
    @Test fun chainVolumes() = shoot("57-chain-volumes") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("④ 卷纲 · 4 卷候选", fontSize = 20.sp, color = Ling.colors.onSurface)
            listOf(
                Triple("第一卷 · 山门初入", "目标：站稳脚跟", "高潮：外门夺魁"),
                Triple("第二卷 · 宗门大比", "目标：查旧案", "高潮：断剑真相"),
                Triple("第三卷 · 北地之乱", "目标：护送卷宗", "高潮：师徒对决"),
                Triple("第四卷 · 归来", "目标：重开山门", "高潮：真相大白"),
            ).forEach { (title, goal, climax) ->
                LingCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(title, style = Ling.type.subhead, color = Ling.colors.onSurface)
                        Text(goal, style = Ling.type.body, color = Ling.colors.onSurface)
                        Text(climax, style = Ling.type.body, color = Ling.colors.onSurface)
                    }
                }
            }
            com.lingwrite.app.ui.components.LingButton("采纳勾选的卷", height = 48.dp, modifier = Modifier.fillMaxWidth()) {}
        }
    }

    /** 58：辅助模式第一节拍清单 + 开篇示意（便签）。 */
    @Test fun firstChapterAssist() = shoot("58-first-chapter-assist", paper = true) {
        val beats = listOf(Beat("300 字内抛出钩子：断剑共鸣"), Beat("比试中藏拙，留一手"), Beat("沈清禾旁观不语"))
        EditorScreen(
            mode = WriteMode.Assist,
            emptyHint = null,
            beats = beats, onBeatToggle = {},
            bottomPanel = {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiNoteCard(
                        "开篇示意 · 3 种",
                        "1. 从断剑共鸣写起：雨夜擂台，剑先于人开口\n2. 从执事唱名写起：名册念到“林砚”时安静了一瞬\n3. 从沈清禾的视线写起：她看懂了那一剑没出鞘",
                        onNote = {}, onCopy = {},
                    )
                    ToolChips(-1, listOf("给方向", "随手问")) {}
                }
            },
        )
    }

    /** 59：生成模式草稿区。 */
    @Test fun firstChapterDraft() = shoot("59-first-chapter-draft", paper = true) {
        EditorScreen(
            mode = WriteMode.Generate,
            emptyHint = null,
            bottomPanel = {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftPane(
                        segments = listOf(
                            "擂台上的血迹还没干透，执事已经抱着名册走了下来。林砚把断剑收进袖里，没回头。",
                            "台下有人在数他出了几剑。三剑，有人说四剑，争到最后谁也说服不了谁。",
                        ),
                        total = 3,
                        streamingIndex = -1,
                        onAdopt = {}, onTweak = {}, onReroll = {}, onAdoptAll = {}, onDismiss = {},
                    )
                    ToolChips(-1, listOf("续写", "起草本章", "对话", "随手问")) {}
                }
            },
        )
    }
}
