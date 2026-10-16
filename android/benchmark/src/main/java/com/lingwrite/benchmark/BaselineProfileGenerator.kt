// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MO4 Baseline Profile 生成器：覆盖启动 → 书架 → 作品 → 编辑器路径。
 * 产物提交到 app/src/main/baseline-prof.txt（motion-design §174）。
 * 生成：./gradlew :benchmark:pixel6Api33BaselineProfileAndroidTest（或对应 managerDevice）
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect("com.lingwrite.app") {
        pressHome()
        startActivityAndWait()

        // 书架 → 作品 → 编辑器
        device.findObject(androidx.test.uiautomator.By.text("青云问道"))?.click()
        device.waitForIdle()
        device.findObject(androidx.test.uiautomator.By.text("外门比试"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.pressBack()
    }
}
