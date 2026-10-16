// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.VolumeLabel
import com.lingwrite.app.i18n.L10n
import com.lingwrite.app.i18n.tr
import com.lingwrite.app.ui.components.DockHost
import com.lingwrite.app.ui.components.DockTab
import com.lingwrite.app.ui.screens.*
import com.lingwrite.app.ui.theme.LingTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 多语言截图：同一批主界面在 en / ja / zh-TW 下各拍一张（输出到 shotDir-<lang>），人工对照检查
 * 文字溢出、截断和换行；另外断言卷 / 章编号和基础字符串按语言切换。
 */
abstract class LocaleShotBase(private val lang: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir")?.let { "$it-$lang" } ?: "build/shots-$lang").apply { mkdirs() }

    @Before fun resetCtx() { L10n.ctx = null }

    protected fun shoot(name: String, tab: DockTab?, paper: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { LingTheme(false) { DeviceFrame(paper) { DockHost(tab, {}) { content() } } } }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Test fun shelf() = shoot("01-shelf", DockTab.Shelf) { ShelfScreen() }
    @Test fun work() = shoot("02-work", DockTab.Shelf) { WorkScreen() }
    @Test fun editor() = shoot("03-editor", null, paper = true) { EditorScreen() }
    @Test fun lore() = shoot("04-lore", DockTab.Shelf) { LoreScreen() }
    @Test fun models() = shoot("05-models", DockTab.Settings) { ModelsScreen() }
    @Test fun firstRun() = shoot("06-firstrun", null) { FirstRunScreen() }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class LocaleShotEnTest : LocaleShotBase("en") {
    @Test fun labels() {
        assertEquals("Volume 2", VolumeLabel.short(2))
        assertEquals("Chapter 118", ChapterLabel.short(118))
        assertEquals("Shelf", tr(R.string.dock_001))
    }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class LocaleShotJaTest : LocaleShotBase("ja") {
    @Test fun labels() {
        assertEquals("第2巻", VolumeLabel.short(2))
        assertEquals("第118章", ChapterLabel.short(118))
        assertEquals("本棚", tr(R.string.dock_001))
    }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rTW-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class LocaleShotZhTwTest : LocaleShotBase("zh-TW") {
    @Test fun labels() {
        assertEquals("第二卷", VolumeLabel.short(2))
        assertEquals("書架", tr(R.string.dock_001))
    }
}
