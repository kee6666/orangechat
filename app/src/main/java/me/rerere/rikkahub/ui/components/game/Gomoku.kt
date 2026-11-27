/*
 * 橘瓣 OrangeChat — 聊天里直接下的五子棋（2026-11-27）
 * 棋盘不是网页，是 Canvas 画在消息气泡里。状态存在本机，AI 用 [[move:h8]] 落子。
 */

package me.rerere.rikkahub.ui.components.game

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.hypot

const val GOMOKU_MARK = "[[gomoku]]"
const val GRID = 15

data class Stone(val x: Int, val y: Int, val mine: Boolean)

object GomokuBus {
    // 用户在棋盘上落子后，把这句话发进聊天
    val outgoing = MutableSharedFlow<String>(extraBufferCapacity = 4)
}

object GomokuStore {
    private const val PREFS = "gomoku_local"
    private const val KEY = "moves"
    private const val KEY_OPEN = "open"

    private var cached: List<Stone>? = null
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureOpen(ctx: Context, open: Boolean) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_OPEN, false) != open) p.edit().putBoolean(KEY_OPEN, open).apply()
    }

    fun moves(ctx: Context): List<Stone> {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY, "").orEmpty()
        val list = raw.split(';').mapNotNull { seg ->
            val parts = seg.split(',')
            if (parts.size != 3) null
            else Stone(parts[0].toIntOrNull() ?: return@mapNotNull null,
                parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2] == "u")
        }
        cached = list
        return list
    }

    fun add(ctx: Context, stone: Stone) {
        val now = moves(ctx)
        if (now.any { it.x == stone.x && it.y == stone.y }) return
        val next = now + stone
        cached = next
        prefs(ctx).edit()
            .putString(KEY, next.joinToString(";") { "${it.x},${it.y},${if (it.mine) "u" else "a"}" })
            .apply()
        version++
    }

    fun reset(ctx: Context) {
        cached = emptyList()
        prefs(ctx).edit().putString(KEY, "").putBoolean(KEY_OPEN, true).apply()
        version++
    }

    fun parseMove(token: String): Stone? {
        val t = token.trim().lowercase()
        if (t.length < 2) return null
        val col = t[0] - 'a'
        val row = t.drop(1).toIntOrNull() ?: return null
        if (col !in 0 until GRID) return null
        val y = row - 1
        if (y !in 0 until GRID) return null
        return Stone(col, y, false)
    }
}

fun coordLabel(x: Int, y: Int): String = "${('a' + x)}${y + 1}"

/** 从正文里抠出 [[move:e8]] 这种落子指令 */
fun extractMove(text: String): Stone? {
    val raw = text.lowercase().replace(" ", "")
    val m = Regex("""\[\[move:([a-o])([0-9]{1,2})\]\]""").find(raw) ?: return null
    return GomokuStore.parseMove(m.groupValues[1] + m.groupValues[2])
}

fun stripMarkers(text: String): String = text
    .replace(Regex("""\[\[gomoku\]\]""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\[\[move:\s*[a-oA-O]\d{1,2}\s*\]\]""", RegexOption.IGNORE_CASE), "")
    .trim()

private fun wins(list: List<Stone>, last: Stone): Boolean {
    val dirs = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    for ((dx, dy) in dirs) {
        var n = 1
        var i = 1
        while (true) {
            val s = list.firstOrNull { it.x == last.x + dx * i && it.y == last.y + dy * i && it.mine == last.mine }
            if (s == null) break
            n++; i++
        }
        i = 1
        while (true) {
            val s = list.firstOrNull { it.x == last.x - dx * i && it.y == last.y - dy * i && it.mine == last.mine }
            if (s == null) break
            n++; i++
        }
        if (n >= 5) return true
    }
    return false
}

@Composable
fun GomokuBoard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = GomokuStore.version
    val stones = GomokuStore.moves(context)
    val myTurn = stones.count { it.mine } <= stones.count { !it.mine }

    val lineColor = Color(0xFF8D6E63)
    val boardColor = Color(0xFFF3E2C7)
    val mineColor = Color(0xFF2E2A28)
    val aiColor = Color(0xFFFDFBF7)

    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(boardColor)
                .pointerInput(v, myTurn) {
                    detectTapGestures { offset ->
                        if (!myTurn) return@detectTapGestures
                        val pad = size.width * 0.055f
                        val span = size.width - pad * 2
                        val step = span / (GRID - 1)
                        val cx = ((offset.x - pad) / step).toInt().coerceIn(0, GRID - 1)
                        val cy = ((offset.y - pad) / step).toInt().coerceIn(0, GRID - 1)
                        if (hypot(offset.x - (pad + cx * step), offset.y - (pad + cy * step)) > step * 0.6f) return@detectTapGestures
                        val stone = Stone(cx, cy, true)
                        GomokuStore.add(context, stone)
                        GomokuBus.outgoing.tryEmit("我下在${coordLabel(cx, cy)}")
                    }
                },
        ) {
            drawGomoku(stones, lineColor, mineColor, aiColor)
        }
        val last = stones.lastOrNull()
        val status = when {
            stones.isEmpty() -> "空盘。你先手 —— 点交叉点落子"
            last != null && wins(stones, last) -> if (last.mine) "五连，你赢了" else "五连，我赢了"
            myTurn -> "轮到你"
            else -> "等我想"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawGomoku(stones: List<Stone>, line: Color, mine: Color, ai: Color) {
    val pad = size.width * 0.055f
    val span = size.width - pad * 2
    val step = span / (GRID - 1)
    for (i in 0 until GRID) {
        drawLine(line, Offset(pad, pad + i * step), Offset(size.width - pad, pad + i * step), strokeWidth = 1.6f)
        drawLine(line, Offset(pad + i * step, pad), Offset(pad + i * step, size.height - pad), strokeWidth = 1.6f)
    }
    listOf(3, 7, 11).forEach { a ->
        listOf(3, 7, 11).forEach { b ->
            drawCircle(line, radius = 3.2f, center = Offset(pad + a * step, pad + b * step))
        }
    }
    stones.forEach { s ->
        val c = Offset(pad + s.x * step, pad + s.y * step)
        drawCircle(if (s.mine) mine else ai, radius = step * 0.44f, center = c)
        drawCircle(Color(0x33000000), radius = step * 0.44f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
    }
}
/** 消息气泡里的棋局：正文里有 [[gomoku]] 就出这盘棋 */
@Composable
fun GomokuCard(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val move = remember(text) { extractMove(text) }
    LaunchedEffect(text) {
        move?.let { GomokuStore.add(context, it) }
    }
    val say = remember(text) { stripMarkers(text) }
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        if (say.isNotBlank()) {
            Text(text = say, style = MaterialTheme.typography.bodyMedium)
        }
        GomokuBoard()
    }
}
