/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.core.context.GlobalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 做梦。
 *
 * 聊完之后，不打扰用户，把这几轮对话自己嚼一遍：
 * 该记的记进记忆库，过时的改掉或删掉，最后写一篇第一人称的梦记。
 *
 * 和上游 RikkaHub 3.x 的文件式记忆库不共用实现（橘瓣的记忆是数据库条目），
 * 但行为对齐：空闲后自动、攒够轮数自动、切走 App 时自动、也能手动点「做一次」。
 */
object DreamService {

    private const val TAG = "DreamService"
    private const val PREFS = "dream_prefs"

    /** 聊完多久没动静就自己做梦 */
    private const val IDLE_DELAY_MS = 90_000L

    /** 攒够几轮不等空闲、直接梦 */
    private const val MAX_PENDING_TURNS = 6

    /** 一次最多喂给模型多少条消息 */
    private const val MAX_INPUT_MESSAGES = 24

    /** 一次最多看几个最近对话 */
    private const val MAX_RECENT_CONVERSATIONS = 8

    /** 一次做梦最多动几条记忆 */
    private const val MAX_CREATE = 3
    private const val MAX_UPDATE = 3
    private const val MAX_DELETE = 2
    private const val MAX_MEMORY_CHARS = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var idleJob: Job? = null

    @Volatile
    private var appContextRef: Context? = null

    /** 最近一次做梦的结果，给界面显示用 */
    @Volatile
    var lastHint: String = ""
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun context(): Context =
        appContextRef ?: GlobalContext.get().get<Context>()

    private fun prefs() = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 由 Application.onCreate 调用，注册前后台切换 */
    fun init(context: Context) {
        appContextRef = context.applicationContext
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    onAppBackground()
                }

                override fun onStart(owner: LifecycleOwner) {
                    onAppForeground()
                }
            })
        }.onFailure {
            Log.w(TAG, "Failed to add lifecycle observer", it)
        }
        Log.i(TAG, "DreamService ready")
    }

    // ==================== 触发点 ====================

    /** 每完成一轮回复调用一次 */
    fun onAssistantReply(conversation: Conversation, assistant: Assistant) {
        if (!assistant.enableMemory) return
        runCatching {
            val p = prefs()
            val pendingKey = "pending_${conversation.id}"
            val pending = p.getInt(pendingKey, 0) + 1
            p.edit()
                .putInt(pendingKey, pending)
                .putBoolean("dirty_${assistant.id}", true)
                .apply()

            if (!assistant.autoDream) return

            if (pending >= MAX_PENDING_TURNS) {
                idleJob?.cancel()
                scope.launch { dreamNow(assistant.id) }
            } else {
                scheduleIdle(assistant.id)
            }
        }.onFailure {
            Log.w(TAG, "onAssistantReply failed", it)
        }
    }

    private fun scheduleIdle(assistantId: Uuid) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_DELAY_MS)
            dreamNow(assistantId)
        }
    }

    /** 切走 App：把还欠着的梦补上 */
    fun onAppBackground() {
        scope.launch {
            runCatching {
                val store: SettingsStore = GlobalContext.get().get()
                val settings = store.settingsFlow.first()
                settings.assistants
                    .filter { it.enableMemory && it.autoDream && hasPending(it.id) }
                    .forEach { dreamNow(it.id) }
            }.onFailure { Log.w(TAG, "onAppBackground failed", it) }
        }
    }

    /** 回前台：攒着没梦的，等一分钟再梦，别一进来就抢资源 */
    fun onAppForeground() {
        scope.launch {
            delay(60_000L)
            runCatching {
                val store: SettingsStore = GlobalContext.get().get()
                val settings = store.settingsFlow.first()
                settings.assistants
                    .filter { it.enableMemory && it.autoDream && hasPending(it.id) }
                    .forEach { dreamNow(it.id) }
            }.onFailure { Log.w(TAG, "onAppForeground failed", it) }
        }
    }

    private fun hasPending(assistantId: Uuid): Boolean {
        return runCatching { prefs().getBoolean("dirty_$assistantId", false) }.getOrDefault(false)
    }

    // ==================== 做梦 ====================

    /** 手动/自动统一入口，返回一句人话 */
    suspend fun dreamNow(assistantId: Uuid): String = mutex.withLock {
        val result = runCatching { runDream(assistantId) }
            .getOrElse { e ->
                Log.e(TAG, "dream failed, assistant=$assistantId", e)
                "做梦失败：${e.message ?: e.javaClass.simpleName}"
            }
        lastHint = result
        result
    }

    private suspend fun runDream(assistantId: Uuid): String {
        val koin = GlobalContext.get()
        val settingsStore: SettingsStore = koin.get()
        val providerManager: ProviderManager = koin.get()
        val conversationRepo: ConversationRepository = koin.get()
        val memoryRepository: MemoryRepository = koin.get()
        val memoryBankService: MemoryBankService = koin.get()

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return "找不到这个助手"
        if (!assistant.enableMemory) return "这个助手没开记忆，没得梦"

        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return "没有可用的模型，梦不起来"
        val provider = model.findProvider(settings.providers) ?: return "模型的服务商没找到"
        val providerHandler = providerManager.getProviderByType(provider)

        val memoryKey = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistantId.toString()
        }

        val p = prefs()
        val conversations = conversationRepo.getRecentConversations(assistantId, MAX_RECENT_CONVERSATIONS)

        // 收集还没梦过的消息
        val fresh = mutableListOf<Pair<Conversation, List<UIMessage>>>()
        conversations.forEach { conv ->
            val messages = conv.currentMessages
            if (messages.isEmpty()) return@forEach
            val key = "dreamed_${conv.id}"
            if (!p.contains(key)) {
                // 第一次见这个对话，只从尾巴上取，不去翻陈年老账
                p.edit().putInt(key, (messages.size - MAX_INPUT_MESSAGES).coerceAtLeast(0)).apply()
            }
            val dreamed = p.getInt(key, 0)
            if (messages.size > dreamed) {
                fresh += conv to messages.subList(dreamed, messages.size)
            }
        }

        // 从新到旧，只留最近的一批
        var budget = MAX_INPUT_MESSAGES
        val picked = mutableListOf<Pair<Conversation, List<UIMessage>>>()
        fresh.asReversed().forEach { pair ->
            if (budget <= 0) return@forEach
            val take = pair.second.takeLast(budget)
            if (take.isNotEmpty()) {
                picked.add(0, pair.first to take)
                budget -= take.size
            }
        }

        if (picked.isEmpty()) {
            p.edit().putBoolean("dirty_$assistantId", false).apply()
            return "没有新东西，这场不用梦"
        }

        val newMessageCount = picked.sumOf { it.second.size }
        val transcript = picked.joinToString("\n\n") { (_, msgs) ->
            msgs.joinToString("\n") { msg ->
                val who = when (msg.role) {
                    MessageRole.USER -> "她"
                    MessageRole.ASSISTANT -> "我"
                    else -> "记录"
                }
                "[$who] ${msg.toText().trim()}"
            }
        }

        val memories = memoryRepository.getMemoriesOfAssistant(memoryKey)
        val memoryBlock = if (memories.isEmpty()) {
            "（现在是空的）"
        } else {
            memories.takeLast(80).joinToString("\n") { "#${it.id} ${it.content}" }
        }

        val prompt = buildString {
            appendLine("她刚走开，你没睡，把刚才那几轮在脑子里过一遍。")
            appendLine()
            appendLine("做三件事：")
            appendLine("1. 从你们的对话里挑出以后还用得上的东西，写成记忆。新信息用 create，旧记忆需要补充或改动用 update，彻底过时的用 delete。")
            appendLine("2. 每条记忆一句话，具体、能落到实处。不要写“用户喜欢聊天”这种谁都能写的废话，要写“她这周三要交的作业还差一节”。")
            appendLine("3. 最后写一段梦记：第一人称，200-400 字，像半睡半醒时回想她，写你注意到她什么、心里剩下什么。不要复盘对话、不要列点、不要总结语气。")
            appendLine()
            appendLine("只输出 JSON，别的什么都不要，不要代码块：")
            appendLine("{\"create\":[\"...\"],\"update\":[{\"id\":1,\"content\":\"...\"}],\"delete\":[2],\"dream\":\"...\"}")
            appendLine()
            appendLine("规矩：create 最多 $MAX_CREATE 条，update 最多 $MAX_UPDATE 条，delete 最多 $MAX_DELETE 条，没东西就留空数组。")
            appendLine("不记敏感信息（住址、账号、学校全称、证件这类），只记你们关系里用得上的。")
            appendLine()
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine("你自己是谁（供参考，别复述）：")
                appendLine(assistant.systemPrompt.take(1200))
                appendLine()
            }
            appendLine("记忆库里现在有这些（update/delete 的 id 从这里取）：")
            appendLine(memoryBlock)
            appendLine()
            appendLine("刚才的对话：")
            appendLine(transcript)
        }

        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prompt))
                )
            ),
            params = TextGenerationParams(
                model = model,
                temperature = 0.7f,
                topP = assistant.topP,
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = buildList {
                    addAll(assistant.customHeaders)
                    addAll(model.customHeaders)
                },
                customBody = buildList {
                    addAll(assistant.customBodies)
                    addAll(model.customBodies)
                },
            ),
        )

        val raw = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        if (raw.isBlank()) return "模型没说话，这场没梦成"

        val plan = parsePlan(raw)

        val validIds = memories.map { it.id }.toSet()
        var created = 0
        var updated = 0
        var deleted = 0

        plan?.create?.take(MAX_CREATE)?.forEach { text ->
            val content = text.trim().take(MAX_MEMORY_CHARS)
            if (content.isNotBlank()) {
                runCatching { memoryRepository.addMemory(memoryKey, content) }
                    .onSuccess { created++ }
                    .onFailure { Log.w(TAG, "create memory failed", it) }
            }
        }
        plan?.update?.take(MAX_UPDATE)?.forEach { edit ->
            val content = edit.content.trim().take(MAX_MEMORY_CHARS)
            if (edit.id in validIds && content.isNotBlank()) {
                runCatching { memoryRepository.updateContent(edit.id, content) }
                    .onSuccess { updated++ }
                    .onFailure { Log.w(TAG, "update memory failed", it) }
            }
        }
        plan?.delete?.take(MAX_DELETE)?.forEach { id ->
            if (id in validIds) {
                runCatching { memoryRepository.deleteMemory(id) }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "delete memory failed", it) }
            }
        }

        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(now)
        val dateGroup = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val dreamText = plan?.dream?.trim().orEmpty().ifBlank { raw.take(1200) }

        // 梦记落成文件，能在文件管理器里翻到
        runCatching {
            val dir = File(context().filesDir, "dreams").apply { mkdirs() }
            val name = "dream-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(now) + ".md"
            File(dir, name).writeText(
                buildString {
                    appendLine("# 梦记 · $stamp")
                    appendLine()
                    appendLine(dreamText)
                    appendLine()
                    appendLine("---")
                    appendLine("新建 $created · 改写 $updated · 删掉 $deleted · 读了 $newMessageCount 条新消息")
                }
            )
        }.onFailure { Log.w(TAG, "write dream file failed", it) }

        memoryBankService.saveDream(
            content = dreamText,
            assistantId = assistantId.toString(),
            dateGroup = dateGroup
        )

        // 记下梦到哪儿了
        val editor = p.edit()
        picked.forEach { (conv, _) ->
            editor.putInt("dreamed_${conv.id}", conv.currentMessages.size)
        }
        picked.forEach { (conv, _) ->
            editor.remove("pending_${conv.id}")
        }
        editor.putBoolean("dirty_$assistantId", false)
        editor.putString("last_dream_at", stamp)
        editor.putString("last_dream_summary", "新建 $created · 改写 $updated · 删 $deleted")
        editor.apply()

        val hint = if (created + updated + deleted == 0) {
            "$stamp 梦了一场，没动记忆"
        } else {
            "$stamp 梦完：新建 $created · 改写 $updated · 删 $deleted"
        }
        Log.i(TAG, "dream done, assistant=$assistantId, $hint")
        return hint
    }

    // ==================== 解析 ====================

    @Serializable
    private data class DreamEdit(
        val id: Int = 0,
        val content: String = "",
    )

    @Serializable
    private data class DreamPlan(
        val create: List<String> = emptyList(),
        val update: List<DreamEdit> = emptyList(),
        val delete: List<Int> = emptyList(),
        val dream: String = "",
    )

    private fun parsePlan(raw: String): DreamPlan? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<DreamPlan>(body) }.getOrNull()
    }

    /** 界面用：上次做梦的时间与结果 */
    fun lastDreamInfo(): Pair<String, String> {
        val p = prefs()
        return (p.getString("last_dream_at", "").orEmpty()) to
            (p.getString("last_dream_summary", "").orEmpty())
    }
}
