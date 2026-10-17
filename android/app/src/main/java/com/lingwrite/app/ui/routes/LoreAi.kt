// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.extractJson
import com.lingwrite.app.data.str
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.pressScale
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 设定卡起名与字段补全的提示词（A4，ai-features §4）。 */
object LoreAiPrompts {
    val NAMING_SYS = "你是网文起名顾问。名字要符合题材和本书已有的命名风格，好记、不撞常见网文主角名。\n" + com.lingwrite.app.data.ai.PromptContract.JSON_ONLY

    fun naming(book: String, genre: String, style: String, sameKind: String, type: String, role: String, fields: String, ask: String) = buildString {
        append(trZh(R.string.lore_ai_001)).append(book).append(' ').append(genre)
        if (style.isNotBlank()) append(' ').append(style.take(300))
        if (sameKind.isNotBlank()) append(trZh(R.string.lore_ai_002)).append(sameKind)
        append(trZh(R.string.lore_ai_003)).append(type).append(trZh(R.string.lore_ai_004)).append(role).append(trZh(R.string.lore_ai_005)).append(fields)
        if (ask.isNotBlank()) append(trZh(R.string.lore_ai_006)).append(ask)
        append(trZh(R.string.lore_ai_007))
    }

    fun complete(synopsis: String, related: String, mentions: String, fields: String) = buildString {
        append(trZh(R.string.book_ai_001)).append(synopsis.take(600))
        if (related.isNotBlank()) append(trZh(R.string.lore_ai_008)).append(related)
        if (mentions.isNotBlank()) append(trZh(R.string.lore_ai_009)).append(mentions)
        append(trZh(R.string.lore_ai_010)).append(fields)
        append(trZh(R.string.lore_ai_011))
    }
}

/** A4 的结果类型。 */
data class NameIdea(val name: String, val meaning: String)

/**
 * A4：起名（8 个名字胶囊）与空字段补全（草稿态，✓/✕ 逐个接受）。
 * 入口组件内嵌在 LoreEditSheet 中，调用方只处理保存。
 */
object LoreAi {
    /** 起名候选；返回空表示失败。 */
    suspend fun suggestNames(
        bookTitle: String, genre: String, style: String, sameKind: String, type: String, role: String, fields: String, ask: String,
    ): Result<List<NameIdea>> = runCatching {
        val raw = AppGraph.ai.complete("extract", LoreAiPrompts.NAMING_SYS,
            LoreAiPrompts.naming(bookTitle, genre, style, sameKind, type, role, fields, ask), null, null, 500).getOrThrow()
        val arr = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json
            ?: extractJson(raw) ?: error(tr(R.string.book_ai_018))
        arr.jsonArray.map { e -> e.jsonObject.let { NameIdea(it.str("name"), it.str("meaning")) } }.filter { it.name.isNotBlank() }
    }

    /** 空字段草稿：返回 字段名→建议值。 */
    suspend fun completeFields(
        synopsis: String, related: String, mentions: String, fields: String,
    ): Result<Map<String, String>> = runCatching {
        val raw = AppGraph.ai.complete("extract", LoreAiPrompts.NAMING_SYS,
            LoreAiPrompts.complete(synopsis, related, mentions, fields), null, null, 600).getOrThrow()
        val obj = JsonReply.parse(raw, array = false).let { (it as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject }
            ?: extractJson(raw, array = false)?.jsonObject ?: error(tr(R.string.book_ai_018))
        obj.entries.associate { it.key to (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.filterValues { it.isNotBlank() }
    }
}

/** 起名小卡：8 个名字胶囊 + 寓意 + 要求输入框 + 换一批。 */
@Composable
fun LoreNamingCard(
    loading: Boolean,
    names: List<NameIdea>,
    ask: String,
    onAskChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onPick: (NameIdea) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ling.colors
    LingCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.SparkleFilled, null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.lore_ai_012), style = Ling.type.label, color = c.tertiary, modifier = Modifier.weight(1f))
                if (names.isNotEmpty()) LingButton(tr(R.string.ai_components_008), BtnKind.Text, height = 32.dp, enabled = !loading, onClick = onGenerate)
            }
            Spacer(Modifier.height(8.dp))
            LingTextField(ask, onAskChange, label = tr(R.string.lore_ai_013), placeholder = tr(R.string.lore_ai_014), singleLine = true)
            Spacer(Modifier.height(10.dp))
            if (loading) com.lingwrite.app.ui.ai.AiThinking(tr(R.string.lore_ai_015))
            else names.forEach { n ->
                val i = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .pressScale(i, 0.96f).clip(RoundedCornerShape(11.dp)).background(c.surfaceContainer)
                        .clickable(i, indication = null) { onPick(n) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.name, style = Ling.type.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.onSurface)
                    if (n.meaning.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(n.meaning, style = Ling.type.micro, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (names.isEmpty() && !loading) {
                LingButton(tr(R.string.lore_ai_016), BtnKind.Tonal, icon = LingIcons.SparkleFilled, height = 38.dp, onClick = onGenerate)
            }
        }
    }
}

/** 空字段草稿行：浅琥珀底 + ✓ / ✕ 两个按钮。 */
@Composable
fun FieldDraftRow(field: String, value: String, onAccept: (String) -> Unit, onReject: () -> Unit) {
    val c = Ling.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LingTextField(value, onAccept, Modifier.weight(1f), label = field, singleLine = false, minLines = 1)
        LingButton("✓", BtnKind.Text, height = 32.dp, onClick = { onAccept(value) })
        LingButton("✕", BtnKind.Text, height = 32.dp, onClick = onReject)
    }
}
