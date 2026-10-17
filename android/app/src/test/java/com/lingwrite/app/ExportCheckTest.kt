// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.ChapterEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ExportCheckTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun exportTemplateTokensAndPresets() {
        val p = exportPresetById("web")
        assertEquals("第12章 雨夜", renderExportTemplate(p.chapterTitle, 12, "十二", "雨夜", "书"))
        assertEquals("第一卷 山门", renderExportTemplate(p.volumeTitle, 1, "一", "山门", "书"))
        assertEquals("第12章", renderExportTemplate("第{n}章 {title}", 12, "十二", "", "书"))
        assertEquals(6, ExportPresets.size)
        assertTrue(ExportPresets.all { it.name.isNotBlank() })
    }

    @Test fun streamExportMatchesRender() = runBlocking {
        val id = AppGraph.books.createBook("导出测试")
        val ch = AppGraph.db.chapters().headersOnce(id).single()
        AppGraph.books.saveContent(ch.id, "第一段。\n\n第二段。")
        val opts = Exporter.Options(preset = exportPresetById("fanqie"))
        val rendered = Exporter.render(id, opts)
        val bytes = ByteArrayOutputStream().also { Exporter.writeToStream(id, opts, it) }.toByteArray()
        assertEquals(rendered, bytes.toString(Charsets.UTF_8))
        assertTrue(rendered.contains("第一段。"))
        assertTrue(rendered.contains("第1章"))
        assertTrue(Exporter.renderPreview(id, opts, 12).length <= 12)
    }

    @Test fun publishCheckFindsTitlePunctuationQuotesAndRiskWords() {
        val text = "“没有闭合的引号。\n  段首空格。\n连续标点！！\n这里有敏感词。" + "正文。".repeat(300)
        val ch = ChapterEntity(id = 1, bookId = 1, volumeId = 1, number = 1, title = "", content = text, wordCount = Words.count(text))
        val issues = PublishCheck.checkChapter(ch, riskWords = listOf("敏感词"), minWords = 1000)
        assertTrue(issues.any { it.kind == "title_empty" && it.severity == CheckSeverity.Must })
        assertTrue(issues.any { it.kind == "punct" })
        assertTrue(issues.any { it.kind == "quote" })
        assertTrue(issues.any { it.kind == "indent" })
        assertTrue(issues.any { it.kind == "risk_word" })
        assertTrue(issues.any { it.kind == "short" })
    }

    @Test fun aiShareMergesOverlapsAndClamps() {
        val text = "0123456789"
        val marks = """[{"s":0,"e":5,"k":"gen"},{"s":3,"e":8,"k":"gen"},{"s":15,"e":20,"k":"gen"},{"s":8,"e":10,"k":"edited"}]"""
        val s = PublishCheck.share(text, marks)
        assertEquals(8, s.genChars)
        assertEquals(2, s.editedChars)
        assertEquals(10, s.totalChars)
        assertEquals(1, s.genParagraphs)
        assertEquals(1, s.editedParagraphs)
        assertEquals(80, s.genPercent)
    }

    @Test fun wordListParsing() {
        val list = PublishCheck.parseWordList("# 备注\n平台禁用|黄赌毒\n\n敏感词\n平台禁用|黄赌毒\n")
        assertEquals(listOf("黄赌毒", "敏感词"), list)
    }

    @Test fun editorJumpOnlyConsumesMatchingChapter() {
        EditorJump.clear()
        EditorJump.put(7, 42)
        assertNull(EditorJump.consume(8))
        assertEquals(42, EditorJump.consume(7))
        assertNull(EditorJump.consume(7))
    }
}
