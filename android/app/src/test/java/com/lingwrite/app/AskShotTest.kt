// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.data.db.IdeaEntity
import com.lingwrite.app.ui.routes.AskPanel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A1 随手问面板截图（ui-spec §3、§11：40 空 / 41 有回答）。ModalBottomSheet 是独立窗口，截 dialog 节点。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xhdpi", application = android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AskShotTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir") ?: "build/shots").apply { mkdirs() }
    private var chapterId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        com.lingwrite.app.Modules.install()
        chapterId = runBlocking {
            val bookId = AppGraph.books.seedSample()
            AppGraph.db.chapters().chaptersOnce(bookId).first().id
        }
    }

    private fun shootSheet(name: String, content: @Composable () -> Unit) {
        val darkState = mutableStateOf(false)
        compose.setContent { key(darkState.value) { com.lingwrite.app.ui.theme.LingTheme(darkState.value) { DeviceFrame(false) { content() } } } }
        for (dark in listOf(false, true)) {
            darkState.value = dark
            compose.mainClock.advanceTimeBy(2500)
            compose.waitForIdle()
            val node = compose.onNode(isDialog())
            node.captureRoboImage(File(dir, "$name-${if (dark) "dark" else "light"}.png").path)
        }
    }

    @Test fun empty() = shootSheet("40-ask-empty") {
        AskPanel(chapterId, bookId = 1, chapterNo = 8, quote = null, onDismiss = {}, onToLoreCard = { _, _ -> })
    }

    @Test fun answer() = shootSheet("41-ask-answer") {
        runBlocking {
            AppGraph.db.stats().insertIdea(
                IdeaEntity(text = "沈砚现在是什么境界？", kind = "ask", bookId = 1, chapterId = chapterId,
                    answer = "筑基后期，第 92 章突破【设定：沈砚】。距金丹还差一次大劫，宗门大比前不会突破【第 92 章】。")
            )
        }
        AskPanel(chapterId, bookId = 1, chapterNo = 8, quote = "断剑收进袖里", onDismiss = {}, onToLoreCard = { _, _ -> })
    }
}
