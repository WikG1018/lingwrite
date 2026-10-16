// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.components.DockHost
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.routes.LocalNav
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.LingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 接真实数据（内存库 + 示例作品）的页面截图。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi", application = android.app.Application::class)
abstract class RouteShotBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir") ?: "build/shots").apply { mkdirs() }
    protected var bookId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        Modules.install()
        bookId = runBlocking { AppGraph.books.seedSample().also { extraSeed(it) } }
    }

    open suspend fun extraSeed(bookId: Long) {}

    protected fun shoot(name: String, paper: Boolean = false, content: @Composable () -> Unit) {
        val darkState = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalNav provides nav) {
                androidx.compose.runtime.key(darkState.value) { LingTheme(darkState.value) { DeviceFrame(paper) { DockHost(null, {}) { content() } } } }
            }
        }
        for (dark in listOf(false, true)) {
            darkState.value = dark
            repeat(6) { Thread.sleep(150); compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }
            compose.onRoot().captureRoboImage(File(dir, "$name-${if (dark) "dark" else "light"}.png").path)
        }
    }

    @Composable protected fun Page(title: String, sub: String? = null, body: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().background(Ling.colors.background)) {
            LingTopBar(title, sub, onBack = {})
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { body() }
        }
    }
}

class OutlineShotTest : RouteShotBase() {
    override suspend fun extraSeed(bookId: Long) {
        val d = AppGraph.db.plan()
        d.upsertBook(BookPlanEntity(bookId, synopsis = "落魄捕快沈砚在一桩旧案里发现师父之死另有隐情，一路追查到朝堂与江湖的交易，最终在揭开真相与守住本心之间做出选择。",
            fields = encodeList(listOf("爽点")), threads = encodeList(listOf("主线", "感情线", "师门旧案")),
            goldenResult = encodeGolden(listOf(GoldenResult(DEFAULT_GOLDEN[0], true, "“沈砚把刀横在膝上”", 1), GoldenResult(DEFAULT_GOLDEN[1], true, "开篇即命案", 1),
                GoldenResult(DEFAULT_GOLDEN[2], false, "前 500 字以环境描写为主，钩子在 800 字后", 1)))))
        val chs = AppGraph.db.chapters().chaptersOnce(bookId)
        chs.forEachIndexed { i, c ->
            d.upsertChapter(ChapterPlanEntity(c.id, bookId, event = listOf("雨夜命案，沈砚在死者袖中发现师父的铜钱", "追查铜钱来历，结识药铺女掌柜林晚", "旧案卷宗失踪，上司勒令结案").getOrElse(i) { "推进调查" },
                hook = "雨夜敲门声", cliff = "铜钱背面刻着自己的名字", threads = if (i % 2 == 0) "主线,师门旧案" else "主线,感情线", fromAi = i == 2,
                landed = if (i == 0) """{"event":true,"hook":true,"cliff":false,"note":"章尾停在收队，没有落下铜钱的悬念"}""" else ""))
        }
    }

    @org.junit.Test fun outline() = shoot("09-outline") { Page("长夜问刀", "大纲") { com.lingwrite.app.ui.routes.OutlineTab(bookId, LocalNav.current) } }
    @org.junit.Test fun golden() = shoot("10-golden") { com.lingwrite.app.ui.routes.GoldenRoute(bookId, LocalNav.current) }
    @org.junit.Test fun threads() = shoot("11-threads") { com.lingwrite.app.ui.routes.ThreadsRoute(bookId, LocalNav.current) }
}
