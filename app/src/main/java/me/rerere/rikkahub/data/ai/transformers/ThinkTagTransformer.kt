/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

private val THINKING_REGEX = Regex(" thinking([\\s\\S]*?)(?: response|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex(" response")

// 元认知/思维链泄漏兜底：
// 部分模型会把内部思考以 [metacognition] / [analysis] / [reasoning] 为标题的纯文本块
// 直接输出到正文（而不是 thinking 标签对），导致用户看到一堆"分析内容/指导内容"。
// 兜底策略：识别这类块，剥离为 Reasoning 组件，正文保留最后一个自然段。
private val METACOGNITION_OPEN_REGEX = Regex(
    """^\s*\[?(?:metacognition|analysis|reasoning|thinking)\]?\s*[:,：]?\s*$""",
    RegexOption.IGNORE_CASE
)
private val PARAGRAPH_SPLIT_REGEX = Regex("""\n\s*\n""")

/**
 * 检测形如 "[metacognition]" 开头的思考块。
 * 返回 Pair(正文, 思考内容)：正文保留最后一个自然段，思考为块内全部文本。
 * 若不以该标记开头，原样返回 (text, null)。
 */
private fun stripMetacognition(text: String): Pair<String, String?> {
    val firstLine = text.lineSequence().firstOrNull() ?: return text to null
    if (!METACOGNITION_OPEN_REGEX.matches(firstLine)) return text to null

    val after = text.substringAfter('\n').trim()
    if (after.isBlank()) return "" to text

    val segments = after.split(PARAGRAPH_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "" to text

    // 思考块 = 除去最后一段前的所有内容；正文 = 最后一段
    val body = segments.last()
    val reasoning = text.substring(0, text.indexOf(body)).trim()
    return body to reasoning
}

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            splitReasoning(part, message, finished = false)
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            splitReasoning(part, message, finished = true)
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    private fun splitReasoning(
        part: UIMessagePart.Text,
        message: UIMessage,
        finished: Boolean,
    ): List<UIMessagePart> {
        var text = part.text
        var reasoning: String? = null
        var hasClosingTag = false

        // 1) thinking 标签对
        if (THINKING_REGEX.containsMatchIn(text)) {
            reasoning = THINKING_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim() ?: ""
            hasClosingTag = CLOSING_TAG_REGEX.containsMatchIn(text)
            text = text.replace(THINKING_REGEX, "")
        }

        // 2) [metacognition] 等开头的思考块
        val (stripped, metaReason) = stripMetacognition(text)
        if (metaReason != null) {
            reasoning = if (reasoning.isNullOrBlank()) metaReason else reasoning + "\n" + metaReason
            text = stripped
            hasClosingTag = true
        }

        if (reasoning.isNullOrBlank()) return listOf(part)

        return listOf(
            UIMessagePart.Reasoning(
                reasoning = reasoning,
                createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                finishedAt = if (finished || hasClosingTag) Clock.System.now() else null,
            ),
            part.copy(text = text),
        )
    }
}
