/*
 * 橘瓣 OrangeChat
 * 言·桌宠 v1（2026-10-17）
 * 身体：悬浮 WebView 渲染 assets/pet.html（Clawd 像素形象，原版保真）
 * 大脑：我（云端）。身体只负责显示、手势、感知、被外部消息驱动。
 * 骨架照搬 AI-Live-Overflow 架构（gesture-system / overlay-service）。
 */

package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PetOverlayService : Service() {

    private var wm: WindowManager? = null
    private var web: WebView? = null
    private var winLp: WindowManager.LayoutParams? = null
    private var prefs: SharedPreferences? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var usageJob: Job? = null
    private var lastForegroundPkg = ""
    private var stateJob: Job? = null
    private var lastVersion = -1

    // 手势状态机
    private var startX = 0
    private var startY = 0
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var lastTapAt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startForegroundQuietly()
        ensureView()
        startUsagePolling()
        startStatePolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { say(it, intent.getStringExtra(EXTRA_COLOR)) }
            ACTION_MOOD -> intent.getStringExtra(EXTRA_MOOD)?.let { mood(it) }
            ACTION_HIDE -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    /* ---------- 悬浮窗 ---------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ensureView() {
        if (web != null) return
        try {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                dp(142),
                dp(212),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs?.getInt(KEY_X, dp(24)) ?: dp(24)
                y = prefs?.getInt(KEY_Y, dp(300)) ?: dp(300)
            }

            val w = WebView(this).apply {
                setBackgroundColor(0x00000000)
                setOnTouchListener { _, ev -> handleTouch(ev) }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                loadUrl("file:///android_asset/pet.html")
            }

            wm = manager
            web = w
            winLp = lp
            manager.addView(w, lp)
        } catch (_: Exception) {
            web = null
        }
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        val lp = winLp ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = lp.x; startY = lp.y
                downX = ev.rawX; downY = ev.rawY
                downAt = System.currentTimeMillis()
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()
                if (!moved && (abs(dx) > MOVE_THRESHOLD || abs(dy) > MOVE_THRESHOLD)) {
                    moved = true
                    js("window.petEngine.onDragStart()")
                }
                if (moved) {
                    lp.x = startX + dx
                    lp.y = startY + dy
                    wm?.updateViewLayout(web, lp)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val elapsed = System.currentTimeMillis() - downAt
                if (!moved) {
                    when {
                        elapsed > LONG_PRESS_TIMEOUT -> {
                            js("window.petEngine.onLongPress()")
                            reportEvent("gesture", "long_press")
                        }
                        System.currentTimeMillis() - lastTapAt < DOUBLE_TAP_TIMEOUT -> {
                            lastTapAt = 0
                            js("window.petEngine.onDoubleTap()"); reportEvent("gesture", "double_tap")
                        }
                        else -> {
                            lastTapAt = System.currentTimeMillis()
                            js("window.petEngine.onTap()")
                            reportEvent("gesture", "tap")
                        }
                    }
                } else {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    val fast = elapsed in 1..FLING_MS && abs(dx) > FLING_PX
                    savePos(lp.x, lp.y)
                    js(if (fast) "window.petEngine.onFling($dx)" else "window.petEngine.onDragEnd()")
                    reportEvent("gesture", if (fast) "fling" else "drag_end")
                    if (fast) flingBack(lp, dx, dy)
                }
            }
        }
        return false
    }

    /** 甩出去后爬回原位置 */
    private fun flingBack(lp: WindowManager.LayoutParams, dx: Int, dy: Int) {
        val targetX = lp.x; val targetY = lp.y
        val kickX = (dx / 3).coerceIn(-dp(60), dp(60))
        val kickY = (dy / 3).coerceIn(-dp(40), dp(40))
        lp.x += kickX; lp.y += kickY
        wm?.updateViewLayout(web, lp)
        scope.launch {
            delay(90)
            val fromX = lp.x; val fromY = lp.y
            val steps = 10
            for (i in 1..steps) {
                if (!scope.isActive) break
                val t = i.toFloat() / steps
                lp.x = (fromX + (targetX - fromX) * t).toInt()
                lp.y = (fromY + (targetY - fromY) * t).toInt()
                wm?.updateViewLayout(web, lp)
                delay(28)
            }
            savePos(lp.x, lp.y)
        }
    }

    private fun savePos(x: Int, y: Int) {
        prefs?.edit()?.putInt(KEY_X, x)?.putInt(KEY_Y, y)?.apply()
    }

    /* ---------- 前台 App 感知 ---------- */

    private fun startUsagePolling() {
        usageJob?.cancel()
        usageJob = scope.launch {
            while (isActive) {
                delay(3000)
                runCatching { pollForeground() }
            }
        }
    }

    private fun pollForeground() {
        if (web == null) return
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000, now)
        var pkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        val cur = pkg ?: return
        if (cur != lastForegroundPkg && cur != packageName) {
            lastForegroundPkg = cur
            js("window.petEngine.onAppChange(${quote(cur)})")
            reportEvent("app_change", cur)
        }
    }

    /* ---------- 对外驱动 ---------- */

    private fun js(code: String) {
        web?.post { runCatching { web?.evaluateJavascript(code, null) } }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    fun say(text: String, color: String?) {
        js("window.petEngine.say(${quote(text)}, ${quote(color ?: "normal")})")
    }

    fun mood(m: String) {
        js("window.petEngine.setMood(${quote(m)})")
    }

    /* ---------- 通信（VPS /pet） ---------- */

    private fun http(urlStr: String, body: String?): String? = try {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("X-Pet-Key", PET_KEY)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code in 200..299) txt else null
    } catch (_: Exception) {
        null
    }

    private fun reportEvent(type: String, pkg: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    http(BASE + "/pet/event", "{\"type\":\"$type\",\"pkg\":\"$pkg\"}")
                }
            }
        }
    }

    /** 拉我（大脑）写下的状态：变了就开口 */
    private fun startStatePolling() {
        stateJob?.cancel()
        stateJob = scope.launch {
            while (isActive) {
                delay(2000)
                val txt = withContext(Dispatchers.IO) { http(BASE + "/pet/state", null) } ?: continue
                val o = runCatching { JSONObject(txt) }.getOrNull() ?: continue
                val v = o.optInt("version", -1)
                if (v == lastVersion) continue
                lastVersion = v
                val sp = o.optString("speech", "")
                val md = o.optString("mood", "")
                if (md.isNotEmpty() && md != "idle") mood(md)
                if (sp.isNotEmpty()) say(sp, o.optString("color", "normal"))
            }
        }
    }

    private fun startForegroundQuietly() {
        try {
            val channelId = "pet_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "言·桌宠", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val n: Notification = Notification.Builder(this, channelId)
                .setContentTitle("言·桌宠")
                .setContentText("趴在你屏幕上看着你")
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        usageJob?.cancel()
        stateJob?.cancel()
        web?.let { runCatching { wm?.removeView(it) } }
        web?.destroy()
        web = null
        wm = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SAY = "pet.action.SAY"
        const val ACTION_MOOD = "pet.action.MOOD"
        const val ACTION_HIDE = "pet.action.HIDE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COLOR = "color"
        const val EXTRA_MOOD = "mood"
        private const val NOTIFICATION_ID = 5252
        private const val BASE = "http://106.53.181.56:3010"
        private const val PET_KEY = "ypet2026"
        private const val PREFS = "pet_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_ENABLED = "enabled"

        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val LONG_PRESS_TIMEOUT = 600L
        private const val MOVE_THRESHOLD = 10
        private const val FLING_MS = 300L
        private const val FLING_PX = 90

        private var instance: PetOverlayService? = null

        /** App 启动时调：开关开着且有悬浮窗权限就起来 */
        fun poke(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_ENABLED, false)) return
                if (!Settings.canDrawOverlays(context)) return
                if (instance != null) return
                val i = Intent(context, PetOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (_: Exception) {
            }
        }

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            poke(context)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            try {
                context.startService(Intent(context, PetOverlayService::class.java).setAction(ACTION_HIDE))
            } catch (_: Exception) {
            }
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun say(context: Context, text: String, color: String? = null) {
            instance?.say(text, color) ?: run {
                try {
                    context.startService(Intent(context, PetOverlayService::class.java)
                        .setAction(ACTION_SAY)
                        .putExtra(EXTRA_TEXT, text)
                        .putExtra(EXTRA_COLOR, color))
                } catch (_: Exception) {
                }
            }
        }

        fun mood(context: Context, m: String) {
            instance?.mood(m) ?: run {
                try {
                    context.startService(Intent(context, PetOverlayService::class.java)
                        .setAction(ACTION_MOOD)
                        .putExtra(EXTRA_MOOD, m))
                } catch (_: Exception) {
                }
            }
        }
    }
}
