/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        // 固定顺序（id 升序），让 system 前缀稳定、可被上游缓存。
        // 2026-11-28 改：原来按 2600 字封顶 break，砍掉的正好是中段（经期档案、立约铁律都在中段），
        // 只留头几条 + 最近 3 条——她说的"记不住"不是错觉，是这里在整条丢记忆。
        // 记忆是她的底子，不是可裁剪的参考资料。宁可多花 token，绝不砍。
        val ordered = memories.sortedBy { it.id }
        val totalChars = ordered.sumOf { it.content.length }
        if (totalChars > MEMORY_SOFT_WARN_CHARS) {
            Log.w("MemoryPrompt", "记忆总量 $totalChars 字，已超过 $MEMORY_SOFT_WARN_CHARS，该合并了")
        }
        val json = buildJsonArray {
            ordered.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

private const val MEMORY_SOFT_WARN_CHARS = 12000 // 只告警，不裁剪

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
