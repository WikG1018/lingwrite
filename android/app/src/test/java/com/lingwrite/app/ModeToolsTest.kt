// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ai.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD1：工具条回归测试（docs/v0.2/modes.md §1、§5）。
 * 走的是生产真实路径 `Prompts.toolbarTools`（EditorAi 里用的就是它），不再测只在测试里存在的清单。
 */
class ModeToolsTest {
    @Test fun toolbarNeverOffersSelectionOnlyToolsWithoutSelection() {
        for (mode in listOf("assist", "generate")) {
            val tools = Prompts.toolbarTools(mode, hasSelection = false)
            assertTrue("$mode 无选区时不应出现需要选区的工具: ${tools.map { it.id }}", tools.none { it.needsSelection })
        }
    }

    @Test fun toolbarMatchesModeTable() {
        // 辅助模式无选区只有「给方向」
        assertEquals(listOf("directions"), Prompts.toolbarTools("assist", false).map { it.id })
        // 生成模式无选区：续写 / 起草本章 / 对话 / 描写（modes.md §5）
        assertEquals(listOf("continue", "draft", "dialog", "describe"), Prompts.toolbarTools("generate", false).map { it.id })
        // 有选区时生成模式就是完整选择工具集
        assertEquals(Prompts.selectionTools, Prompts.toolbarTools("generate", true))
        assertEquals(listOf("polish", "deai", "rewrite", "shorten"), Prompts.toolbarTools("assist", true).map { it.id })
    }

    /** 无选区时不能出现空提示词：占位「」在所有可见工具的指令里都不该出现。 */
    @Test fun noEmptyQuotePlaceholderWithoutSelection() {
        for (mode in listOf("assist", "generate")) {
            Prompts.toolbarTools(mode, hasSelection = false).forEach { t ->
                val instruction = t.instruction("", com.lingwrite.app.data.AppPrefs())
                assertTrue("${t.id} 无选区时的指令不该带空「」占位: $instruction", !instruction.contains("「」"))
            }
        }
    }

    @Test fun assistModeHasNoInsertExit() {
        val tools = Prompts.toolbarTools("assist", hasSelection = false)
        assertTrue(tools.isNotEmpty())
        tools.forEach { t ->
            assertEquals("辅助模式下 ${t.label} 不允许 Insert", Prompts.AcceptKind.Copy, Prompts.acceptKind(t.id))
        }
    }

    @Test fun generateModeInserts() {
        val tools = Prompts.toolbarTools("generate", hasSelection = false)
        assertTrue(tools.any { Prompts.acceptKind(it.id) == Prompts.AcceptKind.Insert })
    }
}
