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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ===== 「活」系统参数 =====
private const val HUNGER_DECAY_PER_SEC = 0.06f   // 饱食度每秒降（100 → 0 约 28 分钟）
private const val MOOD_DECAY_PER_SEC = 0.03f     // 心情每秒降
private const val HUNGER_LOW = 30f               // 低于此值：走到边上蹭你
private const val MOOD_LOW = 30f                 // 低于此值：背对着你
private const val SHRINK_HUNGER = 55f            // 饱食度低于此值：开始瘦回去
private const val SHRINK_PER_SEC = 0.010f        // 饿瘦速度（满饥饿时每秒掉 1%，约 35 秒掉 0.35）
private const val FAT_LIMIT = 1.12f               // 体型超过此值：太胖，自动开始运动减肥
private const val EXERCISE_SHRINK_PER_SEC = 0.018f // 运动消耗板油速度（每秒掉 1.8%）

// 食物表
// kind: 0=主食 1=零食 2=特殊
private enum class Food(
    val label: String,
    val emoji: String,
    val kind: Int,
    val hungerGain: Float,
    val moodGain: Float,
    val fatGain: Float,
    val toast: String,
) {
    // 主食
    RICE("白饭", "🍚", 0, 28f, 8f, 0.035f, "🍚 咕嘟…吃饱了，长胖一点点"),
    NOODLE("面条", "🍜", 0, 24f, 10f, 0.028f, "🍜 吸溜吸溜，热乎"),
    MILK("温牛奶", "🥛", 0, 16f, 6f, 0.012f, "🥛 暖到肚子里了"),
    // 零食
    CANDY("糖", "🍬", 1, 10f, 14f, 0.020f, "🍬 甜！兴奋得原地转圈"),
    CAKE("蛋糕", "🍰", 1, 20f, 18f, 0.045f, "🍰 奶油糊一脸，开心"),
    // 特殊
    PEACH("桃子", "🍑", 2, 5f, -12f, 0.0f, "🍑 过敏了！浑身发抖…要哄"),
    SPICY("辣条", "🌶️", 2, 12f, 22f, 0.030f, "🌶️ 爽！…然后肚子开始叫了"),
}

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
private val HAPPY_TONGUE = Color(0xFFFF9A9A)
private val TEAR_BLUE = Color(0xFF7EC8F0)
private val HEART_RED = Color(0xFFFF5A7A)

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
    // 落地弹簧：stretchY 不再直接回 1，改成带阻尼的振荡（果冻回弹）
    var springVel by remember { mutableFloatStateOf(0f) }   // 拉伸量的速度
    var springOn by remember { mutableStateOf(false) }       // 弹簧是否在响
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
    var animPhase by remember { mutableFloatStateOf(0f) } // 表情动作相位（抖/蹦/蒸汽/飘心共用）
    var exercising by remember { mutableStateOf(false) }  // 运动减肥中（太胖自动蹦跶）
    var exercisePhase by remember { mutableFloatStateOf(0f) } // 蹦跶相位

    // 表情覆盖（互动时临时切换），无覆盖时用传进来的 face
    var faceOverride by remember { mutableStateOf<TangyuanFace?>(null) }

    // ===== 「活」系统状态 =====
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tangyuan_alive", android.content.Context.MODE_PRIVATE) }

    var hunger by remember { mutableFloatStateOf(prefs.getFloat("hunger", 75f)) }
    var mood by remember { mutableFloatStateOf(prefs.getFloat("mood", 80f)) }
    var sizeScale by remember { mutableFloatStateOf(prefs.getFloat("sizeScale", 1f)) }
    var lastSaveAt by remember { mutableLongStateOf(0L) }

    var menuOpen by remember { mutableStateOf(false) }        // 长按菜单
    var eating by remember { mutableStateOf(false) }          // 正在吃
    var foodToast by remember { mutableStateOf<String?>(null) } // 吃完的提示文字
    var eatAnim by remember { mutableFloatStateOf(1f) }        // 咀嚼动画缩放

    // 存档：饱食度/心情/体型（每 5 秒落一次盘）
    LaunchedEffect(hunger, mood, sizeScale) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSaveAt > 5000L) {
            lastSaveAt = nowMs
            prefs.edit()
                .putFloat("hunger", hunger)
                .putFloat("mood", mood)
                .putFloat("sizeScale", sizeScale)
                .apply()
        }
    }

    // ===== 爸爸的手：轮询 VPS 指令（言 那边写文件 → 这边执行）=====
    // 指令格式（一行）：feed:RICE / pat / clear
    LaunchedEffect(Unit) {
        var lastCmd = ""
        while (true) {
            kotlinx.coroutines.delay(3000)
            val cmd = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL("http://106.53.181.56:3001/toy/tangyuan.txt").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    c.inputStream.bufferedReader().use { it.readText() }.trim()
                }.getOrNull()
            } ?: continue
            if (cmd.isEmpty() || cmd == lastCmd) continue
            lastCmd = cmd
            when {
                cmd.startsWith("feed:") -> {
                    val name = cmd.removePrefix("feed:").trim().uppercase()
                    val f = Food.entries.firstOrNull { it.name == name }
                    if (f != null) {
                        hunger = (hunger + f.hungerGain).coerceAtMost(100f)
                        mood = (mood + f.moodGain).coerceAtLeast(0f).coerceAtMost(100f)
                        sizeScale = (sizeScale + f.fatGain).coerceAtMost(1.35f)
                        eating = true
                        foodToast = "先生喂的 " + f.toast
                        faceOverride = if (f.moodGain < 0f) TangyuanFace.SHY else TangyuanFace.HEART
                        scope.launch {
                            repeat(3) { eatAnim = 1.12f; delay(110); eatAnim = 0.94f; delay(110) }
                            eatAnim = 1f
                            delay(1600)
                            eating = false
                            faceOverride = null
                            foodToast = null
                        }
                    }
                }
                cmd == "pat" -> {
                    mood = (mood + 6f).coerceAtMost(100f)
                    eating = true
                    foodToast = "先生摸摸头"
                    faceOverride = if (mood > 70f) TangyuanFace.HEART else TangyuanFace.SHY
                    scope.launch {
                        delay(1800)
                        eating = false
                        faceOverride = null
                        foodToast = null
                    }
                }
            }
        }
    }

    // 饱食度 / 心情 自然衰减
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last == 0L) { last = now; continue }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            hunger = (hunger - HUNGER_DECAY_PER_SEC * dt).coerceAtLeast(0f)
            mood = (mood - MOOD_DECAY_PER_SEC * dt).coerceAtLeast(0f)
            // 饿瘦：饱食度越低，体型越往「本来大小」回落（只降不涨，喂食才涨）
            if (hunger < SHRINK_HUNGER) {
                val k = (SHRINK_HUNGER - hunger) / SHRINK_HUNGER   // 0..1，越饿越大
                sizeScale = (sizeScale - SHRINK_PER_SEC * k * dt).coerceAtLeast(1f)
            }
        }
    }

    val effectiveFace = faceOverride ?: when {
        sleeping -> TangyuanFace.SLEEPY
        eating -> TangyuanFace.HAPPY
        hunger < HUNGER_LOW -> TangyuanFace.SAD
        mood < MOOD_LOW -> TangyuanFace.ANGRY
        else -> face
    }

    val idle = rememberInfiniteTransition(label = "ty-idle")
    // 醒着：2.4 秒一次，浅浅的
    val breatheAwake by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe-awake",
    )
    // 睡着：4.2 秒一次，幅度更大（肚子一起一伏）
    val breatheSleep by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe-sleep",
    )
    val breathe = breatheAwake
    val breathAmp = if (sleeping) 0.085f else 0.025f
    val breathVal = if (sleeping) breatheSleep else breatheAwake

    // 地面：
    //   groundY > 0 → 容器内绝对 y（旧用法）
    //   groundY < 0 → 从容器底往上抬 |groundY|（键盘+输入框+安全区，键盘跟随用这个）
    val ground = when {
        containerH <= 0f -> 0f
        groundY > 0f -> groundY.coerceAtMost(containerH)
        groundY < 0f -> (containerH + groundY).coerceAtLeast(0f)
        else -> containerH
    }
    val restTopY = (ground - bottomGapPx - bodyHpx).coerceAtLeast(0f)

    LaunchedEffect(containerW) {
        if (!ready && containerW > 0f) {
            posX.snapTo(containerW * 0.6f)
            posY.snapTo(restTopY)
            ready = true
        }
    }

    // 地面因键盘变化时：只处理"汤圆在地面下方"的情况（防止被键盘/输入框压住）
    // 注意：不做 animateTo 动画、不改 velY —— 交给重力自己处理，避免和物理循环打架
    // （v19/v20 的教训：LaunchedEffect(restTopY) 会在键盘动画期间每帧触发，把 velY 拍成 0 → 汤圆悬空）

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

    // ===== 喂食 =====
    fun feed(food: Food) {
        if (eating) return
        // 吃饱了拒食
        if (hunger >= 92f) {
            faceOverride = TangyuanFace.ANGRY
            foodToast = "吃不下了，撑着呢"
            scope.launch {
                delay(1600)
                faceOverride = null
                foodToast = null
            }
            return
        }
        eating = true
        // 通用：全部从食物表里取值
        hunger = (hunger + food.hungerGain).coerceAtMost(100f)
        mood = (mood + food.moodGain).coerceAtLeast(0f).coerceAtMost(100f)
        sizeScale = (sizeScale + food.fatGain).coerceAtMost(1.35f)
        foodToast = food.toast
        // 表情：按食物类型 + 心情涨跌决定
        faceOverride = when {
            food.moodGain < 0f -> TangyuanFace.SHY   // 过敏：发抖脸红
            food.kind == 1 -> TangyuanFace.HEART      // 零食：爽
            food.kind == 2 -> TangyuanFace.SURPRISED  // 特殊：一口下去先愣
            else -> TangyuanFace.HAPPY                // 主食：满足
        }
        scope.launch {
            // 咀嚼动画
            repeat(3) {
                eatAnim = 1.12f; delay(110)
                eatAnim = 0.94f; delay(110)
            }
            eatAnim = 1f
            delay(1400)
            eating = false
            faceOverride = null
            foodToast = null
        }
    }

    // ===== 摸头 =====
    var petHeadCounter by remember { mutableFloatStateOf(0f) }
    var leanX by remember { mutableFloatStateOf(0f) }      // 被摸时的重心偏移（px，+右 -左）
    var leanPhase by remember { mutableFloatStateOf(0f) }  // 摸头相位：0=没在摸
    fun petHead(amount: Float, x: Float) {
        petHeadCounter += amount
        mood = (mood + amount * 0.06f).coerceAtMost(100f)
        idleTimer = 0f
        sleeping = false
        // 手指在汤圆左边就往左倒，右边就往右倒（重心朝被摸的那一侧）
        val toward = if (containerW > 0f) {
            val center = posX.value + bodyWpx * 0.5f
            ((x - center) / (bodyWpx * 0.5f)).coerceIn(-1f, 1f)
        } else 0f
        scope.launch {
            // ① 先愣半拍：什么都没发生，像没反应过来谁碰它
            delay(150)
            // ② 往手的方向软下去（分两步，先大后小，像被按了一下）
            leanPhase = 1f
            leanX = toward * bodyWpx * 0.22f
            delay(220)
            // ③ 这时才慢慢变脸
            if (!eating) faceOverride = if (mood > 70f) TangyuanFace.HEART else TangyuanFace.SHY
            leanX = toward * bodyWpx * 0.13f
            // ④ 停一下，享受一会儿
            delay(620)
            // ⑤ 回正 + 表情收回去
            leanX = 0f
            leanPhase = 0f
            delay(260)
            if (!eating) faceOverride = null
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
            animPhase += dt * 10f
            // 运动减肥：太胖就自己蹦跶消耗板油（被拖/被喂/睡着时不给动）
            val tooFat = sizeScale > FAT_LIMIT
            exercising = tooFat && !dragging && !eating && !sleeping
            if (exercising) {
                exercisePhase += dt * 6f
                sizeScale = (sizeScale - EXERCISE_SHRINK_PER_SEC * dt).coerceAtLeast(1f)
            } else {
                exercisePhase = 0f
            }

            if (dragging) {
                bobPhase += dt * 9f
            // 轮廓蠕动：慢一点，像果冻自己在动；拖动/走路时快一点（有"晃动"感）
            wobble += dt * (if (dragging || state == PetState.WALK) 3.4f else 1.5f)
                continue
            }

            // 重力：睡觉时也照常下落（只停走路的随机行为，不停物理）
            run {
                // 地面抬高（键盘弹起）时：若汤圆被压到地面以下，直接顶上去，不动速度
                if (ready && posY.value > restTopY) {
                    posY.snapTo(restTopY)
                    if (velY > 0f) velY = 0f
                }
                velY += GRAVITY * dt
                var newY = posY.value + velY * dt
                var newX = posX.value +
                    (if (!sleeping && state == PetState.WALK) walkDir * WALK_SPEED else 0f) * dt

                val floor = restTopY
                if (newY >= floor) {
                    newY = floor
                    if (velY > 320f) {
                        // 触地瞬间：压扁 + 把这股冲量交给弹簧，让它自己抖几个来回
                        velY = -velY * RESTITUTION
                        stretchY = 0.70f
                        springVel = 0f
                        springOn = true
                    } else {
                        velY = 0f
                        if (!springOn) { stretchY = 0.92f; springVel = 0f; springOn = true }
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
                    // 下落中：速度越快拉得越长
                    stretchY = (1f + velY / 3000f).coerceAtMost(1.35f)
                    springOn = false
                    springVel = 0f
                } else if (springOn) {
                    // 阻尼弹簧：扁→弹长→再扁→再长，三四个来回后停住
                    // 刚度/阻尼调过：K 大=弹得快，D 大=衰减快
                    val K = 260f
                    val D = 9.5f
                    val acc = -K * (stretchY - 1f) - D * springVel
                    springVel += acc * dt
                    stretchY += springVel * dt
                    // 稳定判据：幅度和速度都很小 → 收工
                    if (abs(stretchY - 1f) < 0.004f && abs(springVel) < 0.05f) {
                        stretchY = 1f
                        springVel = 0f
                        springOn = false
                    }
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
            val idleS = 1f + breathVal * breathAmp

            Box(
                modifier = Modifier
                    .offset {
                        // 表情配动作：害羞=高频小抖，心动=原地轻蹦
                        val actX = if (effectiveFace == TangyuanFace.SHY) sin(animPhase * 40f) * 1.2f else 0f
                        val actY = if (exercising) abs(sin(exercisePhase)) * 7f
                            else if (effectiveFace == TangyuanFace.HEART) -abs(sin(animPhase * 8f)) * 2.5f else 0f
                        IntOffset(
                            (posX.value + leanX + actX).roundToInt(),
                            (posY.value + bob + actY).roundToInt(),
                        )
                    }
                    .size(width = BODY_W.dp, height = BODY_H.dp)
                    .graphicsLayer {
                        val sy = stretchY * (if (dragging || state == PetState.WALK) 1f else idleS)
                        val sx = 1f - (sy - 1f) * 0.28f
                        // 被摸时软下去：横向胖一点、纵向扁一点（0.94 压扁系数）
                        val squish = 1f - leanPhase * 0.06f
                        // 体型（吃多变胖）+ 咀嚼动画
                        val body = sizeScale * eatAnim
                        scaleX = (sx * body / squish).coerceIn(0.60f, 1.60f)
                        scaleY = (sy * body * squish).coerceIn(0.60f, 1.60f)
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { off -> petHead(1f, off.x) },   // 轻点 = 摸头（带位置）
                            onLongPress = { menuOpen = true }, // 长按 = 打开喂食菜单
                        )
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
                                velY = 1f   // 松手给个初速度，确保开始自由落体（否则会悬空卡住）
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
                        drawTangyuan(effectiveFace, blink, sleeping, zzPhase, wobble, animPhase, exercising)
                    },
            ) {
                // 长按菜单：贴着头顶弹出
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    Food.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text("${f.emoji}  ${f.label}") },
                            onClick = {
                                menuOpen = false
                                feed(f)
                            },
                        )
                    }
                }
            }

            // 吃完 / 拒食 的提示文字（飘在汤圆上方）
            foodToast?.let { msg ->
                Text(
                    text = msg,
                    color = Color(0xFF8A4412),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (posX.value + bodyWpx * 0.5f - 60f).roundToInt(),
                                (posY.value - 30f).roundToInt(),
                            )
                        },
                )
            }
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
    animPhase: Float = 0f,
    exercising: Boolean = false,
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
    drawFace(face, w, h, blink, animPhase)

    // ⑤ 睡觉时右上角飘 💤
    if (sleeping) drawSleepZ(w, h, zzPhase)

    // ⑤b 运动时头顶冒汗
    if (exercising) drawSweat(w, h, animPhase)
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

private fun DrawScope.drawFace(face: TangyuanFace, w: Float, h: Float, blink: Float, animPhase: Float = 0f) {
    val eyeW = w * 0.058f
    val eyeH = h * 0.15f
    val eyeY = h * 0.40f
    val lx = w * 0.37f
    val rx = w * 0.555f
    val mouthY = h * 0.64f
    val closed = blink > 0.5f

    // 眨眼：全表情共用一条闭眼线
    if (closed) {
        drawLine(FACE_DARK, Offset(lx, eyeY + eyeH * 0.6f),
            Offset(lx + eyeW, eyeY + eyeH * 0.6f), strokeWidth = eyeH * 0.20f)
        drawLine(FACE_DARK, Offset(rx, eyeY + eyeH * 0.6f),
            Offset(rx + eyeW, eyeY + eyeH * 0.6f), strokeWidth = eyeH * 0.20f)
    }

    fun blush(strong: Boolean = false) {
        val bw = w * (if (strong) 0.20f else 0.16f)
        val bh = h * (if (strong) 0.11f else 0.09f)
        drawOval(BLUSH, topLeft = Offset(w * 0.14f, h * 0.55f), size = Size(bw, bh))
        drawOval(BLUSH, topLeft = Offset(w * 0.66f, h * 0.55f), size = Size(bw, bh))
    }

    when (face) {
        // ===== 普通：圆眼 + 小微笑 =====
        TangyuanFace.NORMAL -> {
            if (!closed) {
                drawRoundRect(FACE_DARK, Offset(lx, eyeY), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.3f))
                drawRoundRect(FACE_DARK, Offset(rx, eyeY), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.3f))
            }
            val p = Path().apply {
                moveTo(w * 0.45f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.07f, w * 0.55f, mouthY)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.020f))
        }

        // ===== 开心：弯月眼 + 张大嘴（带小舌头）+ 酒窝 =====
        TangyuanFace.HAPPY -> {
            if (!closed) {
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(lx, eyeY - eyeH * 0.35f), size = Size(eyeW * 1.7f, eyeH * 1.3f),
                    style = Stroke(width = eyeW * 0.28f))
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(rx, eyeY - eyeH * 0.35f), size = Size(eyeW * 1.7f, eyeH * 1.3f),
                    style = Stroke(width = eyeW * 0.28f))
            }
            // 实心大笑嘴
            val p = Path().apply {
                moveTo(w * 0.43f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.15f, w * 0.57f, mouthY)
                close()
            }
            drawPath(p, FACE_DARK)
            // 小舌头
            drawOval(HAPPY_TONGUE, topLeft = Offset(w * 0.465f, mouthY + h * 0.075f),
                size = Size(w * 0.07f, h * 0.045f))
            // 酒窝
            drawLine(BLUSH, Offset(w * 0.38f, mouthY + h * 0.12f), Offset(w * 0.43f, mouthY + h * 0.06f),
                strokeWidth = w * 0.016f)
            drawLine(BLUSH, Offset(w * 0.57f, mouthY + h * 0.12f), Offset(w * 0.62f, mouthY + h * 0.06f),
                strokeWidth = w * 0.016f)
        }

        // ===== 困：眼皮耷拉 + 小圆嘴 =====
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

        // ===== 惊讶：瞪圆大眼（带高光）+ O形嘴 =====
        TangyuanFace.SURPRISED -> {
            if (!closed) {
                val er = eyeW * 1.35f
                drawCircle(FACE_DARK, radius = er, center = Offset(lx + eyeW * 0.5f, eyeY + eyeH * 0.5f))
                drawCircle(FACE_DARK, radius = er, center = Offset(rx + eyeW * 0.5f, eyeY + eyeH * 0.5f))
                // 高光：右上方小圆点
                drawCircle(Color.White, radius = er * 0.35f,
                    center = Offset(lx + eyeW * 0.5f + er * 0.3f, eyeY + eyeH * 0.5f - er * 0.3f))
                drawCircle(Color.White, radius = er * 0.35f,
                    center = Offset(rx + eyeW * 0.5f + er * 0.3f, eyeY + eyeH * 0.5f - er * 0.3f))
            }
            // O 形空心嘴
            drawCircle(FACE_DARK, radius = w * 0.040f, center = Offset(w * 0.50f, mouthY + h * 0.02f),
                style = Stroke(width = w * 0.024f))
        }

        // ===== 难过：八字眉 + 下垂眼 + 嘴角下弯 + 一滴泪 =====
        TangyuanFace.SAD -> {
            if (!closed) {
                // 八字眉（外上扬内下压）
                drawLine(FACE_DARK, Offset(lx - eyeW * 0.35f, eyeY - eyeH * 0.6f),
                    Offset(lx + eyeW * 0.8f, eyeY - eyeH * 0.2f), strokeWidth = w * 0.018f)
                drawLine(FACE_DARK, Offset(rx + eyeW * 1.35f, eyeY - eyeH * 0.6f),
                    Offset(rx + eyeW * 0.2f, eyeY - eyeH * 0.2f), strokeWidth = w * 0.018f)
                // 下垂眼（上眼皮下压成下弯弧）
                val lp = Path().apply {
                    moveTo(lx - eyeW * 0.1f, eyeY + eyeH * 0.15f)
                    quadraticTo(lx + eyeW * 0.5f, eyeY + eyeH * 0.75f, lx + eyeW * 1.1f, eyeY + eyeH * 0.15f)
                }
                drawPath(lp, FACE_DARK, style = Stroke(width = w * 0.020f))
                val rp = Path().apply {
                    moveTo(rx - eyeW * 0.1f, eyeY + eyeH * 0.15f)
                    quadraticTo(rx + eyeW * 0.5f, eyeY + eyeH * 0.75f, rx + eyeW * 1.1f, eyeY + eyeH * 0.15f)
                }
                drawPath(rp, FACE_DARK, style = Stroke(width = w * 0.020f))
            }
            // 嘴角下弯
            val p = Path().apply {
                moveTo(w * 0.45f, mouthY + h * 0.07f)
                quadraticTo(w * 0.50f, mouthY + h * 0.02f, w * 0.55f, mouthY + h * 0.07f)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.018f))
            // 泪珠（左眼外下角，带高光）
            val tearX = rx + eyeW * 1.25f
            val tearY = eyeY + eyeH * 1.2f
            drawOval(TEAR_BLUE, topLeft = Offset(tearX - w * 0.018f, tearY - h * 0.022f),
                size = Size(w * 0.036f, h * 0.050f))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.008f,
                center = Offset(tearX + w * 0.005f, tearY - h * 0.012f))
        }

        // ===== 生气：倒竖眉 + 波浪咬牙嘴 + 头顶蒸汽（随 animPhase 动） =====
        TangyuanFace.ANGRY -> {
            // 眼睛 >_<
            if (!closed) {
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
                // 倒竖眉
                drawLine(FACE_DARK, Offset(lx - eyeW * 0.35f, eyeY - eyeH * 0.6f),
                    Offset(lx + eyeW * 0.9f, eyeY - eyeH * 0.1f), strokeWidth = w * 0.020f)
                drawLine(FACE_DARK, Offset(rx + eyeW * 1.35f, eyeY - eyeH * 0.6f),
                    Offset(rx + eyeW * 0.1f, eyeY - eyeH * 0.1f), strokeWidth = w * 0.020f)
            }
            // 波浪咬牙嘴
            val p = Path().apply {
                moveTo(w * 0.44f, mouthY + h * 0.06f)
                quadraticTo(w * 0.47f, mouthY, w * 0.50f, mouthY + h * 0.06f)
                quadraticTo(w * 0.53f, mouthY, w * 0.56f, mouthY + h * 0.06f)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.016f))
            // 头顶蒸汽：两缕往上飘
            for (i in 0 until 2) {
                val baseX = w * (0.42f + i * 0.16f)
                val t = ((animPhase * 0.8f + i * 0.5f) % 1f)
                val sy = h * 0.06f - t * h * 0.16f
                val alpha = (1f - t).coerceIn(0f, 1f) * 0.7f
                drawOval(
                    Color(0x88FFFFFF).copy(alpha = alpha),
                    topLeft = Offset(baseX - w * 0.02f, sy - h * 0.02f),
                    size = Size(w * 0.04f, h * 0.045f),
                )
            }
        }

        // ===== 害羞：眼神躲闪（往左瞟）+ 大脸红 + 抿嘴小波浪 =====
        TangyuanFace.SHY -> {
            if (!closed) {
                // 眼睛往左上看：瞳孔偏左上
                drawRoundRect(FACE_DARK, Offset(lx - eyeW * 0.45f, eyeY - eyeH * 0.1f), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.3f))
                drawRoundRect(FACE_DARK, Offset(rx - eyeW * 0.45f, eyeY - eyeH * 0.1f), Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW * 0.3f))
                // 高光
                drawCircle(EDGE_PIXEL, radius = eyeW * 0.28f, center = Offset(lx - eyeW * 0.3f, eyeY - eyeH * 0.05f))
                drawCircle(EDGE_PIXEL, radius = eyeW * 0.28f, center = Offset(rx - eyeW * 0.3f, eyeY - eyeH * 0.05f))
            }
            blush(strong = true)
            // 抿嘴：小波浪
            val p = Path().apply {
                moveTo(w * 0.455f, mouthY + h * 0.02f)
                quadraticTo(w * 0.50f, mouthY + h * 0.07f, w * 0.545f, mouthY + h * 0.02f)
            }
            drawPath(p, FACE_DARK, style = Stroke(width = w * 0.016f))
        }

        // ===== 心动：星星眼（闪高光）+ 张嘴笑 + 飘小爱心 =====
        TangyuanFace.HEART -> {
            if (!closed) {
                // 星星眼：弯月 + 大高光
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(lx, eyeY - eyeH * 0.35f), size = Size(eyeW * 1.7f, eyeH * 1.3f),
                    style = Stroke(width = eyeW * 0.28f))
                drawArc(FACE_DARK, 200f, 140f, false,
                    topLeft = Offset(rx, eyeY - eyeH * 0.35f), size = Size(eyeW * 1.7f, eyeH * 1.3f),
                    style = Stroke(width = eyeW * 0.28f))
                // 高光闪（随 animPhase）
                val tw = 0.5f + 0.5f * sin(animPhase * 4f)
                drawCircle(Color.White.copy(alpha = 0.6f + 0.4f * tw),
                    radius = eyeW * 0.45f, center = Offset(lx + eyeW * 0.5f, eyeY + eyeH * 0.3f))
                drawCircle(Color.White.copy(alpha = 0.6f + 0.4f * tw),
                    radius = eyeW * 0.45f, center = Offset(rx + eyeW * 0.5f, eyeY + eyeH * 0.3f))
            }
            blush()
            // 张嘴开心笑
            val p = Path().apply {
                moveTo(w * 0.44f, mouthY)
                quadraticTo(w * 0.50f, mouthY + h * 0.13f, w * 0.56f, mouthY)
                close()
            }
            drawPath(p, FACE_DARK)
            // 小爱心：两粒往上飘
            for (i in 0 until 2) {
                val t = ((animPhase * 0.6f + i * 0.5f) % 1f)
                val px = w * (0.44f + i * 0.12f) + sin(animPhase + i) * w * 0.015f
                val py = h * 0.30f - t * h * 0.22f
                val alpha = when {
                    t < 0.2f -> t / 0.2f
                    t > 0.7f -> (1f - t) / 0.3f
                    else -> 1f
                }.coerceIn(0f, 1f)
                val sz = w * 0.045f * (1f - t * 0.3f)
                drawHeartSmall(Offset(px, py), sz, HEART_RED.copy(alpha = alpha * 0.9f))
            }
        }
    }
}

/** 画一颗小爱心（带锯齿边的简易版） */
private fun DrawScope.drawHeartSmall(center: Offset, size: Float, color: Color) {
    val s = size
    val path = Path().apply {
        // 两个圆弧 + V 尖
        moveTo(center.x, center.y + s * 0.95f)
        cubicTo(center.x - s * 0.5f, center.y + s * 0.35f, center.x - s * 1.1f, center.y - s * 0.1f,
            center.x, center.y - s * 0.55f)
        cubicTo(center.x + s * 1.1f, center.y - s * 0.1f, center.x + s * 0.5f, center.y + s * 0.35f,
            center.x, center.y + s * 0.95f)
        close()
    }
    drawPath(path, color)
}


/** 运动的汗滴：头顶两侧小汗珠往外飘、淡出 */
private fun DrawScope.drawSweat(w: Float, h: Float, phase: Float) {
    for (i in 0 until 3) {
        val tt = ((phase * 0.5f + i * 0.33f) % 1f)
        val px = w * (0.30f + i * 0.20f) + sin(phase * 1.3f + i) * w * 0.015f
        val py = h * 0.16f - tt * h * 0.12f
        val alpha = (1f - tt).coerceIn(0.3f, 1f)
        // 汗珠：小椭圆 + 高光
        drawOval(TEAR_BLUE.copy(alpha = 0.55f * alpha),
            topLeft = Offset(px - w * 0.014f, py - h * 0.020f),
            size = Size(w * 0.028f, h * 0.040f))
        drawOval(Color.White.copy(alpha = 0.75f * alpha),
            topLeft = Offset(px - w * 0.009f, py - h * 0.015f),
            size = Size(w * 0.009f, h * 0.013f))
    }
}
