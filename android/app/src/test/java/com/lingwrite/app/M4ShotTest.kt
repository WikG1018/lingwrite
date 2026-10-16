// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.IssueFix
import com.lingwrite.app.data.PayoffIdea
import com.lingwrite.app.data.db.IssueEntity
import com.lingwrite.app.data.db.LoreSuggestionEntity
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.SkeletonCard
import com.lingwrite.app.ui.routes.FieldDraftRow
import com.lingwrite.app.ui.routes.IssueCard
import com.lingwrite.app.ui.routes.LoreNamingCard
import com.lingwrite.app.ui.routes.NameIdea
import com.lingwrite.app.ui.theme.Ling
import org.junit.Test

/** v0.2 M4 截图（ui-spec §11：42/44/47/48）。 */
class M4ShotTest : RouteShotBase() {
    /** 42：设定整理建议页（新人物/状态/别名/关系四类胶囊）。 */
    @Test fun loreSuggest() = shoot("42-lore-suggest") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设定整理建议 · 来自第 42 章 · 4 条", fontSize = 17.sp, color = Ling.colors.onSurface)
            val rows = listOf(
                "新人物" to ("新人物：白先生（神秘引路人）" to "原文依据：「露出一个陌生名字：白先生」"),
                "状态变化" to ("境界：→ 金丹（第 42 章起）" to "原文依据：「金丹的气息压得人喘不过气」"),
                "新别名" to ("别名：清禾" to "原文依据：「清禾，过来」"),
                "关系" to ("关系：林晚 — 师徒" to "原文依据：「师父在上」"),
            )
            rows.forEach { (label, body) ->
                LingCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Pill(label, if (label == "新人物") PillKind.Amber else PillKind.Primary)
                        Text(body.first, style = Ling.type.bodyLarge, color = Ling.colors.onSurface, modifier = Modifier.padding(top = 8.dp))
                        Text(body.second, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LingButton("✓ 已采纳", BtnKind.Text, height = 34.dp) {}
                            LingButton("忽略", BtnKind.Text, height = 34.dp) {}
                        }
                    }
                }
            }
        }
    }

    /** 44：设定卡起名 + 空字段草稿。 */
    @Test fun loreNaming() = shoot("44-lore-naming") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新建设定卡 · AI 起名", fontSize = 17.sp, color = Ling.colors.onSurface)
            LoreNamingCard(
                loading = false,
                names = listOf(
                    NameIdea("沈砚", "沉稳如砚"),
                    NameIdea("沈清禾", "清正温婉"),
                    NameIdea("沈惊蛰", "蛰伏待发"),
                    NameIdea("沈观澜", "胸有波澜"),
                ),
                ask = "姓沈、清冷、两个字",
                onAskChange = {}, onGenerate = {}, onPick = {},
            )
            Text("AI 补全空字段（3 个）", style = Ling.type.label, color = Ling.colors.primary)
            FieldDraftRow("性格", "沉默、执拗", onAccept = {}, onReject = {})
            FieldDraftRow("动机", "查十年前的灭门案", onAccept = {}, onReject = {})
            FieldDraftRow("金手指", "断剑能共鸣示警", onAccept = {}, onReject = {})
        }
    }

    /** 47：问题卡给改法（改正文 diff）。 */
    @Test fun issueFix() = shoot("47-issue-fix") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("一致性问题 · 给改法", fontSize = 17.sp, color = Ling.colors.onSurface)
            IssueCard(
                IssueEntity(bookId = 1, chapterId = 1, number = 42, kind = "persona",
                    quote = "沈砚笑嘻嘻地讨好上司", start = 0,
                    basis = "性格：沉默、执拗", explain = "与性格不符"),
                onJump = {}, onIntended = {}, onIgnore = {},
            )
            // 展开的改法区（模拟生成后）
            LingCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Pill("改正文", PillKind.Primary)
                    Text("沈砚笑嘻嘻地讨好上司", style = Ling.type.body.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Text("沈砚压着不耐，敷衍地应了上司几句", style = Ling.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Ling.colors.good, modifier = Modifier.padding(top = 2.dp))
                    Text("补一个不耐，符合沉默执拗", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton("去原文应用", BtnKind.Tonal, height = 32.dp) {}
                        LingButton("存到便签", BtnKind.Text, height = 32.dp) {}
                    }
                }
            }
        }
    }

    /** 48：伏笔回收建议（3 张小卡）。 */
    @Test fun foreshadowPayoff() = shoot("48-foreshadow-payoff") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("伏笔 · 怎么收", fontSize = 17.sp, color = Ling.colors.onSurface)
            Text("断剑的来历（第 1 章埋下）", style = Ling.type.bodyLarge, color = Ling.colors.onSurface)
            listOf(
                PayoffIdea(8, "宗门大比上断剑认主", "提前让剑鸣响两次"),
                PayoffIdea(10, "沈清禾当众道破来历", ""),
                PayoffIdea(12, "旧案卷宗现形", "先出现半页残卷"),
            ).forEach { idea ->
                LingCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Pill("第 ${idea.chapter} 章", PillKind.Amber)
                            Spacer(Modifier.width(8.dp))
                            Text(idea.how, style = Ling.type.body, color = Ling.colors.onSurface, modifier = Modifier.weight(1f))
                        }
                        if (idea.setup.isNotBlank()) Text("需要铺垫：${idea.setup}", style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LingButton("写进第 ${idea.chapter} 章章纲备注", BtnKind.Tonal, height = 32.dp) {}
                            LingButton("改计划回收章", BtnKind.Text, height = 32.dp) {}
                        }
                    }
                }
            }
        }
    }
}
