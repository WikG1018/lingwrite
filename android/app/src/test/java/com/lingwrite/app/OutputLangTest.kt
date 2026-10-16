// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ai.OutputLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputLangTest {
    @Test fun detect() {
        assertEquals("zh-CN", OutputLang.detect("沈砚站在山门前，回头看了一眼这座他待了十年的宗门。"))
        assertEquals("zh-TW", OutputLang.detect("沈硯站在山門前，回頭看了一眼這座他待了十年的宗門，說不出話來。"))
        assertEquals("ja", OutputLang.detect("沈硯は山門の前に立ち、十年を過ごした宗門を振り返った。"))
        assertEquals("en", OutputLang.detect("Shen Yan stood before the mountain gate and looked back at the sect."))
        assertNull(OutputLang.detect("……"))
    }

    @Test fun resolveManual() = runBlocking {
        assertEquals("en", OutputLang.resolve("en", null))
        assertNull(OutputLang.resolve("zh-CN", null))
        assertNull(OutputLang.resolve("auto", null))
        assert(OutputLang.directive("ja").contains("日语"))
    }
}
