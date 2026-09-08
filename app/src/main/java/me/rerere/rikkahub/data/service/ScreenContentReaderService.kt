/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */
package me.rerere.rikkahub.data.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.provider.Settings

/**
 * 屏幕内容感知服务（最小权限版）
 *
 * 原则：
 * 1. 只读：只提取屏幕上可见文本，绝不模拟点击/输入
 * 2. 不录屏、不截图、不保存图片
 * 3. 只保留最近一次快照（内存中，退出即清）
 * 4. 开关由用户在设置里手动控制，关闭即完全停止
 * 5. 不开机自启（必须用户主动授权）
 *
 * 由 ScreenSenseController 统一启用/禁用。
 */
class ScreenContentReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenContentReader"

        /** 是否开启（由 ScreenSenseController 写入，默认关） */
        @Volatile
        var enabled: Boolean = false

        /** 最近一次读到的屏幕文本（内存缓存，不落盘） */
        @Volatile
        var lastSnapshot: String = ""

        /** 供上层读取的外部回调（由 ScreenSenseController 注入） */
        var onSnapshot: ((String) -> Unit)? = null

        /** 当前服务的完整类名（flattenToString 格式 = package/class），用于系统设置比对 */
        private val COMP = "me.rerere.rikkahub.data.service.ScreenContentReaderService"

        /** 是否在系统设置里授权了本服务 */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals(COMP, ignoreCase = true) }
        }

        /** 引导用户去系统无障碍设置页授权本服务 */
        fun openAccessibilitySettings(ctx: Context) {
            try {
                ctx.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Log.w(TAG, "openAccessibilitySettings failed", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled) return

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val root = rootInActiveWindow ?: return
                val texts = mutableListOf<String>()
                collectTexts(root, texts, maxNodes = 200, depth = 0)

                val snapshot = texts
                    .filter { it.isNotBlank() && it.length > 1 }
                    .distinct()
                    .take(60)
                    .joinToString(" | ")

                if (snapshot.isNotBlank()) {
                    lastSnapshot = snapshot
                    Log.d(TAG, "snapshot: ${snapshot.take(80)}...")
                    onSnapshot?.invoke(snapshot)
                }
            }
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int,
        depth: Int
    ) {
        if (node == null || out.size >= maxNodes || depth > 12) return
        if (node.text != null && node.isVisibleToUser && node.text.isNotEmpty()) {
            out.add(node.text.toString())
        }
        if (node.contentDescription != null && node.isVisibleToUser) {
            val desc = node.contentDescription.toString()
            if (desc.isNotBlank()) out.add(desc)
        }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, maxNodes, depth + 1)
        }
    }

    override fun onInterrupt() {}
}
