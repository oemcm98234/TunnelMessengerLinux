package com.tunnelmessenger.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * v12 anim: переиспользуемые анимационные помощники UI (порт Anim.kt 1:1).
 *
 * Сюда собраны мелкие визуальные эффекты, общие для нескольких экранов:
 * press-scale для заметных кнопок и плавное появление/скрытие блоков.
 * Никакой логики — только анимации.
 */

/**
 * v12 anim: press-scale для заметных кнопок (FAB, кнопки питания туннеля, кнопки звонка).
 *
 * Принимает ТОТ ЖЕ interactionSource, который передан в кнопку: состояние
 * нажатия читается из него, масштаб анимируется пружиной (medium-bouncy) и
 * применяется в graphicsLayer — т.е. в draw-фазе, без рекомпозиций на каждый
 * кадр. Ripple кнопки не трогается (никаких clickable поверх — только
 * трансформация масштаба).
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.94f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * v12 anim: блок появляется/скрывается плавно — fade + expand/shrink по вертикали.
 *
 * Обёртка над AnimatedVisibility с едиными таймингами (вход 220мс, выход 180мс),
 * чтобы строки синка, баннеры и сообщения результата анимировались одинаково.
 */
@Composable
fun SmoothExpandFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
    ) {
        content()
    }
}
