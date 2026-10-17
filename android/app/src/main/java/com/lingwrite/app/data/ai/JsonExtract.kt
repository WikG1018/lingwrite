// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.data.LingJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 从模型回复里提取 JSON。
 *
 * 比"首个 [ 到末个 ]"更可靠：
 * - 字符串/转义感知，不会把正文、批注、Markdown 链接里的括号当成结构；
 * - 容忍 ```json 代码块与前后解释文字；
 * - 若有多个候选（例如解释文字里出现"第[1]章"），取**最长的**那个完整 JSON，而不是第一个碰上的小片段；
 * - 输出被 maxTokens 截断时，尝试补全字符串与括号、或退到最后一个完整元素，尽量救回前几条。
 */
object JsonExtract {
    private const val MAX_START_TRIES = 120
    private const val MAX_REPAIR_TRIM = 160

    fun extract(raw: String, array: Boolean = true): JsonElement? {
        if (raw.isBlank()) return null
        val opener = if (array) '[' else '{'
        // 第一遍：只找完整闭合的 JSON，取最长的候选（避免把正文里的 [1] 当成结果）
        var best: Pair<Int, JsonElement>? = null
        var starts = 0
        var i = 0
        while (i < raw.length && starts < MAX_START_TRIES) {
            if (raw[i] == opener) {
                starts++
                scanComplete(raw, i)?.let { end ->
                    parse(raw.substring(i, end))?.let { v ->
                        if (matches(v, array) && (best == null || end - i > best!!.first)) best = (end - i) to v
                    }
                }
            }
            i++
        }
        best?.let { return it.second }

        // 第二遍：完整闭合的都没有 → 大概率被截断，逐候选尝试修复，仍取最长的
        var repaired: Pair<Int, JsonElement>? = null
        starts = 0
        i = 0
        while (i < raw.length && starts < MAX_START_TRIES) {
            if (raw[i] == opener) {
                starts++
                repair(raw.substring(i))?.let { (value, len) ->
                    if (matches(value, array) && (repaired == null || len > repaired!!.first)) repaired = len to value
                }
            }
            i++
        }
        return repaired?.second
    }

    private fun parse(text: String): JsonElement? = runCatching { LingJson.parseToJsonElement(text.trim()) }.getOrNull()

    private fun matches(v: JsonElement, array: Boolean): Boolean = if (array) v is JsonArray else v is JsonObject

    /**
     * 从 [start] 的括号开始做结构扫描。字符串外的括号按栈配对，遇到字符串里的内容跳过。
     * 返回匹配的右括号下标 + 1；不完整（被截断/括号不配对）返回 null。
     */
    private fun scanComplete(raw: String, start: Int): Int? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> stack.addLast(c)
                    ']', '}' -> {
                        val open = stack.removeLastOrNull() ?: return null
                        if ((open == '[' && c != ']') || (open == '{' && c != '}')) return null
                        if (stack.isEmpty()) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /** 被截断的输出：先补齐引号/括号；不行就从尾部逐字回退，直到能解析。返回 (值, 原始片段长度)。 */
    private fun repair(fragment: String): Pair<JsonElement, Int>? {
        val escaped = escapeRawNewlines(fragment)
        closeOpen(escaped)?.let { fixed -> parse(fixed)?.let { return it to fragment.length } }
        for (cut in 1..minOf(MAX_REPAIR_TRIM, escaped.length - 1)) {
            val head = escaped.dropLast(cut)
            closeOpen(head)?.let { fixed -> parse(fixed)?.let { return it to (fragment.length - cut) } }
        }
        return null
    }

    /** JSON 字符串里不允许裸换行/制表符；模型偶尔会写出来，这里转义掉再试。 */
    private fun escapeRawNewlines(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var inString = false
        var escaped = false
        for (c in s) {
            if (inString) {
                when {
                    escaped -> { sb.append(c); escaped = false }
                    c == '\\' -> { sb.append(c); escaped = true }
                    c == '"' -> { sb.append(c); inString = false }
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            } else {
                if (c == '"') inString = true
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 补齐未闭合的字符串与括号；括号不配对（多/错）时返回 null。 */
    private fun closeOpen(fragment: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in fragment) {
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> stack.addLast(c)
                    ']', '}' -> {
                        val open = stack.removeLastOrNull() ?: return null
                        if ((open == '[' && c != ']') || (open == '{' && c != '}')) return null
                    }
                }
            }
        }
        val sb = StringBuilder(fragment)
        if (inString) sb.append('"')
        // 末尾可能停在 "," / ":" / 半个键值上，直接闭括号会失败；由外层逐字回退处理。
        while (stack.isNotEmpty()) {
            sb.append(if (stack.removeLast() == '[') ']' else '}')
        }
        return sb.toString()
    }
}
