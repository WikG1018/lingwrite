// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.data.FakeData
import com.lingwrite.app.ui.components.DockHost
import com.lingwrite.app.ui.components.DockTab
import com.lingwrite.app.ui.screens.*
import com.lingwrite.app.ui.theme.LingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 412×915dp @2x，与 ui-hifi 高保真稿同尺寸，便于逐张对比。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("lingwrite.shotDir") ?: "build/shots").apply { mkdirs() }

    private fun shoot(name: String, tab: DockTab?, paper: Boolean = false, content: @Composable () -> Unit) {
        val darkState = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { androidx.compose.runtime.key(darkState.value) { LingTheme(darkState.value) { DeviceFrame(paper) { DockHost(tab, {}) { content() } } } } }
        for (dark in listOf(false, true)) {
            darkState.value = dark
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "$name-${if (dark) "dark" else "light"}.png").path)
        }
    }

    @Test fun shelf() = shoot("01-shelf", DockTab.Shelf) { ShelfScreen() }
    @Test fun work() = shoot("02-work", DockTab.Shelf) { WorkScreen() }
    @Test fun workVolumes() = shoot("62-work-volumes", DockTab.Shelf) {
        WorkScreen(
            header = FakeData.header,
            volumes = listOf(
                com.lingwrite.app.data.Volume("第一卷 · 山门初入", "第 1–60 章 · 14.7 万字", listOf(
                    com.lingwrite.app.data.Chapter(58, "外门比试", "已完成", "4,120"),
                    com.lingwrite.app.data.Chapter(59, "", "已完成", "3,958"),
                    com.lingwrite.app.data.Chapter(60, "下山之前", "已完成", "4,012"),
                )),
                com.lingwrite.app.data.Volume("第二卷", "暂无章节", emptyList()),
            ),
            // 连载看板与上方统计卡的间距由本截图覆盖（无 AppGraph 的静态预览卡）
            serialBoard = {
                com.lingwrite.app.ui.components.LingCard(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    color = com.lingwrite.app.ui.theme.Ling.colors.tertiaryContainer,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("连载看板", style = com.lingwrite.app.ui.theme.Ling.type.label, color = com.lingwrite.app.ui.theme.Ling.colors.onTertiaryContainer, modifier = Modifier.weight(1f))
                            com.lingwrite.app.ui.components.Pill("已发布到第 115 章", com.lingwrite.app.ui.components.PillKind.Neutral)
                        }
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            listOf("1,208" to "今日字数", "3/5" to "存稿", "6" to "连续更新", "2" to "章纲余量").forEachIndexed { i, (n, l) ->
                                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(com.lingwrite.app.ui.theme.Ling.colors.outlineVariant))
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(n, style = com.lingwrite.app.ui.theme.Ling.type.numeral, color = com.lingwrite.app.ui.theme.Ling.colors.onSurface)
                                    Text(l, style = com.lingwrite.app.ui.theme.Ling.type.caption, color = com.lingwrite.app.ui.theme.Ling.colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    @Test fun editor() = shoot("03-editor", null, paper = true) { EditorScreen() }
    @Test fun lore() = shoot("04-lore", DockTab.Shelf) { LoreScreen() }
    @Test fun models() = shoot("05-models", DockTab.Settings) { ModelsScreen() }
    @Test fun settings() = shoot("07-settings", DockTab.Settings) {
        SettingsListScreen("设置", listOf(
            SettingSection("写作", listOf(SettingRow(com.lingwrite.app.ui.components.LingIcons.Draw, "写作模式", "辅助：AI 只给建议；生成：AI 可起草正文", "辅助模式", onClick = {}),
                SettingRow(com.lingwrite.app.ui.components.LingIcons.FactCheck, "写完一章自动检查", "摘要、一致性、伏笔建议", switch = true, tone = Tone.Good))),
            SettingSection("AI", listOf(SettingRow(com.lingwrite.app.ui.components.LingIcons.Bolt, "模型设置", "服务商、Key、写作与自动任务模型", tone = Tone.Amber, onClick = {}))),
            SettingSection("应用", listOf(SettingRow(com.lingwrite.app.ui.components.LingIcons.Info, "关于灵写", value = "0.1.0", tone = Tone.Neutral, onClick = {}))),
        ), footer = "灵写 · 数据只存在这台手机上")
    }
    @Test fun graph() = shoot("08-graph", null) {
        val cs = listOf("沈砚", "林晚", "顾长风", "苏九", "白先生").mapIndexed { i, n -> com.lingwrite.app.data.db.LoreCardEntity(id = i + 1L, bookId = 1, type = "character", name = n) }
        val rs = listOf(Triple(1L, 2L, "青梅" to "love"), Triple(1L, 3L, "宿敌" to "enemy"), Triple(1L, 5L, "师徒" to "mentor"), Triple(2L, 4L, "姐妹" to "family"), Triple(3L, 4L, "同门" to "ally"))
            .mapIndexed { i, (a, b, t) -> com.lingwrite.app.data.db.LoreRelationEntity(i + 1L, 1, a, b, t.first, 1, t.second) }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(com.lingwrite.app.ui.theme.Ling.colors.background).statusBarsPadding()) {
            com.lingwrite.app.ui.components.LingTopBar("人物关系图", "第 42 章时 · 点人物打开卡片，拖动可调整位置", onBack = {})
            com.lingwrite.app.ui.routes.RelationGraph(cs, rs, androidx.compose.ui.Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {}
        }
    }
    @Test fun firstRun() = shoot("06-firstrun", null) { FirstRunScreen() }
}
