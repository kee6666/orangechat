/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "ImageRetirementTransformer"

/**
 * 图片退役转换器：
 * 历史消息中的图片只保留「最新一条用户消息」里的原图，
 * 更早的图片自动转成 OCR 文字描述（复用 OcrTransformer，带缓存），
 * 避免每次请求都把历史截图重新打包成 base64 导致输入超长（~1MiB 硬限制）。
 *
 * 效果：修 bug 时发的截图，最新一张我能看清；它进入历史后再请求时，
 * 自动变成文字描述，体积从几十万字符降到几百字符。
 */
object ImageRetirementTransformer : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 找到最后一条用户消息的位置（含）—— 这部分保留原图
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return messages

        // 检查历史（lastUserIndex 之前）是否有 file:// 图片需要退役
        val hasRetirable = messages.take(lastUserIndex).any { msg ->
            msg.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") } ||
                msg.parts.any { part ->
                    part is UIMessagePart.Tool &&
                        part.output.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
                }
        }
        if (!hasRetirable) return messages

        Log.i(TAG, "retiring historical images before index=$lastUserIndex")

        return withContext(Dispatchers.IO) {
            messages.mapIndexed { index, message ->
                if (index >= lastUserIndex) {
                    // 最新用户消息：保留原图，模型需要看清最新的截图
                    message
                } else {
                    // 历史消息：图片退役 -> 文字描述
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(retireImage(part))
                                }

                                part is UIMessagePart.Tool -> {
                                    part.copy(
                                        output = part.output.map { outputPart ->
                                            if (outputPart is UIMessagePart.Image && outputPart.url.startsWith("file:")) {
                                                UIMessagePart.Text(retireImage(outputPart))
                                            } else {
                                                outputPart
                                            }
                                        }
                                    )
                                }

                                else -> part
                            }
                        }
                    )
                }
            }
        }
    }

    /** 尝试 OCR 识别成文字描述；失败则退化为占位符 */
    private suspend fun retireImage(part: UIMessagePart.Image): String {
        val ocr = OcrTransformer.performOcr(part)
        return if (ocr.startsWith("[ERROR") || ocr.startsWith("[Image]")) {
            "[历史截图已省略：模型已在此前对话中看过该图，如需再看请重新发送]"
        } else {
            ocr
        }
    }
}
