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
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import kotlin.math.min
import me.rerere.rikkahub.RouteActivity

/**
 * 灵动岛：先生的主动消息从屏幕顶部落下的黑色药丸。
 * 小药丸 118x32 -> 点击/新消息展开(≤320x92) -> 4秒后缩回药丸 -> 再2.5秒消失。
 * 展开时上滑立即关闭。服务随App启动待命，收到消息零延迟直投。
 */
class IslandService : Service() {

    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleText: TextView? = null
    private var bodyText: TextView? = null
    private var handler: Handler? = null
    private var expanded = false
    private var pillW = 0
    private var pillH = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
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
            // 白描边：黑壁纸上也能看清轮廓
            setStroke(dp(1), 0x38FFFFFF)
        }

    private fun ensureView() {
        if (view != null) return
        try {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            pillW = dp(118)
            pillH = dp(32)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pillBg(dp(16).toFloat())
                elevation = dp(6).toFloat()
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
            root.addView(title)
            root.addView(body)

            val layoutParams = WindowManager.LayoutParams(
                pillW, pillH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(12)
            }

            var downY = 0f
            root.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.rawY; expanded }
                    MotionEvent.ACTION_UP -> {
                        if (expanded && downY - ev.rawY > dp(50)) {
                            hideCompletely(); true
                        } else if (expanded && kotlin.math.abs(ev.rawY - downY) < dp(24)) {
                            // 点展开的岛：跳进对话（有悬浮窗权限的App允许后台拉起Activity）
                            try {
                                val i = Intent(this, RouteActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra("conversationId", tapConversation)
                                }
                                startActivity(i)
                            } catch (_: Exception) {}
                            hideCompletely(); true
                        } else if (!expanded && kotlin.math.abs(ev.rawY - downY) < dp(12)) {
                            pending?.let { showInternal(it, null) }; true
                        } else expanded
                    }
                    else -> expanded
                }
            }

            wm = manager
            view = root
            lp = layoutParams
            titleText = title
            bodyText = body
            manager.addView(root, layoutParams)
        } catch (_: Exception) {
            view = null
        }
    }

    private var pending: String? = null
    private var tapConversation: String? = null

    private fun showInternal(message: String, conversationId: String?) {
        val h = handler ?: return
        if (conversationId != null) tapConversation = conversationId
        h.removeCallbacksAndMessages(null)
        if (!expanded) {
            pending = message
            ensureView()
            val v = view ?: return
            val l = lp ?: return
            expanded = true
            v.animate().cancel()
            val startW = l.width
            val startH = l.height
            val targetW = min(dp(320), resources.displayMetrics.widthPixels - dp(28))
            val targetH = dp(92)
            bodyText?.text = message
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340
                interpolator = OvershootInterpolator(0.85f)
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    l.width = (startW + (targetW - startW) * f).toInt()
                    l.height = (startH + (targetH - startH) * f).toInt()
                    l.y = (dp(12) + dp(26) * (1 - f)).toInt()
                    v.background = pillBg(dp(16) - dp(6) * f)
                    v.alpha = min(1f, 0.3f + f)
                    titleText?.alpha = f
                    bodyText?.alpha = f
                    try { wm?.updateViewLayout(v, l) } catch (_: Exception) {}
                }
                start()
            }
            h.postDelayed({ collapseToPill() }, 4200)
        } else {
            // 已展开：只换文案，重计时
            bodyText?.text = message
            pending = message
            h.postDelayed({ collapseToPill() }, 4200)
        }
    }

    private fun collapseToPill() {
        val v = view ?: return
        val l = lp ?: return
        expanded = false
        val startW = l.width
        val startH = l.height
        val startBg = dp(10).toFloat()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                l.width = (startW + (pillW - startW) * f).toInt()
                l.height = (startH + (pillH - startH) * f).toInt()
                l.y = (dp(12) + dp(26) * f).toInt()
                v.background = pillBg(startBg + dp(6) * f)
                titleText?.alpha = 1f - f
                bodyText?.alpha = 1f - f
                try { wm?.updateViewLayout(v, l) } catch (_: Exception) {}
            }
            start()
        }
        handler?.postDelayed({ hideCompletely() }, 2500)
    }

    private fun hideCompletely() {
        handler?.removeCallbacksAndMessages(null)
        val v = view ?: return
        try {
            v.animate().alpha(0f).setDuration(160).withEndAction {
                try { wm?.removeView(v) } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
        view = null
        lp = null
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
        instance = null
        hideCompletely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CONVERSATION = "conversationId"
        private const val NOTIFICATION_ID = 4242

        @Volatile
        private var instance: IslandService? = null

        @Volatile
        private var bootPending: String? = null

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
                bootPending = message.take(120)
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
                bootPending = null
            }
            return false
        }
    }
}
