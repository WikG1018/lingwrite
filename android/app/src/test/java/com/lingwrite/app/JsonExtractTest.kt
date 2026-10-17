// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ai.JsonExtract
import com.lingwrite.app.data.ai.JsonReply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化输出解析：真实模型回复里常见的代码块、解释文字、括号干扰、截断。纯 JVM 测试。 */
class JsonExtractTest {
    @Test fun plainArray() {
        val v = JsonExtract.extract("""[{"a":1},{"a":2}]""")
        assertEquals(2, (v as JsonArray).size)
    }

    @Test fun codeFenceAndProse() {
        val raw = "好的，下面是章纲：\n```json\n[{\"title\":\"第一章\"}]\n```\n希望有帮助。"
        val v = JsonExtract.extract(raw) as JsonArray
        assertEquals("第一章", v[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun bracketsInsideStringsAreNotStructure() {
        val raw = """[{"quote":"第[1]章，他说：} 然后离开","note":"a{b}c"}]"""
        val v = JsonExtract.extract(raw) as JsonArray
        assertEquals("第[1]章，他说：} 然后离开", v[0].jsonObject["quote"]!!.jsonPrimitive.content)
    }

    @Test fun proseBracketNumberDoesNotWin() {
        // 解释文字里先出现 [1]，真正的 JSON 在后面；应取更长的真正结果
        val raw = "第[1]章参考：\n[{\"event\":\"冲突\",\"hook\":\"悬念\"}]"
        val v = JsonExtract.extract(raw) as JsonArray
        assertEquals("冲突", v[0].jsonObject["event"]!!.jsonPrimitive.content)
    }

    @Test fun objectWithNestedArray() {
        val raw = """{"synopsis":"总纲","beats":["a","b"]}"""
        val v = JsonExtract.extract(raw, array = false) as JsonObject
        assertEquals(2, v["beats"]!!.jsonArray.size)
    }

    @Test fun truncatedArrayRepaired() {
        val raw = """[{"a":1},{"b":2"""
        val v = JsonExtract.extract(raw) as JsonArray
        assertTrue(v.size >= 1)
    }

    @Test fun truncatedMidStringRepaired() {
        val raw = """[{"quote":"话还没说完"""
        val v = JsonExtract.extract(raw) as JsonArray
        assertTrue(v[0].jsonObject["quote"]!!.jsonPrimitive.content.startsWith("话还没说完"))
    }

    @Test fun rawNewlineInsideStringTolerated() {
        val raw = "[{\"text\":\"第一行\n第二行\"}]"
        val v = JsonExtract.extract(raw) as JsonArray
        assertEquals("第一行\n第二行", v[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun typeMismatchIsRejected() {
        assertNotNull(JsonExtract.extract("""{"a":1}""", array = false))
        assertNull(JsonExtract.extract("""{"a":1}""", array = true))
        assertNull(JsonExtract.extract("""[{"a":1}]""", array = false))
    }

    @Test fun noJsonReturnsNull() {
        assertNull(JsonExtract.extract("模型今天不想工作。"))
    }

    @Test fun jsonReplyParseChecksType() {
        assertTrue(JsonReply.parse("""[{"a":1}]""") is JsonReply.Result.Ok)
        assertTrue(JsonReply.parse("""{"a":1}""") is JsonReply.Result.Retry)
        assertTrue(JsonReply.parse("""{"a":1}""", array = false) is JsonReply.Result.Ok)
        assertTrue(JsonReply.parse("") is JsonReply.Result.Retry)
    }

    @Test fun truncationDetection() {
        assertTrue(JsonReply.looksTruncated("""[{"a":1}""", array = true))
        assertTrue(!JsonReply.looksTruncated("""[{"a":1}]""", array = true))
        assertTrue(!JsonReply.looksTruncated("", array = true))
    }
}
