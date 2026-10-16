// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

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
val authorGuideSteps get() = listOf(
    GuideStep(tr(R.string.author_guide_001), tr(R.string.author_guide_002)),
    GuideStep(tr(R.string.author_guide_003), tr(R.string.author_guide_004)),
    GuideStep(tr(R.string.author_guide_005), tr(R.string.author_guide_006)),
    GuideStep(tr(R.string.author_guide_007), tr(R.string.author_guide_008)),
    GuideStep(tr(R.string.author_guide_009), tr(R.string.author_guide_010)),
    GuideStep(tr(R.string.author_guide_011), tr(R.string.author_guide_012)),
    GuideStep(tr(R.string.author_guide_013), tr(R.string.author_guide_014)),
    GuideStep(tr(R.string.author_guide_015), tr(R.string.author_guide_016))
)

object AuthorGuideModule {
    fun install() {
        AppOverlays.list += { nav -> AuthorGuide(nav) }
        SettingsRegistry.extra += { prefs, nav ->
            val scope = rememberCoroutineScope()
            tr(R.string.author_guide_017) to SettingRow(LingIcons.Help, tr(R.string.author_guide_018), tr(R.string.author_guide_019), onClick = {
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
        if (p.authorGuideInviteRevision < AUTHOR_GUIDE_REVISION && !keyboard) LingSheet({ acknowledge(false) }, tr(R.string.author_guide_020)) {
            Text(tr(R.string.author_guide_021), style = Ling.type.body, color = Ling.colors.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton(tr(R.string.author_guide_022), BtnKind.Text) { acknowledge(false) }
                LingButton(tr(R.string.author_guide_023)) { acknowledge(true) }
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
    if (expanded && !keyboard) LingSheet({ expanded = false }, tr(R.string.author_guide_018), layout = SheetLayout.Viewport) {
        AnimatedContent(step, transitionSpec = { (if (reduced) fadeIn() else fadeIn() + slideInVertically { it / 8 }) togetherWith fadeOut() }, label = "authorGuide") { n ->
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${n + 1}/8 · ${authorGuideSteps[n].title}", style = Ling.type.titleSmall, color = Ling.colors.primary)
                Text(authorGuideSteps[n].instruction, style = Ling.type.body, color = Ling.colors.onSurface)
                if (n in 1..6 && bookId == null) Text(tr(R.string.author_guide_024), style = Ling.type.caption)
                LingButton(tr(R.string.author_guide_025), enabled = n !in 1..6 || bookId != null, modifier = Modifier.fillMaxWidth(), onClick = ::openTarget)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LingButton(tr(R.string.author_guide_026), BtnKind.Text, enabled = n > 0) { setStep(n - 1) }
                    LingButton(if (n == 7) tr(R.string.author_guide_027) else tr(R.string.author_guide_028), BtnKind.Tonal) { setStep(n + 1) }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LingButton(tr(R.string.author_guide_029), BtnKind.Text, modifier = Modifier.fillMaxWidth()) { scope.launch { AppGraph.prefs.update { it.copy(authorGuide = false, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION) } } }
                    LingButton(tr(R.string.author_guide_030), BtnKind.Text, modifier = Modifier.fillMaxWidth()) { setStep(0) }
                }
                Text(tr(R.string.author_guide_031), style = Ling.type.caption)
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
        Text(tr(R.string.author_guide_032, step + 1), style = Ling.type.label, color = Ling.colors.primary, modifier = Modifier.weight(1f))
        LingButton(tr(R.string.author_guide_033), BtnKind.Tonal, height = 36.dp) { AuthorGuideUi.openEpoch++ }
        LingIconButton(LingIcons.Close, tr(R.string.author_guide_034), onClick = onPause)
    }
}
