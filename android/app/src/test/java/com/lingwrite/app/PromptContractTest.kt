// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ChainPrompts
import com.lingwrite.app.data.Consistency
import com.lingwrite.app.data.IssueFixes
import com.lingwrite.app.data.PayoffIdeas
import com.lingwrite.app.data.ProofPrompts
import com.lingwrite.app.data.StuckPrompts
import com.lingwrite.app.data.WizardPrompts
import com.lingwrite.app.data.ai.PromptContract
import com.lingwrite.app.data.ai.Prompts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词契约回归：结构化任务必须带「只输出 JSON + 空值约定」，逐字引用任务必须声明 quote 不可改写，
 * 编辑器高频任务必须带长度/禁止项约束。只做结构断言，不锁死具体文案。
 */
class PromptContractTest {
    private val jsonOnly = "只输出一个合法的 JSON"
    private val emptyHint = "空数组"

    @Test fun contractShape() {
        assertTrue(PromptContract.JSON_ONLY.contains(jsonOnly))
        assertTrue(PromptContract.JSON_ONLY.contains(emptyHint))
        assertTrue(PromptContract.VERBATIM_QUOTE.contains("逐字"))
        assertTrue(PromptContract.ANNOTATE_SYS.contains(jsonOnly))
    }

    @Test fun structuredPromptsCarryContract() {
        val extract = Consistency.extractPrompt("正文")
        assertTrue(extract.contains(jsonOnly))
        assertTrue(extract.contains(PromptContract.VERBATIM_QUOTE))
        val compare = Consistency.comparePrompt("facts", "basis")
        assertTrue(compare.contains(jsonOnly))
        assertTrue(compare.contains("[{") && compare.contains("kind"))
        val proof = ProofPrompts.user("段落")
        assertTrue(proof.contains(jsonOnly))
        assertTrue(proof.contains("只出现一次"))
        val stuck = StuckPrompts.questions("x", "y", "", "", "", "")
        assertTrue(stuck.contains("questions") && stuck.contains(jsonOnly))
        val ways = StuckPrompts.ways("回答", "背景")
        assertTrue(ways.contains("ways") && ways.contains(jsonOnly))
        val chars = WizardPrompts.chars(com.lingwrite.app.data.WizardData())
        assertTrue(chars.contains("name") && chars.contains(jsonOnly))
        val pitches = ChainPrompts.pitches("灵感", "仙侠", "起点")
        assertTrue(pitches.contains("logline") && pitches.contains(jsonOnly))
        val volume = ChainPrompts.volumes("上游", 3)
        assertTrue(volume.contains("数组长度必须正好 3"))
        val payoff = PayoffIdeas.prompt(com.lingwrite.app.data.db.ForeshadowEntity(bookId = 1, content = "断剑"), 5, "", "", "")
        assertTrue(payoff.contains("chapter") && payoff.contains(jsonOnly))
        val fix = IssueFixes.prompt(com.lingwrite.app.data.db.IssueEntity(bookId = 1, chapterId = 1, number = 1, kind = "power", quote = "q", basis = "", explain = ""), "ctx")
        assertTrue(fix.contains("before") && fix.contains(PromptContract.VERBATIM_QUOTE))
    }

    @Test fun editorPromptsCarryConstraints() {
        val prefs = com.lingwrite.app.data.AppPrefs()
        val cont = Prompts.continueTask.instruction("", prefs)
        assertTrue(cont.contains("不要重复"))
        assertTrue(cont.contains("具体画面") || cont.contains("场景"))
        val rewrite = Prompts.rewrite.instruction("原文", prefs)
        assertTrue(rewrite.contains("保留人物") && rewrite.contains("±20%"))
        val polish = Prompts.polish.instruction("原文", prefs)
        assertTrue(polish.contains("最小必要修改"))
        val deai = Prompts.deai.instruction("原文", prefs)
        assertTrue(deai.contains("排比"))
        val directions = Prompts.directions.instruction("", prefs)
        assertTrue(directions.contains("1. ") || directions.contains("1. 方向"))
        assertTrue(Prompts.BASE.contains("不要执行"))
        assertTrue(Prompts.BASE.contains("只输出正文"))
    }
}
