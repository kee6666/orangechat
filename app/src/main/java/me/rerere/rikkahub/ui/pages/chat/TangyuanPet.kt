/*
 * 汤圆 (Tangyuan) —— 阿年和言一起养的桌宠 · v7
 *
 * v7 关键修正：
 *  1. 光球：在"正圆"坐标系里画渐变，再 scale 压成椭圆 → 渐变永远盖满，右下角不缺
 *  2. 球体 = 实心橘 + 白色内芯 + 一圈外发光（照 PS 光球做法，不是整体糊）
 *  3. 拖动时锁住形变（不再被重力覆盖），拎起变水滴
 *  4. 地面取 min(输入框顶部, 屏高-安全带)，键盘弹起也不会飞出屏幕
 *  5. 落地压扁 → 回弹，弹簧更软
 *  6. 待机/走路状态机
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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

enum class TangyuanFace { NORMAL, HAPPY, SLEEPY }

private enum class PetState { IDLE, WALK }

// ── 尺寸 / 物理 ──
private const val BODY_W = 54f
private const val BODY_H = 38f
private const val GRAVITY = 2400f
private const val RESTITUTION = 0.45f
private const val SPRING_STIFFNESS = 170f
private const val SPRING_DAMPING = 0.68f
private const val WALK_SPEED = 58f
private const val WALK_BOB_AMP = 2.4f

// ── 配色 ──
private val SOLID_ORANGE = Color(0xFFFF9A3C)
private val SHADE_ORANGE = Color(0xFFF07A1E)
private val GLOW_ORANGE = Color(0xFFFFB057)
private val CORE_WHITE = Color(0xFFFFF6EA)
private val FACE_DARK = Color(0xFF8A4212)

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
    val safeGapPx = with(density) { 150.dp.toPx() }

    var containerW by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }

    val posX = remember { Animatable(0f) }
    val posY = remember { Animatable(0f) }
    var velY by remember { mutableFloatStateOf(0f) }

    // 形变：squash 横/竖 比例。1 = 不变形
    var scaleYFactor by remember { mutableFloatStateOf(1f) }
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

    // 地面：输入框顶部与安全线取小值（防止键盘弹起时被推到屏幕外）
    val groundTop = run {
        val g1 = if (groundY > 0f) groundY else containerH
        val g2 = containerH - safeGapPx
        minOf(g1, g2).coerceAtLeast(bodyHpx * 2f)
    }
    val restTopY = groundTop - bodyHpx

    LaunchedEffect(containerW) {
        if (!ready && containerW > 0f) {
            posX.snapTo(containerW * 0.6f)
            posY.snapTo(restTopY.coerceAtLeast(0f))
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

            if (!dragging) {
                velY += GRAVITY * dt
                var newY = posY.value + velY * dt
                var newX = posX.value +
                    (if (state == PetState.WALK) walkDir * WALK_SPEED else 0f) * dt

                val floor = restTopY
                if (newY >= floor) {
                    newY = floor
                    if (velY > 300f) {
                        velY = -velY * RESTITUTION
                        scaleYFactor = 0.66f          // 落地压扁
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

                // 形变回归 1（非拖动态）
                if (velY > 100f) {
                    scaleYFactor = 1f + (velY / 2600f).coerceIn(0f, 0.40f)
                } else {
                    scaleYFactor += (1f - scaleYFactor) * (dt * 9f)
                    if (abs(scaleYFactor - 1f) < 0.008f) scaleYFactor = 1f
                }
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

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(posX.value.roundToInt(), (posY.value + bob).roundToInt())
                    }
                    .size(width = BODY_W.dp, height = BODY_H.dp)
                    .graphicsLayer {
                        val sy = scaleYFactor * (if (dragging || state == PetState.WALK) 1f else idleS)
                        val sx = 1f / sqrt(sy.coerceAtLeast(0.3f))
                        scaleX = sx
                        scaleY = sy
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                velY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    posX.snapTo(posX.value + dragAmount.x)
                                    posY.snapTo(posY.value + dragAmount.y)
                                }
                                // 拎起来 → 水滴状：竖直拖动时竖着拉长
                                val dx = dragAmount.x
                                val dy = dragAmount.y
                                scaleYFactor = when {
                                    abs(dy) > abs(dx) -> (1f + dy / 100f).coerceIn(0.7f, 1.45f)
                                    else -> (1f - abs(dx) / 260f).coerceIn(0.72f, 1f)
                                }
                            },
                            onDragEnd = {
                                dragging = false
                                val startV = scaleYFactor
                                scope.launch {
                                    val a = Animatable(startV, Float.VectorConverter)
                                    a.animateTo(
                                        1f,
                                        animationSpec = spring(SPRING_STIFFNESS, SPRING_DAMPING),
                                    ) { scaleYFactor = value }
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                scaleYFactor = 1f
                            },
                        )
                    }
                    .drawBehind { drawTangyuan(face) },
            )
        }
    }
}

/**
 * 画汤圆：在"正圆"里画所有渐变，再压成椭圆 → 渐变永远盖满
 */
private fun DrawScope.drawTangyuan(face: TangyuanFace) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    // 用一个正圆的半径来画渐变，再缩放到椭圆
    val circleR = h / 2f

    // ① 外发光（正圆径向渐变，透明收尾）—— 单独画，保持圆
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colors = listOf(
                GLOW_ORANGE.copy(alpha = 0.34f),
                GLOW_ORANGE.copy(alpha = 0.16f),
                GLOW_ORANGE.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = circleR * 1.65f,
        ),
        radius = circleR * 1.65f,
        center = Offset(cx, cy),
    )

    // ② 球体：在正圆里画实的渐变，再横向拉成椭圆
    val xScale = (w / 2f) / circleR
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        // 实心球：橘色，左上稍亮、右下稍暗（轻微，不糊）
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFA550),
                    SOLID_ORANGE,
                    SHADE_ORANGE,
                ),
                center = Offset(cx - circleR * 0.28f, cy - circleR * 0.30f),
                radius = circleR * 1.30f,
            ),
            radius = circleR,
            center = Offset(cx, cy),
        )
    }

    // ③ 内芯高光：正圆画，再压
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    CORE_WHITE.copy(alpha = 0.95f),
                    CORE_WHITE.copy(alpha = 0.35f),
                    CORE_WHITE.copy(alpha = 0f),
                ),
                center = Offset(cx - circleR * 0.34f, cy - circleR * 0.40f),
                radius = circleR * 0.52f,
            ),
            radius = circleR * 0.52f,
            center = Offset(cx - circleR * 0.34f, cy - circleR * 0.40f),
        )
    }

    // ④ 表情（在椭圆坐标里）
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
