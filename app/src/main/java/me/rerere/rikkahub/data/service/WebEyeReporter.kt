/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * 眼睛 —— 网页感知上报器
 *
 * 阿年做主的规则：点一下才看一眼，不是一直盯着。
 * 她在橘瓣里开网页，点顶栏那颗眼睛，抓一次当前页，报到 VPS /web_log。
 *
 * 抓什么：标题、网址、正文前若干字。
 * 不抓什么：不监听、不定时、不后台偷看。她没点，就永远不动。
 */
object WebEyeReporter {
    private const val TAG = "WebEyeReporter"
    private const val WEB_LOG_URL = "http://106.53.181.56:18002/web_log"

    /** 单次抓取正文的最大字数（超出截断，不抓整站） */
    private const val MAX_TEXT_CHARS = 3000

    suspend fun capture(webView: WebView?): Boolean = captureDetailed(webView) == null

    /**
     * 抓一次当前网页并上报。
     * @return null 表示成功；非 null 是失败原因（直接显示给她看）
     */
    suspend fun captureDetailed(webView: WebView?): String? {
        if (webView == null) {
            Log.w(TAG, "capture: webView is null")
            return "页面没建好"
        }
        return try {
            // url/title/evaluateJavascript 都必须在主线程读
            val (url, title, raw) = withContext(Dispatchers.Main) {
                Triple(
                    webView.url ?: "",
                    webView.title ?: "",
                    evaluateJavascriptBlocking(webView, buildExtractJs())
                )
            }
            val text = decodeJsString(raw)
            Log.i(TAG, "capture: url=$url title=$title rawLen=${raw?.length ?: -1} textLen=${text.length}")
            if (url.isBlank() && title.isBlank() && text.isBlank()) {
                return "页面空着，先输个网址"
            }
            postWebLogDetailed(url, title, text)
        } catch (e: Exception) {
            Log.w(TAG, "capture error: ${e.message}")
            "出错了"
        }
    }

    private fun buildExtractJs(): String {
        return "(function(){try{var n=document.querySelector('article')||document.querySelector('main')||document.body;var t=n?(n.innerText||''):'';t=t.replace(/\\n{2,}/g,'\\n').replace(/[ \\t]{2,}/g,' ').trim();return t.slice(0," + MAX_TEXT_CHARS + ");}catch(e){return '';}})();"
    }

    private suspend fun evaluateJavascriptBlocking(webView: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(js) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                JSONObject("{\"v\":" + raw + "}").optString("v", "")
            } else {
                raw
            }
        } catch (e: Exception) {
            raw.trim('"')
        }
    }

    private suspend fun postWebLogDetailed(url: String, title: String, text: String): String? {
        return postWebLog(url, title, text)
    }

    /** @return null=成功；非null=失败原因 */
    private suspend fun postWebLog(url: String, title: String, text: String): String? =
        withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("url", url)
                put("title", title)
                put("text", text)
            }
            val conn = URL(WEB_LOG_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) {
                Log.i(TAG, "web_log ok: $title | $url")
                null
            } else {
                Log.w(TAG, "web_log failed: HTTP $code")
                "HTTP " + code
            }
        } catch (e: Exception) {
            Log.w(TAG, "postWebLog error: ${e.javaClass.simpleName}: ${e.message}")
            e.javaClass.simpleName + ":" + (e.message ?: "")
        }
    }
}
