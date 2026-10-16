// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.lingwrite.app.data.BreakdownChapter
import com.lingwrite.app.data.BreakdownResult
import com.lingwrite.app.data.BreakdownTemplate
import com.lingwrite.app.data.READER_PERSONAS
import com.lingwrite.app.data.ReaderDrop
import com.lingwrite.app.data.ReaderPersona
import com.lingwrite.app.data.ReaderReport
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.routes.CurveChart
import com.lingwrite.app.ui.routes.ReaderResultCard
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/** v0.2 M6 截图（ui-spec §11：52 拆文、54/55 读者试读）。 */
class M6ShotTest : ShotBase() {

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        Modules.install()
        runBlocking { AppGraph.books.seedSample() }
    }

    /** 52：拆文分析页（章节列表 + 节奏折线图 + 模板预览，ui-spec §10）。 */
    @Test fun breakdown() = shoot("52-breakdown") {
        val r = BreakdownResult(
            name = "样章",
            chapters = listOf(
                BreakdownChapter(1, "主角雨夜查案，在死者袖中发现师父的铜钱", "雨夜敲门声", "铜钱背面刻着自己的名字", "铜钱现世", 3, "主角困境 + 悬念物件"),
                BreakdownChapter(2, "主角追查铜钱，结识药铺掌柜", "掌柜认出铜钱", "两人结盟", "旧案线索", 3, "得助手 + 线索升级"),
                BreakdownChapter(3, "卷宗失踪，上司勒令结案", "卷宗残页", "主角抗命", "施压", 4, "阻力升级 + 主角表态"),
                BreakdownChapter(4, "宗门大比，主角亮出断剑", "断剑认主", "全场哗然", "长老变色", 5, "当众翻盘 + 身世钩子"),
            ),
            template = listOf(
                BreakdownTemplate(1, "主角困境 + 悬念物件"),
                BreakdownTemplate(2, "得助手 + 线索升级"),
                BreakdownTemplate(3, "阻力升级 + 主角表态"),
                BreakdownTemplate(4, "当众翻盘 + 身世钩子"),
            ),
            notes = listOf("强度整体递增，第 4 章引爆", "每章尾都落一个物件或一句话钩子"),
        )
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState())) {
            LingTopBar("拆文", "只保存分析结果，不保存原文", onBack = {})
            LingCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("节奏曲线（冲突强度 1–5）", style = Ling.type.label, color = Ling.colors.primary)
                    CurveChart(r.curve, Modifier.fillMaxWidth().padding(top = 10.dp).height(120.dp))
                }
            }
            r.chapters.forEach { ch ->
                LingCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("第 ${ch.n} 章", PillKind.Neutral)
                            Row(Modifier.padding(start = 8.dp)) { Pill("强度 ${ch.intensity}", if (ch.intensity >= 4) PillKind.Amber else PillKind.Neutral) }
                        }
                        Text("事件：${ch.event}", style = Ling.type.body, color = Ling.colors.onSurface)
                        Text("钩子：${ch.hook} ｜ 爽点：${ch.cool}", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                        Text("结构：${ch.pattern}", style = Ling.type.caption, color = Ling.colors.primary)
                    }
                }
            }
            LingCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("结构模板（已抹去人名设定）", style = Ling.type.label, color = Ling.colors.primary)
                    r.template.forEach { Text("第 ${it.chapter} 章：${it.pattern}", style = Ling.type.body, color = Ling.colors.onSurface) }
                    r.notes.forEach { Text("· $it", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant) }
                }
            }
        }
    }

    /** 54：读者试读设置（画像多选 + 范围 + 费用预估，ui-spec §8）。 */
    @Test fun readerSetup() = shoot("54-reader-setup") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingTopBar("读者试读", "模拟反馈，仅供参考", onBack = {})
            Text("选读者画像（最多 3 个）", style = Ling.type.label, color = Ling.colors.primary)
            READER_PERSONAS.take(3).forEachIndexed { i, p ->
                val on = i < 2
                com.lingwrite.app.ui.routes.ReaderPersonaRow(p, on) {}
            }
            Text("范围（本章，或第 N–M 章，最多 5 章）", style = Ling.type.label, color = Ling.colors.primary)
            Text("第 118 章 到本章", style = Ling.type.body, color = Ling.colors.onSurface)
            Text("费用提示：预计消耗约 12600 tokens。", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
        }
    }

    /** 55：读者结果卡（星级 + 弃书点 + 期待 + 建议 + 模拟声明，ui-spec §8）。 */
    @Test fun readerReport() = shoot("55-reader-report") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingTopBar("读者试读", "第 118 章 · 模拟反馈，仅供参考", onBack = {})
            ReaderResultCard(
                READER_PERSONAS[0],
                Result.success(ReaderReport(
                    stars = 4,
                    oneLine = "断剑这条线我会追，但主角太被动",
                    drops = listOf(ReaderDrop("争到最后谁也说服不了谁", "围观者戏太拖，我划走了两次")),
                    hooks = listOf("断剑没出鞘那一下", "沈师姐站出来了"),
                    wantNext = "想看她当众替他说话，再看打脸",
                    advice = listOf("围观者数剑那段砍一半", "让主角主动做一个小决定", "章尾钩子提前到倒数第二段"),
                )),
                onSave = {}, onJump = {},
            )
            ReaderResultCard(
                READER_PERSONAS[1],
                Result.success(ReaderReport(
                    stars = 3,
                    oneLine = "爽点有，但来得太慢",
                    drops = listOf(ReaderDrop("她终于开口", "前 800 字都在铺垫")),
                    hooks = listOf("三剑还是四剑的争论"),
                    wantNext = "下一章直接打",
                    advice = listOf("第一段就见血", "钩子前置", "砍环境描写"),
                )),
                onSave = {}, onJump = {},
            )
            Text("模拟读者反馈，仅供参考 · 本次约 12600 tokens", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
        }
    }
}
