// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ImportTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    private fun file(name: String, text: String, charset: Charset = Charsets.UTF_8): Uri {
        val f = File(ctx.cacheDir, name)
        f.writeBytes(text.toByteArray(charset))
        return Uri.fromFile(f)
    }

    private fun docx(name: String, paragraphs: List<String>): Uri {
        val f = File(ctx.cacheDir, name)
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            paragraphs.forEach { p -> append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>") }
            append("</w:body></w:document>")
        }
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        return Uri.fromFile(f)
    }

    @Test fun headingSplitKeepsVolumesTitlesAndPreface() = runBlocking {
        val uri = file("novel.txt", "楔子内容\n\n第一卷 山门\n第一章 入门\n第一次正文\n\n第二章 雨夜\n第二次正文\n\n第二卷 大比\n第三章 擂台\n第三次正文\n")
        val p = Importer.parse(ctx, uri)
        assertEquals("novel", p.bookTitle)
        assertEquals(2, p.volumes.size)
        assertEquals("山门", p.volumes[0].title)
        assertEquals("大比", p.volumes[1].title)
        assertEquals(listOf("序", "入门", "雨夜", "擂台"), p.chapters.map { it.title })
        assertEquals(listOf(0, 0, 0, 1), p.chapters.map { it.volume })
        assertEquals("楔子内容", p.chapters[0].content)
        assertTrue(p.chapters[1].content.contains("第一次正文"))
        assertTrue(p.warnings.isEmpty())
    }

    @Test fun gb18030AndMarkdownAreDecoded() = runBlocking {
        val gb = file("gb.txt", "第一章 开始\n正文甲\n第二章 继续\n正文乙\n", Charset.forName("GB18030"))
        val a = Importer.parse(ctx, gb)
        assertEquals("开始", a.chapters[0].title)
        assertTrue(a.chapters[0].content.contains("正文甲"))
        assertEquals("GB18030", a.encoding)

        val md = file("md.md", "# 第一章 起风\n正文一\n## 第二章 落雨\n正文二\n")
        val b = Importer.parse(ctx, md)
        assertEquals(listOf("起风", "落雨"), b.chapters.map { it.title })
    }

    @Test fun docxParagraphsAreExtracted() = runBlocking {
        val uri = docx("sample.docx", listOf("第一章 初见", "第一段", "第二段", "第二章 再会", "第三段"))
        val p = Importer.parse(ctx, uri)
        assertEquals(listOf("初见", "再会"), p.chapters.map { it.title })
        assertTrue(p.chapters[0].content.contains("第一段"))
        assertTrue(p.chapters[0].content.contains("第二段"))
        assertTrue(p.chapters[1].content.contains("第三段"))
        assertTrue(p.warnings.any { it.contains("Word") })
    }

    @Test fun noHeadingFallsBackToLength() = runBlocking {
        val text = (1..20).joinToString("\n") { "这是第${it}段没有章节标题的正文。" }
        val p = Importer.parse(ctx, file("plain.txt", text), ImportOptions(fallbackLength = 100))
        assertTrue(p.chapters.size > 1)
        assertTrue(p.warnings.any { it.contains("章节标题") })
        assertTrue(p.chapters.all { it.volume == 0 && it.title.isEmpty() })
    }

    @Test fun commitCreatesOnlyImportedChapters() = runBlocking {
        val uri = file("book.txt", "第一章 开始\n正文甲\n第二章 继续\n正文乙\n")
        val p = Importer.parse(ctx, uri)
        val id = Importer.commit(p, "导入测试")
        val chapters = AppGraph.db.chapters().chaptersOnce(id)
        assertEquals(2, chapters.size)
        assertEquals(listOf(1, 2), chapters.map { it.number })
        assertEquals("开始", chapters[0].title)
        assertTrue(chapters[0].content.contains("正文甲"))
        assertEquals(Words.count(chapters[1].content), chapters[1].wordCount)
        assertEquals("导入测试", AppGraph.db.books().get(id)!!.title)
    }

    @Test fun invalidOrEmptyFileIsRejected() = runBlocking {
        val empty = file("empty.txt", "   \n\n")
        assertTrue(runCatching { Importer.parse(ctx, empty) }.isFailure)
    }
}
