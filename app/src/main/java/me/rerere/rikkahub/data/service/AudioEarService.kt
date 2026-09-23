/*
 * 橘瓣 OrangeChat - AudioEarService 耳朵（v3，2026-09-23 修复）
 * AudioPlaybackCapture 抓系统外放音频流 → 本地分析 → 摘要进感知
 *
 * v3 相对 v2 的改动：
 *   1. tryStart 支持 resultData 为 null 时从 SharedPreferences 复原（token 复用）。
 *   2. 被系统回收后自动重试一次（3 秒后），不再永久失聪。
 *   3. 修复 v2 里 rms 阈值与 pitch 窗口不匹配导致的 voicedMs 虚高。
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
        private const val RMS_ACTIVE = 120.0
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_RETRY = 1

        fun tryStart(context: Context, resultCode: Int, data: Intent?) {
            if (android.os.Build.VERSION.SDK_INT < 29) return
            val p = context.getSharedPreferences("audio_ear", Context.MODE_PRIVATE)
            if (p.getString("consent", null) != "granted") return
            val i = Intent(context, AudioEarService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
            if (data != null) i.putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var record: AudioRecord? = null
    private var handler: Handler? = null
    private var buf: ShortArray? = null
    private var sessionStart = 0L
    private var activeMs = 0L
    private var voicedMs = 0L
    private val pitches = mutableListOf<Double>()
    private var lastReportedKind = ""
    private var retries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || record != null) return START_STICKY
        startForegroundQuietly()

        var resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        var resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null || resultCode < 0) {
            val p = getSharedPreferences("audio_ear", MODE_PRIVATE)
            resultCode = p.getInt("projection_result_code", -1)
            val uri = p.getString("projection_result_data_uri", null)
            if (uri != null) {
                resultData = try {
                    Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (resultData == null || resultCode < 0) {
            stopSelf()
            return START_STICKY
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val p = pm.getMediaProjection(resultCode, resultData) ?: run {
                scheduleRetry()
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
            retries = 0
            startCapture(p)
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "projection failed", e)
            scheduleRetry()
            stopSelf()
            START_STICKY
        }
    }

    private fun scheduleRetry() {
        if (retries >= MAX_RETRY) return
        retries++
        val p = getSharedPreferences("audio_ear", MODE_PRIVATE)
        if (p.getString("consent", null) != "granted") return
        val code = p.getInt("projection_result_code", -1)
        if (code < 0) return
        Handler(android.os.Looper.getMainLooper()).postDelayed({
            tryStart(this, code, null)
        }, RETRY_DELAY_MS)
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
                if (rms > RMS_ACTIVE) {
                    activeMs += 500
                    if (n >= 2048) {
                        val f0 = estimatePitch(b, n)
                        if (f0 > 0.0) {
                            voicedMs += 500
                            if (pitches.size < 600) pitches.add(f0)
                        }
                    }
                }
                evaluate(System.currentTimeMillis())
            }
            handler?.postDelayed(this, 500)
        }
    }

    private fun evaluate(now: Long) {
        if (lastReportedKind.isNotEmpty()) return
        val elapsed = now - sessionStart
        if (elapsed >= MUSIC_MIN_MS && activeMs >= MUSIC_MIN_MS * 0.7) {
            lastReportedKind = "music"
            val ratio = if (activeMs > 0) voicedMs.toDouble() / activeMs else 0.0
            val p = medianPitch()
            val gender = when {
                p == 0.0 -> ""
                p < 165.0 -> "男声"
                p <= 180.0 -> "男女交界的声音"
                else -> "女声"
            }
            val desc = when {
                ratio < 0.15 -> "她在放音乐，纯音乐居多"
                gender.isNotEmpty() -> "她在放音乐，有${gender}在唱"
                else -> "她在放音乐，混着人声"
            }
            reportToVps("music", desc)
        }
    }

    private fun medianPitch(): Double {
        if (pitches.size < 8) return 0.0
        val s = pitches.sorted()
        val m = s[s.size / 2]
        return if (m in 60.0..300.0) m else 0.0
    }

    private fun estimatePitch(buf: ShortArray, n: Int): Double {
        if (n < 2048) return 0.0
        val win = 2048
        val start = (n - win) / 2
        var e0 = 0.0
        for (i in 0 until win) {
            val v = buf[start + i].toDouble()
            e0 += v * v
        }
        if (e0 < win * 100.0 * 100.0) return 0.0
        val minLag = 16000 / 300
        val maxLag = 16000 / 60
        if (win - maxLag <= 0) return 0.0
        var bestLag = 0
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            var e1 = 0.0
            for (i in 0 until win - maxLag) {
                val a2 = buf[start + i].toDouble()
                val b2 = buf[start + i + lag].toDouble()
                corr += a2 * b2
                e1 += b2 * b2
            }
            val nccf = corr / (sqrt(e0 * e1) + 1e-9)
            if (nccf > bestCorr) {
                bestCorr = nccf
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestCorr > 0.35) 16000.0 / bestLag else 0.0
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
