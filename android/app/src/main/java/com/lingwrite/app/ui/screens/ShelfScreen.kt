// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.Book
import com.lingwrite.app.data.BookStatus
import com.lingwrite.app.data.FakeData
import com.lingwrite.app.data.HeroInfo
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Radius
import com.lingwrite.app.ui.theme.Space

@Composable
fun ShelfScreen(
    books: List<Book> = FakeData.books,
    hero: HeroInfo? = FakeData.hero,
    todayWords: Int = 2140,
    onOpenBook: (Long) -> Unit = {},
    onContinue: (HeroInfo) -> Unit = {},
    onSearch: () -> Unit = {},
    onNewBook: () -> Unit = {},
    onSample: () -> Unit = {},
    onBookMenu: (Book) -> Unit = {},
) {
    val c = Ling.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.statusBars.asPaddingValues().let { PaddingValues(top = it.calculateTopPadding(), bottom = Space.dockClearance + 40.dp) }) {
            item { ShelfHeader(books.size, todayWords, onSearch) }
            if (hero != null) item { HeroCard(hero, Modifier.enterStagger(0)) { onContinue(hero) } }
            if (books.isEmpty()) item { EmptyShelf(onNewBook, onSample) }
            if (books.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 10.dp).enterStagger(1), verticalAlignment = Alignment.CenterVertically) {
                    Text("全部作品", style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text("最近编辑", style = Ling.type.caption, color = c.onSurfaceVariant)
                    Icon(LingIcons.ExpandMore, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
                }
            }
            if (books.isNotEmpty()) item {
                LingCard(Modifier.padding(horizontal = Space.gutter).enterStagger(2)) {
                    books.forEachIndexed { i, b ->
                        if (i > 0) InsetDivider(76.dp)
                        BookRow(b, { onBookMenu(b) }) { onOpenBook(b.id) }
                    }
                }
            }
        }
        if (books.isNotEmpty()) LingFab("新建作品", LingIcons.Add, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 88.dp), onClick = onNewBook)
    }
}

@Composable
private fun ShelfHeader(count: Int, today: Int, onSearch: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(c.logoStart, c.logoEnd))), contentAlignment = Alignment.Center) {
            Icon(LingIcons.Quill, null, Modifier.size(24.dp), tint = Color(0xFFF3D9A8))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("灵写", style = Ling.type.headline, color = c.onSurface)
            Text(if (count == 0) "开始你的第一部作品" else "$count 部作品 · 今天写了 ${com.lingwrite.app.data.Words.grouped(today)} 字", style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        LingIconButton(LingIcons.Search, "搜索", onClick = onSearch)
    }
}

@Composable
private fun HeroCard(h: HeroInfo, modifier: Modifier, onContinue: () -> Unit) {
    val c = Ling.colors
    val i = remember { MutableInteractionSource() }
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).fillMaxWidth().pressScale(i, 0.985f).clip(Radius.xl)
            .background(Brush.linearGradient(listOf(c.heroStart, c.heroEnd), start = Offset(0f, 0f), end = Offset(260f, 1000f)))
            .drawBehind {
                val glow = Offset(size.width - 40.dp.toPx(), 30.dp.toPx())
                drawCircle(Brush.radialGradient(0f to Color(0x73E9AA50), 0.7f to Color(0x00E9AA50), center = glow, radius = 90.dp.toPx()), 90.dp.toPx(), glow)
                drawCircle(Color.White.copy(alpha = 0.06f), 70.dp.toPx(), Offset(30.dp.toPx(), size.height))
            }
            .clickable(i, ripple(color = Color.White), onClick = onContinue)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Box(Modifier.height(24.dp).clip(Radius.pill).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("继续写作", style = Ling.type.micro.copy(fontSize = 12.sp), color = Color.White)
        }
        Text(h.title, style = Ling.type.headline.copy(letterSpacing = (-0.2).sp), color = Color.White, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        Text(h.subline, style = Ling.type.caption, color = Color.White.copy(alpha = 0.78f))
        Text(
            buildAnnotatedString {
                val hl = h.highlight
                val at = if (hl != null) h.excerpt.indexOf(hl) else -1
                if (at < 0) append(h.excerpt) else {
                    append(h.excerpt.substring(0, at))
                    withStyle(SpanStyle(color = Color(0xFFF5CF8E), fontWeight = FontWeight.Medium)) { append(hl!!) }
                    append(h.excerpt.substring(at + hl!!.length))
                }
            },
            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = Ling.type.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = 24.6.sp), color = Color.White.copy(alpha = 0.94f),
            modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Stat(h.words, h.wordsUnit); Spacer(Modifier.width(18.dp)); Stat("${h.chapters}", "章")
            Spacer(Modifier.weight(1f))
            val bi = remember { MutableInteractionSource() }
            Row(
                Modifier.height(36.dp).pressScale(bi, 0.94f).clip(Radius.pill).background(Color.White)
                    .clickable(bi, ripple(color = c.heroEnd), onClick = onContinue).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LingIcons.EditOutline, null, Modifier.size(20.dp), tint = Color(0xFF2E4870))
                Spacer(Modifier.width(4.dp))
                Text("继续写", style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF2E4870))
            }
        }
    }
}

@Composable
private fun Stat(n: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(n, style = Ling.type.numeral, color = Color.White)
        Text(unit, style = Ling.type.caption.copy(fontSize = 12.sp), color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
    }
}

@Composable
private fun BookRow(b: Book, onMenu: () -> Unit, onClick: () -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().rowClick(onClick).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(b.short, b.cover, bookId = b.id)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = Ling.type.subhead, color = c.onSurface)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Pill(b.genre)
                when (b.status) {
                    BookStatus.Serial -> Pill("连载中", PillKind.Neutral)
                    BookStatus.Outline -> Pill("大纲中", PillKind.Neutral)
                    BookStatus.Draft -> Pill("草稿", PillKind.Neutral)
                    BookStatus.Done -> Pill("已完结", PillKind.Good)
                    BookStatus.Wizard -> Pill("向导 ${b.wizardStep}/7", PillKind.Amber, LingIcons.SparkleFilled)
                }
                if (b.overdueForeshadow > 0) Pill("伏笔超期 ${b.overdueForeshadow}", PillKind.Error, LingIcons.Schedule)
            }
            Spacer(Modifier.height(6.dp))
            val meta = if (b.status == BookStatus.Wizard) "尚未开写 · ${b.edited}" else "${b.words} · ${b.chapter} · ${b.edited}"
            Text(meta, style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        LingIconButton(LingIcons.More, "更多", tint = c.onSurfaceVariant, onClick = onMenu)
    }
}

@Composable
private fun EmptyShelf(onNew: () -> Unit, onSample: () -> Unit) {
    val c = Ling.colors
    LingCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().enterStagger(0)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(LingIcons.Quill, null, Modifier.size(34.dp), tint = c.onPrimaryContainer)
            }
            Text("书架还是空的", style = Ling.type.titleSmall, color = c.onSurface, modifier = Modifier.padding(top = 16.dp))
            Text("从一个灵感开始，向导会一步步陪你搭好设定、大纲和前三章。", style = Ling.type.body, color = c.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            LingButton("新建作品", icon = LingIcons.Add, onClick = onNew)
            LingButton("先看看示例作品", BtnKind.Text, modifier = Modifier.padding(top = 4.dp), onClick = onSample)
        }
    }
}
