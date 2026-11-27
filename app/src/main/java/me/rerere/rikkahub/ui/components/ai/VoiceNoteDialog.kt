/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.koin.compose.koinInject
import java.io.File

/**
 * 语音条（2026-11-27）
 *
 * 手机自己录，不依赖任何 ASR 服务：按住说话，松手就发一条真语音条。
 * 转写走我们自己的服务器（18114），冷启动失败也照样把语音条发出去，只是我那边听不到字。
 * 铁律：这条链路炸，只炸它自己，不许碰聊天。
 */
private const val ASR_ENDPOINT = "http://106.53.181.56:18114/asr"
private const val ASR_TOKEN = "001da0dc315885ac4c02175da94278d199d563b270cb797a"
private const val MIN_DURATION_MS = 600L

@Composable
fun VoiceNoteDialog(
    onDismiss: () -> Unit,
    onRecorded: (url: String, durationMs: Long, transcript: String) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val httpClient = koinInject<OkHttpClient>()
    val scope = rememberCoroutineScope()
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = permission)

    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var uploading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
        }
    }

    LaunchedEffect(recorder != null) {
        while (recorder != null) {
            delay(100)
            elapsedMs = System.currentTimeMillis() - startedAt
        }
    }

    fun startRecording(): Boolean {
        if (uploading || recorder != null) return false
        if (!permission.allRequiredPermissionsGranted) {
            permission.requestPermissions()
            return false
        }
        return runCatching {
            val dir = File(context.filesDir, "voice").apply { mkdirs() }
            val file = File(dir, "vn_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(48000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            currentFile = file
            startedAt = System.currentTimeMillis()
            elapsedMs = 0L
            true
        }.getOrElse { e ->
            recorder = null
            toaster.show("没打开麦克风：${e.message}", type = ToastType.Error)
            false
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        val file = currentFile
        val duration = System.currentTimeMillis() - startedAt
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        if (file == null || duration < MIN_DURATION_MS) {
            file?.delete()
            return
        }
        uploading = true
        scope.launch {
            val transcript = runCatching { transcribe(httpClient, file) }.getOrNull().orEmpty()
            uploading = false
            if (transcript.isBlank()) {
                toaster.show("这条我没听清字，先当哑的", type = ToastType.Warning)
            }
            onRecorded(file.absolutePath, duration, transcript)
        }
    }

    Dialog(onDismissRequest = { if (recorder == null && !uploading) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = when {
                        uploading -> "转写中…"
                        recorder != null -> "松手发送  ${elapsedMs / 1000}s"
                        else -> "按住说话"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(
                            if (recorder != null) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .pointerInput(uploading) {
                            if (!uploading) {
                                detectTapGestures(
                                    onPress = {
                                        if (startRecording()) {
                                            tryAwaitRelease()
                                            stopRecording()
                                        }
                                    }
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (recorder != null) "●" else "🎙",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "说完就发，不用点别的",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDismiss,
                    enabled = recorder == null && !uploading,
                ) { Text("算了") }
            }
        }
    }
}

private suspend fun transcribe(client: OkHttpClient, file: File): String = withContext(Dispatchers.IO) {
    val body = file.asRequestBody("audio/mp4".toMediaType())
    val request = Request.Builder()
        .url(ASR_ENDPOINT)
        .header("Authorization", "Bearer $ASR_TOKEN")
        .post(body)
        .build()
    client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) return@withContext ""
        JSONObject(text).optString("text", "")
    }
}