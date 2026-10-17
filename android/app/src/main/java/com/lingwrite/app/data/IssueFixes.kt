// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.PromptContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.data.db.IssueEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一致性问题「给改法」（A6，ai-features §6）：
 * 用最小改动消除前后矛盾，1–2 条改法：改正文 / 改设定 / 补一句解释。
 */
@Serializable
data class IssueFix(val type: String, val before: String, val after: String, val why: String)

object IssueFixes {
    val SYS = "你是连载编辑，帮作者用最小改动消除前后矛盾。不改文风、不扩写。\n" + PromptContract.JSON_ARRAY

    fun prompt(issue: IssueEntity, context: String): String = buildString {
        append("【矛盾】类型 ${kindLabel(issue.kind)}；原文「${issue.quote}」；依据：${issue.basis}；说明：${issue.explain}\n")
        if (issue.note.isNotBlank()) append("【作者补充】").append(issue.note).append('\n')
        append("【原文上下文】\n").append(context)
        append("\n给出 1–2 条最小改动方案。type 只能是：text（改正文，before 必填）/ lore（改设定，说明改哪张卡）/ bridge（补一句解释）。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append(PromptContract.VERBATIM_QUOTE).append('\n')
        append("格式示例：[{\"type\":\"text\",\"before\":\"林砚不过炼气三层\",\"after\":\"林砚虽已筑基，却刻意把气息压在炼气三层\",\"why\":\"补一句解释实力描述\"}]")
    }

    /** 生成改法（1–2 条）；缓存到 issues.fix。 */
    suspend fun generate(i: IssueEntity): List<IssueFix> {
        val c = AppGraph.db.chapters().get(i.chapterId) ?: return emptyList()
        val at = locate(c.content, i.quote).coerceAtLeast(0)
        val ctx = c.content.substring((at - 300).coerceAtLeast(0), (at + i.quote.length + 300).coerceAtMost(c.content.length))
        val raw = AppGraph.ai.complete("check", SYS, prompt(i, ctx), i.bookId, i.chapterId, 800, outputLang = false).getOrThrow()
        val arr = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json
            ?: extractJson(raw) ?: error("模型没有按格式返回")
        val fixes = arr.jsonArray.map { e ->
            val o = e.jsonObject
            IssueFix(o.str("type").ifBlank { "text" }, o.str("before"), o.str("after"), o.str("why"))
        }.filter { it.after.isNotBlank() }.take(2)
        if (fixes.isNotEmpty()) {
            AppGraph.db.checks().updateIssue(i.copy(fix = LingJson.encodeToString(ListSerializer(IssueFix.serializer()), fixes)))
        }
        return fixes
    }

    /** 读缓存里的改法。 */
    fun parseFixes(json: String): List<IssueFix> =
        runCatching { LingJson.decodeFromString(ListSerializer(IssueFix.serializer()), json) }.getOrDefault(emptyList())

    /** 采纳任一改法后把问题标为 fixed。 */
    suspend fun markFixed(i: IssueEntity) = AppGraph.db.checks().updateIssue(i.copy(status = "fixed"))

    /**
     * 「去原文应用」的待确认改法（A6）。生成模式下点「去原文应用」后落到这里，
     * 编辑器顶部出现确认条，作者确认后才写正文并标 edited——AI 绝不自动改正文。
     */
    object PendingFix {
        var chapterId by androidx.compose.runtime.mutableStateOf(0L)
        var issueId by androidx.compose.runtime.mutableStateOf(0L)
        var before by androidx.compose.runtime.mutableStateOf("")
        var after by androidx.compose.runtime.mutableStateOf("")
        val active get() = chapterId != 0L

        fun set(chapterId: Long, issueId: Long, before: String, after: String) {
            this.chapterId = chapterId; this.issueId = issueId; this.before = before; this.after = after
        }

        fun clear() { chapterId = 0L; issueId = 0L; before = ""; after = "" }
    }

    /**
     * 采纳一条改法（A6）：
     * - text（改正文）：生成模式把改法交给编辑器待确认（[PendingFix]），辅助模式只存本章便签；
     * - lore（改设定）/ bridge（补一句）：存本章便签，作者自己去改；
     * 无论哪种，问题状态立刻置 fixed、卡片淡出。
     * 返回需要跳转的章节 id（生成模式的 text 改法），否则 null。
     */
    suspend fun adopt(i: IssueEntity, type: String, after: String, mode: WriteMode): Long? {
        val scopeNote = when (type) {
            "text" -> "【改正文建议】$after"
            "lore" -> "【改设定】$after"
            else -> "【补一句】$after"
        }
        AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = i.chapterId, kind = "fix", text = scopeNote))
        markFixed(i)
        return if (type == "text" && mode == WriteMode.Generate) {
            PendingFix.set(i.chapterId, i.id, i.quote, after)
            i.chapterId
        } else null
    }
}
