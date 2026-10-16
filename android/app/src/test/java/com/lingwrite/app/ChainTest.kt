// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ChainPrompts
import com.lingwrite.app.data.ChainState
import com.lingwrite.app.data.SynopsisFormat
import com.lingwrite.app.data.parseBeats
import com.lingwrite.app.data.encodeBeats
import com.lingwrite.app.data.Beat
import com.lingwrite.app.data.db.BookPlanEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1/C2：链路数据结构与提示词解析（docs/v0.2/outline-chain.md）。 */
class ChainTest {
    @Test fun chainStateRoundTrip() {
        val s = ChainState(cands = mapOf(1 to "[{\"a\":1}]"), upstream = mapOf(2 to 123, 3 to 456), idea = "断剑", genre = "仙侠", platform = "起点")
        val back = ChainState.parse(s.encode())
        assertEquals(s, back)
        assertEquals("[{\"a\":1}]", back.cands[1])
        assertEquals(123, back.upstream[2])
    }

    @Test fun chainStateToleratesGarbage() {
        assertEquals(ChainState(), ChainState.parse("not json"))
        assertEquals(ChainState(), ChainState.parse(""))
    }

    @Test fun synopsisFormatRoundTrip() {
        val stages = listOf(
            SynopsisFormat.Stage("外门", "站稳脚跟", "旧案线索出现", "被卷入内门之争"),
            SynopsisFormat.Stage("内门", "查旧案", "师门阻挠", "真相只差一步"),
        )
        val text = SynopsisFormat.build("断剑少年查旧案", stages, "旧案大白，宗门重建")
        val (spine, back, ending) = SynopsisFormat.parse(text)
        assertEquals("断剑少年查旧案", spine)
        assertEquals(2, back.size)
        assertEquals("外门", back[0].name)
        assertEquals("站稳脚跟", back[0].goal)
        assertEquals("旧案大白，宗门重建", ending)
    }

    @Test fun pitchesParse() {
        val raw = "好的：\n```json\n[{\"title\":\"问剑\",\"logline\":\"断剑少年入青云\",\"hook\":\"旧案一线牵\"},{\"title\":\"青云\",\"logline\":\"另一个\",\"hook\":\"爽点\"},{\"title\":\"C\",\"logline\":\"d\",\"hook\":\"e\"}]\n```"
        val r = ChainPrompts.parsePitches(raw)
        assertEquals(3, r.size)
        assertEquals("问剑", r[0].first)
        assertEquals("断剑少年入青云", r[0].second)
    }

    @Test fun volumesParseStripsNumber() {
        val raw = """[{"name":"第一卷 山门","goal":"g","climax":"c","outline":"o","chapters":60},{"name":"第2卷：大比","goal":"","climax":"","outline":"","chapters":50}]"""
        val r = ChainPrompts.parseVolumes(raw)
        assertEquals("山门", r[0].name)
        assertEquals("大比", r[1].name)
        assertEquals(60, r[0].chapters)
    }

    @Test fun synopsisParse() {
        val raw = """{"spine":"主线一句","stages":[{"name":"一","goal":"g","conflict":"c","end":"e"}],"ending":"结局"}"""
        val r = ChainPrompts.parseSynopsis(raw)
        assertNotNull(r)
        assertEquals("主线一句", r!!.first)
        assertEquals(1, r.second.size)
        assertEquals("结局", r.third)
    }

    @Test fun beatsRoundTrip() {
        val beats = listOf(Beat("抛出钩子"), Beat("对峙升级", done = true))
        assertEquals(beats, parseBeats(encodeBeats(beats)))
    }

    @Test fun upstreamContainsKeyFields() {
        val u = ChainPrompts.upstream(BookPlanEntity(bookId = 1, idea = "断剑", logline = "梗概", sellingPoints = "[\"爽点1\"]", synopsis = "总纲文本"), genre = "仙侠")
        assertTrue(u.contains("断剑"))
        assertTrue(u.contains("梗概"))
        assertTrue(u.contains("爽点1"))
        assertTrue(u.contains("总纲文本"))
        assertTrue(u.contains("仙侠"))
    }
}
