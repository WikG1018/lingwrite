// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import androidx.compose.ui.graphics.Color
import com.lingwrite.app.ui.theme.CoverPalette

/** 仅用于界面开发的示例数据，后续由 Room 仓库替换。 */
enum class BookStatus { Draft, Outline, Serial, Done, Wizard }

data class Book(
    val id: Long, val title: String, val short: String, val genre: String, val status: BookStatus,
    val words: String, val chapter: String, val edited: String, val cover: Pair<Color, Color>,
    val overdueForeshadow: Int = 0, val wizardStep: Int = 0,
)

data class Chapter(val no: Int, val title: String, val state: String, val words: String, val aiShare: Int? = null, val draft: Boolean = false, val checked: Boolean = false, val id: Long = 0, val titleIdeas: Int = 0)
data class Volume(val title: String, val range: String, val chapters: List<Chapter>, val id: Long = 0)

data class HeroInfo(val bookId: Long, val chapterId: Long?, val title: String, val subline: String, val excerpt: String, val highlight: String?, val words: String, val wordsUnit: String, val chapters: Int)
data class WorkHeader(val id: Long, val title: String, val short: String, val genre: String, val statusLabel: String, val target: String?, val words: String, val chapters: Int, val lore: Int, val overdue: Int, val cover: Pair<Color, Color>, val foreshadowOpen: Int = 0)

object FakeData {
    val hero = HeroInfo(1, 118, "青云问道", "第二卷 · 宗门大比　第 118 章 擂台余波", "擂台上的血迹还没干透，执事已经抱着名册走了下来。林砚把断剑收进袖里，没回头……", "断剑", "32.6", "万字", 118)
    val header = WorkHeader(1, "青云问道", "青云", "仙侠", "连载中", "日更 4000", "32.6万", 118, 46, 2, CoverPalette.Indigo, 9)
    val books = listOf(
        Book(1, "青云问道", "青云", "仙侠", BookStatus.Serial, "32.6 万字", "第 118 章", "10 分钟前", CoverPalette.Indigo, overdueForeshadow = 2),
        Book(2, "夜港来信", "夜港", "都市", BookStatus.Outline, "1.2 万字", "第 4 章", "昨天", CoverPalette.Amber),
        Book(3, "星海拾荒人", "星海", "科幻", BookStatus.Wizard, "", "", "3 天前", CoverPalette.Jade, wizardStep = 5),
    )

    val volumes = listOf(
        Volume("第一卷 · 山门初入", "第 1–60 章 · 14.7 万字", listOf(
            Chapter(58, "外门比试", "已完成", "4,120"),
            Chapter(59, "藏剑峰的雨", "已完成", "3,958"),
            Chapter(60, "下山之前", "已完成", "4,012"),
        )),
        Volume("第二卷 · 宗门大比", "第 61–118 章 · 17.9 万字", listOf(
            Chapter(114, "夜探藏经阁", "已完成 · 10月8日", "4,230", checked = true),
            Chapter(115, "内门名额只剩三个", "已完成 · 10月9日", "3,876"),
            Chapter(116, "沈师姐的第三道符", "已完成 · 10月10日", "4,105"),
            Chapter(117, "断剑破阵，满场寂然", "已完成 · 昨天", "3,982", aiShare = 18),
            Chapter(118, "擂台余波", "草稿 · 10 分钟前", "2,416", draft = true),
        )),
    )

    val manuscript = listOf(
        "擂台上的血迹还没干透，执事已经抱着名册走了下来。{林砚}把断剑收进袖里，没回头。",
        "台下有人在数他出了几剑。三剑，有人说四剑，争到最后谁也说服不了谁。只有{沈清禾}站在廊柱的阴影里，什么也没说。",
        "[ai]她看得清楚：最后那一剑根本没出鞘。剑意是从袖子里漏出来的，像一盏灯被风掀开了一角。",
        "“林师弟。”她终于开口，声音不高，“你那把剑，是从哪儿来的？”",
    )
    const val suggestion = "林砚停住脚。袖中的断剑忽然轻轻一震，像是认出了什么人。他没有转身，只说：“捡的。”"
}
