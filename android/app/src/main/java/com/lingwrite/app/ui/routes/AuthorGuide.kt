// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.lingwrite.app.AppGraph
import com.lingwrite.app.ui.AppOverlays
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.launch

const val AUTHOR_GUIDE_REVISION = 1
object AuthorGuideUi { var openEpoch by mutableIntStateOf(0) }

data class GuideStep(val title: String, val instruction: String)
val authorGuideSteps = listOf(
    GuideStep("建一本书", "去书架点新建，直接创建空白书，或选择 8 步创作向导。这里不会代你创建作品。"),
    GuideStep("人物与世界观", "在设定库新建人物、势力、地点等卡片；人物状态与关系可以指定从哪章生效。先留少量关键设定即可。"),
    GuideStep("全书总纲", "打开总纲工作台第 ③ 步，写主线、阶段目标与结局。AI 可选；候选只有你确认后才采纳。"),
    GuideStep("分卷与卷纲", "在工作台第 ④ 步设置每卷目标与高潮。局部采纳时核对卷序号；覆盖卷纲保留已有正文。"),
    GuideStep("每章章纲", "第 ⑤ 步先选卷，再写或生成本卷章纲。事件、开篇钩子、章尾悬念与节拍为正文提供方向。"),
    GuideStep("写正文与修订", "编辑器会自动保存；撤销与重做在右上角。AI 建议需确认，等待时改过正文会提示旧结果冲突。键盘打开时指导收起。"),
    GuideStep("埋设与回收伏笔", "作品的伏笔页可登记线索、关联人物与埋设章、设置回收章。写到回收处后手动标记已回收；检查遗漏提醒。"),
    GuideStep("版本、导出与备份", "编辑器更多菜单查看历史并比较回滚；作品菜单恢复误删章节。最后导出成稿，并在设置的数据页备份。")
)

object AuthorGuideModule {
    fun install() {
        AppOverlays.list += { nav -> AuthorGuide(nav) }
        SettingsRegistry.extra += { prefs, nav ->
            val scope = rememberCoroutineScope()
            "写作" to SettingRow(LingIcons.Help, "作者写作指导", "可继续或从头重看；不会自动创建或修改稿件", onClick = {
                scope.launch { AppGraph.prefs.update { it.copy(authorGuide = true, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION, authorGuideStep = if (prefs.authorGuideStep >= authorGuideSteps.size) 0 else prefs.authorGuideStep) }; AuthorGuideUi.openEpoch++; nav.navigate(Routes.Shelf) }
            })
        }
    }
}

@Composable fun AuthorGuide(nav: NavHostController) {
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val p = prefs ?: return
    val entry by nav.currentBackStackEntryAsState()
    if (!p.onboarded || entry?.destination?.route == Routes.FirstRun) return
    val scope = rememberCoroutineScope()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun acknowledge(start: Boolean) { scope.launch { AppGraph.prefs.update { it.copy(authorGuideInviteRevision = AUTHOR_GUIDE_REVISION, authorGuide = start, authorGuideStep = if (start && it.authorGuideStep >= authorGuideSteps.size) 0 else it.authorGuideStep) } } }
    if (!p.authorGuide) {
        if (p.authorGuideInviteRevision < AUTHOR_GUIDE_REVISION && !keyboard) LingSheet({ acknowledge(false) }, "写作指导已上线") {
            Text("按真实入口梳理建书、章纲、正文、伏笔和备份。可随时跳过，在设置中继续或重看。", style = Ling.type.body, color = Ling.colors.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton("稍后", BtnKind.Text) { acknowledge(false) }
                LingButton("开始指导") { acknowledge(true) }
            }
        }
        return
    }
    val inEditor = entry?.destination?.route == Routes.Editor
    if (inEditor && p.authorGuideStep !in 5..6) return
    val books by remember { AppGraph.books.summaries() }.collectAsState(emptyList())
    val editorBook by produceState<Long?>(null, entry?.id) { value = entry?.arguments?.getLong("chapterId")?.takeIf { it > 0 }?.let { AppGraph.db.chapters().get(it)?.bookId } }
    val bookId = entry?.arguments?.getLong("bookId")?.takeIf { it > 0 } ?: editorBook ?: books.firstOrNull()?.id
    val chapters by remember(bookId) { bookId?.let(AppGraph.books::chapterHeaders) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val step = p.authorGuideStep.coerceIn(0, authorGuideSteps.lastIndex)
    var expanded by rememberSaveable { mutableStateOf(true) }
    val openEpoch = AuthorGuideUi.openEpoch
    LaunchedEffect(openEpoch) { expanded = true }
    val reduced = Motion.reduced()
    fun setStep(n: Int) { scope.launch { AppGraph.prefs.update { it.copy(authorGuideStep = n, authorGuide = n < authorGuideSteps.size, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION) } } }
    // 仅引导跳转；“下一步/跳过”不会执行写库、删除、创建或模型请求。
    fun openTarget() {
        if (inEditor && step == 5) {
            entry?.arguments?.getLong("chapterId")?.takeIf { it > 0 }?.let { Outline.editing = it }
            expanded = false; return
        }
        val target = when (step) {
            0 -> Routes.Shelf
            1 -> bookId?.let { Routes.lore(it, 0) }
            2, 3, 4 -> bookId?.let { "chain/$it?step=${step + 1}" }
            5 -> chapters.firstOrNull()?.let { Routes.editor(it.id) }
            6 -> bookId?.let { "work/$it?tab=3" }
            else -> Routes.Backup
        }
        if (target != null) {
            expanded = false
            val current = nav.currentDestination
            nav.navigate(target) {
                if (current != null && guideReplacesDestination(current.route, target)) popUpTo(current.id) { inclusive = true }
            }
        }
    }
    if (expanded && !keyboard) LingSheet({ expanded = false }, "作者写作指导", layout = SheetLayout.Viewport) {
        AnimatedContent(step, transitionSpec = { (if (reduced) fadeIn() else fadeIn() + slideInVertically { it / 8 }) togetherWith fadeOut() }, label = "authorGuide") { n ->
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${n + 1}/8 · ${authorGuideSteps[n].title}", style = Ling.type.titleSmall, color = Ling.colors.primary)
                Text(authorGuideSteps[n].instruction, style = Ling.type.body, color = Ling.colors.onSurface)
                if (n in 1..6 && bookId == null) Text("先创建作品，再进入这个步骤。", style = Ling.type.caption)
                LingButton("打开实际入口", enabled = n !in 1..6 || bookId != null, modifier = Modifier.fillMaxWidth(), onClick = ::openTarget)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton("上一步", BtnKind.Text, enabled = n > 0) { setStep(n - 1) }
                    LingButton(if (n == 7) "完成指导" else "下一步", BtnKind.Tonal) { setStep(n + 1) }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LingButton("稍后继续 / 跳过", BtnKind.Text, modifier = Modifier.fillMaxWidth()) { scope.launch { AppGraph.prefs.update { it.copy(authorGuide = false, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION) } } }
                    LingButton("从头重看", BtnKind.Text, modifier = Modifier.fillMaxWidth()) { setStep(0) }
                }
                Text("AI 配置可跳过；设置 → 作者写作指导可继续或重看。", style = Ling.type.caption)
            }
        }
    }
}

/** 同一总纲或作品页已打开时，要换掉当前页，新的 step / tab 才会生效。 */
internal fun guideReplacesDestination(currentRoute: String?, target: String): Boolean {
    val route = currentRoute ?: return false
    return (target.startsWith("chain/") && route.startsWith("chain/")) ||
        (target.startsWith("work/") && route.startsWith("work/"))
}

/** Occupies a layout row so the guide never floats over the author's content. */
@Composable fun AuthorGuideBar(step: Int, onPause: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("写作指导 ${step + 1}/8", style = Ling.type.label, color = Ling.colors.primary, modifier = Modifier.weight(1f))
        LingButton("继续指导", BtnKind.Tonal, height = 36.dp) { AuthorGuideUi.openEpoch++ }
        LingIconButton(LingIcons.Close, "暂停写作指导", onClick = onPause)
    }
}
