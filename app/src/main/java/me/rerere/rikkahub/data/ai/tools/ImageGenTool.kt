/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * 聊天里直接生图。用阿年自己配的生图渠道，App 本机直连，不经过 VPS。
 * 生成完把图作为消息附件返回，气泡里直接显示。
 */
fun createImageGenTool(context: Context): Tool {
    return Tool(
        name = "generate_image",
        description = "用阿年配好的生图渠道生成图片，并把图片直接发在聊天里。" +
            "走 App 本机直连，不经过服务器。她说想看图、要图、画一张、生成图、来张图的时候调用。" +
            "prompt 要写得具体：主体、风格、光线、构图。",
        needsApproval = false,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "画面描述，越具体越好：主体、风格、光线、构图")
                    }
                    putJsonObject("aspect_ratio") {
                        put("type", "string")
                        put("description", "square / landscape / portrait，默认 square")
                    }
                    putJsonObject("n") {
                        put("type", "integer")
                        put("description", "生成几张，1 到 4，默认 1")
                    }
                    putJsonObject("model") {
                        put("type", "string")
                        put("description", "可选。指定生图模型名，不填就用生图页当前选中的那个")
                    }
                },
                required = listOf("prompt")
            )
        },
        execute = { args ->
            val p = args.jsonObject
            val prompt = p["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (prompt.isBlank()) {
                return@Tool listOf(UIMessagePart.Text("""{"success":false,"error":"prompt 是空的"}"""))
            }
            val ratio = when (p["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "landscape", "横" -> ImageAspectRatio.LANDSCAPE
                "portrait", "竖" -> ImageAspectRatio.PORTRAIT
                else -> ImageAspectRatio.SQUARE
            }
            val n = (p["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 4)
            val wanted = p["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            try {
                val koin = GlobalContext.get()
                val settings = koin.get<SettingsStore>().settingsFlow.first()
                val providerManager = koin.get<ProviderManager>()

                val allModels = settings.providers.flatMap { it.models }
                val picked = if (wanted.isNotBlank()) {
                    allModels.firstOrNull { it.modelId == wanted || it.displayName == wanted }
                } else {
                    null
                }
                val model = picked
                    ?: settings.findModelById(settings.imageGenerationModelId)
                    ?: allModels.firstOrNull { it.type == ModelType.IMAGE }
                    ?: return@Tool listOf(
                        UIMessagePart.Text(
                            """{"success":false,"error":"没有可用的生图模型。去 设置-模型 把生图渠道的模型类型选成 图片，再到生图页选一下。"}"""
                        )
                    )

                val provider = model.findProvider(settings.providers)
                    ?: return@Tool listOf(
                        UIMessagePart.Text("""{"success":false,"error":"这个模型找不到所属服务商，检查模型配置"}""")
                    )
                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: return@Tool listOf(
                        UIMessagePart.Text("""{"success":false,"error":"服务商配置缺失"}""")
                    )

                val params = ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = n,
                    aspectRatio = ratio,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val result = providerManager.getProviderByType(provider)
                    .generateImage(providerSetting, params)

                if (result.items.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text("""{"success":false,"error":"渠道返回了空结果"}""")
                    )
                }

                val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
                val parts = mutableListOf<UIMessagePart>()
                result.items.forEachIndexed { i, item ->
                    val bytes = Base64.decode(item.data, Base64.DEFAULT)
                    val f = File(dir, "gen_${System.currentTimeMillis()}_${i}.png")
                    f.writeBytes(bytes)
                    parts.add(UIMessagePart.Image(url = "file://${f.absolutePath}"))
                }
                parts.add(
                    0,
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("model", model.displayName)
                            put("count", result.items.size)
                        }.toString()
                    )
                )
                parts
            } catch (e: Exception) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "生图失败: ${e.message}")
                        }.toString()
                    )
                )
            }
        }
    )
}