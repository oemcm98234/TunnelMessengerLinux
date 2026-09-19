package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.UiMsg
import com.tunnelmessenger.desktop.data.model.Chat
import com.tunnelmessenger.desktop.ui.components.HoverTooltip
import com.tunnelmessenger.desktop.ui.components.ImageThumb
import com.tunnelmessenger.desktop.ui.components.SaveMediaAsButton
import com.tunnelmessenger.desktop.ui.components.formatBytes
import com.tunnelmessenger.desktop.ui.theme.accentTextColor
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v8: галерея чата «Медиа и файлы».
 *
 * Собирает из загруженной истории чата: фото (сетка миниатюр), видео,
 * голосовые, документы и ссылки из текста сообщений. Тап по фото — просмотр,
 * по файлу — скачивание/открытие, по ссылке — системный браузер
 * (java.awt.Desktop.browse — вместо android Intent).
 */
@Composable
fun MediaDialog(
    chat: Chat,
    messages: List<UiMsg>,
    onDismiss: () -> Unit,
    onDownload: (UiMsg) -> Unit,
    onError: (String) -> Unit,
) {
    var tab by remember { mutableStateOf("photo") }
    val buckets = remember(messages, chat.id) { collectMedia(messages) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        title = {
            Column {
                Text("Медиа и файлы", style = MaterialTheme.typography.titleMedium)
                Text(
                    chat.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 10.dp),
                ) {
                    mediaTab("photo", "Фото", buckets.photo.size, tab) { tab = it }
                    mediaTab("video", "Видео", buckets.video.size, tab) { tab = it }
                    mediaTab("voice", "Голосовые", buckets.voice.size, tab) { tab = it }
                    mediaTab("file", "Файлы", buckets.file.size, tab) { tab = it }
                    mediaTab("link", "Ссылки", buckets.link.size, tab) { tab = it }
                }
                val isEmpty = when (tab) {
                    "photo" -> buckets.photo.isEmpty()
                    "video" -> buckets.video.isEmpty()
                    "voice" -> buckets.voice.isEmpty()
                    "file" -> buckets.file.isEmpty()
                    else -> buckets.link.isEmpty()
                }
                if (isEmpty) {
                    Text(
                        "пока пусто — здесь появится медиа этого чата",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else if (tab == "photo") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(buckets.photo, key = { "${it.mid}:${it.clientId ?: ""}" }) { m ->
                            // v8.x: асинхронная миниатюра + лоадер, пока декодируется
                            val bmp = ImageThumb.getAsync(m.localPath)
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        if (m.localPath != null) {
                                            openMediaFile(m, onError)
                                        } else onDownload(m)
                                    },
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = m.file?.name ?: "изображение",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (m.localPath != null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .align(Alignment.Center),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(
                                        "нажмите,\nчтобы скачать",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                                // v15: сохранить фото через системный диалог —
                                // маленькая кнопка поверх миниатюры (верхний правый угол)
                                SaveMediaAsButton(
                                    m,
                                    iconSize = 16.dp,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            RoundedCornerShape(50),
                                        )
                                        .size(28.dp),
                                )
                            }
                        }
                    }
                } else {
                    val rows: List<Any> = when (tab) {
                        "video" -> buckets.video
                        "voice" -> buckets.voice
                        "file" -> buckets.file
                        else -> buckets.link
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems(rows, key = { mediaKey(it) }) { item ->
                            when (item) {
                                is UiMsg -> mediaFileRow(item, onDownload)
                                is MediaLink -> linkRow(item)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun mediaTab(key: String, label: String, count: Int, current: String, onPick: (String) -> Unit) {
    FilterChip(
        selected = current == key,
        onClick = { onPick(key) },
        label = { Text(if (count > 0) "$label $count" else label, maxLines = 1) },
    )
}

private fun mediaKey(item: Any): String = when (item) {
    is UiMsg -> "${item.mid}:${item.clientId ?: ""}"
    is MediaLink -> item.url
    else -> item.toString()
}

@Composable
private fun mediaFileRow(m: UiMsg, onDownload: (UiMsg) -> Unit) {
    val isVoice = m.file?.voice != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (m.localPath != null) openMediaFile(m) { } else onDownload(m)
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            if (isVoice) Icons.Filled.PlayArrow else Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.file?.name ?: "файл",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(m.file?.size ?: 0)} · ${m.sender} · ${fmtDayShort(m.createdAt)}" +
                    if (m.localPath != null) " · скачан" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // v15: сохранить через системный диалог «Сохранить как…»
        HoverTooltip("Сохранить как…") {
            SaveMediaAsButton(m, iconSize = 18.dp)
        }
    }
}

@Composable
private fun linkRow(l: MediaLink) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openLink(l.url) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                l.url,
                style = MaterialTheme.typography.bodySmall,
                color = accentTextColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${l.sender} · ${fmtDayShort(l.createdTs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Открыть скачанный медиа-файл системным просмотрщиком (java.awt.Desktop.open). */
private fun openMediaFile(m: UiMsg, onError: (String) -> Unit) {
    val path = m.localPath ?: return
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(File(path))
        } else {
            onError("системный просмотрщик недоступен")
        }
    } catch (e: Exception) {
        onError("не удалось открыть: ${e.message ?: "?"}")
    }
}

/** Открыть ссылку в системном браузере (java.awt.Desktop.browse). */
private fun openLink(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (ignored: Exception) { /* нет браузера — молча пропускаем */ }
}

private fun fmtDayShort(ts: Double): String {
    if (ts <= 0) return ""
    val d = Date((ts * 1000).toLong())
    return SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(d)
}

/** Ссылка из текста сообщения (для вкладки «Ссылки»). */
data class MediaLink(val url: String, val sender: String, val createdTs: Double)

private val MEDIA_URL_RE = Regex("https?://[^\\s<>\"']+")
private val VIDEO_RE = Regex(".*\\.(mp4|webm|mkv|mov|avi|m4v|3gp)$", RegexOption.IGNORE_CASE)

internal class MediaBuckets(
    val photo: List<UiMsg>,
    val video: List<UiMsg>,
    val voice: List<UiMsg>,
    val file: List<UiMsg>,
    val link: List<MediaLink>,
)

internal fun collectMedia(messages: List<UiMsg>): MediaBuckets {
    val photo = mutableListOf<UiMsg>()
    val video = mutableListOf<UiMsg>()
    val voice = mutableListOf<UiMsg>()
    val file = mutableListOf<UiMsg>()
    val link = mutableListOf<MediaLink>()
    for (m in messages.asReversed()) {
        if (m.type == "deleted") continue
        if (m.type == "file" && m.file != null) {
            val name = m.file.name ?: ""
            when {
                m.file.voice != null -> voice.add(m)
                ImageThumb.isImage(name) -> photo.add(m)
                VIDEO_RE.matches(name) -> video.add(m)
                else -> file.add(m)
            }
        } else if (m.type == "text" && !m.plain.isNullOrBlank()) {
            for (u in MEDIA_URL_RE.findAll(m.plain!!)) {
                link.add(MediaLink(u.value, m.sender, m.createdAt))
            }
        }
    }
    return MediaBuckets(photo, video, voice, file, link)
}
