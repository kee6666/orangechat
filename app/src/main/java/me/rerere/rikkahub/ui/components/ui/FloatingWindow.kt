/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    // true 时挂到 Application 上，切到别的 App 也不消失（需要「显示在其他应用上层」权限）
    appLevel: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val windowContext = if (appLevel) context.applicationContext else context

    // 挂 Application 必须有悬浮窗权限，没权限时 addView 会直接抛异常把 App 带走
    val allowed = !appLevel || runCatching { Settings.canDrawOverlays(windowContext) }.getOrDefault(false)

    var window: IFxAppControl? by remember { mutableStateOf(null) }
    var hinted by remember { mutableStateOf(false) }

    LaunchedEffect(allowed) {
        if (!allowed && !hinted) {
            hinted = true
            runCatching {
                Toast.makeText(context, "小窗要开「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(visibility, allowed) {
        if (visibility && allowed) window?.show() else window?.hide()
    }

    DisposableEffect(windowContext, allowed) {
        if (!allowed) {
            window = null
            return@DisposableEffect onDispose { }
        }
        val installed = runCatching {
            FloatingX.install {
                setTag(tag)
                setContext(windowContext)
                setGravity(FxGravity.LEFT_OR_BOTTOM)
                setOffsetXY(20f, -20f)
                setEnableAnimation(true)
                setLayoutView(ComposeView(windowContext).apply {
                    // 浮窗的 View 不在 Activity 的视图树里，要自己给个 LifecycleOwner，否则 Compose 起不来
                    setViewTreeLifecycleOwner(ProcessLifecycleOwner.get())
                    setContent {
                        RikkahubTheme {
                            content()
                        }
                    }
                })
            }
        }.getOrNull()
        window = installed
        if (visibility) installed?.show() else installed?.hide()
        onDispose {
            window = null
            installed?.cancel()
        }
    }
}
