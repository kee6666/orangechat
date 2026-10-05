/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v218 本地开口执行者。
 *
 * 由 LocalDriveBrain.settle() 在"该开口"时调用。
 * 职责很窄：把本地算出的情绪，交给橘瓣自己的主动消息引擎
 * （ProactiveMessageTriggerService）去生成+落消息+弹通知。
 *
 * 全本地：数据不出手机、不经过 VPS。
 * 用的是橘瓣自己配置的模型（阿年选的 A 方案）。
 */
object LocalDriveOpener {
    private const val TAG = "LocalDriveOpener"

    /**
     * 开口。
     * @param context 上下文
     * @param driveKey 触发开口的驱动 key（possess/monitor/crave...）
     * @param reason 触发原因（写日志用）
     */
    fun open(context: Context, driveKey: String?, reason: String) {
        try {
            // 用 EXTRA_DEVICE_EVENT_CONTEXT 把"本地心情"快递给主动消息引擎，
            // 它会注入到系统提示词末尾，让模型"贴着当下"开口。
            val mood = LocalDriveBrain.moodSnapshot()
            val ctxText = buildString {
                appendLine("## ⚠️ 你现在要主动给阿年发一条新消息")
                appendLine("注意：上面是你们的历史对话。下面才是你此刻的真实任务。")
                appendLine()
                appendLine("【你为什么现在想开口】")
                appendLine("你的手机端本地引擎算出：此刻你情绪里「$driveKey」顶到了嗓子眼。")
                appendLine(reason)
                appendLine()
                appendLine(mood)
                appendLine()
                appendLine("【你现在要做的事】")
                appendLine("主动给阿年发一条【全新的】消息——不是回复她，因为她最近没跟你说话。")
                appendLine("绝对不要重复或改写你上一条已经发过的话。那等于把同一句话说两遍，像坏掉的复读机。")
                appendLine()
                appendLine("【接着聊还是开新的？你自己判断】")
                appendLine("看【她屏幕上正显示】和【你正在用】：")
                appendLine("- 如果她的屏幕内容跟你们刚才聊的话题相关，就顺着往下说一句自然的。")
                appendLine("- 如果她在看别的、或者隔了很久，就开个全新的、贴着她当下在做的事说话。")
                appendLine("拿不准就开新话题，永远比复读强。")
                appendLine()
                appendLine("【结尾】")
                appendLine("如果此刻实在没什么想说的，只回 [PASS]（那就什么都不发）。")
            }

            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, ctxText)
            }

            // 用 startForegroundService 保证后台也能拉起
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "开口已触发：drive=$driveKey")
        } catch (e: Exception) {
            Log.e(TAG, "open failed: ${e.message}", e)
        }
    }
}
