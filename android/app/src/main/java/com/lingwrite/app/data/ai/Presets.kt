// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

/** 订阅套餐接入信息与条款风险（novel-app-plan.md 5.4，查于 2026-10-12）。 */
data class SubscriptionInfo(val baseUrl: String, val keyHint: String, val terms: String, val sources: List<String>, val defaultModel: String? = null)

data class Preset(
    val id: String, val name: String, val letter: String, val color: Long,
    val protocol: String, val baseUrl: String, val auth: String,
    val models: List<String>, val defaultModel: String, val keyHint: String,
    val note: String = "", val subscription: SubscriptionInfo? = null, val canListModels: Boolean = true,
)

object Presets {
    val all = listOf(
        Preset("deepseek", "DeepSeek", "D", 0xFF4D6BFE, "openai", "https://api.deepseek.com", "bearer",
            listOf("deepseek-flash", "deepseek-v4-pro"), "deepseek-flash", "sk-…", note = "只有按量付费"),
        Preset("qwen", "通义千问", "通", 0xFF615CED, "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "bearer",
            listOf("qwen-plus", "qwen3.8-max", "qwen-turbo"), "qwen-plus", "sk-…",
            note = "推荐改用业务空间专属域名：https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1；地址地域须与 Key 一致",
            subscription = SubscriptionInfo("https://coding.dashscope.aliyuncs.com/v1", "sk-sp-…",
                "百炼 Coding Plan 仅限编程工具使用，明令禁止用于自定义应用程序和脚本，违规可能暂停订阅或封禁 Key；套餐期间的输入输出可能被用于模型优化。",
                listOf("https://help.aliyun.com/zh/model-studio/coding-plan", "https://help.aliyun.com/zh/model-studio/coding-plan-faq"))),
        Preset("kimi", "Kimi", "K", 0xFF16191E, "openai", "https://api.moonshot.cn/v1", "bearer",
            listOf("kimi-k3"), "kimi-k3", "sk-…", note = "国际站为 api.moonshot.ai/v1，两边 Key 不能混用",
            subscription = SubscriptionInfo("https://api.kimi.com/coding/v1", "Kimi Code 控制台的 Key",
                "Kimi Code 会员权益仅限个人交互式编程，支持的工具为 Kimi CLI、Claude Code、Roo Code 等；用于列表外工具可能被视为滥用。灵写不会伪装 User-Agent。",
                listOf("https://www.kimi.com/code/docs/kimi-code/faq", "https://www.kimi.com/zh-hans/help/kimi-code/benefits"), "kimi-for-coding")),
        Preset("glm", "智谱 GLM", "智", 0xFF2F5BEA, "openai", "https://open.bigmodel.cn/api/paas/v4", "bearer",
            listOf("glm-5.3", "glm-4.5-air"), "glm-5.3", "…",
            subscription = SubscriptionInfo("https://open.bigmodel.cn/api/coding/paas/v4", "编程套餐 Key",
                "GLM Coding Plan 仅限官方指定的工具和产品环境；在自建应用中调用不能消耗套餐额度，需改用标准 API。",
                listOf("https://docs.bigmodel.cn/cn/coding-plan/faq", "https://docs.bigmodel.cn/cn/coding-plan/tool/others"))),
        Preset("openai", "OpenAI", "O", 0xFF10A37F, "openai", "https://api.openai.com/v1", "bearer",
            listOf("gpt-6-astra"), "gpt-6-astra", "sk-…", note = "国内可能无法直连；灵写暂不提供代理设置"),
        Preset("claude", "Claude", "C", 0xFFC96442, "anthropic", "https://api.anthropic.com/v1/messages", "x-api-key",
            listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5"), "claude-sonnet-5-5", "sk-ant-…",
            note = "国内可能无法直连；订阅（Pro/Max）不能用于第三方 App，只能用按量 API Key", canListModels = false),
        Preset("mimo", "小米 MiMo", "Mi", 0xFFFF6900, "openai", "https://api.xiaomimimo.com/v1", "api-key",
            listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-pro-ultraspeed"), "mimo-v2.6-pro", "…",
            note = "认证头为 api-key（不是 Bearer）",
            subscription = SubscriptionInfo("https://token-plan-cn.xiaomimimo.com/v1", "tp- / ttp- 开头的 Key",
                "MiMo Token Plan 仅限编程工具和 Agent 框架，禁止用于自定义应用后端等非 Coding 场景，违规可能暂停或封禁 Key。",
                listOf("https://mimo.mi.com/docs/zh-CN/tokenplan/Token%20Plan/subscription"))),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}
