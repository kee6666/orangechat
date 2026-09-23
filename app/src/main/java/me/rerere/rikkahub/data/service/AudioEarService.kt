/*
 * 橘瓣 OrangeChat - AudioEarService 耳朵（v4，2026-09-23）
 * AudioPlaybackCapture 抓系统外放音频流 → 本地分析 → 摘要进感知
 *
 * v4 相对 v3 的改动：
 *   1. token 不再从 SharedPreferences 复原（那条路走不通），只从内存 EarTokenHolder 取。
 *   2. 加明确日志点，方便 logcat -s AudioEarService 定位。
 *
 * 铁律：
 * - 只听系统外放的媒体音频流，不碰麦克风
 * - 微信/QQ 系统通话音频不碰
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
        private const val CHANNEL_ID = "audio_ear"
        private const val NOTIFICATION_ID = 4343
        private const val SAMPLE_RATE = 16000
        private const val MUSIC_MIN_MS = 90_000L
        private const val RMS_ACTIVE = 120.0

        fun tryStart(context: Context, resultCode: Int, data: Intent?) {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                Log.w(TAG, "tryStart: sdk too low")
                return
            }
            if (data != null) EarTokenHolder.save(resultCode, data)
            val consent = context.getSharedPreferences("audio_ear", Context.MODE_PRIVATE)
                .getString("consent", null)
            if (consent != "granted") {
                Log.w(TAG, "tryStart: no consent")
                return
            }
            Log.i(TAG, "tryStart: launching foreground service")
            val i = Intent(context, AudioEarService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
            if (data != null) i.putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var handler: Handler? = null
    private var buf: ShortArray? = null
    private var sessionStart = 0L
    private var activeMs = 0L
    private var voicedMs = 0L
    private val pitches = mutableListOf<Double>()
    private var lastReportedKind = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (record != null) {
            Log.i(TAG, "onStartCommand: already capturing")
            return START_STICKY
        }
        startForegroundQuietly()

        var resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        var resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null || resultCode < 0) {
            if (EarTokenHolder.alive()) {
                resultCode = EarTokenHolder.code()
                resultData = EarTokenHolder.data()
                Log.i(TAG, "onStartCommand: token recovered from memory holder")
            }
        }

        if (resultData == null || resultCode < 0) {
            Log.w(TAG, "onStartCommand: no token, die")
            stopSelf()
            return START_STICKY
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val p = pm.getMediaProjection(resultCode, resultData)
            if (p == null) {
                Log.w(TAG, "getMediaProjection returned null")
                stopSelf()
                return START_STICKY
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection onStop")
                    cleanup()
                }
            }, Handler(android.os.Looper.getMainLooper()))
            projection = p
            startCapture(p)
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "projection failed: " + e.message, e)
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
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: " + e.message)
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
                Log.w(TAG, "AudioRecord not initialized, state=" + r.state)
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
            Log.i(TAG, "capture started OK")
        } catch (e: Exception) {
            Log.w(TAG, "capture init failed: " + e.message, e)
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
                evaluate(System.currentTimeMillis(), rms)
            }
            handler?.postDelayed(this, 500)
        }
    }

    private fun evaluate(now: Long, rms: Double) {
        if (lastReportedKind.isNotEmpty()) return
        val elapsed = now - sessionStart
        if (elapsed % 30000 < 500) {
            Log.i(TAG, "progress: elapsed=" + elapsed / 1000 + "s active=" + activeMs / 1000 + "s rms=" + rms.toInt())
        }
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
            Log.i(TAG, "reporting: " + desc)
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
                val rc = conn.responseCode
                Log.i(TAG, "reportToVps http=" + rc)
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "reportToVps failed: " + e.message)
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
