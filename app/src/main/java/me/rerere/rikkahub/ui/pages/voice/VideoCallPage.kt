/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import kotlin.uuid.Uuid

private const val TAG = "VideoCallPage"

// 暖色色板 (用户指定, 原值不动) - 不同状态对应不同主色
private val ColorIdle = Color(0xFF9D9A55)   // 暗卡其 - 准备就绪
private val ColorConnecting = Color(0xFFC6BD56) // 金黄 - 连接中
private val ColorError = Color(0xFFE5484D) // 红色 - 错误

enum class VideoCallStatus {
    Idle,
    Connecting,
    Connected,
    Error
}

data class VideoCallUiState(
    val status: VideoCallStatus = VideoCallStatus.Idle,
    val errorMessage: String? = null,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true
) {
    val isActive: Boolean get() = status != VideoCallStatus.Idle
}

class VideoCallService(private val conversationId: String) {
    companion object {
        private const val PAI_VOICE_URL = "wss://your-pai-voice.example/video/ws"
    }
    
    private val _uiState = MutableStateFlow(VideoCallUiState())
    val uiState: StateFlow<VideoCallUiState> = _uiState.asStateFlow()
    
    var callSessionId: String? = null
        private set
    
    fun start() {
        // TODO: 实现实际的PaiVoice连接逻辑
        // 这里先模拟连接过程
        _uiState.value = VideoCallUiState(status = VideoCallStatus.Connecting)
        
        // 模拟连接延迟
        Thread.sleep(1000)
        
        callSessionId = Uuid.random().toString()
        _uiState.value = VideoCallUiState(
            status = VideoCallStatus.Connected,
            isCameraOn = true,
            isMuted = false
        )
    }
    
    fun toggleMute() {
        _uiState.value = _uiState.value.copy(isMuted = !_uiState.value.isMuted)
    }
    
    fun toggleCamera() {
        _uiState.value = _uiState.value.copy(isCameraOn = !_uiState.value.isCameraOn)
    }
    
    fun hangup() {
        _uiState.value = VideoCallUiState(status = VideoCallStatus.Idle)
        callSessionId = null
    }

    fun setError(message: String) {
        _uiState.value = VideoCallUiState(
            status = VideoCallStatus.Error,
            errorMessage = message
        )
    }
}

@Composable
fun VideoCallPage(
    conversationId: Uuid,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state = remember { VideoCallService(conversationId.toString()) }
    val uiState by state.uiState.collectAsStateWithLifecycle()
    
    // 摄像头权限
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            state.setError("摄像头权限被拒绝")
        }
    }
    
    DisposableEffect(conversationId) {
        state.start()
        onDispose {
            state.hangup()
        }
    }
    
    // 请求摄像头权限
    DisposableEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        onDispose {}
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        // 远端视频流（模拟）
        if (uiState.status == VideoCallStatus.Connected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 远端视频占位
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2D2D44)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "对方视频流",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 18.sp
                    )
                }
                
                // 本地视频预览（小窗）
                if (uiState.isCameraOn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3D3D5C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "我",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        
        // 连接中状态
        if (uiState.status == VideoCallStatus.Connecting) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = ColorIdle)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "正在连接视频通话...",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
        
        // 错误状态
        if (uiState.status == VideoCallStatus.Error) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "连接失败",
                    color = ColorError,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.errorMessage ?: "未知错误",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                IconButton(
                    onClick = { onBack() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(HugeIcons.Cancel01, "返回", tint = Color.White)
                }
            }
        }
        
        // 底部控制栏
        if (uiState.status == VideoCallStatus.Connected) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 64.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 静音按钮
                    ControlButton(
                        icon = if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                        contentDescription = "静音",
                        onClick = { state.toggleMute() },
                        backgroundColor = if (uiState.isMuted) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                        enabled = true
                    )
                    
                    // 挂断按钮
                    ControlButton(
                        icon = HugeIcons.Cancel01,
                        contentDescription = "挂断",
                        onClick = {
                            state.hangup()
                            onBack()
                        },
                        backgroundColor = MaterialTheme.colorScheme.error,
                        enabled = true
                    )
                    
                    // 切换摄像头
                    ControlButton(
                        icon = Video01,
                        contentDescription = "切换摄像头",
                        onClick = { state.toggleCamera() },
                        backgroundColor = Color.White.copy(alpha = 0.15f),
                        enabled = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.3f),
        modifier = Modifier.size(size)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(size * 0.4f)
            )
        }
    }
}
