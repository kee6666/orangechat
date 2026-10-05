/*
 * 汤圆 (Tangyuan) —— 阿年和言一起养的桌宠
 * 住在聊天页里，椭圆身体、中间浓橘往外散、自带光晕。
 * 只有身体那个椭圆能拖；松手掉落、弹跳、带挤压变形。
 * v1：本体 + 拖拽 + 弹跳变形 + 待机漂浮。表情先上三个。
 */
package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 汤圆的表情 */
enum class TangyuanFace { NORMAL, HAPPY, SLEEPY }

private val CORAL_CENTER = Color(0xFFFF8F34)
private val CORAL_MID = Color(0xFFFFAB5C)
private val CORAL_EDGE = Color(0xFFFFCA92)
private val CORAL_FADE = Color(0xFFFFE4C6)
private val GLOW_COLOR = Color(0xFFFFA855)
private val FACE_DARK = Color(0xFF7A3A12)

private const val BODY_W = 46f   // dp
private const val BODY_H = 40f   // dp
private const val SPRING_STIFFNESS = 380f
private const val SPRING_DAMPING = 0.42f
private const val DEFORM_FACTOR = 0.0075f
private const val DEFORM_CLAMP = 0.5f

/**
 * 汤圆本体。放在聊天页最上层，可拖动、会弹跳。
 */
@Composable
fun TangyuanPet(
    modifier: Modifier = Modifier,
    face: TangyuanFace = TangyuanFace.NORMAL,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 位置（px 偏移，相对初始停靠点）
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    // 形变：拉伸/挤压，跟随拖动速度
    var deform by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 待机漂浮：没在拖的时候轻轻上下浮
    val idle = rememberInfiniteTransition(label = "tangyuan-idle")
    val breathe by idle.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    // 待机缩放（呼吸感）
    val idleScale = 1f + breathe * 0.035f

    val bodyWpx = with(density) { BODY_W.dp.toPx() }
    val bodyHpx = with(density) { BODY_H.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // 汤圆位置（相对屏幕右上角），松手后停在这儿
        val posX = remember { Animatable(-1f) }
        val posY = remember { Animatable(-1f) }
        var sizeReady by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (!sizeReady && size.width > 0 && size.height > 0) {
                        // 初始：右侧、屏幕偏上 1/3 处，保证露出来、不挡输入框
                        sizeReady = true
                        scope.launch {
                            posX.snapTo(size.width - bodyWpx * 1.8f)
                            posY.snapTo(size.height * 0.30f)
                        }
                    }
                }
        ) {
            if (sizeReady) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(posX.value.roundToInt(), posY.value.roundToInt())
                        }
                        .size(width = BODY_W.dp, height = BODY_H.dp)
                        .graphicsLayer {
                            val s = 1f + deform
                            scaleX = s * (if (dragging) 1f else idleScale)
                            scaleY = (1f / sqrt(s.coerceAtLeast(0.2f))) *
                                (if (dragging) 1f else idleScale)
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    deform = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        posX.snapTo(posX.value + dragAmount.x)
                                        posY.snapTo(posY.value + dragAmount.y)
                                    }
                                    val speed = sqrt(
                                        dragAmount.x * dragAmount.x +
                                            dragAmount.y * dragAmount.y
                                    )
                                    deform = (speed * DEFORM_FACTOR)
                                        .coerceIn(0f, DEFORM_CLAMP)
                                },
                                onDragEnd = {
                                    dragging = false
                                    // 松手：不回原位，只让形变弹回（弹一下）
                                    scope.launch {
                                        val settled = Animatable(
                                            initialValue = deform,
                                            typeConverter = Float.VectorConverter,
                                        )
                                        settled.animateTo(
                                            0f,
                                            animationSpec = spring(
                                                stiffness = SPRING_STIFFNESS,
                                                dampingRatio = SPRING_DAMPING,
                                            ),
                                        ) {
                                            deform = value
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    deform = 0f
                                },
                            )
                        }
                        .drawBehind {
                            drawTangyuan(face = face, deform = deform)
                        },
                )
            }
        }
    }
}


/** 画汤圆本体：光晕 + 浓橘核 + 表情 */
private fun DrawScope.drawTangyuan(face: TangyuanFace, deform: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val rx = w / 2f
    val ry = h / 2f

    // ---- 1. 外圈光晕（暖橘，柔柔散开，不带灰边）----
    val glowScale = 1f + deform * 0.6f
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                GLOW_COLOR.copy(alpha = 0.55f),
                GLOW_COLOR.copy(alpha = 0.22f),
                GLOW_COLOR.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = maxOf(rx, ry) * 1.9f * glowScale,
        ),
        topLeft = Offset(cx - rx * 1.55f, cy - ry * 1.55f),
        size = Size(w * 1.55f, h * 1.55f),
    )

    // ---- 2. 本体：中心浓橘 → 往外越淡越透 → 边缘化开 ----
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                CORAL_CENTER,
                CORAL_MID,
                CORAL_EDGE,
                CORAL_FADE,
                CORAL_FADE.copy(alpha = 0f),
            ),
            center = Offset(cx, cy * 0.98f),
            radius = rx,
        ),
        topLeft = Offset(0f, 0f),
        size = Size(w, h),
    )

    // ---- 3. 左上高光 ----
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0f),
            ),
            center = Offset(w * 0.36f, h * 0.30f),
            radius = w * 0.20f,
        ),
        topLeft = Offset(w * 0.18f, h * 0.16f),
        size = Size(w * 0.36f, h * 0.30f),
    )

    // ---- 4. 表情 ----
    drawFace(face = face, w = w, h = h)
}

/** 像素小表情（左右眼分开画，铁对称） */
private fun DrawScope.drawFace(face: TangyuanFace, w: Float, h: Float) {
    val eyeW = w * 0.075f
    val eyeH = h * 0.14f
    val eyeY = h * 0.40f
    val leftEyeX = w * 0.34f
    val rightEyeX = w * 0.58f
    val mouthW = w * 0.12f
    val mouthH = h * 0.055f
    val mouthY = h * 0.63f

    when (face) {
        TangyuanFace.NORMAL, TangyuanFace.HAPPY -> {
            // 眼睛：竖条圆点
            drawRoundRect(
                color = FACE_DARK,
                topLeft = Offset(leftEyeX, eyeY),
                size = Size(eyeW, eyeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW * 0.3f),
            )
            drawRoundRect(
                color = FACE_DARK,
                topLeft = Offset(rightEyeX, eyeY),
                size = Size(eyeW, eyeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW * 0.3f),
            )
            // 嘴：开心张嘴，平时小弧
            if (face == TangyuanFace.HAPPY) {
                val path = Path().apply {
                    moveTo(w * 0.44f, mouthY)
                    quadraticTo(w * 0.50f, mouthY + mouthH * 2.2f, w * 0.56f, mouthY)
                    close()
                }
                drawPath(path, FACE_DARK)
            } else {
                val path = Path().apply {
                    moveTo(w * 0.45f, mouthY)
                    quadraticTo(w * 0.50f, mouthY + mouthH * 1.4f, w * 0.55f, mouthY)
                }
                drawPath(
                    path = path,
                    color = FACE_DARK,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.022f),
                )
            }
        }
        TangyuanFace.SLEEPY -> {
            // 闭眼：两道下弯弧
            val leftPath = Path().apply {
                moveTo(leftEyeX - eyeW * 0.2f, eyeY)
                quadraticTo(leftEyeX + eyeW * 0.5f, eyeY + eyeH * 0.9f, leftEyeX + eyeW * 1.2f, eyeY)
            }
            drawPath(
                leftPath, FACE_DARK,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.024f),
            )
            val rightPath = Path().apply {
                moveTo(rightEyeX - eyeW * 0.2f, eyeY)
                quadraticTo(rightEyeX + eyeW * 0.5f, eyeY + eyeH * 0.9f, rightEyeX + eyeW * 1.2f, eyeY)
            }
            drawPath(
                rightPath, FACE_DARK,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.024f),
            )
            // 小圆嘴
            drawCircle(
                color = FACE_DARK,
                radius = w * 0.028f,
                center = Offset(w * 0.50f, mouthY + mouthH * 0.5f),
            )
        }
    }
}
