// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.ChapterEntity

enum class CheckSeverity { Must, Warn }

/** 发布前检查的一条结果；[offset] 用于跳回正文，找不到时为 -1。 */
data class PublishIssue(
    val severity: CheckSeverity,
    val chapterId: Long,
    val chapterNumber: Int,
    val kind: String,
    val message: String,
    val offset: Int = -1,
    val quote: String = "",
)

data class AiShare(
    val genChars: Int = 0,
    val editedChars: Int = 0,
    val totalChars: Int = 0,
    val genParagraphs: Int = 0,
    val editedParagraphs: Int = 0,
) {
    val genPercent: Int get() = if (totalChars <= 0) 0 else (genChars * 100 / totalChars).coerceIn(0, 100)
    val editedPercent: Int get() = if (totalChars <= 0) 0 else (editedChars * 100 / totalChars).coerceIn(0, 100)
}

data class PublishReport(
    val issues: List<PublishIssue>,
    val share: AiShare,
    val checkedChapters: Int,
    val checkedWords: Int,
)

/**
 * 发布前本地检查：标题、字数、标点、自定义风险词、AI 标记占比。
 * 不联网、不调用 AI；结果不构成平台合规保证，也不用于规避检测。
 */
object PublishCheck {
    const val MIN_WORDS = 1000
    const val MAX_ISSUES_PER_CHAPTER = 30

    private val repeatedPunct = Regex("""[，。！？；：、,.!?;:]{2,}""")
    private val asciiLineStart = Regex("""(?m)^[ \t]+""")

    /** 单章规则；[bookTitles] 用于检查全书重名。 */
    fun checkChapter(c: ChapterEntity, riskWords: List<String> = emptyList(), minWords: Int = MIN_WORDS, bookTitles: Map<String, Int> = emptyMap()): List<PublishIssue> {
        val out = ArrayList<PublishIssue>()
        fun add(sev: CheckSeverity, kind: String, msg: String, at: Int = -1, quote: String = "") {
            if (out.size < MAX_ISSUES_PER_CHAPTER) out += PublishIssue(sev, c.id, c.number, kind, msg, at, quote)
        }
        val text = c.content
        if (text.isBlank()) { add(CheckSeverity.Must, "empty", "章节正文为空"); return out }
        if (c.title.isBlank()) add(CheckSeverity.Must, "title_empty", "章节标题为空")
        if (c.title.length > 40) add(CheckSeverity.Warn, "title_long", "章节标题超过 40 字")
        if (c.title.isNotBlank() && (bookTitles[c.title.trim()] ?: 0) > 1) add(CheckSeverity.Warn, "title_duplicate", "全书有同名章节")
        if (c.wordCount < minWords) add(CheckSeverity.Warn, "short", "本章 ${c.wordCount} 字，低于设定的 $minWords 字")

        repeatedPunct.findAll(text).take(10).forEach { m ->
            add(CheckSeverity.Warn, "punct", "连续标点", m.range.first, m.value)
        }
        asciiLineStart.findAll(text).take(10).forEach { m ->
            add(CheckSeverity.Warn, "indent", "段首有半角空格", m.range.first, text.substring(m.range.first, (m.range.first + 8).coerceAtMost(text.length)))
        }
        val pairs = listOf('“' to '”', '‘' to '’', '（' to '）', '【' to '】')
        pairs.forEach { (a, b) ->
            val na = text.count { it == a }; val nb = text.count { it == b }
            if (na != nb) add(CheckSeverity.Warn, "quote", "「$a」「$b」数量不一致（$na/$nb）")
        }
        riskWords.filter { it.isNotBlank() }.take(200).forEach { w ->
            var i = text.indexOf(w)
            while (i >= 0 && out.size < MAX_ISSUES_PER_CHAPTER) {
                add(CheckSeverity.Warn, "risk_word", "风险词：$w", i, text.substring(i, (i + w.length + 12).coerceAtMost(text.length)))
                i = text.indexOf(w, i + w.length)
            }
        }
        return out
    }

    /** 合并重叠的 AI 标记，按字符统计 gen / edited。 */
    fun share(text: String, marksJson: String): AiShare {
        val len = text.length
        if (len == 0) return AiShare()
        fun merged(kind: String): List<IntRange> {
            val ranges = parseMarks(marksJson).asSequence()
                .filter { it.k == kind }
                .map { maxOf(0, it.s) to minOf(len, it.e) }
                .filter { it.second > it.first }
                .sortedBy { it.first }
                .toList()
            val out = ArrayList<IntRange>()
            for ((s, e) in ranges) {
                val last = out.lastOrNull()
                if (last != null && s <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, e - 1)
                else out += s until e
            }
            return out
        }
        val gen = merged("gen"); val edited = merged("edited")
        return AiShare(
            genChars = gen.sumOf { it.last - it.first + 1 },
            editedChars = edited.sumOf { it.last - it.first + 1 },
            totalChars = len,
            genParagraphs = gen.size,
            editedParagraphs = edited.size,
        )
    }

    suspend fun run(bookId: Long, chapterId: Long? = null, riskWords: List<String> = emptyList(), minWords: Int = MIN_WORDS): PublishReport {
        val db = AppGraph.db
        val headers = db.chapters().headersOnce(bookId)
        val targets = if (chapterId != null) headers.filter { it.id == chapterId } else headers
        val titles = headers.groupingBy { it.title.trim() }.eachCount()
        val issues = ArrayList<PublishIssue>()
        var gen = 0; var edited = 0; var total = 0; var gParas = 0; var eParas = 0; var checkedWords = 0
        targets.forEach { h ->
            val c = db.chapters().get(h.id) ?: return@forEach
            if (c.deletedAt != 0L) return@forEach
            issues += checkChapter(c, riskWords, minWords, titles)
            val s = share(c.content, c.aiMarks)
            gen += s.genChars; edited += s.editedChars; total += s.totalChars
            gParas += s.genParagraphs; eParas += s.editedParagraphs
            checkedWords += c.wordCount
        }
        return PublishReport(issues, AiShare(gen, edited, total, gParas, eParas), targets.size, checkedWords)
    }

    /** 一行一个词；支持「类别|词」和 # 注释。 */
    fun parseWordList(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringAfterLast('|').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}
