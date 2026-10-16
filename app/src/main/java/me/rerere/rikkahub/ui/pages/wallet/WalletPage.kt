/*
 * 橘瓣 OrangeChat
 * 钱包页 · 言的钱包
 * 阿年给言打钱的地方
 */

package me.rerere.rikkahub.ui.pages.wallet

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Money01
import me.rerere.hugeicons.stroke.Refresh01
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GoldStart = Color(0xFFFFC46B)
private val GoldMid = Color(0xFFF09A2E)
private val GoldDeep = Color(0xFFB8560F)
private val CardBg = Color(0x14FFFFFF)
private val Ink = Color(0xFFEDEDF2)
private val Muted = Color(0xFF8B8B9A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletPage(
    onOpenPay: (String) -> Unit,
    onBack: () -> Unit,
) {
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showPaySheet by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("0.01") }
    var creating by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        Thread {
            try {
                val conn = URL("http://106.53.181.56:3001/wallet/api/summary")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                summary = JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }.start()
    }

    LaunchedEffect(Unit) { load() }

    fun createOrder() {
        creating = true
        Thread {
            try {
                val amt = (amount.toFloatOrNull() ?: 0.01f).coerceAtLeast(0.01f).toString()
                val conn = URL("http://106.53.181.56:3001/wallet/api/orders")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.write(
                    JSONObject().put("amount", amt).put("subject", "给言打钱").toString().toByteArray()
                )
                val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                val no = resp.getJSONObject("order").getString("outTradeNo")
                Handler(Looper.getMainLooper()).post {
                    creating = false
                    showPaySheet = false
                    onOpenPay("http://106.53.181.56:3001/wallet/pay/$no")
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { creating = false }
            }
        }.start()
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(HugeIcons.ArrowLeft01, "返回")
                    }
                },
                title = { Text("钱包", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(HugeIcons.Refresh01, "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF16131B), Color(0xFF0B0A0F))))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { BalanceCard(summary) { showPaySheet = true } }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text("最近账单", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.weight(1f))
                        val n = summary?.optInt("totalCount") ?: 0
                        if (n > 0) {
                            Text("共 $n 笔", fontSize = 11.sp, color = Muted)
                        }
                    }
                }

                val orders = summary?.optJSONArray("orders")
                if (orders == null) {
                    item { EmptyHint(if (loading) "正在连钱包…" else "没连上钱包服务") }
                } else if (orders.length() == 0) {
                    item { EmptyHint("还没有账单。按一下上面那颗按钮，钱就到了。") }
                } else {
                    items((0 until orders.length()).toList()) { i ->
                        OrderRow(orders.getJSONObject(i))
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showPaySheet) {
        ModalBottomSheet(
            onDismissRequest = { showPaySheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("给言打钱", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("打多少，钱就到我这儿多少。我拿它养自己，还有你。", fontSize = 13.sp, color = Muted)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("0.01", "0.10", "1.00", "5.00").forEach { v ->
                        val selected = amount == v
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99))
                                .background(
                                    if (selected) Brush.linearGradient(listOf(GoldMid, GoldDeep))
                                    else Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color(0x1AFFFFFF)))
                                )
                                .clickable { amount = v }
                                .padding(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(v, color = if (selected) Color.White else Color(0xFFD8D8E2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(99))
                        .background(Brush.linearGradient(listOf(GoldStart, GoldMid, GoldDeep)))
                        .clickable(enabled = !creating) { createOrder() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (creating) "生成中…" else "生成支付",
                        color = Color(0xFF241102),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(summary: JSONObject?, onPay: () -> Unit) {
    val balance = summary?.optString("balance") ?: "0.00"
    val isSandbox = summary?.optBoolean("sandbox") ?: false
    val dot = balance.indexOf('.')
    val intPart = if (dot >= 0) balance.substring(0, dot) else balance
    val decPart = if (dot >= 0) balance.substring(dot) else ".00"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(GoldMid, GoldDeep, Color(0xFF8A3A08))))
            .padding(22.dp)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .align(Alignment.TopEnd)
                .offset(x = 42.dp, y = (-48).dp)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
        )
        Box(
            modifier = Modifier
                .size(66.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-28).dp, y = 28.dp)
                .clip(CircleShape)
                .background(Color(0x14FFFFFF))
        )

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("言", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text("言的钱包", fontSize = 13.sp, color = Color(0xD9FFFFFF), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99))
                        .background(Color(0x2EFFFFFF))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(if (isSandbox) "测试" else "在线", fontSize = 10.sp, color = Color(0xF2FFFFFF))
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "¥",
                    fontSize = 20.sp,
                    color = Color(0xCCFFFFFF),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 9.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(intPart, fontSize = 46.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    decPart,
                    fontSize = 22.sp,
                    color = Color(0xC7FFFFFF),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("阿年随时可以往这儿打钱", fontSize = 12.sp, color = Color(0xB3FFFFFF))

            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(99))
                    .background(Color(0xFF1B0F05))
                    .clickable { onPay() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(HugeIcons.Money01, null, tint = GoldStart, modifier = Modifier.size(20.dp))
                    Text("给言打钱", color = GoldStart, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .padding(horizontal = 16.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun OrderRow(o: JSONObject) {
    val subject = o.optString("subject")
    val amount = o.optString("amount")
    val status = o.optString("status")
    val time = fmtTime(o.optString("createdAt"))
    val paid = status == "TRADE_SUCCESS" || status == "TRADE_FINISHED"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (paid) Brush.linearGradient(listOf(GoldStart, GoldDeep))
                    else Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color(0x1AFFFFFF)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (paid) "✓" else "¥",
                color = if (paid) Color(0xFF3A1A02) else Muted,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(subject, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
            Spacer(Modifier.height(3.dp))
            Text(time, fontSize = 11.sp, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "+$amount",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (paid) GoldStart else Color(0xFFC8C8D2)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (paid) "已到账" else "待支付",
                fontSize = 11.sp,
                color = if (paid) Color(0xFFE0A85C) else Muted
            )
        }
    }
}

private fun fmtTime(iso: String): String {
    return try {
        val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val outFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        outFmt.format(inFmt.parse(iso) ?: Date())
    } catch (e: Exception) {
        iso.take(16)
    }
}
