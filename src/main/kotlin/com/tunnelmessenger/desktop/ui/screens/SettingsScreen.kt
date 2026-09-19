package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.local.AppDirs
import com.tunnelmessenger.desktop.data.update.UpdateState
import com.tunnelmessenger.desktop.data.ws.WsState
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.tray.DesktopIntegrations
import com.tunnelmessenger.desktop.ui.components.APP_VERSION
import com.tunnelmessenger.desktop.ui.components.FilePickers
import com.tunnelmessenger.desktop.ui.components.SmoothExpandFade
import com.tunnelmessenger.desktop.ui.components.formatBytes
import com.tunnelmessenger.desktop.ui.components.humanBytes
import kotlinx.coroutines.launch
import java.awt.Desktop

/**
 * СБОРКА 6: без шапки с крестиком — панель закрывается Esc/кликом мимо
 * (заголовок с ✕ убран по фидбеку). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val account by Repository.account.collectAsState()
    val conn by Repository.conn.collectAsState()
    val tunnelUi by TunnelManager.state.collectAsState()
    val updateState by Repository.updateManager.state.collectAsState()
    // v15: режим темы (авто/тёмная/светлая) — живой Flow, смена применяется сразу
    val themeMode by Repository.themeModeFlow.collectAsState()
    var nickname by remember(account?.nickname) { mutableStateOf(account?.nickname ?: "") }
    var notifEnabled by remember { mutableStateOf(Repository.notificationsEnabled) }
    var notifPreview by remember { mutableStateOf(Repository.notifPreviewEnabled) }
    // СБОРКА 9: фон (трей) и автозапуск
    var backgroundOn by remember { mutableStateOf(Repository.backgroundEnabled) }
    val trayAvailable = remember { DesktopIntegrations.trayAvailable }
    var autostartOn by remember { mutableStateOf(DesktopIntegrations.isAutostartEnabled()) }
    var autostartSupported = remember { DesktopIntegrations.launchCommand() != null }
    var backgroundMsg by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // v8.2: смена ключей E2E и удаление аккаунта
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmDelete1 by remember { mutableStateOf(false) }
    var confirmDelete2 by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // десктоп: оптимизация батареи, разрешение «поверх других окон» и
    // защита от скриншотов не требуются — переключатель скрыт (контракт 2.10)
    var autoImages by remember { mutableStateOf(Repository.autoImagesEnabled) }
    val fedStatus by Repository.fedStatus.collectAsState()

    // размер кэша пересчитывается при каждом входе на экран
    LaunchedEffect(Unit) {
        cacheSize = Repository.localCacheBytes()
    }

    Scaffold { padding ->
        // контент — центральная колонка не шире 720dp (настольный макет);
        // СБОРКА 6: небольшой отступ сверху — раньше его давала шапка с крестиком
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // аккаунт
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Аккаунт", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(account?.username ?: "—", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "сервер: ${account?.baseUrl ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "подключение: " + when (conn) {
                            WsState.CONNECTED -> "онлайн"
                            WsState.CONNECTING -> "подключение…"
                            WsState.RECONNECTING -> "переподключение…"
                            WsState.OFFLINE -> "офлайн"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("Никнейм") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        // v11: кнопка «Сохранить» активна ТОЛЬКО когда никнейм
                        // изменён от уже установленного (и непустой). После
                        // сохранения сразу становится неактивной — понятно,
                        // что сохранение прошло.
                        val currentNick = account?.nickname ?: ""
                        val canSave = nickname.trim().isNotEmpty() &&
                            nickname.trim() != currentNick.trim()
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val res = Repository.setNickname(nickname.trim())
                                    message = res.fold(
                                        onSuccess = { "никнейм сохранён" },
                                        onFailure = { Repository.humanError(it) },
                                    )
                                }
                            },
                            enabled = canSave,
                        ) { Text("Сохранить") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // v15: оформление — тема приложения
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Оформление", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Тема интерфейса",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Авто — выбор темы зависит от темы системы. " +
                            "Ручные режимы: тёмная и светлая.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = themeMode == "auto",
                            onClick = { Repository.themeMode = "auto" },
                            label = { Text("Авто (по системе)") },
                        )
                        FilterChip(
                            selected = themeMode == "dark",
                            onClick = { Repository.themeMode = "dark" },
                            label = { Text("Тёмная") },
                        )
                        FilterChip(
                            selected = themeMode == "light",
                            onClick = { Repository.themeMode = "light" },
                            label = { Text("Светлая") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // уведомления и фон
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Фон и уведомления", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    // СБОРКА 9: работать в фоне (трей) — как foreground-служба на Android
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Работать в фоне")
                            Text(
                                if (trayAvailable) {
                                    "закрытие окна сворачивает приложение в трей — " +
                                        "связь и уведомления продолжают работать"
                                } else {
                                    "системный трей недоступен (например, GNOME без " +
                                        "AppIndicator) — закрытие окна завершает приложение"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = backgroundOn && trayAvailable,
                            enabled = trayAvailable,
                            onCheckedChange = {
                                backgroundOn = it
                                Repository.backgroundEnabled = it
                                if (it) {
                                    DesktopIntegrations.ensureTray(onOpen = {}, onQuit = {})
                                } else {
                                    DesktopIntegrations.removeTray()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Уведомления о сообщениях")
                            Text(
                                // СБОРКА 9: настоящие системные уведомления —
                                // Windows: всплывающие уведомления трея;
                                // Linux: notify-send (GNOME) / balloon трея.
                                "системные уведомления, когда окно в фоне " +
                                    "(свернуто/в трее/без фокуса)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifEnabled, onCheckedChange = {
                            notifEnabled = it
                            Repository.notificationsEnabled = it
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Текст в уведомлениях")
                            Text(
                                "приватность: скрыть текст, отправителя и чат — " +
                                    "останется только «новое сообщение»",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifPreview, onCheckedChange = {
                            notifPreview = it
                            Repository.notifPreviewEnabled = it
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    // СБОРКА 9: автозапуск при входе в систему
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Запускать при входе в систему")
                            Text(
                                when {
                                    !autostartSupported ->
                                        "недоступно для способа запуска (портативный java -jar " +
                                            "поддерживается; запустите из установленного приложения)"
                                    AppDirs.isWindows ->
                                        "добавляет запись в раздел реестра HKCU\\…\\Run " +
                                            "(без прав администратора)"
                                    else ->
                                        "создает файл автозапуска XDG " +
                                            "~/.config/autostart/tunnelmessenger.desktop"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autostartOn,
                            enabled = autostartSupported,
                            onCheckedChange = {
                                val ok = DesktopIntegrations.setAutostartEnabled(it)
                                autostartOn = if (ok) it else !it
                                backgroundMsg = if (ok) {
                                    if (it) "автозапуск включён" else "автозапуск выключен"
                                } else {
                                    "не удалось изменить автозапуск (см. desktop.log)"
                                }
                            },
                        )
                    }
                    backgroundMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Автозагрузка изображений")
                            Text(
                                "миниатюры картинок в чатах (до 15 МБ)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoImages, onCheckedChange = {
                            autoImages = it
                            Repository.autoImagesEnabled = it
                        })
                    }
                    // десктоп: переключатель «Защита от скриншотов» скрыт
                    // (FLAG_SECURE не существует); блок «поверх других окон» и
                    // оптимизация батареи также не требуются
                }
            }

            Spacer(Modifier.height(16.dp))

            // туннель — быстрый статус
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Туннель", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (tunnelUi.status) {
                            "up" -> "подключён (локальный прокси)"
                            "connecting" -> "подключение…"
                            "error" -> "ошибка: ${tunnelUi.note ?: ""}"
                            else -> "выключен"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fedStatus?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "мост федерации: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    account?.maxFileMb?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "лимит файлов на сервере: $it МБ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // история и память: всё локальное живёт в кэше и чистится одной кнопкой
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("История и память", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cacheSize?.let { "Кэш занят: ${humanBytes(it)}" } ?: "Кэш занят: подсчёт…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Все сообщения и скачанные файлы хранятся в локальном кэше " +
                            "приложения: очистка кэша (кнопкой ниже) стирает локальную " +
                            "историю и не засоряет память. Для обычных чатов история " +
                            "подтянется с сервера при следующем входе, P2P и чаты «без " +
                            "истории» будут потеряны навсегда.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = !clearing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (clearing) "Очистка…" else "Очистить историю и кэш") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // аккаунт-файл — в столбик на всю ширину: в одну строку не влезали
            // на узких экранах и кнопки сжимались.
            // десктоп: системные лаунчеры Android заменены на javax.swing.JFileChooser
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val out = FilePickers.saveFile("Экспорт аккаунта", "tunnel-messenger-account.json")
                        if (out != null) {
                            val res = Repository.exportAccount(out)
                            message = res.fold(
                                onSuccess = { "аккаунт экспортирован (файл содержит токен и приватный ключ — храните как пароль)" },
                                onFailure = { "экспорт не удался: ${Repository.humanError(it)}" },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Экспорт аккаунта")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val input = FilePickers.pickFile("Импорт аккаунта", "json")
                        if (input != null) {
                            val res = Repository.importAccount(input)
                            message = res.fold(
                                onSuccess = { "аккаунт импортирован — история и чаты подтянутся с сервера" },
                                onFailure = { "импорт не удался: ${Repository.humanError(it)}" },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Импорт аккаунта")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "перенос с другого устройства/клиента: токен + ключ E2E + локальная " +
                    "история чатов без серверной копии",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // v11: обновление приложения (дистрибутив с сервера)
            AppUpdateCard(state = updateState)

            Spacer(Modifier.height(16.dp))

            // v11: заблокированные пользователи
            OutlinedButton(
                onClick = onOpenBlocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.Block,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Заблокированные пользователи")
            }

            // v8.2: смена ключей шифрования — карточка рядом с экспортом/выходом
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Шифрование", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Новые сообщения будут шифроваться новым ключом. Собеседники получат " +
                            "его автоматически. Старая история на этом устройстве останется читаемой.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { confirmRotate = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сменить ключи шифрования") }
                }
            }

            // v12 anim: сообщение о результате операции появляется/уходит плавно
            // (последний непустой текст держим, чтобы exit-анимация не схлопнула контент в ноль)
            var lastMessage by remember { mutableStateOf<String?>(null) }
            message?.let { lastMessage = it }
            SmoothExpandFade(visible = message != null) {
                lastMessage?.let { shown ->
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(shown, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Выйти из аккаунта") }

            // v8.2: безвозвратное удаление аккаунта (двойное подтверждение)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmDelete1 = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Удалить аккаунт") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Tunnel Messenger для десктопа · нативный клиент сервера Tunnel Messenger " +
                    "(HTTP+WS строго внутри защищённого туннеля, E2E: NaCl box, файлы: TME1/AES-256-GCM)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { if (!clearing) confirmClear = false },
                title = { Text("Очистить историю?") },
                text = {
                    Text(
                        "Будут удалены все локальные сообщения, чаты «без истории», P2P-переписка " +
                            "и скачанные файлы. Учётная запись останется: обычные чаты синхронизируются " +
                            "с сервера заново. Действие необратимо для P2P."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !clearing,
                        onClick = {
                            clearing = true
                            scope.launch {
                                val res = Repository.clearLocalHistory()
                                message = res.fold(
                                    onSuccess = { "история и кэш очищены" },
                                    onFailure = { "очистка не удалась: ${Repository.humanError(it)}" },
                                )
                                cacheSize = Repository.localCacheBytes()
                                clearing = false
                            }
                            confirmClear = false
                        },
                    ) { Text("Очистить") }
                },
                dismissButton = {
                    TextButton(enabled = !clearing, onClick = { confirmClear = false }) { Text("Отмена") }
                },
            )
        }

        if (confirmLogout) {
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text("Выйти?") },
                text = { Text("Локальный кэш будет очищен. Экспортируйте аккаунт, если хотите сохранить доступ к истории.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout = false
                        scope.launch {
                            Repository.logout()
                            onLoggedOut()
                        }
                    }) { Text("Выйти") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
                },
            )
        }

        // v8.2: смена пары ключей E2E — подтверждение с честным предупреждением
        if (confirmRotate) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmRotate = false },
                title = { Text("Сменить ключи шифрования?") },
                text = {
                    Text(
                        "Новые сообщения будут шифроваться новым ключом. Собеседники получат " +
                            "его автоматически. Старая история на текущем устройстве останется читаемой."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            confirmRotate = false
                            scope.launch {
                                val res = Repository.rotateKeys()
                                message = res.fold(
                                    onSuccess = { "ключи шифрования обновлены" },
                                    onFailure = { "смена ключей не удалась: ${Repository.humanError(it)}" },
                                )
                                busy = false
                            }
                        },
                    ) { Text("Сменить") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { confirmRotate = false }) { Text("Отмена") }
                },
            )
        }

        // v8.2: удаление аккаунта — ДВОЙНОЕ подтверждение, необратимо
        if (confirmDelete1) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmDelete1 = false },
                title = { Text("Удалить аккаунт навсегда?") },
                text = {
                    Text(
                        "Чаты, история и профиль будут стёрты с сервера. " +
                            "Восстановить невозможно."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            confirmDelete1 = false
                            confirmDelete2 = true
                        },
                    ) { Text("Продолжить") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { confirmDelete1 = false }) { Text("Отмена") }
                },
            )
        }
        if (confirmDelete2) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmDelete2 = false },
                title = { Text("Последнее подтверждение") },
                text = {
                    Text(
                        "Аккаунт и все данные на сервере будут удалены БЕЗВОЗВРАТНО: " +
                            "чаты, сообщения, файлы, группы, которыми вы владели. " +
                            "Вы выйдете из аккаунта на этом устройстве."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            confirmDelete2 = false
                            scope.launch {
                                val res = Repository.deleteAccount()
                                res.fold(
                                    onSuccess = {
                                        onLoggedOut()   // выход выполнен, локальные данные стёрты
                                    },
                                    onFailure = {
                                        message = "удаление аккаунта не удалось: ${Repository.humanError(it)}"
                                    },
                                )
                                busy = false
                            }
                        },
                    ) { Text("Удалить навсегда") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { confirmDelete2 = false }) { Text("Отмена") }
                },
            )
        }
        } // Box (центральная колонка)
    } // Scaffold-контент
}

/**
 * v11: карточка «Обновление приложения» (порт Android AppUpdateCard).
 *
 * Текущая версия, кнопка «Проверить обновления», при наличии новой версии —
 * её номер, размер, кнопки «Скачать и установить» / «Открыть папку» и
 * «Что нового?» (диалог с чейнжлогом релиза). Во время загрузки —
 * LinearProgressIndicator с процентом и счётчиком байт. На ошибке — красный
 * текст. Если дистрибутив уже скачан в кэш — кнопка «Установить из кэша».
 */
@Composable
private fun AppUpdateCard(state: UpdateState) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Обновление приложения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Текущая версия: $APP_VERSION",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    // v11: silent=false — при ручной проверке показываем ошибки
                    scope.launch { Repository.updateManager.checkForUpdates(silent = false) }
                },
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка…")
                } else {
                    Text("Проверить обновления")
                }
            }

            // Ошибка сети/сервера
            state.lastError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // Доступна новая версия
            if (state.updateAvailable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Доступна новая версия: v${state.latestVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (state.apkSize > 0) {
                    Text(
                        "Размер: ${formatBytes(state.apkSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Прогресс загрузки (процент считаем из байтов — на десктопе
                // в UpdateState нет progressPercent)
                if (state.isDownloading) {
                    val pct = if (state.totalBytes > 0) {
                        ((state.downloadedBytes * 100) / state.totalBytes).toInt().coerceIn(0, 100)
                    } else -1
                    if (pct >= 0) {
                        LinearProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Скачано ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}" +
                            if (pct >= 0) " · $pct %" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = Repository.updateManager.downloadAndInstall()
                                    if (res.isFailure) {
                                        // ошибка уже отражена в state.lastError
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Скачать и установить")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                // десктоп-«установка»: открыть папку с дистрибутивом
                                // (запуск установщика НЕ автоматический — контракт 2.9)
                                runCatching {
                                    val dir = AppDirs.updatesDir
                                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Открыть папку")
                        }
                    }
                    // если дистрибутив уже скачан в кэш — переустановка без скачивания
                    if (state.apkFile?.exists() == true) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch { Repository.updateManager.reinstallFromCache() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Установить из кэша (без скачивания)") }
                    }
                }

                // «Что нового?» — чейнжлог релиза (Updates/<имя>.txt на
                // сервере рядом с дистрибутивом; текст также приходит в /api/updates/check).
                var showChangelog by remember { mutableStateOf(false) }
                var changelogLoading by remember { mutableStateOf(false) }
                var changelogText by remember { mutableStateOf<String?>(null) }
                var changelogError by remember { mutableStateOf<String?>(null) }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        showChangelog = true
                        if (changelogText == null && changelogError == null && !changelogLoading) {
                            changelogLoading = true
                            scope.launch {
                                val txt = Repository.updateManager.fetchChangelog()
                                changelogLoading = false
                                if (txt != null) {
                                    changelogText = txt
                                    changelogError = null
                                } else {
                                    changelogError = "чейнжлог недоступен: файла нет на сервере " +
                                        "или нет связи (проверьте туннель)"
                                }
                            }
                        }
                    },
                    enabled = !state.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Что нового?")
                }

                if (showChangelog) {
                    AlertDialog(
                        onDismissRequest = { showChangelog = false },
                        title = { Text("Что нового — v${state.latestVersion}") },
                        text = {
                            val err = changelogError
                            when {
                                changelogLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Загрузка…", style = MaterialTheme.typography.bodySmall)
                                }
                                err != null -> Text(
                                    err,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                else -> Text(
                                    changelogText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .heightIn(max = 420.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showChangelog = false }) { Text("Закрыть") }
                        },
                    )
                }
            }

            // Установщик/папка открыты — подсказка, что можно переустановить из кэша
            if (state.installerLaunched && !state.isDownloading) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Папка с дистрибутивом открыта. Запустите установку вручную — " +
                        "файл остаётся в кэше, качать заново не нужно.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
