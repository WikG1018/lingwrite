// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.data.LingJson
import com.lingwrite.app.data.extractJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 统一的 JSON 解析与重试（F3，ai-features §0.3）：
 * 所有结构化输出先 extractJson；解析失败用“只输出 JSON”的提示重试一次，仍然失败返回 null（调用方进 Error）。
 */
object JsonReply {
    const val RETRY_SUFFIX = "\n\n你上一次的回复不是合法的 JSON。请严格只输出 JSON，不要任何解释、前后缀或代码块标记。"

    /** 解析结果，或需要重试。 */
    sealed interface Result {
        data class Ok(val json: JsonElement) : Result
        data object Retry : Result
    }

    fun parse(raw: String, array: Boolean = true): Result {
        if (raw.isBlank()) return Result.Retry
        val e = extractJson(raw, array) ?: return Result.Retry
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

    /**
     * 真正的「解析失败 → 带「只输出 JSON」提示重发一次」（F3，ai-features §0.3）。
     * 第一次用原提示词；解析失败才追加 [RETRY_SUFFIX] 重发一次；两次都失败返回 error，调用方进 Error 态。
     * 两次请求各自写一条 call_logs（走 AiService），便于作者在「本章 AI 使用记录」里看到。
     */
    suspend fun completeJson(
        task: String,
        system: String,
        user: String,
        bookId: Long,
        chapterId: Long?,
        maxTokens: Int,
        array: Boolean = true,
    ): Attempt {
        val raw1 = runCatching { com.lingwrite.app.AppGraph.ai.complete(task, system, user, bookId, chapterId, maxTokens) }
            .getOrElse { return Attempt("", null, it.message) }
            .getOrElse { return Attempt("", null, it.message) }
        (parse(raw1, array) as? Result.Ok)?.let { return Attempt(raw1, it.json, null) }
        val raw2 = runCatching { com.lingwrite.app.AppGraph.ai.complete(task, system, user + RETRY_SUFFIX, bookId, chapterId, maxTokens) }
            .getOrNull()?.getOrNull()
        val json2 = raw2?.let { raw -> (parse(raw, array) as? Result.Ok)?.json }
        return if (json2 != null) Attempt(raw2, json2, null)
        else Attempt(raw2 ?: raw1, null, "模型两次都没有按格式返回")
    }
}
