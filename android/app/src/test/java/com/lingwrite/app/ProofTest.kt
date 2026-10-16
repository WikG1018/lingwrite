// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.Proof
import com.lingwrite.app.data.ProofIssue
import com.lingwrite.app.data.IdeaExpand
import com.lingwrite.app.data.IdeaExpansion
import com.lingwrite.app.data.BridgeIdea
import com.lingwrite.app.data.PlanFields
import com.lingwrite.app.data.ShortUpdatePlan
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5：A9 校对的片段定位与 AI 标记规则、A14 灵感扩展往返。 */
class ProofTest {
    @Test fun locateFirstThenLast() {
        val text = "沈砚来了。沈砚走了。沈砚又回来。"
        // 首次出现
        assertEquals(0, Proof.locate(text, "沈砚来了"))
        // 同一片片多次出现：取最近的一次
        assertEquals(text.lastIndexOf("沈砚"), Proof.locate(text, "沈砚", lastAt = 6))
        assertEquals(-1, Proof.locate(text, "不存在的片段"))
        assertEquals(-1, Proof.locate(text, ""))
    }

    @Test fun needsAiMarkRule() {
        // 病句类改动超过 6 个字才算「AI 建议 + 人工修改」
        val long = ProofIssue("这句话的语法结构", "这句话的语法结构是有问题的", "grammar")
        assertTrue(Proof.needsAiMark(long))
        val short = ProofIssue("沈砚笑", "沈砚笑。", "grammar")
        assertFalse(Proof.needsAiMark(short))
        // 错别字 / 地的得 / 标点都不标记
        val typo = ProofIssue("已后", "以后", "typo")
        assertFalse(Proof.needsAiMark(typo))
        val de = ProofIssue("的", "地", "de")
        assertFalse(Proof.needsAiMark(de))
        val punct = ProofIssue("，", "。", "punct")
        assertFalse(Proof.needsAiMark(punct))
    }

    @Test fun proofRoundTrip() {
        val list = listOf(ProofIssue("已后", "以后", "typo", "错别字"), ProofIssue("他", "她", "de", "的地得"))
        assertEquals(list, Proof.parse(com.lingwrite.app.data.LingJson.encodeToString(ListSerializer(ProofIssue.serializer()), list)))
    }

    @Test fun ideaExpansionRoundTrip() {
        val list = listOf(IdeaExpansion("悬疑线", "让捕快查师父旧案"), IdeaExpansion("感情线", "师父留下遗物"), IdeaExpansion("升级线", "断剑认主"))
        val json = com.lingwrite.app.data.LingJson.encodeToString(ListSerializer(IdeaExpansion.serializer()), list)
        assertEquals(list, IdeaExpand.parseExpansions(json))
    }

    @Test fun shortUpdatePlanRoundTrip() {
        val p = ShortUpdatePlan(from = 42, splitA = PlanFields("外门比试", "断剑共鸣", "有人认出剑"),
            splitB = PlanFields("雨夜比试", "雨夜来客", "剑鸣示警"),
            bridges = listOf(BridgeIdea("藏剑峰独处", "补内心戏")))
        val json = com.lingwrite.app.data.LingJson.encodeToString(ShortUpdatePlan.serializer(), p)
        assertEquals(p, com.lingwrite.app.data.LingJson.decodeFromString(ShortUpdatePlan.serializer(), json))
    }
}
