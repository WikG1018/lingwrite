// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.ui.theme.LingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 截图测试的公共基座：412×915dp @2x，与 ui-hifi 高保真稿同尺寸，浅色 + 深色各一张。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xhdpi", application = android.app.Application::class)
abstract class ShotBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir") ?: "build/shots").apply { mkdirs() }

    protected fun shoot(name: String, paper: Boolean = false, content: @Composable () -> Unit) {
        val darkState = mutableStateOf(false)
        compose.setContent { key(darkState.value) { LingTheme(darkState.value) { DeviceFrame(paper) { content() } } } }
        for (dark in listOf(false, true)) {
            darkState.value = dark
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "$name-${if (dark) "dark" else "light"}.png").path)
        }
    }
}
