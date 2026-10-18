// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph

/** 章节事件轴的一条事件（按章节顺序，不是严格的「故事内时间」）。 */
data class TimelineEvent(
    val chapter: Int,
    val kind: String,
    val title: String,
    val detail: String = "",
    val cardId: Long? = null,
    val chapterId: Long? = null,
    val storyTime: String = "",
    val cardType: String = "",
)

object Timeline {
    /** 从设定状态、关系、伏笔、章纲事件派生事件；不加载正文。 */
    suspend fun build(bookId: Long): List<TimelineEvent> {
        val db = AppGraph.db
        val cards = db.lore().cardsOnce(bookId).filter { !it.pending }
        val cardMap = cards.associateBy { it.id }
        val headers = db.chapters().headersOnce(bookId)
        val chapterIdByNumber = headers.associate { it.number to it.id }
        val numberById = headers.associate { it.id to it.number }
        val planList = db.plan().chaptersOnce(bookId)
        val out = ArrayList<TimelineEvent>()

        db.lore().statesForBook(bookId).groupBy { it.cardId }.forEach { (cardId, states) ->
            val card = cardMap[cardId] ?: return@forEach
            states.forEach { s ->
                out += TimelineEvent(
                    chapter = s.fromChapter, kind = "lore", title = "${card.name} · ${s.field}",
                    detail = s.value, cardId = cardId, cardType = card.type,
                )
            }
        }
        db.lore().relationsOnce(bookId).forEach { r ->
            val from = cardMap[r.fromId]?.name ?: return@forEach
            val to = cardMap[r.toId]?.name ?: return@forEach
            out += TimelineEvent(chapter = r.fromChapter, kind = "relation", title = "$from — $to", detail = r.label, cardId = r.fromId, cardType = "character")
        }
        db.checks().foreshadowsOnce(bookId).forEach { f ->
            out += TimelineEvent(
                chapter = f.plantNumber, kind = "foreshadow", title = f.content.take(60),
                detail = f.quote.take(80), chapterId = f.chapterId ?: chapterIdByNumber[f.plantNumber],
            )
            f.payoff?.let { p -> out += TimelineEvent(chapter = p, kind = "payoff", title = f.content.take(60), detail = "", chapterId = chapterIdByNumber[p]) }
        }
        planList.forEach { p ->
            val number = numberById[p.chapterId] ?: return@forEach
            val text = p.event.ifBlank { p.note }.ifBlank { p.cliff }
            if (text.isNotBlank() || p.storyTime.isNotBlank()) {
                out += TimelineEvent(
                    chapter = number, kind = "plan", title = ChapterLabel.short(number),
                    detail = listOf(text.take(80), p.storyTime).filter { it.isNotBlank() }.joinToString(" · "),
                    chapterId = p.chapterId, storyTime = p.storyTime,
                )
            }
        }
        return out.sortedWith(compareBy<TimelineEvent> { it.chapter }.thenBy { kindOrder(it.kind) })
    }

    fun filter(events: List<TimelineEvent>, group: String): List<TimelineEvent> = when (group) {
        "all" -> events
        "person" -> events.filter { it.cardType == "character" }
        "place" -> events.filter { it.cardType == "place" }
        "item" -> events.filter { it.cardType == "item" }
        "power" -> events.filter { it.cardType == "power" }
        "foreshadow" -> events.filter { it.kind == "foreshadow" || it.kind == "payoff" }
        "plan" -> events.filter { it.kind == "plan" }
        else -> events
    }

    private fun kindOrder(kind: String): Int = when (kind) { "plan" -> 0; "lore" -> 1; "relation" -> 2; "foreshadow" -> 3; "payoff" -> 4; else -> 5 }
}
