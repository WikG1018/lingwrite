// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lingwrite.app.ui.LingWriteApp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lingwrite.app.ui.theme.LingTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.lingwrite.app.i18n.AppLocale.wrap(newBase))
    }

    override fun onDestroy() {
        if (com.lingwrite.app.i18n.L10n.ctx === this) com.lingwrite.app.i18n.L10n.ctx = applicationContext
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val transparent = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        com.lingwrite.app.i18n.L10n.ctx = this
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        com.lingwrite.app.data.Notifier.consume(intent)
        setContent {
            val prefs by AppGraph.prefs.flow.collectAsState(initial = null)
            val dark = when (prefs?.theme) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            // 升级后首次启动：快照在后台进行，期间不组合主界面（Room 打开也会等它，见 UpgradeGuard）。
            val backingUp by com.lingwrite.app.data.UpgradeGuard.busy.collectAsState()
            LingTheme(dark, prefs?.reduceMotion) {
                androidx.compose.animation.Crossfade(backingUp, animationSpec = androidx.compose.animation.core.tween(com.lingwrite.app.ui.theme.Motion.Medium), label = "upgradeGate") { busy ->
                    if (busy) com.lingwrite.app.ui.screens.UpgradeBackupScreen() else LingWriteApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); com.lingwrite.app.data.Notifier.consume(intent)
    }
}
