package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.call.CallManager
import com.tunnelmessenger.desktop.ui.components.pressScale
import kotlinx.coroutines.delay

/**
 * Полноэкранный оверлей звонка. Монтируется ПОВЕРХ навигации в Main.kt:
 * состояние — CallManager.ui. По контракту 2.10 рисуется при
 * ui.state != IDLE/ENDED (окно десктопа и так одно — отдельное окно звонка
 * не требуется, оверлей перекрывает весь контент).
 */
@Composable
fun CallOverlay() {
    val call by CallManager.ui.collectAsState()
    if (call.state == CallManager.CallState.IDLE || call.state == CallManager.CallState.ENDED) return

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // аватар-инициал
            val initial = call.peer.trim().take(1).uppercase().ifBlank { "?" }
            Box(
                Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initial,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                call.peer,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            // v12 anim: статус звонка (вызов… / таймер / завершён) сменяется мягким
            // кроссфейдом. Таймер живёт ВНУТРИ ACTIVE-контента: тики раз в 500мс
            // не перезапускают анимацию — targetState (call.state) не меняется.
            AnimatedContent(
                targetState = call.state,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "callStatus",
            ) { st ->
                val statusText = when (st) {
                    CallManager.CallState.OUTGOING -> "вызов…"
                    CallManager.CallState.INCOMING -> "входящий звонок"
                    CallManager.CallState.ACTIVE -> {
                        // таймер длительности
                        var sec by remember(call.callId) { mutableLongStateOf(0L) }
                        LaunchedEffect(call.callId) {
                            val start = call.startedAtMs
                            while (true) {
                                sec = (System.currentTimeMillis() - start) / 1000
                                delay(500)
                            }
                        }
                        "%d:%02d".format(sec / 60, sec % 60)
                    }
                    CallManager.CallState.ENDED -> call.note?.ifBlank { "звонок завершён" } ?: "звонок завершён"
                    CallManager.CallState.IDLE -> ""
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9C4BE),
                )
            }
            Spacer(Modifier.height(48.dp))

            // v12 anim: набор кнопок (входящий / активный / отмена) меняется мягким кроссфейдом
            AnimatedContent(
                targetState = call.state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "callActions",
            ) { st ->
            when (st) {
                CallManager.CallState.INCOMING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        CallButton(Icons.Filled.CallEnd, "Отклонить", MaterialTheme.colorScheme.error) {
                            CallManager.decline()
                        }
                        CallButton(Icons.Filled.Call, "Принять", MaterialTheme.colorScheme.tertiary) {
                            CallManager.accept()
                        }
                    }
                }
                CallManager.CallState.ACTIVE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CallButton(
                            if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            if (call.muted) "включить" else "микрофон",
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) { CallManager.toggleMute() }
                        CallButton(Icons.Filled.CallEnd, "завершить", MaterialTheme.colorScheme.error) {
                            CallManager.hangup()
                        }
                        CallButton(
                            if (call.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                            "динамик",
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) { CallManager.toggleSpeaker() }
                    }
                }
                else -> {
                    // OUTGOING / ENDED — единственная кнопка «положить трубку»
                    CallButton(Icons.Filled.CallEnd, "отменить", MaterialTheme.colorScheme.error) {
                        CallManager.hangup()
                    }
                }
            }
            } // v12 anim: конец AnimatedContent
        }
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    container: Color,
    onClick: () -> Unit,
) {
    // v12 anim: кнопки звонка — press-scale (пружина на нажатие)
    val interaction = remember { MutableInteractionSource() }
    // v15: цвет значка — по яркости контейнера: тёмные контейнеры (error,
    // accent) как раньше получают белый, светлые (surfaceContainerHighest в
    // светлой теме) — тёмный, чтобы значок не сливался с кнопкой.
    val iconColor = if (container.luminance() > 0.5f) Color(0xFF0E1512) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier
                .size(68.dp)
                .pressScale(interaction),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = iconColor,
            ),
            interactionSource = interaction,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9C4BE))
    }
}
