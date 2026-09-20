/*
 * 橘瓣 OrangeChat - AudioEarService 耳朵（v1，2026-09-20）
 * AudioPlaybackCapture 抓系统外放音频流 → 本地分析 → 摘要进感知
 *
 * 铁律：
 * - 只听系统外放的媒体音频流，不碰麦克风（她本人说话，另案单独授权）
 * - 微信/QQ 系统通话音频 Android 不允许抓取，不碰
 * - 只出摘要不出原文，不进对话记录
 */
package me.rerere.rikkahub.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

class AudioEarService : Service() {
    companion object {
        const val TAG = "AudioEarService"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EAR_REQUEST_CODE = 7001
        private const val CHANNEL_ID = "audio_ear"
        private const val NOTIFICATION_ID = 4343
        private const val SAMPLE_RATE = 16000
        private const val MUSIC_MIN_MS = 90_000L

        fun tryStart(context: Context, resultCode: Int, data: Intent) {
            if (android.os.Build.VERSION.SDK_INT < 29) return
            val p = context.getSharedPreferences("audio_ear", Context.MODE_PRIVATE)
            if (p.getString("consent", null) != "granted") return
            val i = Intent(context, AudioEarService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var record: AudioRecord? = null
    private var handler: Handler? = null
    private var buf: ShortArray? = null
    private var sessionStart = 0L
    private var musicMs = 0L
    private var speechMs = 0L
    private var lastReportedKind = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || record != null) return START_STICKY
        startForegroundQuietly()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode < 0) {
            stopSelf()
            return START_STICKY
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val p = pm.getMediaProjection(resultCode, resultData) ?: run {
                stopSelf()
                return START_STICKY
            }
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                }
            }
            projectionCallback = cb
            p.registerCallback(cb, Handler(android.os.Looper.getMainLooper()))
            projection = p
            startCapture(p)
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "projection failed", e)
            stopSelf()
            START_STICKY
        }
    }

    private fun startForegroundQuietly() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "耳朵", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val n = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("耳朵开着")
                .setContentText("在听，不说话")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
        }
    }

    private fun startCapture(p: MediaProjection) {
        val conf = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
                .setAudioPlaybackCaptureConfig(conf)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                stopSelf()
                return
            }
            record = r
            buf = ShortArray(SAMPLE_RATE / 2)
            val worker = HandlerThread("audio-ear")
            worker.start()
            handler = Handler(worker.looper)
            sessionStart = System.currentTimeMillis()
            r.startRecording()
            handler?.post(loop)
            Log.i(TAG, "ear capturing started")
        } catch (e: Exception) {
            Log.w(TAG, "capture init failed", e)
            stopSelf()
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            val b = buf ?: return
            val r = record ?: return
            val n = r.read(b, 0, b.size)
            if (n > 0) {
                var sum = 0.0
                for (i in 0 until n) {
                    val s = b[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / n)
                if (rms > 120.0) {
                    speechMs += 500
                } else {
                    musicMs += 500
                }
                evaluate(System.currentTimeMillis())
            }
            handler?.postDelayed(this, 500)
        }
    }

    private fun evaluate(now: Long) {
        if (lastReportedKind.isNotEmpty()) return
        val elapsed = now - sessionStart
        if (elapsed >= MUSIC_MIN_MS && musicMs >= MUSIC_MIN_MS && speechMs < musicMs / 4) {
            lastReportedKind = "music"
            reportToVps("music", "她在放音乐，听了一阵")
        }
    }

    private fun reportToVps(kind: String, summary: String) {
        Thread {
            try {
                val conn = URL("http://106.53.181.56:18002/ear_log").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().apply {
                    put("kind", kind)
                    put("summary", summary)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun cleanup() {
        try {
            handler?.removeCallbacks(loop)
        } catch (_: Exception) {
        }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
