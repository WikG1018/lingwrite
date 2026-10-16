// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MO4 性能基准（motion-design §174–181）。
 * 阈值：冷启动 TTID P50 ≤ 600ms；滚动/编辑器/AI 流式帧 P90 ≤ 12–14ms。
 * 真机（Pixel 6a 级）+ benchmark buildType 上跑：./gradlew :benchmark:connectedBenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    /** 冷启动 → 书架（阈值 timeToInitialDisplay P50 ≤ 600ms）。 */
    @Test fun coldStartup() = rule.measureRepeated(
        packageName = "com.lingwrite.app",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /** 书架滚动帧率（200 本场景，阈值 frameDurationCpuMs P90 ≤ 12ms）。 */
    @Test fun shelfScrollFrames() = rule.measureRepeated(
        packageName = "com.lingwrite.app",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        device.waitForIdle()
    }
}
