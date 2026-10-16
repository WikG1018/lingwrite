// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ai.JsonReply
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3：结构化输出的 JSON 解析与重试提示。 */
class JsonReplyTest {
    @Test fun parsesPlainArray() {
        val r = JsonReply.parse("""[{"name":"沈砚"},{"name":"林晚"}]""")
        assertTrue(r is JsonReply.Result.Ok)
        assertEquals(2, (r as JsonReply.Result.Ok).json.jsonArray.size)
    }

    @Test fun toleratesWrappingText() {
        val r = JsonReply.parse("好的，这是结果：\n```json\n[{\"a\":1}]\n```\n希望有帮助")
        assertTrue(r is JsonReply.Result.Ok)
    }

    @Test fun badJsonAsksRetryOnce() {
        // 没有数组括号 → 重试
        assertTrue(JsonReply.parse("抱歉，我只输出文字。") is JsonReply.Result.Retry)
        assertTrue(JsonReply.parse("") is JsonReply.Result.Retry)
        // 对象模式收到数组 → 重试
        assertTrue(JsonReply.parse("[1,2]", array = false) is JsonReply.Result.Retry)
        // 重试后给出“只输出 JSON”的提示，且提示确实能让第二次成功
        val (json, retry) = JsonReply.parseOrRetry("前缀 [{\"name\": \"v\"}] 后缀")
        assertNotNull(json)
        assertEquals(null, retry)
        val (bad, retryHint) = JsonReply.parseOrRetry("不是 JSON")
        assertEquals(null, bad)
        assertTrue(retryHint!!.contains("只输出 JSON"))
    }
}
