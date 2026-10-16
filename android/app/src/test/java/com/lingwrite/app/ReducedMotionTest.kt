// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.ui.ai.AiThinking
import com.lingwrite.app.ui.components.SkeletonBlock
import com.lingwrite.app.ui.theme.LingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 减弱动画（MO3 / motion-design §16）：reduced=true 下没有正在进行的动画，隔 3 秒两帧完全一致。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ReducedMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir") ?: "build/shots").apply { mkdirs() }

    @Test fun reducedMotionFirstFrameIsFinal() {
        compose.setContent {
            LingTheme(dark = false, reduceMotionOverride = "on") {
                Column(Modifier.padding(16.dp)) {
                    AiThinking("正在想 3 个方向…")
                    SkeletonBlock(3)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val f1 = File(dir, "reduced-a.png").also { compose.onRoot().captureRoboImage(it.path) }
        // 再过 3 秒画面应完全不变：reduced 模式下骨架不循环、思考点静止
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        val f2 = File(dir, "reduced-b.png").also { compose.onRoot().captureRoboImage(it.path) }
        assertTrue("reduced=true 时不应有进行中的动画", f1.readBytes().contentEquals(f2.readBytes()))
        f1.copyTo(File(dir, "reduced-motion-light.png"), overwrite = true)
        f1.delete(); f2.delete()
    }
}
