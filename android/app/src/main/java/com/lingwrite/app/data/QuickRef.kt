// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.data.db.Hit
import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.VolumeSummaryEntity

/** 编辑器速查的数据聚合：只读，不写正文。 */
object QuickRef {
    data class CardHit(val card: LoreCardEntity, val fields: List<ResolvedField>)
    data class SummaryLine(val number: Int, val text: String)

    /** 当前章相关的设定卡：正文/章纲命中、always 注入；按人物/势力/地点/物品排序。 */
    suspend fun cards(bookId: Long, chapterId: Long, chapterNumber: Int, text: String): List<CardHit> {
        val db = AppGraph.db
        val plan = db.plan().chapterOnce(chapterId)?.asPrompt().orEmpty()
        val probe = text.take(4000) + "\n" + plan
        val all = db.lore().cardsOnce(bookId).filter { !it.pending && it.inject != "never" && (it.firstChapter == null || it.firstChapter <= chapterNumber) }
        val states = db.lore().statesForBook(bookId).groupBy { it.cardId }
        val relevant = all.filter { c -> c.inject == "always" || c.allNames().any { it.isNotBlank() && probe.contains(it) } }
        val list = relevant.ifEmpty { all.filter { it.type == "character" }.take(8) }
        return list.sortedBy { kindOrder(it.type) }.take(40).map { c -> CardHit(c, resolveAt(c, states[c.id].orEmpty(), chapterNumber)) }
    }

    private fun kindOrder(type: String): Int = when (type) { "character" -> 0; "faction" -> 1; "place" -> 2; "item" -> 3; "power" -> 4; else -> 5 }

    /** 当前章值得看一眼的伏笔：临期/逾期，或关联卡在正文里出现。 */
    suspend fun foreshadows(bookId: Long, chapterNumber: Int, text: String): List<ForeshadowEntity> {
        val db = AppGraph.db
        val cards = db.lore().cardsOnce(bookId).associateBy { it.id }
        return db.checks().foreshadowsOnce(bookId).filter { it.status == "open" && !it.pending && it.plantNumber < chapterNumber }
            .filter { f ->
                (f.payoff?.let { it - chapterNumber in -50..5 } == true) ||
                    (f.cardId != null && cards[f.cardId]?.allNames()?.filter { it.isNotBlank() }?.any { text.contains(it) } == true)
            }
            .sortedBy { it.payoff ?: Int.MAX_VALUE }
            .take(30)
    }

    /** 最近 N 章摘要 + 当前卷摘要。 */
    suspend fun summaries(bookId: Long, chapterId: Long, chapterNumber: Int, n: Int = 5): Pair<List<SummaryLine>, VolumeSummaryEntity?> {
        val db = AppGraph.db
        val headers = db.chapters().headersOnce(bookId).filter { it.number < chapterNumber }.takeLast(n)
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val lines = headers.mapNotNull { h -> sums[h.id]?.text?.takeIf { it.isNotBlank() }?.let { SummaryLine(h.number, it) } }
        val volumeId = db.chapters().get(chapterId)?.volumeId
        val volume = volumeId?.let { db.context().volumeSummary(it) }
        return lines to volume
    }

    /** 搜索本书；先补索引。 */
    suspend fun search(bookId: Long, query: String, limit: Int = 30): List<Hit> {
        if (query.isBlank()) return emptyList()
        ContextEngine.refresh(bookId)
        return ContextEngine.search(query, bookId, limit = limit)
    }
}
