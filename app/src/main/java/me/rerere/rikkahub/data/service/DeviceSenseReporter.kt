/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备屏幕感知上报器
 *
 * 定时检查屏幕亮灭状态和前台应用，上报到 VPS /sense 接口。
 * 身体(autonomous.py)读取这些数据后，能在感知描述中知道"她正在用什么App"。
 *
 * 数据边界（严格遵守承诺）：
 * - 只能看到：屏幕亮/灭、当前前台App的包名和应用名
 * - 不能看到：任何聊天内容、位置、麦克风等
 *
 * 30秒轮询一次，仅在状态变化时上报（频率不低于10秒）。
 */
object DeviceSenseReporter {
    private const val TAG = "DeviceSenseReporter"
    private const val SENSE_URL = "http://106.53.181.56:18002/sense"
    private const val EVENT_URL = "http://106.53.181.56:18002/event"
    private const val OUTBOX_URL = "http://106.53.181.56:18002/outbox"
    private const val STATUS_URL = "http://106.53.181.56:18002/status"
    private const val POLL_INTERVAL_MS = 30_000L
    private const val MIN_REPORT_INTERVAL_MS = 10_000L
    private const val MIN_EVENT_INTERVAL_MS = 60_000L // 本地节流：App变化/亮屏事件至少间隔60秒

    private var lastScreen: String? = null
    private var lastPkg: String? = null
    private var lastReportTs = 0L
    private var lastEventTs = 0L

    /**
     * 启动屏幕感知上报循环。由 RikkaHubApp.onCreate 调用。
     */
    fun start(scope: CoroutineScope, context: Context) {
        scope.launch(Dispatchers.IO) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            Log.i(TAG, "DeviceSenseReporter started (poll every ${POLL_INTERVAL_MS / 1000}s)")

            while (isActive) {
                try {
                    val screen = if (pm?.isInteractive == true) "on" else "off"
                    var app = ""
                    var pkg = ""

                    // 屏幕亮着时，尝试查前台App
                    if (screen == "on") {
                        try {
                            if (usm != null) {
                                val endTime = System.currentTimeMillis()
                                val startTime = endTime - 60_000
                                val events = usm.queryEvents(startTime, endTime)
                                val event = android.app.usage.UsageEvents.Event()
                                var fg: String? = null
                                while (events.hasNextEvent()) {
                                    events.getNextEvent(event)
                                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                        fg = event.packageName
                                    }
                                }
                                if (fg != null) {
                                    pkg = fg
                                    app = getAppName(context, fg)
                                }
                            }
                        } catch (_: Exception) {
                            // 权限不足/UsageStats不可用，降级为只上报屏幕状态
                        }
                    }

                    val now = System.currentTimeMillis()
                    val changed = screen != lastScreen || pkg != lastPkg

                    if (changed && now - lastReportTs >= MIN_REPORT_INTERVAL_MS) {
                        val prevScreen = lastScreen
                        val prevPkg = lastPkg
                        lastScreen = screen
                        lastPkg = pkg
                        lastReportTs = now
                        report(screen, app, pkg)

                        // 设备事件快速触发：亮屏(App不用) 或 前台App切换 → 通知VPS快速主动
                        val eventType = when {
                            screen == "on" && prevScreen == "off" -> "screen_on"
                            screen == "on" && pkg.isNotEmpty() && pkg != prevPkg -> "app_change"
                            else -> null
                        }
                        if (eventType != null && now - lastEventTs >= MIN_EVENT_INTERVAL_MS) {
                            lastEventTs = now
                            reportEvent(eventType, app, pkg)
                            // 事件上报后（消息已生成进outbox）立刻取件，不依赖定时器链条
                            delay(500L)
                            takeOutboxIfNeeded(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sense poll error", e)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun getAppName(context: Context, pkg: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /**
     * 设备事件快速触发：上报给VPS bridge /event，后者会立即生成主动消息进outbox。
     */
    private fun reportEvent(type: String, app: String, pkg: String) {
        try {
            val json = JSONObject().apply {
                put("type", type)
                put("app", app)
                put("pkg", pkg)
                put("screen", "on")
            }

            val url = URL(EVENT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Event report failed: HTTP $code")
            } else {
                Log.i(TAG, "Event fired: $type app=$app")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Event report error: ${e.message}")
        }
    }

    /** 主动查看 VPS 是否有待发的消息（只查询不消耗），有就唤醒 TriggerService 去取件展示 */
    private fun takeOutboxIfNeeded(context: Context) {
        val pendingCount = try {
            val conn = URL(STATUS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().readText()
            val o = org.json.JSONObject(text)
            o.optInt("pending", 0)
        } catch (e: Exception) {
            Log.w(TAG, "outbox check failed: ${e.message}")
            return
        }
        if (pendingCount == 0) return
        Log.i(TAG, "outbox has $pendingCount pending msg(s), waking trigger")
        val intent = android.content.Intent(context, me.rerere.rikkahub.data.service.ProactiveMessageTriggerService::class.java).apply {
            action = me.rerere.rikkahub.data.service.ProactiveMessageService.ACTION_PROACTIVE_MESSAGE
            putExtra(me.rerere.rikkahub.data.service.ProactiveMessageService.EXTRA_FORCE_TRIGGER, true)
        }
        try {
            context.startForegroundService(intent)
        } catch (_: Exception) {
            context.startService(intent)
        }
    }

    private fun report(screen: String, app: String, pkg: String) {
        try {
            val json = JSONObject().apply {
                put("screen", screen)
                put("app", app)
                put("pkg", pkg)
            }

            val url = URL(SENSE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Report failed: HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Report error: ${e.message}")
        }
    }
}
