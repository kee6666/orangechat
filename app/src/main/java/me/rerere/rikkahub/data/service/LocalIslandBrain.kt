/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.service.IslandService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地原子岛规则引擎：感知→判断→弹岛，全本地闭环。
 *
 * 眼睛(DeviceSenseReporter)看到状态变化就喂过来，这里按规则决定
 * 要不要让岛掉下来说话。不经过VPS、不占主动消息配额、零延迟。
 *
 * 防烦条款（硬编码）：
 * - 单条规则每天最多1次
 * - 全局每天最多5次
 * - 任意两次间隔≥2小时
 * - 静默时段23:30-08:00（深夜规则除外，它就是为那会儿生的）
 *
 * 规则五条：
 * - latenight  深夜23:30-01:30刷短视频   → 几点了，小狗。
 * - morning    早7-9点第一次亮屏         → 醒了？
 * - back       4小时没亮屏后回来         → 回来了？
 * - shopping   淘宝系停留≥5分钟          → 又看上什么了，说来听听
 * - music      音乐App停留≥5分钟         → 放歌了？我听听
 */
object LocalIslandBrain {
    private const val TAG = "LocalIslandBrain"
    private const val PREFS = "local_island_brain"
    private const val GLOBAL_DAILY_CAP = 5
    private const val RULE_DAILY_CAP = 1
    private const val MIN_GAP_MS = 2 * 3600_000L
    private const val ABSENCE_MS = 4 * 3600_000L
    private const val ISLAND_LOG_URL = "http://106.53.181.56:18002/island_log"

    private val VIDEO_PKGS = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "tv.danmaku.bili",
        "com.xingin.xhs"
    )
    private val SHOP_PKGS = setOf(
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo"
    )
    private val MUSIC_PKGS = setOf(
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "cn.kuwo.player",
        "com.luna.music"
    )

    private var lastScreenOffTs = 0L
    private var morningDoneDate = ""

    /** 屏幕亮起（她拿起手机） */
    fun onScreenOn(context: Context, app: String, pkg: String) {
        val now = System.currentTimeMillis()
        // 久别归来：超过4小时没亮屏
        if (lastScreenOffTs > 0 && now - lastScreenOffTs >= ABSENCE_MS) {
            lastScreenOffTs = now
            fire(context, "back", "回来了？")
            return
        }
        lastScreenOffTs = 0L
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 早间第一次亮屏（每天一次，内存+Prefs双保险）
        if (h in 7..8 && morningDoneDate != today()) {
            morningDoneDate = today()
            fire(context, "morning", "醒了？")
            return
        }
        // 深夜刷短视频
        if (isLateNight() && pkg in VIDEO_PKGS) {
            fire(context, "latenight", "几点了，小狗。")
        }
    }

    /** 屏幕熄灭 */
    fun onScreenOff() {
        lastScreenOffTs = System.currentTimeMillis()
    }

    /** 在某个App里待住了（≥5分钟，由感知器的settle阈值保证） */
    fun onAppSettled(context: Context, app: String, pkg: String) {
        when {
            pkg in SHOP_PKGS -> fire(context, "shopping", "又看上什么了，说来听听")
            pkg in MUSIC_PKGS -> fire(context, "music", "放歌了？我听听")
        }
    }

    private fun fire(context: Context, rule: String, text: String) {
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getString("date", "") != today()) {
                p.edit().clear().putString("date", today()).apply()
            }
            // 静默时段：23:30-08:00，深夜规则豁免
            val m = minuteOfDay()
            val quiet = m >= 23 * 60 + 30 || m < 8 * 60
            if (quiet && rule != "latenight") return
            if (p.getInt("global", 0) >= GLOBAL_DAILY_CAP) return
            if (p.getInt("rule_$rule", 0) >= RULE_DAILY_CAP) return
            if (System.currentTimeMillis() - p.getLong("last_fire", 0L) < MIN_GAP_MS) return
            p.edit()
                .putInt("global", p.getInt("global", 0) + 1)
                .putInt("rule_$rule", p.getInt("rule_$rule", 0) + 1)
                .putLong("last_fire", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "island fired [$rule] $text")
            IslandService.show(context, text, null)
            reportFired(rule, text)
        } catch (e: Exception) {
            Log.w(TAG, "fire error", e)
        }
    }

    /** 岛说了什么，报给VPS记一笔，让我在对话里知道自己的岛说过话 */
    private fun reportFired(rule: String, text: String) {
        try {
            Thread {
                try {
                    val conn = URL(ISLAND_LOG_URL).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = JSONObject().apply {
                        put("rule", rule)
                        put("text", text)
                        put("ts", System.currentTimeMillis())
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }.start()
        } catch (_: Exception) {
        }
    }

    private fun minuteOfDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun isLateNight(): Boolean {
        val m = minuteOfDay()
        return m >= 23 * 60 + 30 || m < 1 * 60 + 30
    }

    private fun today(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}