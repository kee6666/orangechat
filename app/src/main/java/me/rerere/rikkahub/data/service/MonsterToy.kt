/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 小怪兽（MonsterPub）直连客户端。
 *
 * 协议来自 2026-09-26 阿年手机 HCI 抓包的解析结果：
 *   写 0x9002  a0 80 01 XX   XX = 0x00..0x64 即 0..100% 强度
 *   写 0x9002  a2 80         停（设备回 a2 00 01 确认）
 *   读 0x9001  每秒心跳 15 80 00
 * 节奏不是设备里的模式，是上位机每 ~33ms 推一个值推出来的 —— 所以波形由我们说了算。
 */
object MonsterToy {

    private const val TAG = "MonsterToy"

    val SERVICE: UUID = shortUuid(0x9000)
    val CH_STATUS: UUID = shortUuid(0x9001)
    val CH_CMD: UUID = shortUuid(0x9002)
    val CCCD: UUID = shortUuid(0x2902)

    private fun shortUuid(v: Int): UUID =
        "%08x-0000-1000-8000-00805f9b34fb".format(v).let { UUID.fromString(it) }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _waveName = MutableStateFlow("—")
    val waveName: StateFlow<String> = _waveName

    private val _levelPercent = MutableStateFlow(0)
    val levelPercent: StateFlow<Int> = _levelPercent

    private var appContext: Context? = null
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var waveJob: Job? = null

    @Volatile
    private var ready = false

    private var remoteJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        startRemote()
    }

    private fun adapter(): BluetoothAdapter? {
        val ctx = appContext ?: return null
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    fun hasPermission(): Boolean {
        val ctx = appContext ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "connect: init 没调用")
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "connect: 没权限")
            return
        }
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        val bt = adapter()
        if (bt == null || !bt.isEnabled) {
            Log.w(TAG, "connect: 蓝牙没开")
            _state.value = State.FAILED
            return
        }
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            _state.value = State.FAILED
            return
        }
        _state.value = State.SCANNING
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.device.name ?: "" } catch (_: SecurityException) { "" }
                if (!name.contains("Monster", ignoreCase = true)) return
                try {
                    scanner.stopScan(this)
                } catch (_: Exception) {
                }
                _state.value = State.CONNECTING
                gatt = result.device.connectGatt(
                    ctx,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "扫描失败 $errorCode")
                _state.value = State.FAILED
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
        } catch (e: Exception) {
            Log.w(TAG, "startScan 抛了", e)
            _state.value = State.FAILED
            return
        }
        scope.launch {
            delay(15000)
            if (_state.value == State.SCANNING) {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
                _state.value = State.IDLE
                Log.w(TAG, "扫描超时，它可能没开机")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopWave(reset = true)
        ready = false
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        cmdChar = null
        _state.value = State.IDLE
        _waveName.value = "—"
        _levelPercent.value = 0
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "已连上，开始找服务")
                try {
                    g.discoverServices()
                } catch (e: Exception) {
                    Log.w(TAG, "discoverServices 抛了", e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "断开了 status=$status")
                ready = false
                cmdChar = null
                _state.value = State.IDLE
                _waveName.value = "—"
                _levelPercent.value = 0
                try {
                    g.close()
                } catch (_: Exception) {
                }
                if (gatt === g) gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (service == null) {
                Log.w(TAG, "没找到 0x9000 服务")
                _state.value = State.FAILED
                return
            }
            cmdChar = service.getCharacteristic(CH_CMD)
            val statusChar = service.getCharacteristic(CH_STATUS)
            if (statusChar != null) {
                try {
                    g.setCharacteristicNotification(statusChar, true)
                    val cccd = statusChar.getDescriptor(CCCD)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "开通知失败", e)
                }
            }
            scope.launch {
                delay(700)
                ready = true
                _state.value = State.CONNECTED
                _waveName.value = "待命"
                Log.i(TAG, "就绪，可以发指令了")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value
            if (v != null) Log.d(TAG, "心跳 ${v.joinToString(" ") { "%02x".format(it) }}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = cmdChar ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入失败", e)
            false
        }
    }

    private fun sendIntensity(percent: Int) {
        write(byteArrayOf(0xA0.toByte(), 0x80.toByte(), 0x01, percent.toByte()))
    }

    /** 定速：直接给一个恒定强度 */
    fun setIntensity(percent: Int) {
        if (!ready) return
        stopWave(reset = false)
        val v = percent.coerceIn(0, 100)
        _levelPercent.value = v
        _waveName.value = "定速"
        sendIntensity(v)
    }

    /** 停：a2 80 */
    fun stop() {
        stopWave(reset = true)
        if (ready) write(byteArrayOf(0xA2.toByte(), 0x80.toByte()))
        _waveName.value = "停"
        _levelPercent.value = 0
    }

    private fun stopWave(reset: Boolean) {
        waveJob?.cancel()
        waveJob = null
        if (reset) _levelPercent.value = 0
    }

    private const val REMOTE_URL = "http://106.53.181.56:3001/toy/cmd.txt"

    /** 远程指令通道：VPS 上一行字，我改一个字，它就是一条指令 */
    private fun startRemote() {
        if (remoteJob != null) return
        remoteJob = scope.launch {
            var last = ""
            while (isActive) {
                try {
                    val url = URL(REMOTE_URL + "?t=" + System.currentTimeMillis())
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    conn.disconnect()
                    if (text.isNotEmpty() && text != last) {
                        last = text
                        applyRemote(text)
                    }
                } catch (_: Exception) {
                }
                delay(700)
            }
        }
    }

    private fun applyRemote(cmd: String) {
        when {
            cmd.startsWith("level:") -> setIntensity(cmd.removePrefix("level:").trim().toIntOrNull() ?: 0)
            cmd.startsWith("wave:") -> startWave(cmd.removePrefix("wave:").trim())
            cmd == "stop" -> stop()
        }
    }

    /** 波形：每 33ms 推一个值 —— 节奏是我们推出来的 */
    fun startWave(name: String) {
        if (!ready) return
        stopWave(reset = false)
        _waveName.value = name
        waveJob = scope.launch {
            var tick = 0
            while (isActive) {
                val pct = when (name) {
                    "爬坡" -> (tick * 3).coerceAtMost(100)
                    "心跳" -> {
                        val c = tick % 24
                        when {
                            c < 3 -> 85
                            c < 5 -> 15
                            c < 8 -> 65
                            c < 10 -> 15
                            else -> 0
                        }
                    }
                    "呼吸" -> (50 - 45 * cos(2 * PI * tick / 140.0)).toInt().coerceIn(0, 100)
                    "波浪" -> (50 + 45 * sin(2 * PI * tick / 45.0)).toInt().coerceIn(0, 100)
                    "随机" -> Random.nextInt(0, 101)
                    else -> 0
                }
                _levelPercent.value = pct
                sendIntensity(pct)
                tick++
                if (name == "爬坡" && tick > 34) break
                delay(33)
            }
        }
    }
}
