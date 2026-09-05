/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}

/**
 * 过滤"悬空的主动消息碎片"：连续堆积、无人回复的 assistant 纯文本消息。
 *
 * 主动消息（ProactiveMessageService / L3网关头）生成的内容会以 ASSISTANT 角色写入
 * 对话历史，用于 UI 展示和连续性。但如果用户没有回复，这些消息在下次请求时会被
 * 当作普通 assistant 消息发给模型，形成"自说自话"的碎片流（多连发时尤其严重），
 * 污染模型上下文。
 *
 * 规则（不依赖新增标记，对历史存量数据同样生效）：
 * - 将连续的"纯文本 assistant 消息"视为一段（含工具/图片/推理的 assistant 消息不处理）
 * - 遇到下一段消息时：
 *   - 若是 USER 消息（用户回复了）→ 保留段内最后一条（作为回复的引导上下文）
 *   - 否则（system / 对话结束等）→ 整段丢弃（无人回复的自嗨碎片）
 * - 对话末尾的悬空段一律丢弃
 *
 * 这样 UI 历史完全不受影响，只有发给模型的消息流被净化，正常一问一答不受影响。
 */
internal fun filterProactiveNoise(messages: List<UIMessage>): List<UIMessage> {
    if (messages.isEmpty()) return messages

    val result = mutableListOf<UIMessage>()
    val pending = mutableListOf<UIMessage>()  // 暂存连续的纯文本 assistant 消息

    fun isPureTextAssistant(msg: UIMessage): Boolean =
        msg.role == MessageRole.ASSISTANT &&
            msg.parts.isNotEmpty() &&
            msg.parts.all { it is UIMessagePart.Text }

    fun flush(keepLast: Boolean) {
        if (pending.isNotEmpty()) {
            if (keepLast) result.add(pending.last())
            pending.clear()
        }
    }

    for (msg in messages) {
        if (isPureTextAssistant(msg)) {
            pending.add(msg)
        } else {
            // 段结束：只有紧跟着 USER 消息才保留段内最后一条
            flush(keepLast = msg.role == MessageRole.USER)
            result.add(msg)
        }
    }
    // 末尾悬空段：无人回复，丢弃
    flush(keepLast = false)
    return result
}
