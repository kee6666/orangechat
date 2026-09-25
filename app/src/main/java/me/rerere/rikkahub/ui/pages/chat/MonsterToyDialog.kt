/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.service.MonsterToy

private fun toyStateLabel(s: MonsterToy.State): String = when (s) {
    MonsterToy.State.IDLE -> "未连接"
    MonsterToy.State.SCANNING -> "在找它…"
    MonsterToy.State.CONNECTING -> "连接中…"
    MonsterToy.State.CONNECTED -> "已握住"
    MonsterToy.State.FAILED -> "失败（看看它开机没）"
}

/**
 * 小怪兽直连面板：
 * 强度是"定速"，波形是每 33ms 推一个值 —— 节奏由发指令的人说了算。
 */
@Composable
fun MonsterToyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MonsterToy.init(context) }
    val state by MonsterToy.state.collectAsStateWithLifecycle()
    val waveName by MonsterToy.waveName.collectAsStateWithLifecycle()
    val percent by MonsterToy.levelPercent.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (MonsterToy.hasPermission()) MonsterToy.connect()
    }

    val connected = state == MonsterToy.State.CONNECTED
    val levels = listOf(20, 40, 60, 80, 100)
    val waves = listOf("爬坡", "心跳", "呼吸", "波浪", "随机")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小怪兽 · 直连") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态：${toyStateLabel(state)}")
                Text("波形：$waveName    强度：$percent%")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (connected) {
                                MonsterToy.disconnect()
                            } else if (MonsterToy.hasPermission()) {
                                MonsterToy.connect()
                            } else {
                                val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                permLauncher.launch(need)
                            }
                        }
                    ) {
                        Text(if (connected) "断开" else "连接")
                    }
                    OutlinedButton(onClick = { MonsterToy.stop() }) {
                        Text("停")
                    }
                }

                Text("强度（定速）")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    levels.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { v ->
                                OutlinedButton(onClick = { MonsterToy.setIntensity(v) }) {
                                    Text("$v")
                                }
                            }
                        }
                    }
                }

                Text("波形（谁推流谁说了算）")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    waves.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { w ->
                                OutlinedButton(onClick = { MonsterToy.startWave(w) }) {
                                    Text(w)
                                }
                            }
                        }
                    }
                }

                Text(
                    "第一次先在桌上试。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关") }
        }
    )
}
