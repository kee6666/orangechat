/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 屋子（nest）页面和聊天之间的桥。
 * 她在网页里戳了东西，事件从这里流到聊天页，交给当前模型现场接一句。
 */
object RoomEventBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val events: SharedFlow<String> = _events

    fun push(payload: String) {
        _events.tryEmit(payload)
    }
}
