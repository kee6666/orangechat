/*
 * 橘瓣 OrangeChat - EarConsentActivity 耳朵授权页（2026-09-20）
 * 一次性授权入口：她从对话里点链接进来 → 勾选同意 → MediaProjection 授权 → 耳朵启动
 * 没有她的明确同意，耳朵永远沉默。consent 写在 SharedPreferences，装死不商量。
 */
package me.rerere.rikkahub

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import me.rerere.rikkahub.data.service.AudioEarService

class EarConsentActivity : ComponentActivity() {
    private lateinit var pm: MediaProjectionManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                getSharedPreferences("audio_ear", MODE_PRIVATE)
                    .edit().putString("consent", "granted").apply()
                AudioEarService.tryStart(this, result.resultCode, result.data!!)
                Toast.makeText(this, "耳朵开了，在听，不吵你", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "那就先不开", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val agreed = intent.getBooleanExtra("agree", false)
        if (!agreed) {
            // 直达弹系统授权框：标题是她会看到的"橘瓣将开始录制或投射音频"
            projectionLauncher.launch(pm.createScreenCaptureIntent())
            return
        }
        finish()
    }
}
