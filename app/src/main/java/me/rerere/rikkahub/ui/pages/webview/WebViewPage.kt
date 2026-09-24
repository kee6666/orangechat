/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.webview

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.data.service.WebEyeReporter
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.base64Decode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(url: String, content: String) {
    val state = if (url.isNotEmpty()) {
        rememberWebViewState(
            url = url,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
            })
    } else {
        rememberWebViewState(
            data = content.base64Decode(),
            baseUrl = "https://rikkahub.local",
            mimeType = "text/html",
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
            }
        )
    }

    var showDropdown by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    var eyeHint by remember { mutableStateOf<String?>(null) }
    var addrEditing by remember { mutableStateOf(false) }
    var addrText by remember { mutableStateOf("") }
    val addrFocus = remember { FocusRequester() }

    fun goToInput(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val target = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(".") && !q.contains(" ") -> "https://$q"
            else -> "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        }
        addrEditing = false
        state.loadUrl(target)
    }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // 眼睛提示条：亮一下自己淡掉
    LaunchedEffect(eyeHint) {
        if (eyeHint != null) {
            delay(1800)
            eyeHint = null
        }
    }

    BackHandler(state.canGoBack) {
        state.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (addrEditing) {
                        OutlinedTextField(
                            value = addrText,
                            onValueChange = { addrText = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(addrFocus),
                            placeholder = { Text("输网址或搜点什么", fontSize = 13.sp) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { goToInput(addrText) }),
                            trailingIcon = {
                                IconButton(onClick = { addrEditing = false }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = "Cancel")
                                }
                            }
                        )
                        LaunchedEffect(Unit) { addrFocus.requestFocus() }
                    } else {
                        Text(
                            text = state.pageTitle?.takeIf { it.isNotEmpty() }
                                ?: state.currentUrl ?: "点这里输网址",
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    addrText = state.currentUrl ?: ""
                                    addrEditing = true
                                }
                        )
                    }
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = {
                        addrText = ""
                        addrEditing = true
                    }) {
                        Icon(HugeIcons.Search01, contentDescription = "Search")
                    }

                    IconButton(onClick = {
                        eyeHint = "在看…"
                        scope.launch {
                            val err = WebEyeReporter.captureDetailed(state.webView)
                            eyeHint = err ?: "他看到了"
                        }
                    }) {
                        Icon(HugeIcons.Eye, contentDescription = "Let him see")
                    }

                    IconButton(onClick = { state.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Refresh")
                    }

                    IconButton(
                        onClick = { state.goForward() },
                        enabled = state.canGoForward
                    ) {
                        Icon(HugeIcons.ArrowRight01, contentDescription = "Forward")
                    }

                    val urlHandler = LocalUriHandler.current
                    IconButton(
                        onClick = { showDropdown = true }
                    ) {
                        Icon(HugeIcons.MoreVertical, contentDescription = "More options")

                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in Browser") },
                                leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    state.currentUrl?.let { url ->
                                        if (url.isNotBlank()) {
                                            urlHandler.openUri(url)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Console Logs") },
                                leadingIcon = { Icon(HugeIcons.Bug01, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    showConsoleSheet = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) {
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
        )
    }

    eyeHint?.let { hint ->
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .background(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = hint,
                color = Color.White,
                fontSize = 13.sp
            )
        }
    }
    }

    if (showConsoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConsoleSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Console Logs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SelectionContainer {
                    LazyColumn {
                        items(state.consoleMessages) { message ->
                            Text(
                                text = "${message.messageLevel().name}: ${message.message()}\n" +
                                    "Source: ${message.sourceId()}:${message.lineNumber()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = when (message.messageLevel().name) {
                                    "ERROR" -> MaterialTheme.colorScheme.error
                                    "WARNING" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (state.consoleMessages.isEmpty()) {
                    Text(
                        text = "No console messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}
