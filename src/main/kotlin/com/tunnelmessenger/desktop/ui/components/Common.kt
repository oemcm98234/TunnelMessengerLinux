package com.tunnelmessenger.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

/** Версия приложения (на десктопе нет BuildConfig — единая с versionCode сервера/Android: просто 15). */
const val APP_VERSION = "15"

/**
 * «Последнее рукопожатие» WG/AWG-туннеля по-человечески (зеркало Android Common.kt).
 * Движок шлёт unix-секунды последнего handshake (hs) — на вход даём их же в миллисекундах.
 */
fun humanizeHandshake(ms: Long): String {
    if (ms <= 0) return "рукопожатия ещё не было"
    val ago = (System.currentTimeMillis() - ms) / 1000
    return when {
        ago < 60 -> "рукопожатие ${ago} с назад"
        ago < 3600 -> "рукопожатие ${ago / 60} мин назад"
        else -> "рукопожатие ${ago / 3600} ч назад"
    }
}

/**
 * десктоп: тултип для иконок-кнопок — проявляется при наведении мыши.
 * Обёртка над material3 TooltipBox/PlainTooltip: оборачиваем любую кнопку,
 * например HoverTooltip("Новый чат") { IconButton(...) { ... } }.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/**
 * десктоп: тонкий заголовок раздела — тулбар 48dp БЕЗ back-стрелок
 * (навигация между разделами живёт в левом рейле Main.kt).
 * Необязательная кнопка «✕» рисуется только у оверлеев (например «Туннель»
 * с экрана входа), у рейл-разделов её нет.
 */
@Composable
fun SectionHeader(
    title: String,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            actions()
            if (onClose != null) {
                HoverTooltip("Закрыть") {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Аватар: СТРОГО круглый (фиксированный size + clip(CircleShape)),
 * первая буква имени. online == true — зелёная точка НА краю аватара
 * (правый нижний край, наполовину выступает за круг, с обводкой цветом фона).
 */
@Composable
fun Avatar(name: String, size: Dp = 44.dp, online: Boolean? = null, modifier: Modifier = Modifier) {
    // Фирменная палитра — только зелёные оттенки (никаких фиолетовых/синих).
    val colors = listOf(
        Color(0xFF1E9B7B), // teal-500
        Color(0xFF177A63), // teal-600
        Color(0xFF2E7D5F),
        Color(0xFF12433A), // teal-800
        Color(0xFF3C7A1E),
        Color(0xFF1FA983), // mint-dark
    )
    val idx = (name.hashCode() and 0x7FFFFFFF) % colors.size
    // Внешний Box БЕЗ clip: круг рисуется внутренним боксом, а точка онлайна
    // — соседним элементом, поэтому её край не срезается кругом аватара.
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(colors[idx], CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
        if (online == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(Color(0xFF2BD9A8))
                    .border(
                        (size.value * 0.034f).dp,
                        MaterialTheme.colorScheme.background,
                        CircleShape,
                    ),
            )
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1 shl 30 -> String.format(Locale.US, "%.1f ГБ", b / 1073741824.0)
    b >= 1 shl 20 -> String.format(Locale.US, "%.1f МБ", b / 1048576.0)
    b >= 1 shl 10 -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
    else -> "$b Б"
}

fun formatTs(ts: Double): String {
    if (ts <= 0.0) return ""
    val millis = (ts * 1000).toLong()
    val now = System.currentTimeMillis()
    val diff = now - millis
    val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(millis)
    return when {
        diff < 18 * 3600_000L -> time
        diff < 48 * 3600_000L -> "вчера $time"
        else -> java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(millis)
    }
}

fun formatFullTs(ts: Double): String {
    if (ts <= 0.0) return ""
    val millis = (ts * 1000).toLong()
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(millis)
}

/** Человекочитаемый размер (БД + медиа + служебные файлы кэша) — из SettingsScreen. */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f ГБ", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f МБ", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

/**
 * Копировать текст в системный буфер обмена.
 * (В Android был LocalClipboardManager из compose; на десктопе по контракту —
 * java.awt.Toolkit напрямую.)
 */
fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
    }
}
