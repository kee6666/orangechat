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
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 心潮·此刻 注入器（2026-11-27）
 *
 * 每轮生成前向 VPS 拉一次 /v1/now（只读代理，短超时），把"此刻"追加到最新一条 user 消息。
 * 铁律：拿不到就不注入——这条改动永远不许影响聊天本身。
 *
 * 缓存友好（2026-11-27 改）：此刻每 90 秒就变。以前追加在 system 末尾，system 一变
 * 就把后面整段历史的前缀缓存全打掉了。现在挂到最后一条 user 消息上——system 和落库的
 * 历史保持恒定，只有这条最新消息需要重算，前面一大截都能命中上游缓存。
 *
 * 真实身体（2026-11-28 改）：心潮那边的"心跳"原本是脉算法自己算出来的，跟手表无关。
 * 现在每轮从 Gadgetbridge 导出的库读一次实测心率，作为 ?hr= 一起带上去，服务端优先用它。
 * 读不到就老实不传，让它退回算的——不编数据。缓存同时从 90 秒压到 30 秒。
 */
object XinchaoNowTransformer : InputMessageTransformer {
    private const val TAG = "XinchaoNow"
    private const val ENDPOINT = "http://106.53.181.56:18112/now"
    private const val CACHE_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1500
    private const val HR_FRESH_MS = 15 * 60_000L

    @Volatile private var cachedText: String? = null
    @Volatile private var cachedAt: Long = 0L

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = runCatching { fetch(ctx) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return messages

        val block = buildString {
            append("\n\n<xinchao-now>\n")
            append(now)
            append("\n这是你此刻的底子，不是给你念的。她说起自己的事时以她为准；这块空着就当没有。\n")
            append("</xinchao-now>")
        }

        // 优先挂到最后一条 user 消息：绝不动 system，保住前缀缓存
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex >= 0) {
            return messages.toMutableList().apply {
                this[lastUserIndex] = this[lastUserIndex].appendText(block)
            }
        }
        // 兜底：没有 user 消息时才退回 system（极少发生）
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText(block)
            }
        } else {
            listOf(UIMessage.system(block)) + messages
        }
    }

    /** 读一次手表最新采样。读不到就是 null，不猜。 */
    private fun readWatchHeartRate(ctx: TransformerContext): Pair<Int, Long>? = runCatching {
        val dbPath = ctx.settings.systemToolsSetting.gadgetbridgeDbPath
        val sample = GadgetbridgeReader.readLatestActivitySample(dbPath) ?: return@runCatching null
        val hr = sample.heartRate ?: return@runCatching null
        if (hr < 30 || hr > 220) return@runCatching null
        val at = System.currentTimeMillis()
        val sampleAt = if (sample.timestamp > 0) sample.timestamp else at
        // 采样太旧（超过 15 分钟）就当没有，免得把上一段的心跳当成现在
        if (at - sampleAt > HR_FRESH_MS) return@runCatching null
        hr to sampleAt
    }.getOrNull()

    private suspend fun fetch(ctx: TransformerContext): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedText
        if (cached != null && now - cachedAt < CACHE_MS) return@withContext cached

        val watch = runCatching { readWatchHeartRate(ctx) }.getOrNull()
        val urlText = if (watch != null) "$ENDPOINT?hr=${watch.first}&hrAt=${watch.second}" else ENDPOINT

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
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
