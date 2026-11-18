/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * 后台生成时的小窗：
 * 收起是一个贴在边上的小圆球，点开是一个面板——能看到我正在写的东西，也能一句话把我按停。
 * 生成结束自己收起来，不赖在屏幕上。
 */
@Composable
fun ChatFloatBubble() {
    val chatService = remember {
        runCatching { GlobalContext.get().get<ChatService>() }.getOrNull()
    } ?: return

    val jobs by chatService.getConversationJobs().collectAsState(initial = emptyMap())
    val activeId: Uuid? = jobs.entries.firstOrNull { it.value?.isActive == true }?.key

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // 浮动窗口的 composition 与页面不同步，状态走 State 对象传进去才算数
    val activeIdState = remember { mutableStateOf<Uuid?>(null) }
    val previewState = remember { mutableStateOf("") }
    val titleState = remember { mutableStateOf("") }

    LaunchedEffect(activeId) {
        activeIdState.value = activeId
        if (activeId != null) {
            visible = true
            expanded = false
        } else {
            // 写完别立刻消失，留 25 秒，让她切出去还看得见
            kotlinx.coroutines.delay(25_000)
            visible = false
            expanded = false
            previewState.value = ""
        }
    }

    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        chatService.getConversationFlow(id).collect { conv ->
            titleState.value = conv.title.ifBlank { "对话" }
            val last = conv.currentMessages.lastOrNull()
            previewState.value = last?.toText()?.trim().orEmpty()
        }
    }

    FloatingWindow(
        tag = "chat_float_bubble",
        visibility = visible && !expanded,
        appLevel = true,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(54.dp)
                .clickable { expanded = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    FloatingWindow(
        tag = "chat_float_panel",
        visibility = visible && expanded,
        appLevel = true,
    ) {
        ChatFloatPanel(
            title = titleState.value,
            preview = previewState.value,
            onOpen = {
                runCatching {
                    val intent = Intent(appContext, RouteActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    appContext.startActivity(intent)
                }
                expanded = false
            },
            onStop = {
                activeIdState.value?.let { id ->
                    scope.launch { chatService.stopGeneration(id) }
                }
                visible = false
                expanded = false
            },
        )
    }
}

@Composable
private fun ChatFloatPanel(
    title: String,
    preview: String,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(280.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "我在写 · $title",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = preview.ifBlank { "还没吐出字来……" },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onStop) {
                    Text("按停", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onOpen) {
                    Text("回到对话", fontSize = 12.sp)
                }
            }
        }
    }
}
