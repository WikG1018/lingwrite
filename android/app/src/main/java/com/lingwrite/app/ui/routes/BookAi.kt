// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.extractJson
import com.lingwrite.app.data.str
import com.lingwrite.app.data.BookCovers
import com.lingwrite.app.ui.components.CoverReload
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.FileOutputStream
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.pressScale
import com.lingwrite.app.ui.components.rowClick
import com.lingwrite.app.ui.theme.Ling
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 书名、简介、卖点文案的提示词（A10，ai-features §10）。 */
object BookAiPrompts {
    val SYS = "你是资深网文策划，熟悉起点、番茄、晋江等平台的上架包装。文案要具体、有卖点，不写空话。\n" + com.lingwrite.app.data.ai.PromptContract.JSON_ONLY

    fun pitch(synopsis: String, volumeOutline: String, recentSummaries: String, title: String, blurb: String, platform: String): String = buildString {
        if (synopsis.isNotBlank()) append(trZh(R.string.book_ai_001)).append(synopsis).append('\n')
        if (volumeOutline.isNotBlank()) append(trZh(R.string.book_ai_002)).append(volumeOutline).append('\n')
        if (recentSummaries.isNotBlank()) append(trZh(R.string.book_ai_003)).append(recentSummaries).append('\n')
        if (title.isNotBlank()) append(trZh(R.string.book_ai_004)).append(title).append('\n')
        if (blurb.isNotBlank()) append(trZh(R.string.book_ai_005)).append(blurb).append('\n')
        append(trZh(R.string.book_ai_006)).append(platform.ifBlank { trZh(R.string.book_ai_007) }).append('\n')
        append(trZh(R.string.book_ai_008))
    }

    fun cover(title: String, genre: String, synopsis: String, blurb: String) = buildString {
        append(trZh(R.string.book_ai_009)).append(title.ifBlank { trZh(R.string.book_ai_010) }).append(trZh(R.string.book_ai_011))
        if (genre.isNotBlank()) append(trZh(R.string.book_ai_012)).append(genre).append('\n')
        if (synopsis.isNotBlank()) append(trZh(R.string.book_ai_001)).append(synopsis.take(400)).append('\n')
        if (blurb.isNotBlank()) append(trZh(R.string.book_ai_013)).append(blurb.take(200)).append('\n')
        append(trZh(R.string.book_ai_014))
    }
}

/** A10 的结果。 */
data class BookPitch(val titles: List<String>, val blurbs: List<String>, val oneLine: String, val tags: List<String>, val recommend: String)

object BookAi {
    suspend fun generate(bookId: Long, platform: String): Result<BookPitch> = runCatching {
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: error(tr(R.string.book_ai_015))
        val bp = db.plan().bookOnce(bookId)
        val vols = db.chapters().volumesOnce(bookId)
        val vplans = db.plan().volumes(bookId).first()
        val volOutline = vplans.joinToString("\n") { v ->
            val name = vols.firstOrNull { it.id == v.volumeId }?.title.orEmpty()
            if (v.outline.isBlank()) "" else trZh(R.string.book_ai_016, name, v.outline)
        }
        val chs = db.chapters().chaptersOnce(bookId).sortedBy { it.number }.takeLast(10)
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val recent = chs.mapNotNull { c -> sums[c.id]?.let { trZh(R.string.book_ai_017, c.number, it.text) } }.joinToString("\n")
        val raw = AppGraph.ai.complete("wizard", BookAiPrompts.SYS,
            BookAiPrompts.pitch(bp?.synopsis.orEmpty(), volOutline, recent, b.title, b.blurb, platform), bookId, null, 1500).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error(tr(R.string.book_ai_018))
        val titles = o["titles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val blurbs = o["blurbs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val pitch = o["pitch"]?.jsonObject
        BookPitch(
            titles = titles.filter { it.isNotBlank() },
            blurbs = blurbs.filter { it.isNotBlank() },
            oneLine = pitch?.str("oneLine").orEmpty(),
            tags = pitch?.get("tags")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().filter { it.isNotBlank() },
            recommend = pitch?.str("recommend").orEmpty(),
        )
    }
}

data class CoverLook(val top: Int, val bottom: Int, val accent: Int, val motif: String, val line: String)

private fun coverColor(raw: String?, fallback: Int): Int {
    val hex = raw?.trim()?.removePrefix("#").orEmpty()
    val n = hex.toLongOrNull(16) ?: return fallback
    return if (hex.length == 6) (0xFF000000L or n).toInt() else fallback
}

fun renderCover(look: CoverLook, title: String): Bitmap {
    val w = 460
    val h = 640
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), look.top, look.bottom, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = 0x40FFFFFF
    canvas.drawRect(28f, 0f, 36f, h.toFloat(), paint)
    paint.color = look.accent
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = 88f
    canvas.drawText(look.motif.take(2).ifBlank { tr(R.string.book_ai_019) }, 64f, 300f, paint)
    paint.textSize = 28f
    canvas.drawText(look.line.take(8), 64f, 360f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 42f
    canvas.drawText(title.ifBlank { trZh(R.string.book_ai_010) }.take(10), 56f, h - 78f, paint)
    return bmp
}

suspend fun generateCoverLook(book: BookEntity): Result<CoverLook> = runCatching {
    val bp = AppGraph.db.plan().bookOnce(book.id)
    val raw = AppGraph.ai.complete("wizard", BookAiPrompts.SYS, BookAiPrompts.cover(book.title, book.genre, bp?.synopsis.orEmpty(), book.blurb), book.id, null, 400).getOrThrow()
    val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
        ?: extractJson(raw, array = false)?.jsonObject ?: error(tr(R.string.book_ai_018))
    CoverLook(
        top = coverColor(o.str("top"), 0xFF4F6E9C.toInt()),
        bottom = coverColor(o.str("bottom"), 0xFF2E4870.toInt()),
        accent = coverColor(o.str("accent"), 0xFFF3EEDF.toInt()),
        motif = o.str("motif").orEmpty().take(2),
        line = o.str("line").orEmpty().take(8),
    )
}

/** 作品页菜单「AI 生成书面」：用现有书名画封面，不改书名。 */
@Composable
fun BookCoverSheet(book: BookEntity, onDismiss: () -> Unit) {
    val c = Ling.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LingSheet(onDismiss, tr(R.string.book_ai_020)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.book_ai_021, book.title.ifBlank { trZh(R.string.book_ai_010) }), style = Ling.type.body, color = c.onSurfaceVariant)
            preview?.let { Image(it.asImageBitmap(), book.title, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth) }
            if (busy) com.lingwrite.app.ui.ai.AiThinking(tr(R.string.book_ai_022))
            LingButton(if (busy) tr(R.string.ai_components_003) else if (preview == null) tr(R.string.book_ai_023) else tr(R.string.book_ai_024), BtnKind.Tonal, icon = LingIcons.Sparkle, enabled = !busy) {
                busy = true
                err = null
                scope.launch {
                    generateCoverLook(book).fold(
                        { preview = withContext(Dispatchers.Default) { renderCover(it, book.title) } },
                        { err = it.message ?: tr(R.string.book_ai_025) },
                    )
                    busy = false
                }
            }
            err?.let { Text(tr(R.string.ai_components_011, it), style = Ling.type.caption, color = c.error) }
            if (preview != null) LingButton(tr(R.string.book_ai_026), modifier = Modifier.fillMaxWidth(), height = 50.dp, enabled = !busy) {
                val bmp = preview ?: return@LingButton
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        FileOutputStream(BookCovers.file(ctx, book.id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    CoverReload.bump()
                    onDismiss()
                }
            }
            if (BookCovers.file(ctx, book.id).isFile) LingButton(tr(R.string.book_ai_027), BtnKind.Text, enabled = !busy) {
                BookCovers.file(ctx, book.id).delete()
                CoverReload.bump()
                preview = null
            }
        }
    }
}

/** 上架文案卡（一句话卖点 + 标签 + 推荐语）。 */
@Composable
fun PitchCard(p: BookPitch, onCopy: () -> Unit) {
    val c = Ling.colors
    LingCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(tr(R.string.book_ai_028), style = Ling.type.caption, color = c.onSurfaceVariant)
            Text(p.oneLine, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
            if (p.tags.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.tags.forEach { Pill(it, PillKind.Amber) }
                }
            }
            if (p.recommend.isNotBlank()) {
                Text(tr(R.string.book_ai_029), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text(p.recommend, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurface)
            }
            LingButton(tr(R.string.book_ai_030), BtnKind.Tonal, height = 36.dp, modifier = Modifier.padding(top = 10.dp), onClick = onCopy)
        }
    }
}

/** 书名候选胶囊行（A10，ui-spec §10）：点一下用这个书名。 */
@Composable
fun TitleChips(titles: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        titles.forEach { t ->
            Pill(t, PillKind.Amber, modifier = Modifier.rowClick { onPick(t) })
        }
    }
}

/** 简介候选（A10，ui-spec §10）：3 版候选卡（1/3），点“用这版”写回简介。 */
@Composable
fun BlurbCandidates(blurbs: List<String>, selected: Int, onSelect: (Int) -> Unit, onUse: () -> Unit, modifier: Modifier = Modifier) {
    com.lingwrite.app.ui.ai.AiCandidateCard(
        header = tr(R.string.book_ai_031, blurbs.size),
        candidates = blurbs.map { com.lingwrite.app.ui.ai.Candidate(it) },
        selected = selected,
        modifier = modifier,
        onSelect = onSelect,
        onAccept = onUse,
        acceptLabel = tr(R.string.book_ai_032),
    )
}
