// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.AppGraph
import com.lingwrite.app.ui.components.DockHost
import com.lingwrite.app.ui.components.DockTab
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.screens.*
import com.lingwrite.app.ui.theme.Motion
import kotlinx.coroutines.launch

object Routes {
    const val FirstRun = "firstrun"; const val Shelf = "shelf"; const val Import = "import"; const val Work = "work/{bookId}?tab={tab}"; const val Editor = "editor/{chapterId}"
    const val Lore = "lore/{bookId}/{cardId}"; const val Models = "models"; const val Ideas = "ideas"; const val Stats = "stats"
    const val Settings = "settings"; const val Search = "search/{bookId}"; const val Wizard = "wizard/{bookId}"
    const val About = "about"; const val Backup = "backup"; const val Export = "export/{bookId}"
    const val Breakdown = "breakdown/{bookId}/{viewId}"; const val Reader = "reader/{bookId}/{chapter}"
    fun work(id: Long) = "work/$id"; fun editor(id: Long) = "editor/$id"; fun lore(book: Long, card: Long) = "lore/$book/$card"
    fun search(book: Long) = "search/$book"; fun wizard(id: Long) = "wizard/$id"; fun export(id: Long) = "export/$id"
    fun reader(book: Long, chapter: Int) = "reader/$book/$chapter"
    /** A12 拆文：bookId=0 表示还没选书（只能分析）；viewId>0 直接打开已存的分析。 */
    fun breakdown(book: Long = 0, view: Long = 0) = "breakdown/$book/$view"
}

fun dockTabFor(route: String?): DockTab? = when (route) {
    Routes.Shelf, Routes.Work, Routes.Lore, "lorelib/{bookId}/{type}" -> DockTab.Shelf
    Routes.Ideas -> DockTab.Ideas
    Routes.Stats -> DockTab.Stats
    Routes.Models, Routes.Settings -> DockTab.Settings
    else -> null
}

private val topLevel = setOf(Routes.Shelf, Routes.Ideas, Routes.Stats, Routes.Settings)

/** 四个 dock 顶层页之间，前进和返回都用同一套淡入缩放。回到书架不能再走子页面的横滑。 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.dockSwitch(): Boolean =
    initialState.destination.route in topLevel && targetState.destination.route in topLevel

/** 后续模块注册的额外目的地（保持本文件稳定）。 */
/** 全局浮层（启动检查更新、通知权限说明等），渲染在导航之上。 */
object AppOverlays {
    val list = mutableListOf<@Composable (NavHostController) -> Unit>()
}

object ExtraDestinations {
    val list = mutableListOf<androidx.navigation.NavGraphBuilder.(NavHostController) -> Unit>()
}

@Composable
fun LingWriteApp(nav: NavHostController = rememberNavController()) {
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(initial = null)
    val p = prefs ?: return
    val start = remember { if (p.onboarded) Routes.Shelf else Routes.FirstRun }
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: start
    val guideBar = p.authorGuide && route != Routes.Editor && route != Routes.FirstRun && WindowInsets.ime.getBottom(LocalDensity.current) == 0
    DockHost(dockTabFor(route), onSelect = { t ->
        val target = when (t) { DockTab.Shelf -> Routes.Shelf; DockTab.Ideas -> Routes.Ideas; DockTab.Stats -> Routes.Stats; DockTab.Settings -> Routes.Settings }
        nav.navigate(target) { popUpTo(Routes.Shelf) { saveState = true }; launchSingleTop = true; restoreState = true }
    }) {
        val reducedMotion = Motion.reduced()
        CompositionLocalProvider(LocalNav provides nav) { com.lingwrite.app.ui.components.LingOverlayRoot { Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        if (guideBar) AuthorGuideBar(p.authorGuideStep) { scope.launch { AppGraph.prefs.update { it.copy(authorGuide = false) } } }
        Box(Modifier.weight(1f).then(if (guideBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) { NavHost(
            nav, start, Modifier,
            enterTransition = {
                when {
                    reducedMotion -> fadeIn(tween(Motion.Short))
                    dockSwitch() -> fadeIn(tween(Motion.Medium, 60)) + scaleIn(tween(Motion.Medium, 60, Motion.Emphasized), 0.98f)
                    else -> slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 6 } + fadeIn(tween(Motion.Medium))
                }
            },
            exitTransition = {
                when {
                    reducedMotion -> fadeOut(tween(Motion.Short))
                    dockSwitch() -> fadeOut(tween(Motion.Short))
                    else -> slideOutHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { -it / 10 } + fadeOut(tween(Motion.Medium))
                }
            },
            popEnterTransition = {
                when {
                    reducedMotion -> fadeIn(tween(Motion.Short))
                    dockSwitch() -> fadeIn(tween(Motion.Medium, 60)) + scaleIn(tween(Motion.Medium, 60, Motion.Emphasized), 0.98f)
                    else -> slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { -it / 10 } + fadeIn(tween(Motion.Medium))
                }
            },
            popExitTransition = {
                when {
                    reducedMotion -> fadeOut(tween(Motion.Short))
                    dockSwitch() -> fadeOut(tween(Motion.Short))
                    else -> slideOutHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 6 } + fadeOut(tween(Motion.Medium))
                }
            },
        ) {
            composable(Routes.FirstRun) {
                FirstRunScreen { mode ->
                    scope.launch { AppGraph.prefs.update { it.copy(onboarded = true, authorGuide = true, authorGuideStep = 0, authorGuideInviteRevision = com.lingwrite.app.ui.routes.AUTHOR_GUIDE_REVISION, writeMode = if (mode == WriteMode.Generate) "generate" else "assist") } }
                    nav.navigate(Routes.Shelf) { popUpTo(Routes.FirstRun) { inclusive = true } }
                }
            }
            composable(Routes.Shelf) {
                ShelfRoute(
                    onOpenBook = { nav.navigate(Routes.work(it)) }, onOpenChapter = { nav.navigate(Routes.editor(it)) },
                    onSearch = { nav.navigate(Routes.search(0)) }, onWizard = { nav.navigate(Routes.wizard(it)) },
                    onImport = { nav.navigate(Routes.Import) },
                )
            }
            composable(Routes.Import) { ImportRoute(nav) }
            composable(Routes.Work, listOf(navArgument("bookId") { type = NavType.LongType }, navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
                val id = e.arguments!!.getLong("bookId")
                WorkRoute(id, onBack = { nav.popBackStack() }, onOpenChapter = { nav.navigate(Routes.editor(it)) },
                    onSearch = { nav.navigate(Routes.search(id)) }, onExport = { nav.navigate(Routes.export(it)) }, initialTab = e.arguments!!.getInt("tab"))
            }
            composable(Routes.Editor, listOf(navArgument("chapterId") { type = NavType.LongType })) { e ->
                EditorRoute(e.arguments!!.getLong("chapterId"), nav)
            }
            composable(Routes.Ideas) { IdeasRoute(nav) }
            composable(Routes.Stats) { StatsRoute(nav) }
            composable(Routes.Settings) { SettingsRoute(nav) }
            composable(Routes.Models) { ModelsRoute(nav) }
            composable("provider/{id}/{preset}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ProviderEditRoute(e.arguments!!.getLong("id"), e.arguments!!.getString("preset") ?: "custom", nav)
            }
            composable(Routes.Lore, listOf(navArgument("bookId") { type = NavType.LongType }, navArgument("cardId") { type = NavType.LongType })) { e ->
                LoreRoute(e.arguments!!.getLong("bookId"), e.arguments!!.getLong("cardId"), nav)
            }
            composable(Routes.Breakdown, listOf(navArgument("bookId") { type = NavType.LongType }, navArgument("viewId") { type = NavType.LongType })) { e ->
                com.lingwrite.app.ui.routes.BreakdownRoute(e.arguments!!.getLong("bookId"), e.arguments!!.getLong("viewId"), nav)
            }
            composable(Routes.Reader, listOf(navArgument("bookId") { type = NavType.LongType }, navArgument("chapter") { type = NavType.IntType })) { e ->
                com.lingwrite.app.ui.routes.ReaderRoute(e.arguments!!.getLong("bookId"), e.arguments!!.getInt("chapter"), nav)
            }
            ExtraDestinations.list.forEach { it(nav) }
        }
        }
        AppOverlays.list.forEach { it(nav) }
        }
        }
        }
        }
    }
}
