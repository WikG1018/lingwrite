// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

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
    const val NAMING_SYS = "你是网文起名顾问。名字要符合题材和本书已有的命名风格，好记、不撞常见网文主角名。严格只输出 JSON。"

    fun naming(book: String, genre: String, style: String, sameKind: String, type: String, role: String, fields: String, ask: String) = buildString {
        append("【书名/类型/文风卡】").append(book).append(' ').append(genre)
        if (style.isNotBlank()) append(' ').append(style.take(300))
        if (sameKind.isNotBlank()) append("\n【已有同类名字】").append(sameKind)
        append("\n【这张卡】类型 ").append(type).append("，定位 ").append(role).append("，已知字段 ").append(fields)
        if (ask.isNotBlank()) append("\n【作者要求】").append(ask)
        append("\n输出：[{\"name\":\"\",\"meaning\":\"≤10字\"}] 共 8 个。")
    }

    fun complete(synopsis: String, related: String, mentions: String, fields: String) = buildString {
        append("【总纲】").append(synopsis.take(600))
        if (related.isNotBlank()) append("\n【相关卡】").append(related)
        if (mentions.isNotBlank()) append("\n【正文中的提及】").append(mentions)
        append("\n【这张卡】").append(fields)
        append("\n严格只输出 JSON 对象 {字段名: 值}，只补空字段。不确定的写“待定”。")
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
            ?: extractJson(raw) ?: error("模型没有按格式返回")
        arr.jsonArray.map { e -> e.jsonObject.let { NameIdea(it.str("name"), it.str("meaning")) } }.filter { it.name.isNotBlank() }
    }

    /** 空字段草稿：返回 字段名→建议值。 */
    suspend fun completeFields(
        synopsis: String, related: String, mentions: String, fields: String,
    ): Result<Map<String, String>> = runCatching {
        val raw = AppGraph.ai.complete("extract", LoreAiPrompts.NAMING_SYS,
            LoreAiPrompts.complete(synopsis, related, mentions, fields), null, null, 600).getOrThrow()
        val obj = JsonReply.parse(raw, array = false).let { (it as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject }
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
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
                Text("AI 起名", style = Ling.type.label, color = c.tertiary, modifier = Modifier.weight(1f))
                if (names.isNotEmpty()) LingButton("换一批", BtnKind.Text, height = 32.dp, enabled = !loading, onClick = onGenerate)
            }
            Spacer(Modifier.height(8.dp))
            LingTextField(ask, onAskChange, label = "要求（可空）", placeholder = "姓沈、清冷、两个字", singleLine = true)
            Spacer(Modifier.height(10.dp))
            if (loading) com.lingwrite.app.ui.ai.AiThinking("正在起名…")
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
                LingButton("让 AI 起个头", BtnKind.Tonal, icon = LingIcons.SparkleFilled, height = 38.dp, onClick = onGenerate)
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
