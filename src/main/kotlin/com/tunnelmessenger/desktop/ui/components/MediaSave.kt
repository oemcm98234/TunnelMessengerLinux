package com.tunnelmessenger.desktop.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.UiMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v15: кнопка «Сохранить как…» для медиа сообщения (фото/видео/голосовые/
 * файлы) — в чате и в галерее «Медиа и файлы».
 *
 * Что делает: если файл ещё не скачан — сначала скачивает и расшифровывает
 * (как обычная кнопка скачивания), затем КАЖДЫЙ РАЗ открывает СИСТЕМНЫЙ
 * диалог сохранения (Swing JFileChooser) — место и имя файла выбирает
 * пользователь. Раньше расшифрованные файлы жили только во внутреннем кэше
 * (~/.tunnelmessenger/downloads), который стирается при выходе из аккаунта.
 * Результат — снекбар Repository.notice (внизу окна, гаснет через 6с).
 */
@Composable
fun SaveMediaAsButton(
    msg: UiMsg,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember("${msg.chatId}:${msg.clientId ?: msg.mid}") { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (busy) return@IconButton
            scope.launch {
                busy = true
                try {
                    // 1) локальная расшифрованная копия (или скачиваем сейчас)
                    val local = msg.localPath?.let { File(it) }?.takeIf { it.isFile }
                    val src = if (local != null) {
                        Result.success(local)
                    } else {
                        val chat = Repository.chats.value.firstOrNull { it.id == msg.chatId }
                        chat?.let { Repository.downloadMessageFile(it, msg) }
                            ?: Result.failure(IllegalStateException("чат не найден"))
                    }
                    val file = src.getOrNull()
                    if (file == null) {
                        Repository.postNotice(
                            "не удалось скачать: ${Repository.humanError(src.exceptionOrNull() ?: RuntimeException("ошибка скачивания"))}")
                        return@launch
                    }
                    // 2) системный диалог сохранения — каждый раз заново
                    val name = (msg.file?.name ?: "файл").replace(Regex("[/\\\\]"), "_")
                        .ifBlank { file.name }
                    val dst = FilePickers.saveFile("Сохранить как…", name)
                    if (dst == null) return@launch // отмена — молча
                    // 3) копия в выбранное место (перезапись подтверждается в диалоге)
                    withContext(Dispatchers.IO) {
                        file.copyTo(dst, overwrite = true)
                    }
                    Repository.postNotice("сохранено: ${dst.absolutePath}")
                } catch (e: Exception) {
                    Repository.postNotice("не удалось сохранить: ${Repository.humanError(e)}")
                } finally {
                    busy = false
                }
            }
        },
        modifier = modifier,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                Icons.Filled.SaveAlt,
                contentDescription = "Сохранить как…",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
