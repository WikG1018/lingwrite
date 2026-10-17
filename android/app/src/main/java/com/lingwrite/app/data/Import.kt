// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.ChapterEntity
import com.lingwrite.app.data.db.VolumeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** 导入拆分方式。 */
enum class ImportSplit { Heading, Length, Single }

data class ImportOptions(
    val split: ImportSplit = ImportSplit.Heading,
    /** 按长度拆分时每章的目标字数。 */
    val fallbackLength: Int = 3000,
    /** 指定编码；null = 自动检测。 */
    val encoding: Charset? = null,
)

data class ImportVolume(val title: String)

data class ImportChapter(val title: String, val volume: Int, val content: String)

data class ImportPreview(
    val bookTitle: String,
    val volumes: List<ImportVolume>,
    val chapters: List<ImportChapter>,
    val warnings: List<String>,
    val encoding: String,
    val inputBytes: Int,
    val totalWords: Int = chapters.sumOf { Words.count(it.content) },
)

/**
 * 旧稿导入：TXT / Markdown / DOCX → 作品 + 分卷 + 章节。
 *
 * 只在本地解析，不上传文件；预览阶段不写库，确认后才在一个事务里落库。
 */
object Importer {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_CHAPTERS = 5000
    const val MAX_HEADING = 80

    private val volumeRe = Regex("""^第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*卷(?:\s*[·:：\-—]?\s*.*)?$""")
    private val chapterRe = Regex("""^第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章回节集部篇](?:\s*[·:：\-—]?\s*.*)?$""")
    private val englishRe = Regex("""^Chapter\s+\d+\b.*$""", RegexOption.IGNORE_CASE)
    private val markdownRe = Regex("""^#{1,3}\s+\S.*$""")

    suspend fun parse(ctx: Context, uri: Uri, options: ImportOptions = ImportOptions()): ImportPreview = withContext(Dispatchers.IO) {
        val bytes = readLimited(ctx, uri)
        val name = displayName(ctx, uri).ifBlank { "导入作品" }
        val lower = name.lowercase()
        val isDocx = lower.endsWith(".docx")
        val decoded = if (isDocx) DocxText.extract(bytes) else decode(bytes, options.encoding)
        val raw = decoded.text.trim()
        require(raw.isNotBlank()) { "文件里没有可导入的正文" }
        val baseTitle = name.substringBeforeLast('.').ifBlank { "导入作品" }
        val warnings = mutableListOf<String>()
        if (decoded.fellBack) warnings += "编码不是 UTF-8，已按 ${decoded.charset} 读取；如果出现乱码，请重试并指定编码。"
        if (isDocx) warnings += "Word 只导入正文段落；图片、批注和脚注不会导入。"

        var preview = split(raw, baseTitle, options, warnings)
            .copy(encoding = decoded.charset.name(), inputBytes = bytes.size)
        if (preview.chapters.isEmpty()) {
            preview = ImportPreview(baseTitle, listOf(ImportVolume("")),
                listOf(ImportChapter("", 0, raw)), warnings + "没有识别到章节，整篇作为一章导入。", decoded.charset.name(), bytes.size)
        }
        if (preview.chapters.size > MAX_CHAPTERS) error("章节超过 $MAX_CHAPTERS 章，建议先拆分文件再导入")
        preview.copy(warnings = preview.warnings.distinct())
    }

    suspend fun commit(preview: ImportPreview, title: String): Long = withContext(Dispatchers.IO) {
        val db = AppGraph.db
        db.withTransaction {
            val bookId = db.books().insert(BookEntity(
                title = title.trim().ifBlank { preview.bookTitle.ifBlank { "导入作品" } },
                status = "draft",
            ))
            val vols = preview.volumes.ifEmpty { listOf(ImportVolume("")) }
            val volIds = vols.mapIndexed { i, v -> db.chapters().insertVolume(VolumeEntity(bookId = bookId, title = v.title, sort = i)) }
            val chapters = preview.chapters.mapIndexed { i, c ->
                val text = c.content.trim('\n', ' ', '\u3000')
                ChapterEntity(
                    bookId = bookId,
                    volumeId = volIds.getOrElse(c.volume.coerceIn(0, volIds.lastIndex)) { volIds.first() },
                    number = i + 1,
                    title = c.title.trim(),
                    content = text,
                    wordCount = Words.count(text),
                    state = "draft",
                )
            }
            db.chapters().insertAll(chapters)
            db.books().touch(bookId)
            bookId
        }
    }

    private fun split(text: String, baseTitle: String, options: ImportOptions, warnings: MutableList<String>): ImportPreview {
        if (options.split == ImportSplit.Single) {
            return ImportPreview(baseTitle, listOf(ImportVolume("")), listOf(ImportChapter("", 0, text)), warnings, "UTF-8", text.toByteArray().size)
        }
        if (options.split == ImportSplit.Length) return byLength(text, baseTitle, options.fallbackLength, warnings)

        val volumes = mutableListOf<ImportVolume>()
        val chapters = mutableListOf<ImportChapter>()
        var volume = -1
        var title = ""
        var sb = StringBuilder()
        var preface = StringBuilder()
        var seenChapter = false
        fun ensureVolume(): Int {
            if (volumes.isEmpty()) volumes += ImportVolume("")
            return volume.coerceIn(0, volumes.lastIndex)
        }
        fun flush() {
            if (sb.isNotBlank()) {
                chapters += ImportChapter(Naming.stripChapter(title), ensureVolume(), sb.toString().trim('\n'))
                sb = StringBuilder()
            }
        }
        for (line in text.lines()) {
            val t = line.trim()
            if (isVolumeHeading(t)) {
                flush()
                volumes += ImportVolume(Naming.stripVolume(t))
                volume = volumes.lastIndex
                title = ""
                continue
            }
            if (isChapterHeading(t)) {
                flush()
                seenChapter = true
                title = headingTitle(t)
                continue
            }
            if (!seenChapter && chapters.isEmpty()) preface.appendLine(line) else sb.appendLine(line)
        }
        flush()
        if (!seenChapter || chapters.size < 2) {
            warnings += "没有识别到至少两个章节标题，已改为按长度拆分；可在预览里切换拆分方式。"
            return byLength(text, baseTitle, options.fallbackLength, warnings)
        }
        if (preface.isNotBlank()) {
            if (volumes.isEmpty()) volumes += ImportVolume("")
            chapters.add(0, ImportChapter("序", 0, preface.toString().trim('\n')))
        }
        return ImportPreview(baseTitle, volumes, chapters, warnings, "UTF-8", text.toByteArray().size)
    }

    private fun byLength(text: String, baseTitle: String, length: Int, warnings: MutableList<String>): ImportPreview {
        val target = length.coerceIn(500, 20_000)
        val chapters = mutableListOf<ImportChapter>()
        var sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) { chapters += ImportChapter("", 0, sb.toString().trim('\n')); sb = StringBuilder() }
        }
        for (line in text.lines()) {
            sb.appendLine(line)
            if (sb.length >= target) flush()
        }
        flush()
        if (chapters.isEmpty()) warnings += "文件没有正文。"
        else warnings += "按每章约 $target 字拆分，共 ${chapters.size} 章；可在预览里切换为「不分章」。"
        return ImportPreview(baseTitle, listOf(ImportVolume("")), chapters, warnings, "UTF-8", text.toByteArray().size)
    }

    private fun isVolumeHeading(t: String): Boolean =
        t.length in 2..MAX_HEADING && volumeRe.matches(t) && !t.endsWithPunctuation()

    private fun isChapterHeading(t: String): Boolean =
        t.length in 2..MAX_HEADING && !t.endsWithPunctuation() &&
            (chapterRe.matches(t) || englishRe.matches(t) || markdownRe.matches(t))

    private fun String.endsWithPunctuation(): Boolean {
        val ch = lastOrNull() ?: return false
        return ch in setOf('。', '！', '？', '，', '；', '、', '.', '!', '?', ',', ';')
    }

    private fun headingTitle(t: String): String = when {
        markdownRe.matches(t) -> t.trimStart('#').trim()
        else -> t
    }

    private fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault(uri.lastPathSegment.orEmpty())

    private fun readLimited(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("无法读取文件")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_BYTES) { "文件超过 20MB，请先拆分再导入" }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private data class Decoded(val text: String, val charset: Charset, val fellBack: Boolean)

    private fun decode(bytes: ByteArray, forced: Charset?): Decoded {
        forced?.let { return Decoded(String(bytes, it).removePrefix("\uFEFF"), it, false) }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), Charsets.UTF_8, false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF"), Charsets.UTF_16LE, false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF"), Charsets.UTF_16BE, false)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return Decoded(utf8, Charsets.UTF_8, false)
        val gb = Charset.forName("GB18030")
        return Decoded(String(bytes, gb), gb, true)
    }
}

/** 最小 DOCX 正文提取：只读 word/document.xml，不引入第三方库。 */
private object DocxText {
    fun extract(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= Importer.MAX_BYTES) { "Word 文档过大" }
                        out.write(buf, 0, n)
                    }
                    return parse(out.toByteArray())
                }
            }
        }
        error("Word 文档缺少 document.xml")
    }

    private fun parse(xml: ByteArray): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val out = StringBuilder()
        val para = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (local(parser.name)) {
                    "p" -> para.clear()
                    "t" -> para.append(parser.nextText())
                    "br" -> para.append('\n')
                    "tab" -> para.append('\t')
                }
            } else if (event == XmlPullParser.END_TAG && local(parser.name) == "p") {
                val line = para.toString().trim()
                if (line.isNotBlank()) out.append(line).append('\n')
                para.clear()
            }
            event = parser.next()
        }
        return out.toString()
    }

    private fun local(name: String?): String = name.orEmpty().substringAfter(':')
}
