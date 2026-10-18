// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.ai

import com.lingwrite.app.i18n.trText

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.LingSheet
import com.lingwrite.app.ui.components.LingTextField
import com.lingwrite.app.ui.components.Skeleton
import com.lingwrite.app.ui.components.pressScale
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

/** 编辑器顶栏的模式徽章（ui-spec §1）：胶囊，点开模式 Sheet。 */
@Composable
fun ModeBadge(mode: WriteMode, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = Ling.colors
    val (bg, fg, icon) = if (mode == WriteMode.Assist) Triple(c.primaryContainer, c.onPrimaryContainer, LingIcons.Draw)
    else Triple(c.tertiaryContainer, c.onTertiaryContainer, LingIcons.SparkleFilled)
    val i = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(i, 0.94f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(i, indication = null, onClick = onClick)
            .padding(start = 7.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(trText(mode.label), style = Ling.type.micro.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** 编辑器底栏的 AI 主按钮（ui-spec §1）。 */
@Composable
fun AiMainButton(mode: WriteMode, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    val (label, icon) = if (mode == WriteMode.Assist) tr(R.string.ai_components_001) to LingIcons.IdeaFilled else tr(R.string.ai_components_002) to LingIcons.SparkleFilled
    val shape = RoundedCornerShape(19.dp)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .pressScale(i, 0.93f)
            .clip(shape)
            .then(if (mode == WriteMode.Assist) Modifier.border(1.5.dp, c.primary, shape) else Modifier.background(c.primary))
            .clickable(i, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (mode == WriteMode.Assist) c.primary else c.onPrimary, modifier = Modifier.size(18.dp))
        Text(label, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (mode == WriteMode.Assist) c.primary else c.onPrimary)
    }
}

/** 卡片语气：区分 AI 卡的类型/状态，不再用左侧竖色条（ui-plan「禁用左侧色条」）。 */
enum class CardTone { Primary, Amber, Error, Good, Neutral }

/** 语气色：(浅底, 细描边, 图标圆底, 图标/标题色)。 */
@Composable
fun toneColors(tone: CardTone): List<Color> {
    val c = Ling.colors
    val (accent, fg) = when (tone) {
        CardTone.Primary -> c.primary to c.primary
        CardTone.Amber -> c.amber to c.tertiary
        CardTone.Error -> c.error to c.error
        CardTone.Good -> c.good to c.good
        CardTone.Neutral -> c.onSurfaceVariant to c.onSurfaceVariant
    }
    val tint = if (c.isDark) 0.08f else 0.045f
    val bg = androidx.compose.ui.graphics.lerp(c.surface, accent, tint)
    return listOf(bg, accent.copy(alpha = if (c.isDark) 0.28f else 0.22f), accent.copy(alpha = if (c.isDark) 0.22f else 0.14f), fg)
}

/** 标题前的小圆底图标（22dp 圆，14dp 图标）。 */
@Composable
fun ToneIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: CardTone, modifier: Modifier = Modifier) {
    val (_, _, chip, fg) = toneColors(tone)
    Box(modifier.size(22.dp).clip(CircleShape).background(chip), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
    }
}

/** 通用 AI 卡：很浅的语气色底 + 1dp 细描边 + 圆底图标标题。 */
@Composable
fun ToneCard(
    tone: CardTone,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    content: @Composable () -> Unit,
) {
    val (bg, line, _, fg) = toneColors(tone)
    val shape = RoundedCornerShape(16.dp)
    LingCard(modifier.border(1.dp, line, shape), shape = shape, color = bg, elevated = true) {
        Column(Modifier.fillMaxWidth().padding(contentPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToneIcon(icon, tone)
                Text(title, style = Ling.type.caption.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 辅助模式的便签卡：浅底 + 细描边（不用左侧竖条）、要点列表、再来一次 / 复制 / 存到便签（ui-spec §1）。 */
@Composable
fun AiNoteCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onAnother: () -> Unit = {},
    onCopy: (() -> Unit)? = null,
    onNote: (() -> Unit)? = null,
) {
    val c = Ling.colors
    ToneCard(CardTone.Primary, title, LingIcons.StickyNote, modifier) {
        val lines = text.lines().filter { it.isNotBlank() }
        lines.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text("${i + 1}.  ", style = Ling.type.body.copy(fontSize = 14.5.sp, lineHeight = 23.sp), color = c.primary)
                Text(line.replace(Regex("^\\s*[0-9]+[.、)]\\s*"), ""), style = Ling.type.body.copy(fontSize = 14.5.sp, lineHeight = 23.sp), color = c.onSurface)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LingButton(if (busy) tr(R.string.ai_components_003) else tr(R.string.ai_components_004), BtnKind.Text, height = 36.dp, enabled = !busy, onClick = onAnother)
            onCopy?.let { LingButton(tr(R.string.ai_components_005), BtnKind.Outlined, height = 36.dp, onClick = it) }
            onNote?.let { LingButton(tr(R.string.ai_components_006), BtnKind.Tonal, height = 36.dp, onClick = it) }
        }
    }
}

/** 流式光标：2dp × 1em amber，530ms 闪烁；减弱动画时常亮（motion §20）。 */
@Composable
fun AiStreamCursor(modifier: Modifier = Modifier) {
    val c = Ling.colors
    val reduced = Motion.reduced()
    val alpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "streamCursor")
        t.animateFloat(
            initialValue = 1f, targetValue = 0.15f,
            animationSpec = infiniteRepeatable(tween(530, easing = Motion.Standard), RepeatMode.Reverse),
            label = "cursorAlpha",
        ).value
    }
    Box(
        modifier
            .padding(start = 1.dp)
            .width(2.dp)
            .height(18.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(c.amber, RoundedCornerShape(1.dp)),
    )
}

/** 生成模式的候选卡：amber 竖条、页码点、左右滑动 peek、弹簧吸附（ui-spec §1 / motion §20–21）。 */
@Composable
fun AiCandidateCard(
    header: String,
    candidates: List<Candidate>,
    selected: Int,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    ready: (Int) -> Boolean = { true },
    onSelect: (Int) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onTweak: () -> Unit = {},
    onAccept: () -> Unit = {},
    acceptLabel: String = tr(R.string.ai_components_007),
) {
    val c = Ling.colors
    val reduced = Motion.reduced()
    val count = candidates.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = selected.coerceIn(0, count - 1), pageCount = { count })
    LaunchedEffect(pagerState.currentPage) { if (pagerState.currentPage != selected) onSelect(pagerState.currentPage) }
    LaunchedEffect(selected) {
        if (pagerState.currentPage != selected) {
            if (reduced) pagerState.scrollToPage(selected) else pagerState.animateScrollToPage(selected)
        }
    }
    // 「换一批」离场：当前这批上移 16dp + 淡出（Short / EmphasizedAccelerate），然后回到思考态（motion §21）
    val leave = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val liftPx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(candidates.size, streaming) { if (leave.value != 0f && streaming) leave.snapTo(0f) }
    val regenerate: () -> Unit = {
        if (reduced) onRegenerate() else scope.launch {
            leave.animateTo(1f, tween(Motion.Short, easing = Motion.EmphasizedAccelerate))
            onRegenerate()
            leave.snapTo(0f)
        }
    }
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = if (reduced) tween(0) else Motion.snap(),
    )
    // 页码指示放进标题行右侧，不再单独占一行（polish3：去掉标题与正文之间的空洞）
    ToneCard(CardTone.Amber, header, LingIcons.SparkleFilled, modifier, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    candidates.indices.forEach { i ->
                        val dotW by animateDpAsState(if (i == selected) 16.dp else 6.dp, tween(Motion.Medium, easing = Motion.Standard), label = "candDot$i")
                        Box(Modifier.size(dotW, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == selected) c.amber else if (ready(i)) c.tertiary else c.outline))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("${selected + 1}/${candidates.size.coerceAtLeast(1)}", style = Ling.type.caption.copy(fontSize = 12.sp), color = c.onSurfaceVariant)
                }
    }) {
        Column(Modifier.animateContentSize(Motion.grow())) {
            Spacer(Modifier.height(6.dp))
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(end = 12.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                flingBehavior = fling,
                userScrollEnabled = candidates.size > 1,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                    .graphicsLayer { alpha = 1f - leave.value; translationY = -liftPx * leave.value },
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                val scale = if (reduced) 1f else lerp(1f, 0.96f, pageOffset.coerceIn(0f, 1f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        candidates.getOrNull(page)?.display?.ifEmpty { "…" } ?: "…",
                        style = Ling.type.bodyLarge.copy(lineHeight = 26.sp),
                        color = c.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (streaming && page == selected) AiStreamCursor(Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            AnimatedVisibility(
                visible = !streaming,
                enter = if (reduced) fadeIn(tween(Motion.Short))
                else fadeIn(tween(Motion.Medium)) + slideInVertically(tween(Motion.Medium, easing = Motion.EmphasizedDecelerate)) { 8 },
                exit = fadeOut(tween(Motion.Short)),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(tr(R.string.ai_components_008), BtnKind.Text, height = 36.dp, onClick = regenerate)
                    LingButton(tr(R.string.ai_components_009), BtnKind.Outlined, height = 36.dp, onClick = onTweak)
                    LingButton(acceptLabel, BtnKind.Tonal, height = 36.dp, onClick = onAccept)
                }
            }
            if (streaming) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(tr(R.string.ai_components_003), BtnKind.Text, height = 36.dp, enabled = false)
                    LingButton(tr(R.string.ai_components_009), BtnKind.Outlined, height = 36.dp, enabled = false)
                    LingButton(acceptLabel, BtnKind.Tonal, height = 36.dp, enabled = false)
                }
            }
        }
    }
}

/** 思考中：三个 amber 点依次跳动 3dp + 文案 + 两条骨架（motion §19）。 */
@Composable
fun AiThinking(text: String, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val reduced = Motion.reduced()
    val amp = with(LocalDensity.current) { 3.dp.toPx() }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val dy = if (reduced) 0f else {
                    val t = rememberInfiniteTransition(label = "think$i")
                    t.animateFloat(
                        initialValue = 0f, targetValue = -amp,
                        animationSpec = infiniteRepeatable(
                            tween(900, delayMillis = i * 150, easing = Motion.Standard),
                            RepeatMode.Reverse,
                        ), label = "thinkDot$i",
                    ).value
                }
                Box(Modifier.size(6.dp).graphicsLayer { translationY = dy }.clip(CircleShape).background(c.amber))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (reduced) tr(R.string.ai_components_010, text) else text, style = Ling.type.body.copy(fontSize = 13.sp), color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Skeleton(0.9f)
        Spacer(Modifier.height(10.dp))
        Skeleton(0.62f)
    }
}

/** 错误卡：图标 + “没拿到结果：{原因}” + 重试次数 + 换个模型 / 重试（ui-spec §1）。 */
@Composable
fun AiErrorCard(message: String, retries: Int = 0, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onChangeModel: (() -> Unit)? = null) {
    val c = Ling.colors
    val (ebg, eline) = toneColors(CardTone.Error)
    LingCard(modifier.border(1.dp, eline, RoundedCornerShape(16.dp)), shape = RoundedCornerShape(16.dp), color = ebg, elevated = true) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToneIcon(LingIcons.ErrorOutline, CardTone.Error)
                Text(tr(R.string.ai_components_011, message), style = Ling.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
            }
            if (retries > 0) Text(tr(R.string.ai_components_012, retries), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, top = 2.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onChangeModel?.let { LingButton(tr(R.string.ai_components_013), BtnKind.Text, height = 36.dp, onClick = it) }
                onRetry?.let { LingButton(tr(R.string.ai_components_014), BtnKind.Outlined, height = 36.dp, onClick = it) }
            }
        }
    }
}

/**
 * 采纳高亮（正文类）：amber 30% 底色在 1200ms 内淡到常规底色（gen 16% / edited 8%）。
 * 减弱动画时直接显示常规底色（motion §22）。
 */
@Composable
fun Modifier.adoptHighlight(kind: String = "gen", trigger: Any? = Unit): Modifier {
    val c = Ling.colors
    val reduced = Motion.reduced()
    val final = if (kind == "edited") 0.08f else 0.16f
    val a = remember { Animatable(if (reduced) final else 0.30f) }
    LaunchedEffect(trigger) {
        if (reduced) { a.snapTo(final) } else { a.snapTo(0.30f); a.animateTo(final, tween(1200, easing = Motion.Standard)) }
    }
    return then(Modifier.background(c.amber.copy(alpha = a.value)))
}

/** 非正文采纳（设定建议、伏笔、章纲）：2dp amber 55% 描边闪一次（0→1→0，共 800ms）。 */
@Composable
fun Modifier.adoptFlash(trigger: Any?): Modifier {
    val c = Ling.colors
    val reduced = Motion.reduced()
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null || reduced) return@LaunchedEffect
        a.snapTo(0f)
        a.animateTo(1f, tween(400, easing = Motion.Standard))
        a.animateTo(0f, tween(400, easing = Motion.Standard))
    }
    return border(2.dp, c.amber.copy(alpha = 0.55f * a.value), RoundedCornerShape(14.dp))
}

/** 上下文胶囊：随手问 / 卡文急救共用的“带入了哪些上下文”行（ui-spec §1）。 */
@Composable
fun ContextChips(chips: List<String>, modifier: Modifier = Modifier, onRemove: (Int) -> Unit = {}) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEachIndexed { i, chip ->
            Row(
                Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Ling.colors.surfaceContainer)
                    .clickable { onRemove(i) }
                    .padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(chip, style = Ling.type.micro, color = Ling.colors.onSurfaceVariant)
                Icon(LingIcons.Close, tr(R.string.ai_components_015, chip), tint = Ling.colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** 微调 Sheet：上面原文只读、下面可编辑候选（ui-spec §1）。 */
@Composable
fun AiTweakSheet(
    original: String,
    initial: String,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
) {
    val c = Ling.colors
    var t by remember { mutableStateOf(initial) }
    LingSheet(onDismiss, tr(R.string.ai_components_016)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(tr(R.string.ai_components_017), style = Ling.type.caption.copy(fontWeight = FontWeight.Medium), color = c.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                Text(original.take(600) + if (original.length > 600) "…" else "", style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurfaceVariant, maxLines = 4)
            }
            Spacer(Modifier.height(14.dp))
            LingTextField(t, { t = it }, Modifier.fillMaxWidth(), singleLine = false, minLines = 5, maxLines = 12)
            Text(tr(R.string.ai_components_018), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        LingButton(tr(R.string.ai_components_019), modifier = Modifier.padding(20.dp).fillMaxWidth(), height = 50.dp, enabled = t.isNotBlank()) { onAccept(t.trim()) }
    }
}
