/*
 * 橘瓣 OrangeChat - EarConsentActivity 耳朵授权页（v4，2026-09-23 修复）
 *
 * 三版病史：
 *   v2：逻辑整个写反，agree 标志永远为 false -> 每点必弹框。
 *   v3：想用 SharedPreferences 存 token，但 MediaProjection 凭证带 Binder，序列化
 *       往返必失败 -> 复原失败落回弹框分支 -> 还是每点必弹。
 *   v4（本版）：放弃持久化 token 的幻想。token 只放进程内存（EarTokenHolder），
 *       同进程内复用不弹框；进程被系统杀了，就老老实实再弹一次。
 *
 * 行为预期：
 *   - 装机后第一次点耳朵 -> 弹一次系统授权框，同意，耳朵开。
 *   - 之后同一次开机、橘瓣进程还活着时，点耳朵不再弹，提示"耳朵已经开着"。
 *   - 手机重启 / 橘瓣被系统彻底杀掉后，第一次点还会弹一次（无法避免）。
 *   - 想减少这种弹框：系统设置里给橘瓣关掉电池优化。
 *
 * 铁律不变：没有她的明确同意，耳朵永远沉默。
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
import me.rerere.rikkahub.data.service.EarTokenHolder

class EarConsentActivity : ComponentActivity() {

    companion object {
        const val PREFS = "audio_ear"
        const val KEY_CONSENT = "consent"

        fun open(context: Context, forceReauth: Boolean = false) {
            val i = Intent(context, EarConsentActivity::class.java)
            i.putExtra("force", forceReauth)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        fun hasConsented(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CONSENT, null) == "granted"
    }

    private lateinit var pm: MediaProjectionManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                EarTokenHolder.save(result.resultCode, data)
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_CONSENT, "granted").apply()
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

        if (!force && EarTokenHolder.alive()) {
            val code = EarTokenHolder.code()
            val data = EarTokenHolder.data()!!
            AudioEarService.tryStart(this, code, data)
            toast("耳朵已经开着")
            finish()
            return
        }

        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
