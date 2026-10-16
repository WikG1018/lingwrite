// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.PromptHooks
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.routes.StyleRoute
import com.lingwrite.app.ui.routes.TasteList
import com.lingwrite.app.ui.routes.LocalNav
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private const val SAMPLE = "这一刻，空气仿佛凝固了一般。\n他嘴角勾起一抹冷笑，眼中闪过一丝杀意。\n沈砚把刀横在膝上，没说话。\n他想走，他想留，他想哭，他想笑。\n雨停了。或许这就是成长吧。"

class StyleShotTest : RouteShotBase() {
    @Test fun taste() {
        val f = aiTaste(SAMPLE, bannedList(AppPrefs.DEFAULT_BANNED))
        val reasons = f.flatMap { it.reasons }
        assertTrue(reasons.any { it.contains("仿佛") }); assertTrue(reasons.any { it.contains("表情") }); assertTrue("排比", reasons.contains("排比堆砌"))
        assertTrue(reasons.contains("段末感悟式总结"))
        assertFalse(f.any { it.sentence.startsWith("沈砚把刀") })
        f.forEach { assertEquals(it.sentence, SAMPLE.substring(it.start, it.end)) }
    }

    @Test fun styleHook() = runBlocking {
        AppGraph.db.plan().upsertBook(com.lingwrite.app.data.db.BookPlanEntity(bookId, style = "多用短句。"))
        val s = PromptHooks.style(bookId, AppGraph.prefs.flow.first())
        assertTrue(s.contains("【文风要求】")); assertTrue(s.contains("多用短句")); assertTrue(s.contains("宛如"))
        val off = PromptHooks.style(bookId, AppGraph.prefs.flow.first().copy(styleEnabled = false, bannedWords = ""))
        assertFalse(off.contains("【文风要求】"))
    }

    @Test fun styleShot() = shoot("18-style") { StyleRoute(bookId, LocalNav.current) }
    @Test fun tasteShot() = shoot("19-aitaste") {
        Column(Modifier.fillMaxSize().background(Ling.colors.surface)) {
            LingTopBar("AI 味检查", onBack = {})
            TasteList(bookId, aiTaste(SAMPLE, bannedList(AppPrefs.DEFAULT_BANNED))) {}
        }
    }
}
