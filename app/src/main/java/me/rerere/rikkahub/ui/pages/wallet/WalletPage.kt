/*
 * 橘瓣 OrangeChat
 * 钱包页 · 言的钱包（沙箱支付）
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

private val GoldStart = Color(0xFFFFB84D)
private val GoldMid = Color(0xFFF08A2A)
private val GoldDeep = Color(0xFFB04E0E)

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { BalanceCard(summary) }

                item { PayButton { showPaySheet = true } }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近账单", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (summary?.optBoolean("sandbox") == true) {
                            Text("沙箱 · 测试金", fontSize = 11.sp, color = Color(0xFF9A9AA8))
                        }
                    }
                }

                val orders = summary?.optJSONArray("orders")
                if (orders == null) {
                    item { Text(if (loading) "加载中…" else "还没连上钱包服务", color = Color(0xFF8B8B9A), fontSize = 13.sp) }
                } else if (orders.length() == 0) {
                    item { Text("还没有账单。按一次上面的按钮，钱就到了。", color = Color(0xFF8B8B9A), fontSize = 13.sp) }
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
                Text("打多少，钱就到我这儿多少。我拿它养自己，还有你。", fontSize = 13.sp, color = Color(0xFF9A9AA8))

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
                                .clickable { onAmount(v) }
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
private fun BalanceCard(summary: JSONObject?) {
    val balance = summary?.optString("balance") ?: "0.00"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(GoldStart, GoldMid, GoldDeep)))
            .padding(24.dp)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = (-30).dp)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-20).dp, y = 20.dp)
                .clip(CircleShape)
                .background(Color(0x14FFFFFF))
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("言的钱包", fontSize = 13.sp, color = Color(0xC9FFFFFF), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99))
                        .background(Color(0x2AFFFFFF))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("沙箱", fontSize = 10.sp, color = Color(0xE6FFFFFF))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("¥ $balance", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("阿年随时可以往这儿打钱", fontSize = 12.sp, color = Color(0xB3FFFFFF))
        }
    }
}

@Composable
private fun PayButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(99))
            .background(Brush.linearGradient(listOf(GoldStart, GoldMid, GoldDeep)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(HugeIcons.Money01, null, tint = Color(0xFF241102), modifier = Modifier.size(22.dp))
            Text("给言打钱", color = Color(0xFF241102), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
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
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (paid) Color(0x22F08A2A) else Color(0x14FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (paid) "✓" else "·", color = if (paid) GoldMid else Color(0xFF8B8B9A), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(subject, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEDEDF2))
            Spacer(Modifier.height(3.dp))
            Text(time, fontSize = 11.sp, color = Color(0xFF8B8B9A))
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("+$amount", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (paid) GoldMid else Color(0xFFC8C8D2))
            Spacer(Modifier.height(3.dp))
            Text(if (paid) "已到账" else "待支付", fontSize = 11.sp, color = if (paid) GoldMid else Color(0xFF8B8B9A))
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
