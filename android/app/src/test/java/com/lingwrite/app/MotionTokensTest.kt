// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.animation.core.Easing
import com.lingwrite.app.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/** 动效 token（MO1 / motion-design §3）：Standard 与 Emphasized 曲线区分。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MotionTokensTest {
    @Test fun emphasizedStartsSlowerThanStandard() {
        // Emphasized 是 M3 两段贝塞尔：前段更慢、后段更快
        for (t in floatArrayOf(0.05f, 0.1f, 0.15f)) {
            assertTrue(
                "Emphasized.transform($t) 应小于 Standard.transform($t)",
                Motion.Emphasized.transform(t) < Motion.Standard.transform(t),
            )
        }
    }

    @Test fun emphasizedIsContinuousAndMonotonicAcrossBothSegments() {
        var previous = Motion.Emphasized.transform(0f)
        for (i in 1..10000) {
            val value = Motion.Emphasized.transform(i / 10000f)
            assertTrue("backwards at $i: $previous -> $value", value >= previous - 0.00001f)
            assertTrue("out of range", value in 0f..1f)
            assertTrue("discontinuity at $i", value - previous < 0.01f)
            previous = value
        }
        assertEquals(.4f, Motion.Emphasized.transform(.167f), 0.00001f)
        assertTrue(kotlin.math.abs(Motion.Emphasized.transform(.500001f) - Motion.Emphasized.transform(.5f)) < .0001f)
    }

    @Test fun curvesEndAtOne() {
        assertEquals(1f, Motion.Emphasized.transform(1f), 1e-4f)
        assertEquals(0f, Motion.Emphasized.transform(0f), 1e-4f)
        assertEquals(1f, Motion.Standard.transform(1f), 1e-4f)
        assertEquals(0f, Motion.Standard.transform(0f), 1e-4f)
        assertEquals(1f, Motion.EmphasizedDecelerate.transform(1f), 1e-4f)
        assertEquals(1f, Motion.EmphasizedAccelerate.transform(1f), 1e-4f)
    }

    @Test fun durationsAreTokens() {
        assertEquals(150, Motion.Short)
        assertEquals(280, Motion.Medium)
        assertEquals(420, Motion.Long)
        assertEquals(35, Motion.StaggerStep)
    }
}
