// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Radius

/** Shared retained host: buttons, back, scrim and swipe all finish exit before removal. */
@Composable
fun LingSheet(onDismiss: () -> Unit, title: String? = null, skipPartial: Boolean = true, layout: SheetLayout = SheetLayout.Scroll, content: @Composable ColumnScope.() -> Unit) {
    RegisterSheet(onDismiss, title, skipPartial, layout, content)
}

@Composable
fun SheetAction(icon: ImageVector, text: String, danger: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    val c = Ling.colors
    val fg = if (danger) c.error else c.onSurface
    Row(Modifier.fillMaxWidth().then(if (LocalOverlayClosing.current) Modifier else Modifier.rowClick(onClick)).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (danger) c.error else c.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, style = Ling.type.bodyLarge, color = fg)
            if (sub != null) Text(sub, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
    }
}

/** 填充式输入框：surfaceContainer 底、14dp 圆角、无下划线。 */
@Composable
fun LingTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, placeholder: String? = null,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8, keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None, textStyle: TextStyle = Ling.type.bodyLarge,
    supporting: String? = null, isError: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    val c = Ling.colors
    Column(modifier) {
        if (label != null) Text(label, style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        TextField(
            value, onValueChange,
            Modifier.fillMaxWidth().then(
                // 视觉 label 没有进 M3 语义；这里补 contentDescription，TalkBack 聚焦时能听到字段名。
                label?.let { l -> if (l.isNotBlank()) Modifier.semantics { contentDescription = l } else Modifier } ?: Modifier
            ),
            singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            textStyle = textStyle.copy(color = c.onSurface), shape = Radius.card, isError = isError,
            placeholder = placeholder?.let { { Text(it, style = textStyle, color = c.onSurfaceVariant.copy(alpha = 0.7f)) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), visualTransformation = visualTransformation, trailingIcon = trailing,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceContainer, unfocusedContainerColor = c.surfaceContainer, errorContainerColor = c.errorContainer,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                cursorColor = c.primary, focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface,
            ),
        )
        if (supporting != null) Text(supporting, style = Ling.type.caption, color = if (isError) c.error else c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }
}

/** 确认对话框（统一配色、圆角）。 */
@Composable
fun LingConfirm(
    title: String, text: String, confirm: String = tr(R.string.sheets_001), dismiss: String = tr(R.string.sheets_002), danger: Boolean = false,
    onDismiss: () -> Unit, onConfirm: () -> Unit, extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Ling.colors
    RegisterConfirm(onDismiss) {
        Text(title, style = Ling.type.title, color = c.onSurface)
        Spacer(Modifier.height(16.dp))
        Text(text, style = Ling.type.body.copy(lineHeight = Ling.type.bodyLarge.lineHeight), color = c.onSurfaceVariant)
        extra?.invoke(this)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (dismiss.isNotBlank()) TextButton(onDismiss, enabled = !LocalOverlayClosing.current) { Text(dismiss, style = Ling.type.label, color = c.onSurfaceVariant) }
            TextButton(onConfirm, enabled = !LocalOverlayClosing.current) { Text(confirm, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (danger) c.error else c.primary) }
        }
    }
}
