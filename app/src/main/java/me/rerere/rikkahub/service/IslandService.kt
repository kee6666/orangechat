package me.rerere.rikkahub.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.abs
import me.rerere.rikkahub.RouteActivity

/**
 * 灵动岛 v4（2026-09-19 阿年实测四bug重写）：
 * 1. 窗口固定尺寸居中，创建后不再 updateViewLayout —— 每帧跨进程调用是卡顿+偏右的病根
 * 2. 流程：黑药丸先弹跳掉落 -> 自动展开 -> 4.2s缩回药丸 -> 2.5s消失
 * 3. 药丸态点击=展开；展开态点击=跳进对话；上滑=关闭
 * 动画全部在视图层（View 属性/LayoutParam），零窗口IPC。
 */
class IslandService : Service() {

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var pill: LinearLayout? = null
    private var pillLp: FrameLayout.LayoutParams? = null
    private var titleText: TextView? = null
    private var bodyText: TextView? = null
    private var handler: Handler? = null

    private var expanded = false
    private var pillW = 0
    private var pillH = 0
    private var fullW = 0
    private var fullH = 0

    private var pending: String? = null
    private var tapConversation: String? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        startForegroundQuietly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val msg = intent?.getStringExtra(EXTRA_MESSAGE)
        if (!msg.isNullOrBlank()) showInternal(msg, intent?.getStringExtra(EXTRA_CONVERSATION))
        return START_STICKY
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pillBg(radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(0xF208080A.toInt())
            cornerRadius = radiusPx
            setStroke(dp(1), 0x38FFFFFF)
        }

    private fun ensureView() {
        if (root != null) return
        try {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            pillW = dp(118)
            pillH = dp(32)
            fullW = min(dp(330), resources.displayMetrics.widthPixels - dp(24))
            fullH = dp(92)

            // 窗口：固定尺寸、固定位置（居中靠顶），创建后永不再改
            val winLp = WindowManager.LayoutParams(
                min(dp(360), resources.displayMetrics.widthPixels),
                dp(160),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(6)
            }

            val container = FrameLayout(this)
            val p = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pillBg(dp(16).toFloat())
                elevation = dp(6).toFloat()
                clipToOutline = true
                alpha = 0f
            }
            val title = TextView(this).apply {
                setTextColor(0xFF9E9EA8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.1f
                text = "先生"
                alpha = 0f
            }
            val body = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                gravity = Gravity.CENTER
                maxLines = 3
                setPadding(dp(16), dp(6), dp(16), dp(10))
                alpha = 0f
            }
            p.addView(title)
            p.addView(body)
            p.layoutParams = FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(10)
            }
            container.addView(p)

            // 触摸：判定放宽（药丸点20dp内算点击，展开点32dp内算点击）
            var downY = 0f
            p.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.rawY; true }
                    MotionEvent.ACTION_UP -> {
                        val dy = ev.rawY - downY
                        when {
                            expanded && dy < -dp(40) -> { hideCompletely(); true }
                            expanded && abs(dy) < dp(32) -> { openConversation(); true }
                            !expanded && abs(dy) < dp(20) -> {
                                pending?.let { showInternal(it, null) }
                                true
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }

            wm = manager
            root = container
            pill = p
            pillLp = p.layoutParams as FrameLayout.LayoutParams
            titleText = title
            bodyText = body
            manager.addView(container, winLp)
        } catch (_: Exception) {
            root = null
        }
    }

    private fun openConversation() {
        try {
            val i = Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", tapConversation)
            }
            startActivity(i)
        } catch (_: Exception) {
        }
        hideCompletely()
    }

    private fun showInternal(message: String, conversationId: String?) {
        val h = handler ?: return
        if (conversationId != null) tapConversation = conversationId
        h.removeCallbacksAndMessages(null)
        ensureView()
        val r = root ?: return
        val p = pill ?: return
        val lp = pillLp ?: return
        pending = message
        bodyText?.text = message
        r.animate().cancel()
        p.animate().cancel()

        if (!expanded) {
            // 阶段一：重置成小药丸，从顶上弹跳掉落
            expanded = false
            lp.width = pillW
            lp.height = pillH
            p.background = pillBg(dp(16).toFloat())
            p.alpha = 1f
            titleText?.alpha = 0f
            bodyText?.alpha = 0f
            p.translationY = -dp(70).toFloat()
            p.requestLayout()
            ValueAnimator.ofFloat(-dp(70).toFloat(), 0f).apply {
                duration = 420
                interpolator = OvershootInterpolator(1.7f)
                addUpdateListener { a ->
                    p.translationY = a.animatedValue as Float
                    r.alpha = min(1f, r.alpha + 0.12f)
                }
                start()
            }
            // 阶段二：落稳后自动展开
            h.postDelayed({ expandToFull() }, 480)
        } else {
            // 已展开：只换文案，重计时
            bodyText?.alpha = 1f
        }
        h.postDelayed({ collapseToPill() }, 4600)
        h.postDelayed({ hideCompletely() }, 7300)
    }

    private fun expandToFull() {
        val p = pill ?: return
        val lp = pillLp ?: return
        expanded = true
        val startW = lp.width
        val startH = lp.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = OvershootInterpolator(0.85f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.width = (startW + (fullW - startW) * f).toInt()
                lp.height = (startH + (fullH - startH) * f).toInt()
                p.background = pillBg(dp(16) - dp(6) * f)
                titleText?.alpha = f
                bodyText?.alpha = f
                p.requestLayout()
            }
            start()
        }
    }

    private fun collapseToPill() {
        val p = pill ?: return
        val lp = pillLp ?: return
        expanded = false
        val startW = lp.width
        val startH = lp.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.width = (startW + (pillW - startW) * f).toInt()
                lp.height = (startH + (pillH - startH) * f).toInt()
                p.background = pillBg(dp(10) + dp(6) * f)
                titleText?.alpha = 1f - f
                bodyText?.alpha = 1f - f
                p.requestLayout()
            }
            start()
        }
    }

    private fun hideCompletely() {
        handler?.removeCallbacksAndMessages(null)
        val r = root ?: return
        try {
            r.animate().alpha(0f).setDuration(150).withEndAction {
                try { wm?.removeView(r) } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
        root = null
        pill = null
        pillLp = null
        titleText = null
        bodyText = null
        pending = null
    }

    private fun startForegroundQuietly() {
        try {
            val channelId = "island_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "灵动岛", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val n: Notification = Notification.Builder(this, channelId)
                .setContentTitle("灵动岛待命中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            startForeground(NOTIFICATION_ID, n)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        hideCompletely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CONVERSATION = "conversationId"
        private const val NOTIFICATION_ID = 4242

        private var instance: IslandService? = null

        /** App 启动时调：有悬浮窗权限就待命 */
        fun poke(context: Context) {
            try {
                if (!Settings.canDrawOverlays(context)) return
                if (instance != null) return
                val i = Intent(context, IslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {
            }
        }

        /** 主动消息入口：服务活着直接投，没活着尝试拉起；返回是否成功弹岛 */
        fun show(context: Context, message: String, conversationId: String? = null): Boolean {
            if (message.isBlank()) return false
            val live = instance
            if (live != null) {
                live.handler?.post { live.showInternal(message, conversationId) }
                return true
            }
            try {
                if (!Settings.canDrawOverlays(context)) return false
                val i = Intent(context, IslandService::class.java)
                    .putExtra(EXTRA_MESSAGE, message.take(120))
                    .putExtra(EXTRA_CONVERSATION, conversationId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                return true
            } catch (_: Exception) {
            }
            return false
        }
    }
}
