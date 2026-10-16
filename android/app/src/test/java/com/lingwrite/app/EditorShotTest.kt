// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingwrite.app.data.FakeData
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.ui.ai.AiCandidateCard
import com.lingwrite.app.ui.ai.AiNoteCard
import com.lingwrite.app.ui.ai.Candidate
import com.lingwrite.app.ui.ai.ModeBadge
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.screens.ToolChips
import org.junit.Test

/** v0.2 编辑器模式差异截图（ui-spec §2、§11）。 */
class EditorShotTest : ShotBase() {
    @Test fun assistEditor() = shoot("30-editor-assist", paper = true) {
        EditorScreen(
            mode = WriteMode.Assist,
            notesUnread = 2,
            badge = { ModeBadge(WriteMode.Assist) {} },
            bottomPanel = {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiNoteCard(
                        "写作方向 · 3 个",
                        "1. 从沈清禾的视角重新写这场戏，把她压着不问的心情写透\n2. 让执事先宣布一个坏消息，打断两人的对峙\n3. 跳过当夜，直接从第二天晨课写起",
                        onNote = {}, onCopy = {},
                    )
                    ToolChips(-1, listOf("给方向", "随手问")) {}
                }
            },
        )
    }

    @Test fun generateEditor() = shoot("31-editor-generate", paper = true) {
        EditorScreen(
            mode = WriteMode.Generate,
            badge = { ModeBadge(WriteMode.Generate) {} },
            bottomPanel = {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiCandidateCard(
                        "续写 · 3 个候选",
                        listOf(
                            Candidate("袖中的断剑又轻轻一震。林砚垂下眼，指腹在剑脊上按了按，像是安抚。"),
                            Candidate("台下还在争他出了几剑。没人注意他袖口那道新裂的口子。"),
                            Candidate("“捡的。”他说完这两个字，廊柱阴影里的人影动了一下。"),
                        ),
                        0,
                    )
                    ToolChips(-1, listOf("续写", "对话", "描写", "随手问")) {}
                }
            },
        )
    }
}
