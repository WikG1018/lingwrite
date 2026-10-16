// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/** 后台文风提示词（默认附在所有生成和改写任务后；可在“自然文风”里查看和修改）。 */
const val DEFAULT_STYLE_PROMPT = """避免套话、空泛总结句和排比堆砌；句子长短错开，段落节奏有变化。
对白口语化，符合人物身份，少用书面连接词。
用具体动作、细节、感官写画面，少用抽象形容。
段落末尾不要写感悟式、升华式的总结句。"""

data class TasteFlag(val start: Int, val end: Int, val sentence: String, val reasons: List<String>)

/** 句子切分（保留偏移）。 */
fun sentences(text: String): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    var s = 0
    val enders = setOf('。', '！', '？', '…', '\n', '!', '?')
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch in enders) {
            var e = i + 1
            while (e < text.length && (text[e] in enders || text[e] == '”' || text[e] == '"') && text[e] != '\n') e++
            val seg = text.substring(s, e)
            if (seg.isNotBlank()) out += (s + seg.indexOfFirst { !it.isWhitespace() }) to seg.trim()
            s = e; i = e
        } else i++
    }
    if (s < text.length && text.substring(s).isNotBlank()) out += (s + text.substring(s).indexOfFirst { !it.isWhitespace() }) to text.substring(s).trim()
    return out
}

private val templateRegex = listOf(
    Regex("嘴角(勾起|扬起|微微上扬)") to "模板化表情描写",
    Regex("眼中闪过一[丝抹]") to "模板化眼神描写",
    Regex("仿佛.{1,12}一般") to "“仿佛……一般”比喻",
    Regex("一股.{1,8}涌上心头") to "抽象情绪套话",
    Regex("^(这一刻|此时此刻|那一刻)") to "“这一刻”式煽情开头",
    Regex("(不仅|不只).{1,20}(更|还)") to "“不仅……更……”书面句式",
    Regex("(总而言之|值得一提的是|与此同时|毫无疑问)") to "书面连接词",
    Regex("(心中|内心)(五味杂陈|百感交集|久久不能平静)") to "空泛情绪总结",
)
private val summaryEnd = Regex("(这就是|这便是|或许这就是|也许，?这就是|他(终于)?明白了|她(终于)?明白了|人生|命运|成长)")

/** 本地 AI 味检查：只标出、不改写。作者手写段落同样适用。 */
fun aiTaste(text: String, banned: List<String>): List<TasteFlag> {
    val ss = sentences(text)
    val flags = mutableListOf<TasteFlag>()
    val openings = ss.map { it.second.take(2) }
    ss.forEachIndexed { idx, (start, s) ->
        val r = mutableListOf<String>()
        banned.filter { it.isNotBlank() && s.contains(it) }.forEach { r += "禁用词“$it”" }
        templateRegex.forEach { (re, why) -> if (re.containsMatchIn(s)) r += why }
        // 排比：同一句里 3 个以上相同开头的分句
        val clauses = s.split('，', '；', ',').map { it.trim() }.filter { it.length >= 2 }
        if (clauses.size >= 3 && clauses.groupBy { it.take(2) }.values.any { it.size >= 3 }) r += "排比堆砌"
        // 连续 3 句相同开头
        if (idx >= 2 && openings[idx] == openings[idx - 1] && openings[idx] == openings[idx - 2] && !openings[idx].startsWith("“")) r += "连续三句同样开头"
        // 段末总结句
        val endOfPara = idx == ss.lastIndex || text.substring(start + s.length).trimStart(' ', '　').let { it.startsWith("\n") || it.isEmpty() }
        if (endOfPara && s.length in 8..60 && !s.startsWith("“") && summaryEnd.containsMatchIn(s)) r += "段末感悟式总结"
        if (r.isNotEmpty()) flags += TasteFlag(start, start + s.length, s, r.distinct())
    }
    return flags
}

fun bannedList(s: String) = s.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
