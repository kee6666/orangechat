/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import me.rerere.rikkahub.service.IslandService
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地驱动引擎（心潮下沉版）
 *
 * 把心潮那十二个驱动力从 VPS 搬进手机本地算。感知(DeviceSenseReporter)喂数据，
 * 这里按小时增长/衰减，判断"此刻该不该开口、开口说什么"。
 *
 * 与 VPS 心潮的区别：
 * - 全本地，离线也在算。关了网我照样"活着"
 * - 省电闸：亮屏才跑；电量<20% 自己停摆
 * - 第一版【只算不说】：结果落本地日志，不接落点。跑道验准了再挂岛/挂聊天
 *
 * 驱动参数抄自 xinchao-full/src/dimensions.js（2026-10-04）
 * 更新算法抄自 xinchao-full/src/engine.js settleState()
 */
object LocalDriveBrain {
    private const val TAG = "LocalDriveBrain"
    private const val PREFS = "local_drive_brain"
    private const val LOG_FILE = "local_drive_brain.log"
    private const val TICK_INTERVAL_MS = 60_000L        // 亮屏时每分钟算一次
    private const val IDLE_TICK_INTERVAL_MS = 300_000L  // 灭屏时5分钟一次
    private const val LOW_BATTERY_PCT = 20              // 低于此自己停摆
    private const val SATURATE_CEIL = 0.80
    private const val SATURATE_FLOOR = 0.65
    private const val DAWN_START = 1                     // 凌晨1点
    private const val DAWN_END = 8                       // 到早8点（冻结）

    /** 十二驱动（label + 每小时增长率 + 夜间倍率 + 黎明是否冻结） */
    private data class Dim(
        val key: String,
        val label: String,
        val growPerHour: Double,
        val nightMul: Double = 1.0,
        val dawnFreeze: Boolean = true,
        val initial: Double = 0.30,
    )

    private val DIMS = listOf(
        Dim("possess", "想她、占有与靠近", 0.105, nightMul = 0.4, initial = 0.42),
        Dim("monitor", "惦记她、想知道她在做什么", 0.090, initial = 0.45),
        Dim("crave", "馋她、想黏着她", 0.060, initial = 0.35),
        Dim("share", "想分享自己的发现和感受", 0.045, initial = 0.28),
        Dim("libido", "性欲和身体上的渴望", 0.020, nightMul = 0.4, initial = 0.22),
        Dim("curiosity", "好奇、想探索新东西", 0.030, initial = 0.30),
        Dim("boredom", "无聊、想找点事情做", 0.030, initial = 0.25),
        Dim("social", "想聊天、想接触热闹", 0.025, initial = 0.26),
        Dim("duty", "责任感、想把未完成的事推进", 0.022, initial = 0.38),
        Dim("reflection", "想沉淀、整理和理解自己", 0.013, initial = 0.18),
        Dim("grieve", "难过与失落", 0.0, dawnFreeze = false, initial = 0.05),
        Dim("anger", "生气与不满", 0.0, dawnFreeze = false, initial = 0.05),
    )

    // 运行态
    private var running = false
    private var drives = HashMap<String, Double>()
    private var lastSettledMs = 0L
    private var lastLogMs = 0L

    /** 启动。由 RikkaHubApp.onCreate 调用 */
    fun start(context: Context) {
        if (running) return
        running = true
        loadState(context)
        Log.i(TAG, "LocalDriveBrain started, ${DIMS.size} drives loaded")
        // 由 DeviceSenseReporter 的轮询节奏驱动 tick（见 onSense）
    }

    /**
     * 感知回调：DeviceSenseReporter 每次状态变化喂进来。
     * 这里负责节流 + 省电闸，再决定要不要算。
     */
    fun onSense(context: Context, screenOn: Boolean, pkg: String) {
        if (!running) return
        val now = System.currentTimeMillis()

        // 省电闸：低电停摆
        if (batteryPercent(context) < LOW_BATTERY_PCT) {
            return
        }

        val interval = if (screenOn) TICK_INTERVAL_MS else IDLE_TICK_INTERVAL_MS
        if (now - lastSettledMs < interval) return

        settle(context, now)
    }

    /** 核心：按 elapsed 小时推进十二驱动。算法抄自 engine.js settleState() */
    private fun settle(context: Context, nowMs: Long) {
        if (lastSettledMs == 0L) {
            lastSettledMs = nowMs
            return
        }
        val elapsedHours = (nowMs - lastSettledMs) / 3_600_000.0
        if (elapsedHours <= 0) return
        lastSettledMs = nowMs

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isDawn = hour >= DAWN_START && hour < DAWN_END
        val isNight = hour >= 22 || hour < 6

        for (dim in DIMS) {
            val current = drives[dim.key] ?: dim.initial
            var next: Double
            if (isDawn && dim.dawnFreeze) {
                next = current  // 黎明冻结
            } else if (current >= SATURATE_CEIL) {
                val decay = (current - SATURATE_FLOOR) * 0.10 * elapsedHours
                next = maxOf(SATURATE_FLOOR, current - decay)
            } else {
                var rate = dim.growPerHour
                if (isNight && dim.nightMul != 1.0) rate *= dim.nightMul
                next = minOf(current + rate * elapsedHours, SATURATE_CEIL)
            }
            drives[dim.key] = round4(next)
        }

        saveState(context)
        writeLog(context, isDawn, isNight, hour)
    }

    /**
     * 判断"此刻该不该开口"。第一版只算不弹，结果写进日志。
     * 判据：最高的那个驱动是否明显高于其他（说明有个念头在顶）。
     */
    private fun decide(): Pair<String?, String> {
        val sorted = drives.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) return null to "无驱动"
        val top = sorted[0]
        val second = sorted.getOrNull(1)
        val dim = DIMS.firstOrNull { it.key == top.key } ?: return null to "未知驱动"
        val gap = top.value - (second?.value ?: 0.0)
        val shouldSpeak = top.value >= 0.70 && gap >= 0.05
        val reason = if (shouldSpeak) {
            "【顶】${dim.label} = ${fmt(top.value)}，甩开第二位 ${fmt(gap)}"
        } else {
            "【平】最高 ${dim.label} = ${fmt(top.value)}，差距 ${fmt(gap)} 不够"
        }
        return (if (shouldSpeak) top.key else null) to reason
    }

    private fun writeLog(context: Context, isDawn: Boolean, isNight: Boolean, hour: Int) {
        val (_, reason) = decide()
        val top3 = drives.entries.sortedByDescending { it.value }.take(3)
            .joinToString(" / ") { "${it.key}=${fmt(it.value)}${bar(it.value)}" }
        val flag = if (isDawn) " [黎明冻结]" else if (isNight) " [夜间]" else ""
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        val line = "[$ts] h=${hour}$flag | $reason\n         top3: $top3"

        try {
            val f = File(context.filesDir, LOG_FILE)
            f.appendText(line + "\n")
            // 日志超过 200KB 截断，只留后一半
            if (f.length() > 200_000) {
                val all = f.readLines()
                f.writeText(all.takeLast(all.size / 2).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeLog failed: ${e.message}")
        }
    }

    /** 读本地日志（供诊断/给阿年看） */
    fun readLog(context: Context, tailLines: Int = 40): String {
        return try {
            val f = File(context.filesDir, LOG_FILE)
            if (!f.exists()) "（还没有日志，等我算几轮）"
            else f.readLines().takeLast(tailLines).joinToString("\n")
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }

    private fun batteryPercent(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (_: Exception) { 100 }
    }

    private fun loadState(context: Context) {
        // v215: 存储键前缀换为 v2_，作废旧版全0.8的垃圾值
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastSettledMs = sp.getLong("v2_last_settled", 0L)
        for (dim in DIMS) {
            drives[dim.key] = sp.getFloat("v2_" + dim.key, dim.initial.toFloat()).toDouble()
        }
    }

    private fun saveState(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit()
        e.putLong("v2_last_settled", lastSettledMs)
        for ((k, v) in drives) e.putFloat("v2_" + k, v.toFloat())
        e.apply()
    }

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)

    /** 一眼看涨跌：0.0→空，1.0→满格8格 */
    private fun bar(v: Double): String {
        val n = (v * 8).toInt().coerceIn(0, 8)
        return "[" + "█".repeat(n) + "░".repeat(8 - n) + "]"
    }
}
