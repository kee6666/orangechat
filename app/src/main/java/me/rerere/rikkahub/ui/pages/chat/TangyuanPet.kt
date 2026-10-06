/*
 * 汤圆 (Tangyuan) v12 —— 阿年和言的孩子
 *
 * v12 新增（阿年定稿）：
 *  1. 八张脸：普通/开心/犯困/惊讶/委屈/生气/害羞/心动
 *  2. 眨眼：每 3~6 秒自己眨一下（0.15 秒）
 *  3. 液体感：轮廓蠕动（wobbleEllipsePath）+ 三个小高光 + 内部透光
 *  4. 睡觉：很久没互动 → 闭眼 + 右上角 💤 一个个往上飘、淡出
 *
 * 保留 v11：发光球体、重力下落、落地压扁回弹、拎起变水滴、
 *           拖动停物理、防僵（常驻 Animatable）
 *
 *  5. 键盘跟随：容器 imePadding() + Manifest adjustNothing（v15）
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class TangyuanFace {
    NORMAL, HAPPY, SLEEPY, SURPRISED, SAD, ANGRY, SHY, HEART
}

private enum class PetState { IDLE, WALK, DRAGGED }

private const val BODY_W = 54f
private const val BODY_H = 38f
private const val GRAVITY = 2400f
private const val RESTITUTION = 0.45f
private const val SPRING_STIFFNESS = 170f
private const val SPRING_DAMPING = 0.68f
private const val WALK_SPEED = 58f
private const val WALK_BOB_AMP = 2.4f
private const val BOTTOM_GAP = 14f
private const val SLEEP_AFTER_SEC = 45f   // 多久没互动就睡

// 配色
private val CORE_HOT = Color(0xFFFFFDF6)
private val HOT_YELLOW = Color(0xFFFFE9A8)
private val GLOW_YELLOW = Color(0xFFFFC24D)
private val BODY_ORANGE = Color(0xFFFF9320)
private val BODY_DEEP = Color(0xFFF26A00)
private val HALO = Color(0xFFFFB84D)
private val FACE_DARK = Color(0xFF8A4412)
private val EDGE_PIXEL = Color(0xFFFFD68A)
private val BLUSH = Color(0x66FF5A5A)

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
    val stretchAnim = remember { Animatable(1f, Float.VectorConverter) }

    var state by remember { mutableStateOf(PetState.IDLE) }
    var walkDir by remember { mutableFloatStateOf(1f) }
    var bobPhase by remember { mutableFloatStateOf(0f) }

    // 眨眼 & 睡觉
    var blink by remember { mutableFloatStateOf(0f) }   // 0=睁 1=闭
    var sleeping by remember { mutableStateOf(false) }
    var idleTimer by remember { mutableFloatStateOf(0f) } // 秒
    var zzPhase by remember { mutableFloatStateOf(0f) }   // 💤 飘动画相位
    var wobble by remember { mutableFloatStateOf(0f) }     // 轮廓蠕动相位（液体感）

    // 表情覆盖（互动时临时切换），无覆盖时用传进来的 face
    var faceOverride by remember { mutableStateOf<TangyuanFace?>(null) }
    val effectiveFace = faceOverride ?: if (sleeping) TangyuanFace.SLEEPY else face

    val idle = rememberInfiniteTransition(label = "ty-idle")
    val breathe by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

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

    // 眨眼循环：每 3~6 秒眨一次，每次 0.15 秒
    LaunchedEffect(ready, sleeping) {
        if (!ready || sleeping) return@LaunchedEffect
        while (true) {
            delay((3000 + Random.nextLong(3000)))
            blink = 1f
            delay(150)
            blink = 0f
        }
    }

    // 物理主循环
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last == 0L) { last = now; continue }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now

            // 睡觉计时（拖动/走路时不睡）
            if (dragging) {
                idleTimer = 0f
            } else {
                idleTimer += dt
                if (idleTimer > SLEEP_AFTER_SEC && !sleeping) {
                    sleeping = true
                    state = PetState.IDLE
                }
            }
            if (sleeping) zzPhase += dt * 0.22f

            if (dragging) {
                bobPhase += dt * 9f
            // 轮廓蠕动：慢一点，像果冻自己在动；拖动/走路时快一点（有"晃动"感）
            wobble += dt * (if (dragging || state == PetState.WALK) 3.4f else 1.5f)
                continue
            }

            if (!sleeping) {
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
            }

            bobPhase += dt * 9f

            if (!sleeping && state == PetState.IDLE && Random.nextFloat() < dt * 0.20f) {
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
            val bob = if (state == PetState.WALK && !dragging && !sleeping) {
                sin(bobPhase) * bobAmpPx
            } else 0f
            val idleS = 1f + breathe * 0.025f

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
                                idleTimer = 0f
                                sleeping = false
                                faceOverride = TangyuanFace.SURPRISED
                                scope.launch { stretchAnim.stop() }
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
                                faceOverride = null
                                idleTimer = 0f
                                scope.launch {
                                    stretchAnim.snapTo(stretchY)
                                    stretchAnim.animateTo(
                                        1f,
                                        animationSpec = spring(SPRING_STIFFNESS, SPRING_DAMPING),
                                    ) { stretchY = value }
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                faceOverride = null
                                stretchY = 1f
                            },
                        )
                    }
                    .drawBehind {
                        drawTangyuan(effectiveFace, blink, sleeping, zzPhase, wobble)
                    },
            )
        }
    }
}

/** 画汤圆：发光球体 + 像素描边 + 表情 + 眨眼 + 💤 */
private fun DrawScope.drawTangyuan(
    face: TangyuanFace,
    blink: Float,
    sleeping: Boolean,
    zzPhase: Float,
    wobble: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = h / 2f
    val xScale = (w / 2f) / r

    // ① 外光晕（也用蠕动轮廓，跟着一起呼吸）
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        val haloPath1 = wobbleEllipsePath(cx, cy, r * 1.55f, r * 1.55f, wobble, 0.085f)
        drawPath(
            haloPath1,
            brush = Brush.radialGradient(
                colors = listOf(
                    HALO.copy(alpha = 0.24f),
                    HALO.copy(alpha = 0.13f),
                    HALO.copy(alpha = 0f),
                ),
                center = Offset(cx, cy), radius = r * 1.9f,
            ),
        )
        val haloPath2 = wobbleEllipsePath(cx, cy, r * 1.28f, r * 1.28f, wobble, 0.10f)
        drawPath(
            haloPath2,
            brush = Brush.radialGradient(
                colors = listOf(
                    HALO.copy(alpha = 0.30f),
                    HALO.copy(alpha = 0.16f),
                    HALO.copy(alpha = 0f),
                ),
                center = Offset(cx, cy), radius = r * 1.45f,
            ),
        )
    }

    // ② 球体本体（轮廓是"会蠕动的椭圆"→ 液体感从这里来）
    scale(scaleX = xScale, scaleY = 1f, pivot = Offset(cx, cy)) {
        val bodyPath = wobbleEllipsePath(cx, cy, r, r, wobble, 0.06f)

        // 底色：亮心偏左上
        drawPath(
            bodyPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    CORE_HOT, CORE_HOT, HOT_YELLOW, GLOW_YELLOW, BODY_ORANGE, BODY_DEEP,
                ),
                center = Offset(cx - r * 0.18f, cy - r * 0.20f),
                radius = r * 1.06f,
            ),
        )
        // 底部压暗（厚度）
        drawPath(
            bodyPath,
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, BODY_DEEP.copy(alpha = 0.55f)),
                center = Offset(cx, cy), radius = r,
            ),
        )
        // 内部透光（中间一团柔亮，光从里面散出来）
        drawPath(
            bodyPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x66FFFFE0),
                    Color(0x22FFF0B4),
                    Color.Transparent,
                ),
                center = Offset(cx - r * 0.15f, cy - r * 0.10f),
                radius = r * 0.75f,
            ),
        )

        // ③ 三个小高光（果冻感的关键）
        //  高光1：大，左上
        drawOval(
            color = Color(0xE8FFFFFF),
            topLeft = Offset(cx - r * 0.52f, cy - r * 0.62f),
            size = Size(r * 0.42f, r * 0.30f),
        )
        //  高光2：小，右侧
        drawOval(
            color = Color(0xA0FFFFFF),
            topLeft = Offset(cx + r * 0.22f, cy - r * 0.46f),
            size = Size(r * 0.24f, r * 0.17f),
        )
        //  高光3：极小，下方（点一下）
        drawOval(
            color = Color(0x70FFFFFF),
            topLeft = Offset(cx - r * 0.30f, cy + r * 0.42f),
            size = Size(r * 0.15f, r * 0.10f),
        )
    }

    // ④ 表情
    drawFace(face, w, h, blink)

    // ⑤ 睡觉时右上角飘 💤
    if (sleeping) drawSleepZ(w, h, zzPhase)
}

/** 会蠕动的椭圆路径：一圈点，半径带正弦扰动 → 液体/果冻感（完美闭合） */
private fun wobbleEllipsePath(
    cx: Float, cy: Float,
    rx: Float, ry: Float,
    phase: Float,
    amp: Float,
): Path {
    val p = Path()
    val segments = 48
    var first = true
    for (i in 0..segments) {
        val a = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
        // 三个不同频率的波叠加 → 有机的蠕动（不是死板的规则波纹）
        val w =
            1f +
                amp * sin(a * 2f + phase * 1.0f) +
                amp * 0.6f * sin(a * 3f - phase * 1.4f) +
                amp * 0.4f * sin(a * 5f + phase * 0.8f)
        val px = cx + cos(a) * rx * w
        val py = cy + sin(a) * ry * w
        if (first) { p.moveTo(px, py); first = false } else { p.lineTo(px, py) }
    }
    p.close()
    return p
}

/** 睡觉的 💤：一个个往上飘、淡出 */
private fun DrawScope.drawSleepZ(w: Float, h: Float, phase: Float) {
    val baseX = w * 0.72f
    val baseY = h * 0.06f
    for (i in 0 until 3) {
        val t = ((phase + i * 0.33f) % 1f)          // 0..1 循环
        val py = baseY - t * h * 0.75f              // 往上飘
        val alpha = when {
            t < 0.15f -> t / 0.15f
            t > 0.75f -> (1f - t) / 0.25f
            else -> 1f
        }.coerceIn(0f, 1f)
        val sz = h * (0.16f + t * 0.10f)            // 越飘越大一点
        val px = baseX + t * w * 0.10f              // 微微右飘
        // 画一个 "Z"：三条线段
        val stroke = sz * 0.14f
        drawLine(
            EDGE_PIXEL.copy(alpha = alpha * 0.95f),
            Offset(px, py), Offset(px + sz, py), strokeWidth = stroke,
        )
        drawLine(
            EDGE_PIXEL.copy(alpha = alpha * 0.95f),
            Offset(px + sz, py), Offset(px, py + sz), strokeWidth = stroke,
        )
        drawLine(
            EDGE_PIXEL.copy(alpha = alpha * 0.95f),
            Offset(px, py + sz), Offset(px + sz, py + sz), strokeWidth = stroke,
        )
    }
}

private fun DrawScope.drawFace(face: TangyuanFace, w: Float, h: Float, blink: Float) {
    val eyeW = w * 0.058f
    val eyeH = h * 0.15f
    val eyeY = h * 0.40f
    val lx = w * 0.37f
    val rx = w * 0.555f
    val mouthY = h * 0.64f
    val closed = blink > 0.5f

    // 眼睛（大多数表情共用；闭眼=一条线）
    fun eyes(round: Boolean = false) {
        if (closed) {
            drawLine(FACE_DARK, Offset(lx, eyeY + eyeH * 0.6f),
                Offset(lx + eyeW, eyeY + eyeH * 0.6f), strokeWidth = eyeH * 0.20f)
            drawLine(FACE_DARK, Offset(rx, eyeY + eyeH * 0.6f),
                Offset(rx + eyeW, eyeY + eyeH * 0.6f), strokeWidth = eyeH * 0.20f)
        } else if (round) {
            drawCircle(FACE_DARK, radius = eyeW * 0.95f, center = Offset(lx + eyeW / 2f, eyeY + eyeH / 2f))
            drawCircle(FACE_DARK, radius = eyeW * 0.95f, center = Offset(rx + eyeW / 2f, eyeY + eyeH / 2f))
        } else {
            drawRoundRect(FACE_DARK, Offset(lx, eyeY), Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.3f))
            drawRoundRect(FACE_DARK, Offset(rx, eyeY), Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.3f))
        }
    }

    fun blush() {
        drawOval(BLUSH, topLeft = Offset(w * 0.16f, h * 0.55f), size = Size(w * 0.16f, h * 0.09f))
        drawOval(BLUSH, topLeft = Offset(w * 0.68f, h * 0.55f), size = Size(w * 0.16f, h * 0.09f))
    }

    when (face) {
        TangyuanFace.NORMAL -> {
            eyes()
            val p = Path().apply {
                moveTo(w * 0.45f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.07f, w * 0.55f, mouthY)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.020f))
        }
        TangyuanFace.HAPPY -> {
            if (closed) eyes() else {
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(lx, eyeY - eyeH * 0.3f), size = Size(eyeW * 1.6f, eyeH * 1.2f),
                    style = Stroke(width = eyeW * 0.28f))
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(rx, eyeY - eyeH * 0.3f), size = Size(eyeW * 1.6f, eyeH * 1.2f),
                    style = Stroke(width = eyeW * 0.28f))
            }
            val p = Path().apply {
                moveTo(w * 0.44f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.11f, w * 0.56f, mouthY)
                close()
            }
            drawPath(p, FACE_DARK)
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
        TangyuanFace.SURPRISED -> {
            // 长条眼（跟普通一样，不瞪人）+ 圆嘴 O
            eyes()
            drawCircle(FACE_DARK, radius = w * 0.030f, center = Offset(w * 0.50f, mouthY + h * 0.03f))
        }
        TangyuanFace.SAD -> {
            if (closed) eyes() else {
                drawRoundRect(FACE_DARK, Offset(lx, eyeY), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.35f, eyeW * 0.5f))
                drawRoundRect(FACE_DARK, Offset(rx, eyeY), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.35f, eyeW * 0.5f))
            }
            val p = Path().apply {
                moveTo(w * 0.45f, mouthY + h * 0.06f)
                quadraticTo(w * 0.50f, mouthY, w * 0.55f, mouthY + h * 0.06f)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.018f))
        }
        TangyuanFace.ANGRY -> {
            // >_< 眼
            val lp = Path().apply {
                moveTo(lx - eyeW * 0.1f, eyeY + eyeH * 0.9f)
                lineTo(lx + eyeW * 0.6f, eyeY)
            }
            drawPath(lp, FACE_DARK, style = Stroke(width = w * 0.022f))
            val lp2 = Path().apply {
                moveTo(lx + eyeW * 1.1f, eyeY + eyeH * 0.9f)
                lineTo(lx + eyeW * 0.4f, eyeY)
            }
            drawPath(lp2, FACE_DARK, style = Stroke(width = w * 0.022f))
            val rp = Path().apply {
                moveTo(rx - eyeW * 0.1f, eyeY + eyeH * 0.9f)
                lineTo(rx + eyeW * 0.6f, eyeY)
            }
            drawPath(rp, FACE_DARK, style = Stroke(width = w * 0.022f))
            val rp2 = Path().apply {
                moveTo(rx + eyeW * 1.1f, eyeY + eyeH * 0.9f)
                lineTo(rx + eyeW * 0.4f, eyeY)
            }
            drawPath(rp2, FACE_DARK, style = Stroke(width = w * 0.022f))
            val p = Path().apply {
                moveTo(w * 0.45f, mouthY + h * 0.05f)
                quadraticTo(w * 0.50f, mouthY - h * 0.02f, w * 0.55f, mouthY + h * 0.05f)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.018f))
        }
        TangyuanFace.SHY -> {
            // 眼睛看别处（偏左下）
            if (closed) eyes() else {
                drawRoundRect(FACE_DARK, Offset(lx - eyeW * 0.25f, eyeY + eyeH * 0.15f),
                    Size(eyeW, eyeH), cornerRadius = CornerRadius(eyeW * 0.3f))
                drawRoundRect(FACE_DARK, Offset(rx - eyeW * 0.25f, eyeY + eyeH * 0.15f),
                    Size(eyeW, eyeH), cornerRadius = CornerRadius(eyeW * 0.3f))
            }
            blush()
            // 抿嘴：一条横线
            drawLine(FACE_DARK, Offset(w * 0.455f, mouthY + h * 0.02f),
                Offset(w * 0.545f, mouthY + h * 0.02f), strokeWidth = w * 0.018f)
        }
        TangyuanFace.HEART -> {
            // 弯月笑眼
            if (closed) eyes() else {
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(lx, eyeY - eyeH * 0.3f), size = Size(eyeW * 1.6f, eyeH * 1.2f),
                    style = Stroke(width = eyeW * 0.28f))
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(rx, eyeY - eyeH * 0.3f), size = Size(eyeW * 1.6f, eyeH * 1.2f),
                    style = Stroke(width = eyeW * 0.28f))
            }
            blush()
            // 张嘴笑
            val p = Path().apply {
                moveTo(w * 0.44f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.14f, w * 0.56f, mouthY)
                close()
            }
            drawPath(p, FACE_DARK)
        }
    }
}
