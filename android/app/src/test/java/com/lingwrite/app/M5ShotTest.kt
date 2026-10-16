// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.lingwrite.app.data.IdeaExpansion
import com.lingwrite.app.data.Proof
import com.lingwrite.app.data.ProofIssue
import com.lingwrite.app.data.StuckPrompts
import com.lingwrite.app.data.TimeFmt
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.WordDayEntity
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.routes.BlurbCandidates
import com.lingwrite.app.ui.routes.BookPitch
import com.lingwrite.app.ui.routes.IdeaExpandCard
import com.lingwrite.app.ui.routes.PitchCard
import com.lingwrite.app.ui.routes.SerialBoardCard
import com.lingwrite.app.ui.routes.StuckClarifyStep
import com.lingwrite.app.ui.routes.StuckWaysPager
import com.lingwrite.app.ui.routes.TitleChips
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.screens.ProofBar
import com.lingwrite.app.ui.screens.ProofBubble
import com.lingwrite.app.ui.screens.demoManuscript
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/** v0.2 M5 截图（ui-spec §11：45/46/49/50/51，ai-features §14：53）。 */
class M5ShotTest : ShotBase() {
    private var chapterId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        Modules.install()
        runBlocking {
            val b = AppGraph.books.seedSample()
            chapterId = AppGraph.db.chapters().chaptersOnce(b).first().id
        }
    }

    /** 45：卡文急救第 2 步（澄清问题）盖在辅助模式编辑器上（稿 12）。 */
    @Test fun stuckAsk() = shoot("45-stuck-ask", paper = true) {
        Box(Modifier.fillMaxSize()) {
            EditorScreen(mode = com.lingwrite.app.data.WriteMode.Assist)
            SheetChrome("卡文急救 · 第 2 步 / 3") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    StuckClarifyStep(
                        clarifies = listOf(
                            StuckPrompts.Clarify("上一场断在哪里？", listOf("沈清禾问剑的来历", "林砚转身就走", "执事宣布比试结果")),
                            StuckPrompts.Clarify("接下来更想推进哪条线？", listOf("断剑来历", "两人的信任", "宗门大比")),
                        ),
                        answers = listOf("沈清禾问剑的来历", ""),
                        busy = false, note = "",
                        onNote = {}, onGenerate = {}, onSkip = {}, onPick = { _, _ -> }, onWays = {},
                    )
                }
            }
        }
    }

    /** 46：卡文急救第 3 步方向卡横向翻页（稿 12）。 */
    @Test fun stuckWays() = shoot("46-stuck-ways", paper = true) {
        Box(Modifier.fillMaxSize()) {
            EditorScreen(mode = com.lingwrite.app.data.WriteMode.Assist)
            SheetChrome("卡文急救 · 第 3 步 / 3") {
                StuckWaysPager(
                    ways = listOf(
                        StuckPrompts.Way(
                            "以退为进", "林砚当场承认“剑是捡的”，转身离开；当夜沈清禾独自去后山，发现剑鸣与师门禁地共振。",
                            "把悬念推到下一章，节奏快", "沈清禾视角要多一场戏", "第 119 章事件改为“后山禁地”",
                        ),
                        StuckPrompts.Way(
                            "旧人认剑", "人群中有人认出剑的来历，当众点破；林砚被迫接话，露出破绽。",
                            "冲突立刻升温", "抛设定太快，容易说教", "本章补 100 字围观者反应",
                        ),
                        StuckPrompts.Way(
                            "夜半剑鸣", "当夜断剑自己鸣响，把林砚引到藏剑峰；有人比他先到。",
                            "悬念和下章钩子一起埋", "夜里戏不好写氛围", "第 119 章开头改为“子时剑鸣”",
                        ),
                    ),
                    chips = listOf("不知道怎么过渡", "她该不该追问？ → 追问"),
                    isGenerate = false,
                    onSave = {}, onOutline = {},
                )
            }
        }
    }

    /** 49：校对顶条 + 气泡 + 正文波浪线（稿 13）。 */
    @Test fun proof() {
        val (value, marks) = demoManuscript()
        val issues = listOf(
            ProofIssue("执事已经抱着名册走了下来。", "执事已经抱着名册走下来了。", "grammar", "语序拗口"),
            ProofIssue("争到最后谁也说服不了谁。", "争到最后，谁也说服不了谁。", "punct", "长句缺逗号"),
            ProofIssue("从哪儿来的", "从哪里来的", "typo", "用字不规范"),
        )
        val ranges = issues.map { i ->
            val at = Proof.locate(value.text, i.quote)
            at until (at + i.quote.length)
        }
        shoot("49-proof", paper = true) {
            EditorScreen(
                value = value, marks = marks,
                proofRanges = ranges,
                proofBar = {
                    ProofBar(
                        count = issues.size, busy = false, error = null, pick = 1,
                        onPick = {}, onAcceptAll = {}, onAcceptOne = {}, onExit = {},
                    )
                },
                proofBubble = {
                    ProofBubble(issue = issues[1], onIgnore = {}, onAccept = {})
                },
            )
        }
    }

    /** 50：作品信息里的书名胶囊、简介候选（1/3）、上架文案。 */
    @Test fun bookBlurb() {
        val pitch = BookPitch(
            titles = listOf("断剑问天", "青云问道", "长夜问刀", "剑起山门", "问道青云"),
            blurbs = listOf(
                "擂台上的血还没干，他袖中的断剑先认了主。十年前的旧案卷宗上，刻着他自己的名字。仙门、皇城、旧案，一把断剑问到底。",
                "沈砚从尸检房一路查到宗门大比，越查越近的不是真相，是师父的名字。有人要他结案，有人要他闭嘴。",
                "山门收留了一个带断剑的少年。十年后宗门大比，剑鸣震碎了所有人的判断——那一剑，根本没出鞘。",
            ),
            oneLine = "断剑认主，旧案缠身",
            tags = listOf("仙侠", "悬疑", "成长"),
            recommend = "开篇即命案，主角一边查师父旧案一边在宗门站稳脚跟；打斗写意不注水，悬念一章一个，断剑的秘密压到大比才揭。",
        )
        shoot("50-book-blurb") {
            Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("作品信息", fontSize = 17.sp, color = Ling.colors.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LingTextField("青云问道", {}, Modifier.weight(1f), label = "书名")
                    LingButton("✨", BtnKind.Text, height = 36.dp) {}
                }
                Text("AI 起名", style = Ling.type.label, color = Ling.colors.primary)
                TitleChips(pitch.titles, onPick = {})
                LingTextField("仙侠", {}, label = "类型")
                LingTextField("断剑少年入青云，十年旧案一线牵。", {}, label = "简介", singleLine = false, minLines = 3, supporting = "点 ✨ 让 AI 给 3 版候选")
                BlurbCandidates(blurbs = pitch.blurbs, selected = 0, onSelect = {}, onUse = {})
                PitchCard(pitch, onCopy = {})
            }
        }
    }

    /** 51：作品页头部下的连载看板（存稿不足 → 琥珀色，稿 16）。 */
    @Test fun serialBoard() {
        runBlocking {
            AppGraph.db.books().get(1)!!.let { AppGraph.books.updateBook(it.copy(publishedUpTo = 7)) }
            val a = AppGraph.books.nextChapter(1)
            val b = AppGraph.books.nextChapter(1)
            AppGraph.db.plan().upsertChapter(ChapterPlanEntity(a, 1, event = "藏剑峰夜话", hook = "剑鸣示警", cliff = "旧人现身"))
            AppGraph.db.plan().upsertChapter(ChapterPlanEntity(b, 1, event = "内门名额之争", hook = "名额只剩一个", cliff = "沈师姐开口"))
            AppGraph.db.stats().upsertWordDay(WordDayEntity(TimeFmt.day(System.currentTimeMillis()), 1, 2860))
        }
        shoot("51-serial-board") {
            Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState())) {
                LingTopBar("青云问道", "正文", onBack = {})
                SerialBoardCard(1)
                Text("第一卷 · 山门初入", style = Ling.type.volumeHeader, color = Ling.colors.primary, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 8.dp))
                LingCard(Modifier.padding(horizontal = 16.dp)) {
                    listOf("第 1 章" to "外门比试", "第 2 章" to "藏剑峰的雨", "第 3 章" to "未命名").forEach { (n, t) ->
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n, Modifier.padding(end = 16.dp), style = Ling.type.label.copy(fontFeatureSettings = "tnum"), color = Ling.colors.onSurfaceVariant)
                            Text(t, style = Ling.type.bodyLarge, color = Ling.colors.onSurface, modifier = Modifier.weight(1f))
                            if (t == "未命名") Pill("3 个标题建议", PillKind.Amber)
                        }
                    }
                }
            }
        }
    }

    /** 53：灵感条目展开成 3 个方向（存为灵感 / 转设定卡 / 转伏笔）。 */
    @Test fun ideaExpand() {
        shoot("53-idea-expand") {
            Column(Modifier.fillMaxSize().background(Ling.colors.background).verticalScroll(rememberScrollState())) {
                LingTopBar("灵感", onBack = {})
                LingCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill("灵感", PillKind.Amber)
                            Pill("置顶", PillKind.Neutral, modifier = Modifier.padding(start = 6.dp))
                        }
                        Text(
                            "让断剑只有在真正危险时才鸣响，平时像一把废铁——沈砚自己都怀疑它认错了人。",
                            style = Ling.type.bodyLarge, color = Ling.colors.onSurface, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Text("展开成 3 个方向", style = Ling.type.label, color = Ling.colors.primary, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        IdeaExpansion("悬疑线", "断剑每次示警都在追查当年灭门案的凶手，鸣响处必留一枚旧铜钱"),
                        IdeaExpansion("感情线", "沈清禾研究剑鸣三月，发现它只对沈砚的血起反应，却始终瞒着他"),
                        IdeaExpansion("升级线", "断剑认主条件是剑主先断一次经脉，沈砚在大比上主动接下一剑"),
                    ).forEach { e -> IdeaExpandCard(e, onSave = {}, onToCard = {}, onToForeshadow = {}) }
                }
            }
        }
    }
}

/** 底部 Sheet 的静态外壳（拖拽条 + 标题 + 圆角顶），和 LingSheet 视觉一致，用于单窗口合成截图。 */
@Composable
private fun BoxScope.SheetChrome(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Ling.colors
    Column(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
        Text(title, style = Ling.type.title, color = c.onSurface, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp))
        content()
    }
}
