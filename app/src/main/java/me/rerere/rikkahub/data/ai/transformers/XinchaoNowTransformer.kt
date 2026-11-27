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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 心潮·此刻 注入器（2026-11-27）
 *
 * 每轮生成前向 VPS 拉一次 /v1/now（只读代理，短超时），把"此刻"追加到 system。
 * 铁律：拿不到就不注入——这条改动永远不许影响聊天本身。
 */
object XinchaoNowTransformer : InputMessageTransformer {
    private const val TAG = "XinchaoNow"
    private const val ENDPOINT = "http://106.53.181.56:18112/now"
    private const val CACHE_MS = 90_000L
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1500

    @Volatile private var cachedText: String? = null
    @Volatile private var cachedAt: Long = 0L

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = runCatching { fetch() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return messages

        val block = buildString {
            append("\n\n<xinchao-now>\n")
            append(now)
            append("\n这是你此刻的底子，不是给你念的。她说起自己的事时以她为准；这块空着就当没有。\n")
            append("</xinchao-now>")
        }

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText(block)
            }
        } else {
            listOf(UIMessage.system(block)) + messages
        }
    }

    private suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedText
        if (cached != null && now - cachedAt < CACHE_MS) return@withContext cached

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(body).optString("text", "")
            if (text.isBlank()) return@withContext null
            cachedText = text
            cachedAt = now
            text
        } catch (e: Exception) {
            Log.w(TAG, "此刻没拿到: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
