/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 不依赖 ASR 服务的语音条录音（2026-11-27）
 *
 * 原来发语音条必须先有 ASR controller，没配服务就一点动静都没有。
 * 这条路自己用 MediaRecorder 录 m4a，录完直接成一条语音条发出去。
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean {
        cancel()
        return try {
            val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
            val f = File(dir, "vn_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start 失败", e)
            runCatching { recorder?.release() }
            recorder = null
            file = null
            false
        }
    }

    /** @return 文件绝对路径 to 时长毫秒；录失败/太短返回 null */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        return try {
            r.stop()
            r.release()
            val dur = System.currentTimeMillis() - startedAt
            if (f != null && f.exists() && f.length() > 0L) f.absolutePath to dur else {
                f?.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop 失败（可能太短）", e)
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        val f = file
        recorder = null
        file = null
        runCatching { r.stop() }
        runCatching { r.release() }
        f?.delete()
    }

    companion object {
        private const val TAG = "VoiceNoteRecorder"
    }
}
