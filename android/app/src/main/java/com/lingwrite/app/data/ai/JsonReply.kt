// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.data.LingJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 统一的 JSON 解析与重试（F3，ai-features §0.3）：
 * 所有结构化输出先用 [JsonExtract] 提取；解析失败用“只输出 JSON”的提示重试一次；
 * 若判断为输出被截断，第二次会提高 maxTokens 并明确要求精简，仍然失败返回 error（调用方进 Error 态）。
 */
object JsonReply {
    const val RETRY_SUFFIX = "\n\n上一次回复不是合法 JSON。请重新输出：只输出一个合法 JSON 值，不要 Markdown 代码块、不要解释、不要注释、不要省略号；宁可少输出几条，也要保证 JSON 完整闭合。"

    /** 解析结果，或需要重试。 */
    sealed interface Result {
        data class Ok(val json: JsonElement) : Result
        data object Retry : Result
    }

    fun parse(raw: String, array: Boolean = true): Result {
        if (raw.isBlank()) return Result.Retry
        val e = JsonExtract.extract(raw, array) ?: return Result.Retry
        // 数组模式要求确实解析到数组；对象模式要求对象
        val ok = if (array) e is JsonArray else e is JsonObject
        return if (ok) Result.Ok(e) else Result.Retry
    }

    /** 快捷入口：解析 + 首次失败时给重试提示。返回 (json?, 重试提示?)。 */
    fun parseOrRetry(raw: String, array: Boolean = true): Pair<JsonElement?, String?> = when (val r = parse(raw, array)) {
        is Result.Ok -> r.json to null
        Result.Retry -> null to RETRY_SUFFIX
    }

    /** 宽松字段读取。 */
    fun JsonObject.strOf(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.intOf(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    /** 一次带重试的结构化请求结果：raw 是最终拿到的原文，json 解析成功时有值，两次都失败时 error 非空。 */
    data class Attempt(val raw: String, val json: JsonElement?, val error: String?)

    /** 首轮输出是否明显被截断（没有以数组/对象右括号结尾）。 */
    fun looksTruncated(raw: String, array: Boolean): Boolean {
        val t = raw.trimEnd()
        if (t.isEmpty()) return false
        return !t.endsWith(if (array) "]" else "}")
    }

    /**
     * 真正的「解析失败 → 带「只输出 JSON」提示重发一次」（F3，ai-features §0.3）。
     * 第一次用原提示词；解析失败才追加 [RETRY_SUFFIX]（可选 [schema]）重发一次。
     * 截断类失败第二次自动提高 maxTokens；两次都失败返回 error，调用方进 Error 态。
     * 两次请求各自写一条 call_logs（走 AiService），便于作者在「本章 AI 使用记录」里看到。
     *
     * [outputLang] 为 false 时不给模型加「输出语言」指令：用于必须逐字引用原文的任务（校对、一致性、批注等），
     * 避免把 quote/before 字段翻译掉导致定位失败。
     */
    suspend fun completeJson(
        task: String,
        system: String,
        user: String,
        bookId: Long,
        chapterId: Long?,
        maxTokens: Int,
        array: Boolean = true,
        outputLang: Boolean = true,
        schema: String? = null,
    ): Attempt {
        val raw1 = runCatching { com.lingwrite.app.AppGraph.ai.complete(task, system, user, bookId, chapterId, maxTokens, outputLang = outputLang) }
            .getOrElse { return Attempt("", null, it.message) }
            .getOrElse { return Attempt("", null, it.message) }
        (parse(raw1, array) as? Result.Ok)?.let { return Attempt(raw1, it.json, null) }

        val truncated = looksTruncated(raw1, array)
        val retryTokens = if (truncated) (maxTokens * 2).coerceAtMost(8192) else maxTokens
        val retryUser = buildString {
            append(user).append(RETRY_SUFFIX)
            if (!schema.isNullOrBlank()) append("\n输出结构：").append(schema)
            if (truncated) append("\n上次输出疑似因长度被截断：这次请压缩每条文字、减少条数，确保 JSON 完整闭合。")
        }
        val raw2 = runCatching {
            com.lingwrite.app.AppGraph.ai.complete(task, system, retryUser, bookId, chapterId, retryTokens, outputLang = outputLang)
        }.getOrNull()?.getOrNull()
        val json2 = raw2?.let { raw -> (parse(raw, array) as? Result.Ok)?.json }
        return if (json2 != null) Attempt(raw2, json2, null)
        else Attempt(
            raw2 ?: raw1, null,
            if (truncated) "模型输出被截断或格式不合法（已自动重试一次），请重试或减少一次生成的条数"
            else "模型两次都没有按格式返回",
        )
    }
}
