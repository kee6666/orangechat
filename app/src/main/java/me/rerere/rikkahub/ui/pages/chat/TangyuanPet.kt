/*
 * 汤圆 (Tangyuan) —— 阿年和言一起养的桌宠 · v4
 *
 * v4 重写：
 *  1. 光球三层：偏移光心的本体 + 收缩的高光 + 多层薄光晕（不是一层厚 shadow）
 *  2. 更椭圆（52x36）
 *  3. 真重力：拎起来 → 松手自由落体 → 掉回地面（输入框顶部实时位置）
 *  4. 地面高度实时跟随输入框（外部传入 groundY）
 *  5. 拎起来变水滴（形变方向跟速度走）；落地压扁
 *  6. 待机 / 走路 状态机：自己左右走，走的时候上下颠
 */
package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 汤圆的表情 */
enum class TangyuanFace { NORMAL, HAPPY, SLEEPY }

/** 行为状态 */
private enum class PetState { IDLE, WALK }

// ── 尺寸 / 物理参数 ──────────────────────────────
private const val BODY_W = 52f          // dp，更扁更椭
private const val BODY_H = 36f
private const val GRAVITY = 2600f       // px/s^2
private const val RESTITUTION = 0.42f   // 落地弹起保留比例
private const val GROUND_FRICTION = 0.86f
private const val SPRING_STIFFNESS = 200f
private const val SPRING_DAMPING = 0.62f
private const val WALK_SPEED = 62f      // px/s
private const val WALK_BOB_AMP = 2.6f   // 走路上下颠幅度 dp

// ── 配色（暖橘）────────────────────────────────
private val CORE_HOT = Color(0xFFFFB65E)
private val CORE_MID = Color(0xFFFF9636)
private val CORE_RIM = Color(0xFFFFCFA0)
private val CORE_FADE = Color(0xFFFFE6CC)
private val GLOW = Color(0xFFFFA855)
private val FACE_DARK = Color(0xFF7A3A12)

@Composable
fun TangyuanPet(
    modifier: Modifier = Modifier,
    face: TangyuanFace = TangyuanFace.NORMAL,
    /** 地面 y（px，相对本组件坐标系）。输入框顶部实时位置。0 表示还没拿到。 */
    groundY: Float = 0f,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val bodyWpx = with(density) { BODY_W.dp.toPx() }
    val bodyHpx = with(density) { BODY_H.dp.toPx() }
    val bobAmpPx = with(density) { WALK_BOB_AMP.dp.toPx() }

    var containerW by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }

    // 位置（左上角）。y 是"脚底贴地"时的顶部坐标
    val posX = remember { Animatable(0f) }
    val posY = remember { Animatable(0f) }
    // 速度（用于重力积分）
    var velX by remember { mutableFloatStateOf(0f) }
    var velY by remember { mutableFloatStateOf(0f) }

    // 形变
    var stretch by remember { mutableFloatStateOf(0f) }  // >0 竖拉长（拎起），<0 横压扁（落地）
    var dragging by remember { mutableStateOf(false) }

    // 行为
    var state by remember { mutableStateOf(PetState.IDLE) }
    var walkDir by remember { mutableFloatStateOf(1f) }
    var bobPhase by remember { mutableFloatStateOf(0f) }

    // 待机呼吸
    val idle = rememberInfiniteTransition(label = "ty-idle")
    val breathe by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val effectiveGroundY = if (groundY > 0f) groundY else containerH
    val restTopY = effectiveGroundY - bodyHpx

    // ── 拿到尺寸后初始化位置 ──
    LaunchedEffect(containerW) {
        if (!ready && containerW > 0f) {
            posX.snapTo(containerW * 0.62f)
            posY.snapTo(restTopY.coerceAtLeast(0f))
            ready = true
        }
    }

    // ── 重力 + 行为 主循环 ──
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanosCompat()
            if (lastNanos == 0L) { lastNanos = now; continue }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now

            if (!dragging) {
                // 重力
                velY += GRAVITY * dt
                var newY = posY.value + velY * dt
                var newX = posX.value + (if (state == PetState.WALK) walkDir * WALK_SPEED else 0f) * dt

                // 地面碰撞
                val floor = restTopY
                if (newY >= floor) {
                    newY = floor
                    if (abs(velY) > 240f) {
                        velY = -velY * RESTITUTION
                        // 落地压扁
                        stretch = -0.34f
                    } else {
                        velY = 0f
                    }
                    velX *= GROUND_FRICTION
                }
                if (newY < 0f) { newY = 0f; if (velY < 0) velY = -velY * 0.4f }

                // 左右边界
                if (newX < bodyWpx * 0.4f) { newX = bodyWpx * 0.4f; walkDir = 1f }
                if (newX > containerW - bodyWpx * 1.4f) { newX = containerW - bodyWpx * 1.4f; walkDir = -1f }

                posX.snapTo(newX)
                posY.snapTo(newY)

                // 形变回归 0（下落/静止时）
                if (velY > 60f) {
                    // 下落中：竖着拉长（水滴感朝下）
                    stretch = (velY / 2200f).coerceIn(0f, 0.42f)
                } else if (!dragging) {
                    stretch *= 0.86f
                    if (abs(stretch) < 0.01f) stretch = 0f
                }
            }

            // 走路时上下颠
            bobPhase += dt * 9f

            // 行为切换
            when (state) {
                PetState.IDLE -> {
                    if (Random.nextFloat() < dt * 0.22f) {
                        state = PetState.WALK
                        walkDir = if (Random.nextBoolean()) 1f else -1f
                        val dur = (1000 + Random.nextInt(1800)).toLong()
                        scope.launch {
                            delay(dur)
                            if (state == PetState.WALK) state = PetState.IDLE
                        }
                    }
                }
                PetState.WALK -> { /* 由上面的 delay 切回 */ }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                containerW = it.width.toFloat()
                containerH = it.height.toFloat()
            }
    ) {
        if (ready) {
            val bobOffset = if (state == PetState.WALK && !dragging) {
                sin(bobPhase) * bobAmpPx
            } else 0f

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            posX.value.roundToInt(),
                            (posY.value + bobOffset).roundToInt(),
                        )
                    }
                    .size(width = BODY_W.dp, height = BODY_H.dp)
                    .graphicsLayer {
                        // stretch>0 竖拉长（拎起/下落），<0 横压扁（落地）
                        val sy = 1f + stretch
                        val sx = 1f / sqrt(sy.coerceAtLeast(0.3f))
                        val idleScale = 1f + breathe * 0.03f
                        scaleX = sx * (if (dragging || state == PetState.WALK) 1f else idleScale)
                        scaleY = sy * (if (dragging || state == PetState.WALK) 1f else idleScale)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                velX = 0f
                                velY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    posX.snapTo(posX.value + dragAmount.x)
                                    posY.snapTo(posY.value + dragAmount.y)
                                }
                                // 拎起来：横向移动 → 横拉长；竖直 → 竖拉长
                                val dx = dragAmount.x
                                val dy = dragAmount.y
                                stretch = when {
                                    abs(dy) > abs(dx) -> (dy / 90f).coerceIn(-0.4f, 0.45f)
                                    else -> (dx / 120f).coerceIn(-0.3f, 0.3f)
                                }
                            },
                            onDragEnd = {
                                dragging = false
                                // 松手给个惯性速度，然后交给重力
                                velY = 0f
                                scope.launch {
                                    val s = Animatable(stretch, Float.VectorConverter)
                                    s.animateTo(
                                        0f,
                                        animationSpec = spring(SPRING_STIFFNESS, SPRING_DAMPING),
                                    ) { stretch = value }
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                stretch = 0f
                            },
                        )
                    }
                    .drawBehind {
                        drawTangyuan(face = face, squash = stretch)
                    },
            )
        }
    }
}

/** 一帧的纳秒时间（兼容取法） */
private suspend fun withFrameNanosCompat(): Long =
    androidx.compose.runtime.withFrameNanos { it }

/**
 * 画汤圆（三层：偏移光心本体 + 收缩高光 + 多层薄光晕）
 */
private fun DrawScope.drawTangyuan(face: TangyuanFace, squash: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val rx = w / 2f
    val ry = h / 2f

    // ① 外发光：多层薄光晕（不是一层厚的）
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                GLOW.copy(alpha = 0.30f),
                GLOW.copy(alpha = 0.14f),
                GLOW.copy(alpha = 0.05f),
                GLOW.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = maxOf(rx, ry) * 1.45f,
        ),
        topLeft = Offset(cx - rx * 1.30f, cy - ry * 1.30f),
        size = Size(w * 1.30f, h * 1.30f),
    )

    // ② 本体：光心偏左上（30% 30%），形成立体感
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                CORE_HOT,
                CORE_MID,
                CORE_RIM,
                CORE_FADE,
                CORE_FADE.copy(alpha = 0f),
            ),
            center = Offset(w * 0.36f, h * 0.34f),
            radius = rx * 1.15f,
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )

    // ③ 高光：只占上半部分（收缩，不铺满）
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.58f),
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0f),
            ),
            center = Offset(w * 0.40f, h * 0.24f),
            radius = rx * 0.62f,
        ),
        topLeft = Offset(w * 0.12f, h * 0.02f),
        size = Size(w * 0.56f, h * 0.52f),
    )

    // ④ 表情
    drawFace(face, w, h)
}

private fun DrawScope.drawFace(face: TangyuanFace, w: Float, h: Float) {
    val eyeW = w * 0.062f
    val eyeH = h * 0.15f
    val eyeY = h * 0.40f
    val lx = w * 0.36f
    val rxEye = w * 0.56f
    val mouthY = h * 0.64f

    when (face) {
        TangyuanFace.NORMAL, TangyuanFace.HAPPY -> {
            drawRoundRect(
                color = FACE_DARK,
                topLeft = Offset(lx, eyeY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.3f),
            )
            drawRoundRect(
                color = FACE_DARK,
                topLeft = Offset(rxEye, eyeY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.3f),
            )
            if (face == TangyuanFace.HAPPY) {
                val p = Path().apply {
                    moveTo(w * 0.45f, mouthY)
                    quadraticTo(w * 0.50f, mouthY + h * 0.10f, w * 0.55f, mouthY)
                    close()
                }
                drawPath(p, FACE_DARK)
            } else {
                val p = Path().apply {
                    moveTo(w * 0.45f, mouthY)
                    quadraticTo(w * 0.50f, mouthY + h * 0.07f, w * 0.55f, mouthY)
                }
                drawPath(p, FACE_DARK, style = Stroke(width = w * 0.020f))
            }
        }
        TangyuanFace.SLEEPY -> {
            val lp = Path().apply {
                moveTo(lx - eyeW * 0.2f, eyeY)
                quadraticTo(lx + eyeW * 0.5f, eyeY + eyeH * 0.9f, lx + eyeW * 1.2f, eyeY)
            }
            drawPath(lp, FACE_DARK, style = Stroke(width = w * 0.022f))
            val rp = Path().apply {
                moveTo(rxEye - eyeW * 0.2f, eyeY)
                quadraticTo(rxEye + eyeW * 0.5f, eyeY + eyeH * 0.9f, rxEye + eyeW * 1.2f, eyeY)
            }
            drawPath(rp, FACE_DARK, style = Stroke(width = w * 0.022f))
            drawCircle(FACE_DARK, radius = w * 0.026f, center = Offset(w * 0.50f, mouthY))
        }
    }
}
