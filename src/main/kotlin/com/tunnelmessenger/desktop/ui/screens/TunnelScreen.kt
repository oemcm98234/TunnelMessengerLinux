@file:Suppress("SpellCheckingInspection") // AmneziaWG, freeturn, netstack, VpnService — не опечатки
package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.tunnel.FreeTurnManager
import com.tunnelmessenger.desktop.tunnel.ProtocolMode
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.ui.components.FilePickers
import com.tunnelmessenger.desktop.ui.components.HoverTooltip
import com.tunnelmessenger.desktop.ui.components.copyToClipboard
import com.tunnelmessenger.desktop.ui.components.formatBytes
import com.tunnelmessenger.desktop.ui.components.humanizeHandshake
import com.tunnelmessenger.desktop.ui.components.pressScale
import com.tunnelmessenger.desktop.ui.theme.accentTextColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Управление встроенным туннелем (порт Android TunnelScreen.kt).
 *
 * Режим «локальный прокси»: userspace-движок (дочерний процесс
 * tunnel-core / freeturn-client) поднимает туннель целиком внутри приложения,
 * системный VPN не включается. Трафик мессенджера уходит в туннель через
 * собственный SOCKS5-прокси (127.0.0.1) со случайными учётными данными.
 *
 * Отличия от Android-версии (по контракту 2.4):
 *  - статус/статистика из TunnelManager.state (TunnelState), журнал — TunnelManager.log;
 *  - подключение — TunnelManager.startWithConf(текст .conf), отключение — stopEngine();
 *  - режим хранится ядром (config.json), выбор — TunnelManager.setMode(ProtocolMode);
 *  - FreeTurn — блок настроек 1:1 как на Android (Применить URI → Сохранить),
 *    подключение — общей кнопкой «Подключить» по сохранённому конфигу;
 *    капча VK открывается в системном браузере (java.awt.Desktop.browse),
 *    WebView не используется;
 *  - импорт .conf — javax.swing.JFileChooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(
    onBack: () -> Unit,
    // СБОРКА 6: шапка с крестиком убрана — панель закрывается Esc/кликом мимо.
) {
    val tunnel by TunnelManager.state.collectAsState()
    val engineLog by TunnelManager.log.collectAsState()
    // СБОРКА 8: отдельные кнопки «Подключить FreeTurn»/«Остановить релей» УБРАНЫ
    // (на Android их нет) — подключение ТОЛЬКО общей кнопкой «Подключить»,
    // конфиг — как на Android: «Применить URI» → «Сохранить настройки FreeTurn».
    // СБОРКА 5 (фикс): поле конфига СЕЕДИТСЯ сохранённым .conf (TunnelManager
    // хранит его sealed в config.json). Раньше поле всегда начиналось пустым —
    // выглядело так, будто конфиг не сохранён, хотя туннель работал.
    var confDraft by remember { mutableStateOf(TunnelManager.confText() ?: "") }
    var confError by remember { mutableStateOf<String?>(null) }
    var healthResult by remember { mutableStateOf<String?>(null) }
    var healthOk by remember { mutableStateOf<Boolean?>(null) }
    var editingConf by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Текущий режим: СБОРКА 9 (фикс) — читаем СОХРАНЁННЫЙ режим из config.json
    // (TunnelManager.persistedMode()). Раньше режим брали из TunnelState.mode,
    // который до первого подключения = "off" — выбор FreeTurn/AmneziaWG
    // «забывался» при каждом запуске приложения и подключение шло по Авто.
    var selectedMode by remember {
        mutableStateOf(
            when (TunnelManager.state.value.mode) {
                "amnezia" -> ProtocolMode.AMNEZIA
                "freeturn", "freelay" -> ProtocolMode.FREELAY
                "auto" -> ProtocolMode.AUTO
                else -> TunnelManager.persistedMode()
            }
        )
    }
    fun connect() {
        // СБОРКА 8 (точь-в-точь Android): FreeTurn подключается по СОХРАНЁННОМУ
        // конфигу (URI применяется кнопкой «Применить URI», ссылки/потоки —
        // «Сохранить настройки FreeTurn»). Отдельной кнопки подключения релея нет.
        if (selectedMode == ProtocolMode.FREELAY) {
            val cfg = FreeTurnManager.config
            when {
                cfg.clientId.isBlank() ->
                    confError = "примените freeturn:// URI в настройках FreeTurn"
                cfg.vkLinks.isEmpty() ->
                    confError = "добавьте ссылки звонка VK в настройках FreeTurn"
                else -> {
                    confError = null
                    scope.launch { FreeTurnManager.startSaved(preconnectOnly = false) }
                }
            }
        } else {
            if (confDraft.isBlank()) {
                confError = "нет конфига: импортируйте .conf или вставьте его текст"
            } else {
                confError = null
                TunnelManager.startWithConf(confDraft).fold(
                    onSuccess = {},
                    onFailure = { confError = it.message ?: "ошибка конфига" },
                )
            }
        }
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
            // ------- статус
            // v12 anim: цвет карточки статуса перетекает плавно (tween 250)
            val statusContainer by animateColorAsState(
                targetValue = when (tunnel.status) {
                    "up" -> MaterialTheme.colorScheme.primaryContainer
                    "connecting" -> MaterialTheme.colorScheme.surfaceVariant
                    "error" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = tween(250),
                label = "tunnelStatusColor",
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = statusContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // v12 anim: появление/скрытие ошибки и статистики без рывка
                Column(
                    Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                ) {
                    // v12 anim: текст статуса сменяется мягко (fade + slide по вертикали)
                    AnimatedContent(
                        targetState = tunnel.status,
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 6 })
                                .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 })
                        },
                        label = "tunnelStatusText",
                    ) { status ->
                        Text(
                            when (status) {
                                // v8.2: статусы «запущен/не запущен» вместо «подключён/отключён»
                                "up" -> "Запущен"
                                "connecting" -> "Запуск…"
                                "error" -> "Ошибка"
                                else -> "Не запущен"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    tunnel.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (tunnel.status == "up" || tunnel.rxBytes > 0 || tunnel.txBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "↓ ${formatBytes(tunnel.rxBytes)}   ↑ ${formatBytes(tunnel.txBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            // СБОРКА 14: движок шлёт последний handshake (unix-сек), а не счётчик —
                            // вместо «рукопожатий: 1789675009» показываем по-человечески (как Android).
                            humanizeHandshake(tunnel.lastHandshakeMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        if (tunnel.status == "up") {
                            // v12 anim: кнопка питания — press-scale (пружина)
                            val disconnectInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = {
                                    TunnelManager.stopEngine()
                                    FreeTurnManager.stop()
                                },
                                interactionSource = disconnectInteraction,
                                modifier = Modifier.pressScale(disconnectInteraction),
                            ) { Text("Отключить") }
                        } else {
                            // v12 anim: кнопка подключения — press-scale (пружина)
                            val connectInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = { connect() },
                                enabled = tunnel.status != "connecting",
                                interactionSource = connectInteraction,
                                modifier = Modifier.pressScale(connectInteraction),
                            ) { Text("Подключить") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // v11: выбор протокола туннеля (Авто / AmneziaWG / FreeTurn)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Протокол туннеля",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    // v12: только текущий режим — без объяснений, когда и
                    // почему происходит переключение
                    Text(
                        "Текущий режим: ${modeTitle(selectedMode)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    ProtocolMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    TunnelManager.setMode(mode)
                                    // Если туннель активен — переподключаемся с новым режимом
                                    if (tunnel.status == "up" || tunnel.status == "connecting") {
                                        TunnelManager.stopEngine()
                                        FreeTurnManager.stop()
                                        scope.launch {
                                            delay(500)
                                            connect()
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(modeTitle(mode), fontWeight = FontWeight.SemiBold)
                                Text(
                                    modeDescription(mode),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------- AmneziaWG: импорт/редактирование .conf (первый блок настроек)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Настройки AmneziaWG",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { editingConf = !editingConf }) {
                            Text(if (editingConf) "скрыть" else "изменить")
                        }
                    }
                    Text(
                        when (tunnel.mode) {
                            "amnezia" -> "активный режим: AmneziaWG"
                            "freeturn" -> "активный режим: FreeTurn"
                            "auto" -> "активный режим: Авто"
                            else -> "движок остановлен"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (editingConf) {
                        Text(
                            "Вставьте .conf из AmneziaVPN (секции [Interface]/[Peer]). " +
                                "Поддерживаются протоколы AWG 1.5 (Jc/Jmin/Jmax/S1/S2/H1–H4), " +
                                "AWG 2.0 (S3/S4/I1–I5) и AWG 3.x (HeaderProtectionKey, " +
                                "ContentPaddingAddition, RandomTrailers, …)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confDraft,
                            onValueChange = { confDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            placeholder = { Text("[Interface]\nPrivateKey = ...\nAddress = 10.10.10.2/32\nJc = 4\n...\n[Peer]\nEndpoint = ...") },
                        )
                        confError?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                // десктоп: текст конфига подаётся движку при
                                // подключении (TunnelManager.startWithConf)
                                if (confDraft.isBlank()) {
                                    confError = "конфиг пустой"
                                } else {
                                    confError = null
                                    editingConf = false
                                }
                            }) { Text("Сохранить") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val f = FilePickers.pickFile("Импорт конфига туннеля", "conf", "txt")
                                    if (f != null) {
                                        val text = runCatching { f.readText() }.getOrNull()
                                        if (text != null) {
                                            confDraft = text
                                            confError = null
                                        } else {
                                            confError = "не удалось прочитать файл"
                                        }
                                    }
                                }
                            }) {
                                Text("Из файла")
                            }
                        }
                    } else {
                        TextButton(onClick = { editingConf = true }) { Text("Открыть редактор конфига") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // СБОРКА 8: настройки FreeTurn — ПОРТ ANDROID 1:1 (ui/screens/TunnelScreen.kt):
            // «Применить URI» разбирает URI и ПОДСТАВЛЯЕТ значения в форму,
            // «Сохранить настройки FreeTurn» пишет конфиг. Отдельных кнопок
            // «Подключить FreeTurn»/«Остановить релей» нет (на Android их нет) —
            // подключение выполняет общая кнопка «Подключить» выше.
            if (selectedMode == ProtocolMode.FREELAY || selectedMode == ProtocolMode.AUTO) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Настройки FreeTurn",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "UDP/TCP поверх TURN через WebRTC-реле (VK Calls), " +
                                "серверный бэкенд AmneziaWG. Трафик маскируется " +
                                "под медиа-трафик ВК-звонка (RTP/OPUS AEAD).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        // СБОРКА 9: капча VK решается ВСТРОЕННЫМ WebView (диалог
                        // открывается автоматически). Если пользователь скрыл
                        // диалог («решу позже») — открыть снова отсюда.
                        val captchaActive by FreeTurnManager.captchaUrl.collectAsState()
                        val captchaHidden by FreeTurnManager.captchaHidden.collectAsState()
                        if (captchaActive != null && captchaHidden) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "⚠ Требуется капча VK",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { FreeTurnManager.showCaptchaDialog() }) {
                                    Text("Открыть окно капчи")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // v11: форма ужата до 3 полей: URI/VK-ссылки/потоки.
                        // Все остальные параметры (peer/transport/mode/dns/obf/client_id)
                        // приходят из URI — его генерирует сервер при `freeturn-install.sh
                        // client-add <name>` и даёт пользователю.
                        var ftCfg by remember { mutableStateOf(FreeTurnManager.config) }
                        var ftUriText by remember { mutableStateOf("") }
                        // v12: ошибка импорта URI — ТОЛЬКО в блоке FreeTurn (не путать
                        // с confError редактора конфига Amnezia)
                        var ftUriError by remember { mutableStateOf<String?>(null) }
                        var ftVkLinks by remember(ftCfg) {
                            mutableStateOf(ftCfg.vkLinks.joinToString("\n"))
                        }
                        var ftStreams by remember(ftCfg) { mutableStateOf(ftCfg.streams.toString()) }
                        // Валидация VK-ссылок: только https://vk.ru/call/join/ или https://vk.com/call/join/
                        val vkLinkPattern = Regex("^https://(vk\\.ru|vk\\.com)/call/join/[A-Za-z0-9_-]+$")

                        // 1) Поле «Импорт freeturn:// URI» — основное. Вставляется
                        //    строка, полученная от админа сервера (через `client-add`).
                        OutlinedTextField(
                            value = ftUriText,
                            onValueChange = { ftUriText = it },
                            label = { Text("Импорт freeturn:// URI") },
                            placeholder = { Text("freeturn://eyJwZWVyIjoiMS4yLjMuNDo1...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    val parsed = FreeTurnManager.parseUri(ftUriText.trim())
                                    if (parsed == null) {
                                        ftUriError = "неверный формат freeturn:// URI"
                                    } else {
                                        // URI содержит peer/mode/transport/obf_profile/obf_key/client_id.
                                        // VK-ссылки пользователь вводит отдельно — их не трогаем.
                                        // Потоки: берём из URI (если есть), иначе сохраняем текущие.
                                        ftCfg = ftCfg.copy(
                                            peer = parsed.peer,
                                            listen = parsed.listen,
                                            provider = parsed.provider,
                                            transport = parsed.transport,
                                            mode = parsed.mode,
                                            dnsServers = parsed.dnsServers,
                                            clientId = parsed.clientId,
                                            obfProfile = parsed.obfProfile,
                                            obfKey = parsed.obfKey,
                                            streams = if (parsed.streams > 0) parsed.streams else ftCfg.streams,
                                        )
                                        ftStreams = ftCfg.streams.toString()
                                        ftUriError = null
                                    }
                                },
                                enabled = ftUriText.startsWith("freeturn://"),
                            ) { Text("Применить URI") }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "URI содержит peer/mode/obf-ключ/Client ID " +
                                    "(генерируется на сервере при создании клиента).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Индикатор: URI применён (показывает clientId — частично, для контроля)
                        if (ftCfg.clientId.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "✓ URI применён. Client ID: ${ftCfg.clientId.take(8)}…  " +
                                    "Peer: ${ftCfg.peer.ifBlank { "—" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentTextColor(),
                            )
                        }
                        // Ошибка импорта URI — здесь же, в блоке FreeTurn
                        ftUriError?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        // 2) Поле «VK-ссылки звонка» — единственное ручное поле.
                        //    Валидация: до 4 штук, формат https://vk.ru/call/join/...
                        OutlinedTextField(
                            value = ftVkLinks,
                            onValueChange = { newValue: String ->
                                // Ограничение: не больше 4 строк + 200 символов на строку
                                // (чтобы нельзя было навредить огромным вводом).
                                val lines = newValue.lines().take(4)
                                val filtered = lines.joinToString("\n") { line ->
                                    line.take(200)
                                }
                                ftVkLinks = filtered
                            },
                            label = { Text("Ссылки звонка VK (обязательно, до 4 шт.)") },
                            placeholder = { Text("https://vk.ru/call/join/<hash>\nпо одной на строку") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Подсказка + счётчик валидных ссылок
                        val vkLinksParsed = ftVkLinks.lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        val vkValid = vkLinksParsed.count { vkLinkPattern.matches(it) }
                        val vkInvalid = vkLinksParsed.count { !vkLinkPattern.matches(it) && it.isNotBlank() }
                        Text(
                            "Валидных: $vkValid из ${vkLinksParsed.size}" +
                                if (vkInvalid > 0) "  ⚠ $vkInvalid не похоже на VK Calls ссылку" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vkInvalid > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(12.dp))
                        // 3) Поле «Число потоков» — параллельные TURN-сессии.
                        //    Hard cap 1-25 (предел разумного для VK Calls — больше
                        //    перегрузит сервер и TURN-relay, не даст реального прироста).
                        val streamsParsed = ftStreams.toIntOrNull()
                        val streamsValid = streamsParsed != null && streamsParsed in 1..25
                        OutlinedTextField(
                            value = ftStreams,
                            onValueChange = { s: String ->
                                // Только цифры, не больше 2 символов (максимум 25 — 2 знака)
                                val digits = s.filter { it.isDigit() }.take(2)
                                ftStreams = digits
                            },
                            label = { Text("Число потоков (1-25)") },
                            placeholder = { Text("17") },
                            singleLine = true,
                            isError = !streamsValid && ftStreams.isNotBlank(),
                            supportingText = {
                                Text(
                                    "Рекомендуется: 17 (больше = выше нагрузка на сервер, " +
                                        "но и пропускная способность выше. Лимит: 1-25.)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(8.dp))
                        // Кнопка «Сохранить» активна когда:
                        // - URI применён (clientId не пустой) И
                        // - есть хотя бы одна валидная VK-ссылка И
                        // - streams валидны (1-25) И
                        // - есть ИЗМЕНЕНИЯ относительно сохранённого состояния
                        //   (без этого кнопка оставалась бы активной после save)
                        val savedVkJoined = ftCfg.vkLinks.joinToString("\n").trim()
                        val currentVkJoined = ftVkLinks.trim()
                        val savedStreams = ftCfg.streams
                        val currentStreamsParsed = ftStreams.toIntOrNull() ?: -1
                        val hasChanges = currentVkJoined != savedVkJoined ||
                            currentStreamsParsed != savedStreams
                        val canSave = ftCfg.clientId.isNotBlank() &&
                            vkValid >= 1 &&
                            streamsValid &&
                            hasChanges
                        Button(
                            onClick = {
                                val links = vkLinksParsed.filter { vkLinkPattern.matches(it) }.take(4)
                                val streamsFinal = (ftStreams.toIntOrNull() ?: 17).coerceIn(1, 25)
                                FreeTurnManager.saveConfig(
                                    ftCfg.copy(
                                        vkLinks = links,
                                        streams = streamsFinal,
                                    ),
                                )
                                // После save: обновляем ftCfg из сохранённого
                                // состояния — hasChanges станет false, кнопка
                                // сразу деактивируется.
                                ftCfg = FreeTurnManager.config
                                ftVkLinks = ftCfg.vkLinks.joinToString("\n")
                                ftStreams = ftCfg.streams.toString()
                            },
                            enabled = canSave,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Сохранить настройки FreeTurn") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ------- локальный прокси
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Локальный прокси", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Трафик мессенджера идёт в туннель через SOCKS5 на 127.0.0.1. " +
                            "Логин и пароль генерируются случайно при каждом запуске — " +
                            "посторонние приложения прокси использовать не могут.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // десктоп: креды прокси выдаёт движок (TunnelManager.currentProxy);
                    // ротация выполняется ядром при перезапуске — отдельной кнопки нет
                    TunnelManager.currentProxy?.let { proxy ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Порт: ${proxy.port} · пользователь: ${proxy.user}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ------- проверка связи
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Проверка сервера", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val server = Repository.account.collectAsState().value?.baseUrl ?: ""
                    Text(
                        if (server.isBlank()) "аккаунт ещё не настроен" else server,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            healthResult = "проверяем…"
                            val res = Repository.checkHealth(server.ifBlank { "http://10.10.10.1:80" })
                            healthOk = res.isSuccess
                            healthResult = res.fold(
                                onSuccess = { d -> "✓ сервер доступен из туннеля (домен: $d)" },
                                onFailure = { "✗ недоступен: ${Repository.humanError(it)}" },
                            )
                        }
                    }) { Text("Проверить связь") }
                    healthResult?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (healthOk) {
                                true -> MaterialTheme.colorScheme.tertiary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // ------- журнал движка
            if (engineLog.isNotEmpty() || tunnel.status == "error") {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Журнал движка", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            // v11: кнопка «Копировать лог» (десктоп: java.awt.Toolkit)
                            if (engineLog.isNotEmpty()) {
                                TextButton(onClick = {
                                    runCatching {
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                            StringSelection(engineLog.joinToString("\n")),
                                            null,
                                        )
                                    }
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Копировать", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (engineLog.isEmpty()) {
                            Text(
                                "Журнал пуст — движок завершился, ничего не записав.\n" +
                                    "Причина ошибки показана в статусе выше.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(
                                Modifier
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                engineLog.forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Как это работает: userspace-движок поднимает шифрованный туннель " +
                    "целиком внутри приложения — собственный сетевой стек вместо VpnService, поэтому " +
                    "системный VPN не включается, другие приложения не затронуты. Приложение направляет " +
                    "свой трафик в собственный локальный SOCKS5-прокси (127.0.0.1, случайный логин " +
                    "и пароль при каждом запуске), а прокси отправляет его через туннель. " +
                    "DNS-запросы тоже идут через туннель (fail-closed: без туннеля связи нет).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

/** Человекочитаемое имя режима (на десктопе enum без displayName). */
private fun modeTitle(mode: ProtocolMode): String = when (mode) {
    ProtocolMode.AUTO -> "Авто"
    ProtocolMode.AMNEZIA -> "AmneziaWG"
    ProtocolMode.FREELAY -> "FreeTurn"
}

/** Краткое описание режима (как ProtocolMode.displayName/description в Android). */
private fun modeDescription(mode: ProtocolMode): String = when (mode) {
    ProtocolMode.AUTO -> "Оба протокола, выбирается рабочий"
    ProtocolMode.AMNEZIA -> "Прямой туннель AmneziaWG"
    ProtocolMode.FREELAY -> "Через TURN-релей (VK Calls)"
}
