/*
 * 橘瓣 OrangeChat - EarTokenHolder（2026-09-23 v4）
 *
 * v3 的错误：把 MediaProjection 的 resultData Intent 用 toUri() 序列化存进
 * SharedPreferences，重启后 parseUri() 复原。
 * 问题：MediaProjection 凭证里带 Binder 对象（系统会话句柄），根本没法序列化成字符串，
 * toUri/parseUri 往返必失败 —— 复原失败就落回弹框分支，用户看到的"点第二次还弹"。
 *
 * v4 的做法：不序列化。Intent 对象放进程内存，同进程直接复用，进程死了认命重弹。
 *
 * consented 标记仍然写 prefs（"她同意过没有"必须持久化），
 * 但"token 还在不在"只信内存。
 */
package me.rerere.rikkahub.data.service

import android.content.Intent

object EarTokenHolder {
    @Volatile
    private var resultCode: Int = -1

    @Volatile
    private var resultData: Intent? = null

    fun save(code: Int, data: Intent) {
        resultCode = code
        resultData = data
    }

    fun code(): Int = resultCode

    fun data(): Intent? = resultData

    fun alive(): Boolean = resultCode >= 0 && resultData != null

    fun clear() {
        resultCode = -1
        resultData = null
    }
}
