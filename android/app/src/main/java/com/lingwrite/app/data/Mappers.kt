// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.BookSummary
import com.lingwrite.app.data.db.ChapterEntity
import com.lingwrite.app.data.db.VolumeEntity
import com.lingwrite.app.ui.theme.CoverPalette
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class AiMark(val s: Int, val e: Int, val k: String = "gen")

val LingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun parseMarks(json: String): List<AiMark> = runCatching { LingJson.decodeFromString<List<AiMark>>(json) }.getOrDefault(emptyList())
fun encodeMarks(m: List<AiMark>): String = LingJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AiMark.serializer()), m)

fun statusOf(s: String) = when (s) {
    "serial" -> BookStatus.Serial; "outline" -> BookStatus.Outline; "wizard" -> BookStatus.Wizard; "done" -> BookStatus.Done; else -> BookStatus.Draft
}
fun statusLabel(s: String) = when (s) { "serial" -> "连载中"; "outline" -> "大纲中"; "wizard" -> "向导中"; "done" -> "已完结"; else -> "草稿" }

fun shortTitle(t: String) = t.filter { !it.isWhitespace() }.take(2)

fun wordsShort(n: Int) = if (n >= 10_000) "%.1f万".format(n / 10_000f) else "$n"

fun BookSummary.toUi() = Book(
    id = id, title = title, short = shortTitle(title), genre = genre, status = statusOf(status),
    words = Words.format(words), chapter = if (lastChapter != null) ChapterLabel.short(lastChapter) else "", edited = TimeFmt.relative(updatedAt),
    cover = CoverPalette.of(coverIndex), wizardStep = wizardStep,
)

fun ChapterEntity.toUi(): Chapter {
    val ai = parseMarks(aiMarks).sumOf { it.e - it.s }
    val share = if (wordCount > 50 && ai > 0) (ai * 100 / wordCount.coerceAtLeast(1)).coerceIn(1, 100) else null
    return Chapter(
        id = id, no = number, title = title,
        state = (if (state == "done") "已完成" else "草稿") + " · " + TimeFmt.relative(updatedAt),
        words = Words.grouped(wordCount), aiShare = share, draft = state != "done",
    )
}

fun volumesUi(vols: List<VolumeEntity>, chs: List<ChapterEntity>, titleIdeas: Map<Long, Int> = emptyMap()): List<Volume> = vols.sortedBy { it.sort }.mapIndexed { i, v ->
    val list = chs.filter { it.volumeId == v.id }.sortedBy { it.number }
    val range = if (list.isEmpty()) "暂无章节" else "第 ${list.minOf { it.number }}–${list.maxOf { it.number }} 章 · ${Words.format(list.sumOf { it.wordCount })}"
    Volume(VolumeLabel.full(i + 1, v.title), range, list.map { c -> val n = titleIdeas[c.id] ?: 0; c.toUi().let { if (n > 0) it.copy(titleIdeas = n) else it } }, v.id)
}

fun workHeader(b: BookEntity, chs: List<ChapterEntity>, lore: Int, overdue: Int, openForeshadow: Int) = WorkHeader(
    id = b.id, title = b.title, short = shortTitle(b.title), genre = b.genre, statusLabel = statusLabel(b.status),
    target = if (b.dailyTarget > 0) "日更 ${b.dailyTarget}" else null, words = wordsShort(chs.sumOf { it.wordCount }),
    chapters = chs.size, lore = lore, overdue = overdue, cover = CoverPalette.of(b.coverIndex), foreshadowOpen = openForeshadow,
)
