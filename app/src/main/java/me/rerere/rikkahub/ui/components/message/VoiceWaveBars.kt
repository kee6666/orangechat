package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动态音柱：7根圆角柱，播放时错落伸缩。
 * 每根柱周期 0.9s 起逐根 +50ms，相位错开 90ms，伸缩幅度 0.2~1.0，无限往返。
 */
@Composable
fun VoiceWaveBars(
    modifier: Modifier = Modifier,
    barCount: Int = 7,
    color: Color,
    maxBarHeight: Dp = 18.dp,
    minBarHeight: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "voiceWave")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(barCount) { index ->
            val scale by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 900 + index * 50,
                        delayMillis = index * 90,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(minBarHeight + (maxBarHeight - minBarHeight) * scale)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}
