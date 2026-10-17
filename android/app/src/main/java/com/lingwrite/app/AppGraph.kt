// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.content.Context
import com.lingwrite.app.data.BookRepository
import com.lingwrite.app.data.db.LingDatabase

/** 极简依赖容器（MVP 不引入 DI 框架）。 */
object AppGraph {
    /** 应用级协程作用域：离开页面后仍需完成的保存等。 */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    lateinit var db: LingDatabase; private set
    lateinit var books: BookRepository; private set
    lateinit var prefs: com.lingwrite.app.data.Prefs; private set
    lateinit var ai: com.lingwrite.app.data.ai.AiService; private set
    private var ready = false

    fun init(context: Context, inMemory: Boolean = false) {
        if (ready && !inMemory) return
        db = LingDatabase.build(context.applicationContext, inMemory)
        books = BookRepository(db)
        prefs = com.lingwrite.app.data.Prefs(context.applicationContext)
        com.lingwrite.app.data.RestoreHints.init(context.applicationContext)
        // 非 Composable 场景（通知、错误文案、统计聚合等）也需要取本地化字符串；MainActivity 会再覆盖成 Activity。
        if (com.lingwrite.app.i18n.L10n.ctx == null) com.lingwrite.app.i18n.L10n.ctx = context.applicationContext
        ai = com.lingwrite.app.data.ai.AiService(db.ai(), prefs)
        com.lingwrite.app.ui.routes.AboutModule.bind(context)
        ready = true
    }
}

class LingApp : android.app.Application() {
    override fun onCreate() { super.onCreate(); AppGraph.init(this); Modules.install() }
}
