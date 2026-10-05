/*
 * 汤圆 (Tangyuan) v8
 *
 * v8 修正（阿年实测反馈）：
 *  1. 拖动时【完全暂停】物理循环：不重力、不回归。形变只由手控制。
 *     松手才恢复物理。→ 修"落地后再提没反应"
 *  2. 提起形变方向修正：往上提 = 竖着拉长（水滴），往下甩 = 竖着压扁
 *     用"累积 + 夹紧"避免溢出。→ 修"提两次就扁了变不回来"
 *  3. 挤压不再反向放大（scaleX 只用温和的补偿）；不加无谓变形。→ 修"莫名变扁"
 *  4. 地面用【相对屏幕底部往上固定距离】，键盘弹起也不消失。
 *  5. 球体改通透发光：更亮、内层光晕、边缘辉光，不是一块实心橘。
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
private const val BOTTOM_MARGIN = 120f   // dp：地面离屏幕底部的高度

// ── 配色（更通透，偏发光）──
private val GLOW_OUT = Color(0xFFFFC77A)
private val BODY_LIGHT = Color(0xFFFFC978)
private val BODY_MID = Color(0xFFFFA03C)
private val BODY_DEEP = Color(0xFFF5811A)
private val INNER_GLOW = Color(0xFFFFF0D2)
private val FACE_DARK = Color(0xFF95501C)

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
    val bottomMarginPx = with(density) { BOTTOM_MARGIN.dp.toPx() }

    var containerW by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }

    val posX = remember { Animatable(0f) }
    val posY = remember { Animatable(0f) }
    var velY by remember { mutableFloatStateOf(0f) }

    // 形变：拉伸系数。1 = 不变形；>1 竖长（拎起）；<1 竖扁（甩下/落地）
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

    // 地面：钉在屏幕底部往上固定距离（键盘弹起时容器变矮也不消失）
    val groundTopY = (containerH - bottomMarginPx).coerceAtLeast(bodyHpx * 1.4f)
    val restTopY = groundTopY - bodyHpx

    LaunchedEffect(containerW) {
        if (!ready && containerW > 0f) {
            posX.snapTo(containerW * 0.6f)
            posY.snapTo(restTopY.coerceAtLeast(0f))
            ready = true
        }
    }

    // ── 物理主循环（拖动时整段跳过）──
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last == 0L) { last = now; continue }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now

            if (dragging) {
                // 拖动中：物理完全停手，形变由手控制
                bobPhase += dt * 9f
                continue
            }

            // 重力
            velY += GRAVITY * dt
            var newY = posY.value + velY * dt
            var newX = posX.value +
                (if (state == PetState.WALK) walkDir * WALK_SPEED else 0f) * dt

            val floor = restTopY
            if (newY >= floor) {
                newY = floor
                if (velY > 320f) {
                    velY = -velY * RESTITUTION
                    stretchY = 0.72f            // 落地压扁
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

            // 形变回归 1（越接近越慢，柔和收敛）
            if (velY > 110f) {
                // 下落：竖着拉长（水滴/下坠感）
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

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(posX.value.roundToInt(), (posY.value + bob).roundToInt())
                    }
                    .size(width = BODY_W.dp, height = BODY_H.dp)
                    .graphicsLayer {
                        val sy = stretchY * (if (dragging || state == PetState.WALK) 1f else idleS)
                        // 温和补偿：只在竖着拉长时稍微收窄，收窄幅度最多 12%
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
                                // 向上提（dy<0）→ 竖着拉长；向下甩（dy>0）→ 竖着压扁
                                // 用 dy 判断方向，幅度直接映射，不累积
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

/** 画汤圆：正圆里画渐变 → 压成椭圆（渐变永远盖满） */
private fun DrawScope.drawTangyuan(face: TangyuanFace) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = h / 2f
    val xScale = (w / 2f) / r

    // ① 外发光（正圆，柔和散开）
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                GLOW_OUT.copy(alpha = 0.42f),
                GLOW_OUT.copy(alpha = 0.18f),
                GLOW_OUT.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = r * 1.75f,
        ),
        radius = r * 1.75f,
        center = Offset(cx, cy),
    )

    // ② 球体：通透发光感（正圆里画，再压椭圆）
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        // 主体：中心偏左上亮，向外橘→深橘，但保持"发光通透"
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    INNER_GLOW,
                    BODY_LIGHT,
                    BODY_MID,
                    BODY_DEEP,
                ),
                center = Offset(cx - r * 0.30f, cy - r * 0.32f),
                radius = r * 1.28f,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
        // 内层亮核（让它"发光"而不是"实心"）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    INNER_GLOW.copy(alpha = 0.95f),
                    INNER_GLOW.copy(alpha = 0.35f),
                    INNER_GLOW.copy(alpha = 0f),
                ),
                center = Offset(cx - r * 0.34f, cy - r * 0.38f),
                radius = r * 0.62f,
            ),
            radius = r * 0.62f,
            center = Offset(cx - r * 0.34f, cy - r * 0.38f),
        )
        // 边缘辉光（轮廓光，让球有"亮边"）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    GLOW_OUT.copy(alpha = 0.30f),
                    GLOW_OUT.copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
                radius = r * 1.05f,
            ),
            radius = r * 1.05f,
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
