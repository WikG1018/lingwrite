// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.ui.routes.EditorAi

/** 各功能模块在启动时注册扩展点。 */
object Modules {
    private var done = false
    fun install() {
        if (done) return; done = true
        EditorAi.install()
        com.lingwrite.app.ui.routes.LoreModule.install()
        com.lingwrite.app.ui.routes.OutlineModule.install()
        com.lingwrite.app.ui.routes.WizardModule.install()
        com.lingwrite.app.ui.routes.ContextModule.install()
        com.lingwrite.app.ui.routes.CheckModule.install()
        com.lingwrite.app.ui.routes.StyleModule.install()
        com.lingwrite.app.ui.routes.StatsModule.install()
        com.lingwrite.app.ui.routes.DataModule.install()
        com.lingwrite.app.ui.routes.UpdateModule.install()
        com.lingwrite.app.ui.routes.AboutModule.install()
        com.lingwrite.app.ui.routes.AuthorGuideModule.install()
        com.lingwrite.app.ui.routes.SeriesModule.install()
    }
}
