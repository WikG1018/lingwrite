// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ReadMetricEntity
import kotlin.math.roundToInt

/** 一行追读数据；-1 表示平台没给。 */
data class ReadPoint(
    val chapter: Int,
    val reads: Int = -1,
    val completion: Int = -1,
    val favorites: Int = -1,
    val comments: Int = -1,
    val platform: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
)

data class ReadInsight(
    val chapter: Int,
    val completion: Int,
    val dropPercent: Int,
    val belowMedian: Boolean,
    val noCliff: Boolean,
    val shortChapter: Boolean,
)

/** 追读数据解析与本地诊断；不联网、不抓取平台。 */
object ReadStats {
    private val chapterNum = Regex("""(\d{1,5})""")

    private val chapterKeys = listOf("章", "Chapter", "Episode", "話", "チャプター")
    private val readsKeys = listOf("追读", "阅读", "UV", "PV", "reads", "views", "readers", "閲覧", "読者")
    private val completionKeys = listOf("完读", "读完", "completion", "complete", "finish", "読了", "完読")
    private val favoriteKeys = listOf("收藏", "加书架", "favorite", "bookmark", "follow", "お気に入り", "ブックマーク")
    private val commentKeys = listOf("评论", "comment", "コメント", "感想")
    private val platformKeys = listOf("平台", "platform", "プラットフォーム", "サイト")

    /** 章节号：优先「第 12 章」，其次「12 标题」，最后第一个数字。 */
    fun chapterOf(cell: String): Int? {
        val s = cell.trim()
        val m = Regex("""第\s*(\d{1,5})\s*[章节回]""").find(s)
        if (m != null) return m.groupValues[1].toIntOrNull()
        return chapterNum.find(s)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 百分比：12.3% / 0.123 / 12.3 都转成 0–100 的整数；空或非法返回 -1。
     * 带 % / ％ 的值本身就是百分数（"0.8%" → 1，不能再乘 100）；不带的 ≤ 1 视为小数比例（"0.45" → 45）。
     */
    fun percent(cell: String): Int {
        val raw = cell.trim()
        val hasPct = raw.endsWith('%') || raw.endsWith('％')
        val s = raw.trimEnd('%', '％').trim()
        val d = s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return -1
        val v = if (hasPct || d > 1.0) d else d * 100.0
        return v.roundToInt().coerceIn(0, 100)
    }

    private fun int(cell: String): Int = cell.trim().replace(",", "").replace("，", "").toIntOrNull() ?: -1

    /**
     * 粘贴解析：支持 CSV/TSV/空白分隔，要求表头含「章/章节」以及追读/完读/收藏等列；
     * 没有表头时按「章号, 追读, 完读率, 收藏, 评论」位置解析。
     */
    fun parseTable(text: String): List<ReadPoint> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        fun cells(line: String): List<String> {
            // 优先用制表符/分号；只有没有这些分隔符时才按逗号分，且不把 1,200 这种千分位拆开。
            val parts = when {
                line.contains('\t') -> line.split('\t')
                line.contains('；') -> line.split('；')
                line.contains(';') -> line.split(';')
                line.contains('，') -> line.split('，')
                else -> line.split(Regex(",(?![0-9]{3}(?:\\D|$))"))
            }
            return parts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { line.split(Regex("\\s+")) }
        }
        val header = cells(lines.first())
        fun has(h: String, keys: List<String>) = keys.any { h.contains(it, ignoreCase = true) }
        // 表头同时认中文、英文、日文（平台后台导出的表头语言不一）。
        val hasHeader = header.any { has(it, chapterKeys) } && header.any { has(it, readsKeys + completionKeys) }
        val idx = if (hasHeader) {
            fun find(keys: List<String>) = header.indexOfFirst { h -> has(h, keys) }
            mapOf(
                "chapter" to find(chapterKeys), "reads" to find(readsKeys),
                "completion" to find(completionKeys), "favorites" to find(favoriteKeys),
                "comments" to find(commentKeys), "platform" to find(platformKeys),
            )
        } else mapOf("chapter" to 0, "reads" to 1, "completion" to 2, "favorites" to 3, "comments" to 4)
        val start = if (hasHeader) 1 else 0
        return lines.drop(start).mapNotNull { line ->
            val c = cells(line)
            val ch = c.getOrNull(idx["chapter"] ?: 0)?.let(::chapterOf) ?: return@mapNotNull null
            fun at(k: String) = (idx[k] ?: -1).takeIf { it in c.indices }?.let { c[it] } ?: ""
            ReadPoint(
                chapter = ch,
                reads = int(at("reads")),
                completion = percent(at("completion")),
                favorites = int(at("favorites")),
                comments = int(at("comments")),
                platform = at("platform"),
            )
        }.filter { it.chapter > 0 }
    }

    /** 同一章多次记录取最新（recordedAt，时间相同取 id 大者）。 */
    fun latest(rows: List<ReadMetricEntity>): List<ReadMetricEntity> =
        rows.groupBy { it.chapterNumber }.mapNotNull { (_, list) -> list.maxWithOrNull(compareBy<ReadMetricEntity> { it.recordedAt }.thenBy { it.id }) }
            .sortedBy { it.chapterNumber }

    /** 本地诊断：相对前章掉点、低于中位数、章纲缺章尾钩子、字数偏短。 */
    fun insights(rows: List<ReadMetricEntity>, plans: Map<Long, ChapterPlanEntity> = emptyMap(), chapterNumbers: Map<Long, Int> = emptyMap(), chapterWords: Map<Int, Int> = emptyMap(), medianWords: Int = 0): List<ReadInsight> {
        val latest = latest(rows)
        val comps = latest.map { it.completion }.filter { it >= 0 }.sorted()
        val median = if (comps.isEmpty()) -1 else comps[comps.size / 2]
        // 章号 → 章节 id 的反向表：避免每行线性查找（2000 章时 O(n²)）。
        val idByNumber = HashMap<Int, Long>(chapterNumbers.size * 2).also { m -> chapterNumbers.forEach { (id, n) -> m.putIfAbsent(n, id) } }
        return latest.mapIndexedNotNull { i, m ->
            if (m.completion < 0) return@mapIndexedNotNull null
            val prev = latest.getOrNull(i - 1)?.completion ?: -1
            val drop = if (prev > 0) ((prev - m.completion) * 100.0 / prev).roundToInt() else 0
            val plan = idByNumber[m.chapterNumber]?.let { plans[it] }
            val words = chapterWords[m.chapterNumber] ?: 0
            ReadInsight(
                chapter = m.chapterNumber,
                completion = m.completion,
                dropPercent = drop,
                belowMedian = median >= 0 && m.completion < median,
                noCliff = plan != null && plan.cliff.isBlank(),
                shortChapter = medianWords > 0 && words > 0 && words < medianWords * 0.7,
            )
        }
    }
}
