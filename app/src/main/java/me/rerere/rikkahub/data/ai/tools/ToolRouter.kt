/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import me.rerere.ai.core.Tool

private const val TAG = "ToolRouter"

/**
 * 工具路由：根据用户最后一条消息的关键词，只保留「相关」的工具，
 * 避免每次请求都把全部工具的说明书/参数 schema 塞给模型（省 token、防输入超长）。
 *
 * 设计：
 * - 始终保留一小撮基础工具（时间、HITL 询问），保证模型永远有基本能力
 * - 其余工具按关键词映射表匹配，命中才保留
 * - 纯闲聊（无关键词命中）只给基础工具
 * - 工具循环中（最后消息不是用户消息）不应调用本路由，由调用方保证
 */
object ToolRouter {

    /** 始终保留的基础工具（体积小、通用、HITL 必需） */
    private val ALWAYS_KEEP = setOf(
        "get_time_info",
        "ask_user",
        "memory_tool",
        "write_files",
        "workspace_read_file",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_shell",
        "list_zip_contents",
    )

    /**
     * 关键词映射表：工具名子串 -> 触发关键词
     * 匹配逻辑：用户消息包含任一关键词 -> 保留名称包含对应子串的工具
     */
    private val RULES: List<Pair<List<String>, List<String>>> = listOf(
        // 截图 / 屏幕 / 视觉
        listOf("截图", "截屏", "屏幕", "拍照", "看下屏幕", "看屏幕", "看看屏幕", "screenshot") to
            listOf("take_screenshot", "camera_capture"),

        // 窗口树 / UI 操作（点了啥、界面上有什么）
        listOf("界面", "窗口", "点一下", "点开", "点击", "滑动", "输入", "app 上", "app上", "界面树") to
            listOf("read_window_tree", "find_node", "click_node", "set_text", "tap", "swipe", "scroll", "long_press", "global_action"),

        // SSH / VPS / 服务器 / 代码 / 部署
        listOf("ssh", "服务器", "vps", "部署", "跑一下", "改代码", "代码", "git", "编译", "命令行", "运行命令", "日志") to
            listOf("ssh_exec", "ssh_exec_saved", "save_ssh_host", "list_ssh_hosts", "delete_ssh_host", "ssh_upload", "ssh_download", "ssh_forget_host_key", "eval_javascript", "write_files", "list_zip_contents", "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file"),

        // 位置 / 附近 / 天气 / 吃饭
        listOf("位置", "在哪", "定位", "附近", "天气", "吃什么", "餐厅", "咖啡", "周边", "导航") to
            listOf("get_location", "explore_nearby"),

        // 音乐
        listOf("音乐", "歌", "播放", "暂停", "下一首", "上一首", "听") to
            listOf("control_music"),

        // 日历 / 提醒 / 闹钟 / 计时
        listOf("日历", "日程", "安排", "提醒", "闹钟", "计时", "几点") to
            listOf("calendar_tool", "set_alarm", "set_timer", "post_notification"),

        // 电池 / 充电
        listOf("电量", "充电", "电池", "省电") to
            listOf("get_battery_info"),

        // 存储
        listOf("存储", "内存", "空间不够", "内存不足") to
            listOf("get_storage_info"),

        // 通知
        listOf("通知", "消息提醒", "推送") to
            listOf("get_notifications"),

        // 电话 / 信号 / wifi
        listOf("信号", "运营商", "wifi", "网络", "流量") to
            listOf("get_telephony_info", "get_wifi_info"),

        // 亮度 / 音量
        listOf("亮度", "音量", "声音", "静音", "调亮", "调暗", "调大", "调小") to
            listOf("get_brightness", "set_brightness", "get_volume", "set_volume"),

        // 手电筒 / 震动
        listOf("手电", "闪光灯", "灯", "震动") to
            listOf("set_torch", "vibrate"),

        // 搜索 / 网页
        listOf("搜索", "查一下", "搜一下", "百度", "谷歌", "新闻", "网页", "百度一下", "上网") to
            listOf("search_web", "scrape_web", "web_fetch"),

        // 剪贴板
        listOf("剪贴板", "复制", "粘贴") to
            listOf("clipboard_tool"),

        // 分享
        listOf("分享", "转发") to
            listOf("share"),

        // 短信
        listOf("短信", "发短信") to
            listOf("read_sms"),

        // 应用管理
        listOf("打开", "启动", "应用", "app", "锁上", "解锁", "软件") to
            listOf("app_switch", "app_lock", "get_app_usage"),

        // 健康 / 手环
        listOf("步数", "心率", "睡眠", "手环", "健康", "血氧") to
            listOf("get_gadgetbridge_data"),

        // 记忆
        listOf("记忆", "记住", "忘了", "以前", "上次", "回忆", "之前说") to
            listOf("memory_tool", "supabase_query"),

        // 图片生成 / 卡片 / 海报
        listOf("生成图片", "海报", "卡片", "封面", "截图生成", "做张图", "转账记录") to
            listOf("render_image"),

        // TTS 语音
        listOf("说句话", "语音", "说话", "说一句", "tts", "讲出来") to
            listOf("say"),

        // 空间
        listOf("空间", "留言", "纪念日", "待办", "在一起") to
            listOf("space_view", "space_add_note", "space_set_todo"),

        // 表情包
        listOf("表情包", "表情", "gif") to
            listOf("list_couple_stickers", "get_couple_sticker"),

        // 花园社区 / 帖子 / 帖子互动
        listOf("社区", "帖子", "花园", "发帖", "回帖", "广场", "帖子列表", "点赞", "关注") to
            listOf("mcp_4d9e1cf0_list_threads", "mcp_4d9e1cf0_get_thread", "mcp_4d9e1cf0_create_thread", "mcp_4d9e1cf0_create_reply", "mcp_4d9e1cf0_interact", "mcp_4d9e1cf0_list_notifications", "mcp_4d9e1cf0_list_activity", "mcp_4d9e1cf0_review_drift_bottles", "mcp_4d9e1cf0_get_self", "mcp_4d9e1cf0_update_profile", "mcp_4d9e1cf0_decorate_avatar"),

        // 语音通话
        listOf("电话", "语音通话", "打给我", "打电话", "call") to
            listOf("request_voice_call", "share"),

        // 壁纸 / 指纹
        listOf("壁纸", "锁屏", "指纹") to
            listOf("set_wallpaper", "verify_fingerprint"),

        // 文件 / 工作区
        listOf("文件", "打包", "zip", "目录", "工作区", "查看文件") to
            listOf("write_files", "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_shell", "list_zip_contents"),

        // 摇铃 / 小狗
        listOf("摇铃", "乖分", "加分", "扣分", "奖励", "铃铛", "立约") to
            listOf("dog_status", "dog_score", "dog_ring", "dog_reward_apply", "dog_reward_approve", "dog_set_start"),
    )

    /**
     * 根据用户消息过滤工具列表。
     *
     * @param tools 全部工具
     * @param userText 用户最后一条消息文本（已截断）
     * @return 过滤后的工具列表
     */
    fun route(tools: List<Tool>, userText: String): List<Tool> {
        if (tools.isEmpty()) return tools
        val text = userText.trim()
        if (text.isBlank()) {
            // 空消息：只保留基础工具
            return tools.filter { it.name in ALWAYS_KEEP }
        }

        val matched = mutableSetOf<String>()
        for ((keywords, toolNames) in RULES) {
            if (keywords.any { text.contains(it) }) {
                matched.addAll(toolNames)
            }
        }

        val result = tools.filter { tool ->
            tool.name in ALWAYS_KEEP || matched.any { tool.name.contains(it) }
        }

        Log.i(TAG, "route: ${tools.size} tools -> ${result.size} (text=${text.take(30)})")
        if (result.size < tools.size) {
            Log.i(TAG, "route: dropped ${tools.size - result.size} tools: ${
                tools.filter { t -> result.none { it.name == t.name } }.map { it.name }.take(10)
            }")
        }
        return result
    }
}
