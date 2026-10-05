/*
 * 汤圆 (Tangyuan) v9
 *
 * v9 修正：
 *  1. 球体发光：中心爆白 → 亮黄 → 橘 → 深橘（自发光感），
 *     外发光用多层径向渐变叠加（柔和光晕，稳定无边界问题）
 *  2. 地面 = 传入的 groundY（输入框顶部，窗口坐标）；键盘弹起时 ChatPage 会更新它
 *  3. 拖动时先 stop 上一个回弹动画，避免频繁提放"僵住"
 *  4. 拖动时物理完全停手
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.drawscope.scale
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
import kotlin.random.Random

enum class TangyuanFace { NORMAL, HAPPY, SLEEPY }

private enum class PetState { IDLE, WALK }

private const val BODY_W = 54f
private const val BODY_H = 38f
private const val GRAVITY = 2400f
private const val RESTITUTION = 0.45f
private const val SPRING_STIFFNESS = 170f
private const val SPRING_DAMPING = 0.68f
private const val WALK_SPEED = 58f
private const val WALK_BOB_AMP = 2.4f
private const val BOTTOM_GAP = 14f  // dp：球底与地面（输入框顶）的间隙

// 发光体配色：中心白 → 亮黄 → 橘 → 深橘
private val CORE_HOT = Color(0xFFFFFDF6)
private val HOT_YELLOW = Color(0xFFFFE9A8)
private val GLOW_YELLOW = Color(0xFFFFC24D)
private val BODY_ORANGE = Color(0xFFFF9320)
private val BODY_DEEP = Color(0xFFF26A00)
private val HALO = Color(0xFFFFB84D)
private val FACE_DARK = Color(0xFF8A4412)

@Composable
fun TangyuanPet(
    modifier: Modifier = Modifier,
    face: TangyuanFace = TangyuanFace.NORMAL,
    groundY: Float = 0f,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val bodyWpx = with(density) { BODY_W.dp.toPx() }
    val bodyHpx = with(density) { BODY_H.dp.toPx() }
    val bobAmpPx = with(density) { WALK_BOB_AMP.dp.toPx() }
    val bottomGapPx = with(density) { BOTTOM_GAP.dp.toPx() }

    var containerW by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }

    val posX = remember { Animatable(0f) }
    val posY = remember { Animatable(0f) }
    var velY by remember { mutableFloatStateOf(0f) }

    var stretchY by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }

    var state by remember { mutableStateOf(PetState.IDLE) }
    var walkDir by remember { mutableFloatStateOf(1f) }
    var bobPhase by remember { mutableFloatStateOf(0f) }

    val idle = rememberInfiniteTransition(label = "ty-idle")
    val breathe by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    // 地面：优先用传入的输入框顶部（窗口坐标）；兜底用容器底部
    val ground = if (groundY > 0f && containerH > 0f && groundY < containerH) {
        groundY
    } else {
        containerH
    }
    val restTopY = (ground - bottomGapPx - bodyHpx).coerceAtLeast(0f)

    LaunchedEffect(containerW) {
        if (!ready && containerW > 0f) {
            posX.snapTo(containerW * 0.6f)
            posY.snapTo(restTopY)
            ready = true
        }
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last == 0L) { last = now; continue }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now

            if (dragging) {
                bobPhase += dt * 9f
                continue
            }

            velY += GRAVITY * dt
            var newY = posY.value + velY * dt
            var newX = posX.value +
                (if (state == PetState.WALK) walkDir * WALK_SPEED else 0f) * dt

            val floor = restTopY
            if (newY >= floor) {
                newY = floor
                if (velY > 320f) {
                    velY = -velY * RESTITUTION
                    stretchY = 0.72f
                } else {
                    velY = 0f
                }
            }
            if (newY < 0f) { newY = 0f; if (velY < 0f) velY = 0f }

            if (newX < bodyWpx * 0.3f) { newX = bodyWpx * 0.3f; walkDir = 1f }
            if (newX > containerW - bodyWpx * 1.3f) {
                newX = containerW - bodyWpx * 1.3f; walkDir = -1f
            }

            posX.snapTo(newX)
            posY.snapTo(newY)

            if (velY > 110f) {
                stretchY = (1f + velY / 3000f).coerceAtMost(1.35f)
            } else {
                stretchY += (1f - stretchY) * (dt * 10f)
                if (abs(stretchY - 1f) < 0.006f) stretchY = 1f
            }

            bobPhase += dt * 9f

            if (state == PetState.IDLE && Random.nextFloat() < dt * 0.20f) {
                state = PetState.WALK
                walkDir = if (Random.nextBoolean()) 1f else -1f
                val dur = (900 + Random.nextInt(1700)).toLong()
                scope.launch {
                    delay(dur)
                    if (state == PetState.WALK) state = PetState.IDLE
                }
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
            val bob = if (state == PetState.WALK && !dragging) sin(bobPhase) * bobAmpPx else 0f
            val idleS = 1f + breathe * 0.025f

            // 球体本体（含多层渐变光晕，同坐标，无模糊风险）
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(posX.value.roundToInt(), (posY.value + bob).roundToInt())
                    }
                    .size(width = BODY_W.dp, height = BODY_H.dp)
                    .graphicsLayer {
                        val sy = stretchY * (if (dragging || state == PetState.WALK) 1f else idleS)
                        val sx = 1f - (sy - 1f) * 0.28f
                        scaleX = sx.coerceIn(0.88f, 1.12f)
                        scaleY = sy.coerceIn(0.70f, 1.40f)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                velY = 0f
                                stretchY = 1f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    posX.snapTo(posX.value + dragAmount.x)
                                    posY.snapTo(posY.value + dragAmount.y)
                                }
                                val dy = dragAmount.y
                                val target = 1f - dy / 110f
                                stretchY = target.coerceIn(0.72f, 1.45f)
                            },
                            onDragEnd = {
                                dragging = false
                                val startV = stretchY
                                scope.launch {
                                    val a = Animatable(startV, Float.VectorConverter)
                                    a.animateTo(
                                        1f,
                                        animationSpec = spring(SPRING_STIFFNESS, SPRING_DAMPING),
                                    ) { stretchY = value }
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                stretchY = 1f
                            },
                        )
                    }
                    .drawBehind { drawTangyuan(face) },
            )
        }
    }
}

/** 画汤圆本体：自发光球体（正圆画渐变 → 压椭圆） */
private fun DrawScope.drawTangyuan(face: TangyuanFace) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = h / 2f
    val xScale = (w / 2f) / r

    // ① 多层柔光晕（由外到内，越往里越亮）—— 营造"在发光"的感觉
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        // 最外圈：很淡的一大团
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HALO.copy(alpha = 0f),
                    HALO.copy(alpha = 0.10f),
                    HALO.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
                radius = r * 1.9f,
            ),
            radius = r * 1.9f,
            center = Offset(cx, cy),
        )
        // 中圈：明显一点的暖光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HALO.copy(alpha = 0.26f),
                    HALO.copy(alpha = 0.14f),
                    HALO.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
                radius = r * 1.45f,
            ),
            radius = r * 1.45f,
            center = Offset(cx, cy),
        )
        // 内圈：贴着球边缘的辉光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HALO.copy(alpha = 0.55f),
                    HALO.copy(alpha = 0.22f),
                    HALO.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
                radius = r * 1.15f,
            ),
            radius = r * 1.15f,
            center = Offset(cx, cy),
        )
    }

    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        // 主体：自发光质感 —— 中心爆白 → 亮黄 → 橘 → 深橘
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CORE_HOT,          // 0.0  最中心：近白
                    CORE_HOT,
                    HOT_YELLOW,        // 亮黄
                    GLOW_YELLOW,
                    BODY_ORANGE,
                    BODY_DEEP,
                ),
                center = Offset(cx - r * 0.18f, cy - r * 0.20f), // 光心偏左上
                radius = r * 1.06f,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
        // 边缘压暗一点（球体感，不死白）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Transparent,
                    BODY_DEEP.copy(alpha = 0.55f),
                ),
                center = Offset(cx, cy),
                radius = r * 1.0f,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }

    drawFace(face, w, h)
}

private fun DrawScope.drawFace(face: TangyuanFace, w: Float, h: Float) {
    val eyeW = w * 0.058f
    val eyeH = h * 0.15f
    val eyeY = h * 0.40f
    val lx = w * 0.37f
    val rx = w * 0.555f
    val mouthY = h * 0.64f

    when (face) {
        TangyuanFace.NORMAL, TangyuanFace.HAPPY -> {
            drawRoundRect(
                FACE_DARK, Offset(lx, eyeY), Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.3f),
            )
            drawRoundRect(
                FACE_DARK, Offset(rx, eyeY), Size(eyeW, eyeH),
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
                moveTo(rx - eyeW * 0.2f, eyeY)
                quadraticTo(rx + eyeW * 0.5f, eyeY + eyeH * 0.9f, rx + eyeW * 1.2f, eyeY)
            }
            drawPath(rp, FACE_DARK, style = Stroke(width = w * 0.022f))
            drawCircle(FACE_DARK, radius = w * 0.026f, center = Offset(w * 0.50f, mouthY))
        }
    }
}
