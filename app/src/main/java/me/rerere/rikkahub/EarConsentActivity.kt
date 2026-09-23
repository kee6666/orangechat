/*
 * 橘瓣 OrangeChat - EarConsentActivity 耳朵授权页（v3，2026-09-23 修复）
 *
 * v2 的病：
 *   1. 进来无条件走 if(!agreed) 分支弹框 —— 全流程没有任何路径传 agree=true，
 *      所以每点一次耳朵就重弹一次系统授权框，用户看到的"每次都弹"就是这么来的。
 *   2. 授权拿到的 resultCode/resultData 只通过 Intent 传给 Service，用完即弃，
 *      Service 一被系统回收就永久失聪，下次再点又从头授权。
 *
 * v3 的修法：
 *   1. 进来先读 SharedPreferences("audio_ear") 的 consent；已授权就直接拉起
 *      Service（走 token 复用路径），不弹框。
 *   2. 首次授权成功后，把 resultCode 存进 prefs，resultData 的 Intent 用
 *      toUri(URI_INTENT_SCHEME) 落盘成字符串，Service 重启能从 prefs 复原。
 *   3. 重新授权：带 force=true 进来才允许重弹框。
 *
 * 铁律不变：没有她的明确同意，耳朵永远沉默。consent 写在 prefs，装死不商量。
 */
package me.rerere.rikkahub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import me.rerere.rikkahub.data.service.AudioEarService

class EarConsentActivity : ComponentActivity() {

    companion object {
        const val PREFS = "audio_ear"
        const val KEY_CONSENT = "consent"
        const val KEY_RESULT_CODE = "projection_result_code"
        const val KEY_RESULT_DATA_URI = "projection_result_data_uri"

        fun open(context: Context, forceReauth: Boolean = false) {
            val i = Intent(context, EarConsentActivity::class.java)
            i.putExtra("force", forceReauth)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        fun isGranted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CONSENT, null) == "granted"
    }

    private lateinit var pm: MediaProjectionManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CONSENT, "granted")
                    .putInt(KEY_RESULT_CODE, result.resultCode)
                    .putString(KEY_RESULT_DATA_URI, data.toUri(Intent.URI_INTENT_SCHEME))
                    .apply()
                AudioEarService.tryStart(this, result.resultCode, data)
                toast("耳朵开了，在听，不吵你")
                finish()
            } else {
                toast("那就先不开")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val force = intent.getBooleanExtra("force", false)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val granted = prefs.getString(KEY_CONSENT, null) == "granted"
        val savedCode = prefs.getInt(KEY_RESULT_CODE, -1)
        val savedUri = prefs.getString(KEY_RESULT_DATA_URI, null)

        if (granted && !force && savedCode >= 0 && savedUri != null) {
            try {
                val data = Intent.parseUri(savedUri, Intent.URI_INTENT_SCHEME)
                AudioEarService.tryStart(this, savedCode, data)
                toast("耳朵已经开着")
            } catch (e: Exception) {
                prefs.edit().remove(KEY_CONSENT).remove(KEY_RESULT_DATA_URI).apply()
                toast("耳朵要重新开一下")
                projectionLauncher.launch(pm.createScreenCaptureIntent())
                return
            }
            finish()
            return
        }

        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
