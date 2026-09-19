package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.call.CallManager
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.UiMsg
import com.tunnelmessenger.desktop.data.model.Chat
import com.tunnelmessenger.desktop.data.model.UserShort
import com.tunnelmessenger.desktop.data.model.VoiceMeta
import com.tunnelmessenger.desktop.data.ws.WsState
import com.tunnelmessenger.desktop.ui.components.Avatar
import com.tunnelmessenger.desktop.ui.components.HoverTooltip
import com.tunnelmessenger.desktop.ui.components.SaveMediaAsButton
import com.tunnelmessenger.desktop.ui.theme.accentTextColor
import com.tunnelmessenger.desktop.ui.components.ImageThumb
import com.tunnelmessenger.desktop.ui.components.SmoothExpandFade
import com.tunnelmessenger.desktop.ui.components.VoicePlayer
import com.tunnelmessenger.desktop.ui.components.VoiceRecorder
import com.tunnelmessenger.desktop.ui.components.copyToClipboard
import com.tunnelmessenger.desktop.ui.components.formatBytes
import com.tunnelmessenger.desktop.ui.components.formatFullTs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

/**
 * десктоп: прежний мобильный экран чата — тонкая обёртка над [ChatPane]
 * для узкого режима (<1000dp): панель чата на всю ширину с кнопкой «✕».
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    chatId: Long,
    onOpenChat: (Long) -> Unit = {},
) {
    ChatPane(
        chatId = chatId,
        onClose = onBack,
        onOpenChat = onOpenChat,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * десктоп: панель чата для master-detail (и для узкого режима).
 *
 * Та же логика, что у прежнего ChatScreen (пузыри, реакции, reply, меню,
 * голосовые, файлы, MediaDialog, звонок, typing, догрузка истории), но:
 *  - тулбар 56dp (аватар/имя/статус + звонок/файл/поиск/меню), без back-стрелки —
 *    в master-detail чат закрывается кнопкой «✕» (onClose), в узком режиме тоже;
 *  - пузыри занимают не более 68% ширины панели;
 *  - поле ввода многострочное: Enter — отправить, Shift+Enter — новая строка;
 *  - контекстное меню сообщения открывается и правым кликом (detectTapGestures
 *    обрабатывает любой клик), и long-press — как раньше.
 *
 * @param chatId    открытый чат (Repository.openChat вызывается эффектом ниже)
 * @param onClose   закрыть панель (сбросить выбор чата в оболочке)
 * @param onOpenChat открыть другой чат (переключение режима чата)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPane(
    chatId: Long,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onOpenChat: (Long) -> Unit = {},
) {
    val chats by Repository.chats.collectAsState()
    val messages by Repository.messages.collectAsState()
    val typingMap by Repository.typing.collectAsState()
    val conn by Repository.conn.collectAsState()
    val account by Repository.account.collectAsState()
    val blocks by Repository.blocks.collectAsState()
    val scope = rememberCoroutineScope()
    // v12: режим хвостовой кнопки ввода: false = стрелка отправки (по умолчанию),
    // true = микрофон; переключение — ДОЛГИМ нажатием (стрелка ↔ микрофон)
    var voiceMode by remember { mutableStateOf(false) }
    // v12: панель эмодзи над нижним баром (открывается кнопкой возле стрелки/микрофона)
    var emojiOpen by remember { mutableStateOf(false) }

    val chat = chats.firstOrNull { it.id == chatId }
    var input by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<UiMsg?>(null) }
    var editing by remember { mutableStateOf<UiMsg?>(null) }
    var replyTo by remember { mutableStateOf<UiMsg?>(null) } // v11: ответ на сообщение
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var busyFile by remember { mutableStateOf(false) }
    var sendProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // v11: мультивыбор сообщений
    var selectedMsgIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var msgSelectionMode by remember { mutableStateOf(false) }
    // v11: блокировка собеседника из чата
    var confirmBlock by remember { mutableStateOf(false) }

    // активность отправки — общий критерий для кнопки-стрелки и Enter
    val sendEnabled = input.isNotBlank() && (conn == WsState.CONNECTED || chat?.is_p2p == true)

    /**
     * общая отправка/правка текста — для кнопки-стрелки И для Enter в поле
     * ввода (Enter = отправить, Shift+Enter = новая строка, см. onPreviewKeyEvent).
     */
    fun submitInput() {
        val c = chat ?: return
        if (!sendEnabled || busyFile || voiceMode) return
        val editingMsg = editing
        if (editingMsg != null) {
            scope.launch {
                val res = Repository.editMessage(c, editingMsg, input)
                res.fold(
                    onSuccess = { input = ""; editing = null; error = null },
                    onFailure = { error = Repository.humanError(it) },
                )
            }
        } else {
            val text = input
            input = ""
            // v11: фиксируем replyTo до отправки и сразу чистим
            // баннер — он вернётся в onFailure, если отправка не вышла.
            val reply = replyTo
            replyTo = null
            scope.launch {
                val res = Repository.sendText(c, text, replyTo = reply)
                res.fold(onSuccess = { error = null }, onFailure = { e ->
                    error = Repository.humanError(e)
                    input = text // вернуть текст в поле
                    replyTo = reply // вернуть баннер ответа
                })
            }
        }
    }

    // запись голосового (десктоп: пермиссия на микрофон не требуется)
    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var recTick by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        while (recording) { recTick++; kotlinx.coroutines.delay(300) }
    }

    // меню «⋮», поиск, участники, профиль, подтверждения
    var menuOpen by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var membersOpen by remember { mutableStateOf(false) }
    var memberAddr by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<UserShort?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // v8: галерея чата
    var mediaOpen by remember { mutableStateOf(false) }

    // v8: мультивыбор файлов — выбор нескольких файлов сразу, отправка по одному.
    // v8.x: отправка в Repository.launchSend — выход из чата НЕ отменяет загрузку.
    // (На Android был rememberLauncherForActivityResult(OpenMultipleDocuments);
    // на десктопе — javax.swing.JFileChooser, см. FilePickers.)
    fun pickAndSendFiles() {
        val c = chat ?: return
        scope.launch {
            val files = com.tunnelmessenger.desktop.ui.components.FilePickers.pickFiles("Отправить файлы")
            if (files.isNotEmpty()) {
                busyFile = true
                Repository.launchSend {
                    var lastError: Throwable? = null
                    var sent = 0
                    for (f in files) {
                        val res = Repository.sendFile(c, f) { done, total -> sendProgress = done to total }
                        res.fold(
                            onSuccess = { sent++ },
                            onFailure = { lastError = it },
                        )
                    }
                    busyFile = false
                    sendProgress = null
                    // v8: частичный сбой при мультиотправке НЕ скрываем — сообщаем,
                    // сколько файлов ушло и почему остальные не отправились
                    lastError?.let {
                        error = if (sent > 0)
                            "отправлено $sent из ${files.size}: " + Repository.humanError(it)
                        else Repository.humanError(it)
                    }
                }
            }
        }
    }

    // Чат открыт ТОЛЬКО пока этот экран в композиции: при выходе в список
    // чатов активный чат сбрасывается — иначе новые сообщения «ложно
    // прочитывались» и не приходили уведомления.
    DisposableEffect(chatId) {
        onDispose {
            Repository.openChat(null)
            // v12 audit: покидаем экран — глушим запись и плеер
            if (recorder.isRecording) recorder.cancel()
            VoicePlayer.stop()
        }
    }
    LaunchedEffect(chatId) {
        Repository.openChat(chatId)
    }
    // Назад — только когда список чатов уже загружен и чата в нём нет:
    // иначе на холодном старте экран мигнёт «чат не найден».
    LaunchedEffect(chat?.id, chats.isEmpty()) {
        if (chat == null && chats.isNotEmpty()) onClose?.invoke()
    }

    // Панель чата: тулбар 56dp + содержимое. Фон — единый background темы
    // (СБОРКА 5: один и тот же фон во всех экранах, как на Android).
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatPaneToolbar(
            chat = chat,
            chatId = chatId,
            typingMap = typingMap,
            msgSelectionMode = msgSelectionMode,
            selectedCount = selectedMsgIds.size,
            canDeleteAll = chat != null && chat.history && !chat.is_p2p &&
                messages.any { it.mid in selectedMsgIds } &&
                messages.filter { it.mid in selectedMsgIds }
                    .all { it.isMine && it.type != "deleted" },
            onClose = onClose,
            onExitSelection = {
                msgSelectionMode = false
                selectedMsgIds = emptySet()
            },
            onDeleteSelected = {
                val c = chat ?: return@ChatPaneToolbar
                val toDelete = messages.filter { it.mid in selectedMsgIds }
                scope.launch {
                    var lastError: Throwable? = null
                    var done = 0
                    for (m in toDelete) {
                        val res = Repository.unsendMessage(c, m)
                        res.fold(
                            onSuccess = { done++ },
                            onFailure = { lastError = it },
                        )
                    }
                    info = if (lastError != null && done < toDelete.size)
                        "удалено $done из ${toDelete.size}: " + Repository.humanError(lastError!!)
                    else "удалено $done сообщений"
                    selectedMsgIds = emptySet()
                    msgSelectionMode = false
                }
            },
            onCopySelected = {
                val selMsgs = messages.filter { it.mid in selectedMsgIds }
                val texts = selMsgs.mapNotNull { m ->
                    if (m.kind == "system" || m.type == "deleted" ||
                        m.plain.isNullOrBlank()) null
                    else m.plain
                }
                if (texts.isNotEmpty()) {
                    copyToClipboard(texts.joinToString("\n"))
                    info = "Скопировано ${texts.size} сообщений"
                } else {
                    info = "нет текста для копирования"
                }
                selectedMsgIds = emptySet()
                msgSelectionMode = false
            },
            onCall = { chat?.let { CallManager.startAudio(it, false) } },
            onOpenFiles = { pickAndSendFiles() },
            onToggleSearch = { searchMode = !searchMode; if (!searchMode) searchQuery = "" },
            searchMode = searchMode,
            onMenu = { menuOpen = true },
            menuOpen = menuOpen,
            onMenuDismiss = { menuOpen = false },
            // Пункты меню «⋮» — та же логика, что была в TopAppBar (профиль, режим,
            // блокировка, участники, медиа, очистка, удаление чата).
            dropdownContent = {
                if (chat != null) {
                    if (!chat.is_group && chat.peerAddress != null) {
                        DropdownMenuItem(
                            text = { Text("Профиль собеседника") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    profile = runCatching {
                                        Repository.userProfile(chat.peerAddress!!)
                                    }.getOrNull() ?: run {
                                        error = "не удалось загрузить профиль"
                                        null
                                    }
                                }
                            },
                        )
                    }
                    if (!chat.is_group && chat.peerAddress != null) {
                        DropdownMenuItem(
                            text = { Text("Чат в другом режиме") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    Repository.switchChatMode(chat).fold(
                                        onSuccess = { onOpenChat(it.id) },
                                        onFailure = { error = Repository.humanError(it) },
                                    )
                                }
                            },
                        )
                    }
                    // v11: блокировка собеседника (только 1:1, не для групп)
                    if (!chat.is_group && chat.peerAddress != null) {
                        val peerBlocked = blocks.any {
                            it.address.equals(chat.peerAddress, ignoreCase = true)
                        }
                        DropdownMenuItem(
                            text = {
                                Text(if (peerBlocked) "Разблокировать пользователя"
                                     else "Заблокировать пользователя")
                            },
                            onClick = {
                                menuOpen = false
                                if (peerBlocked) {
                                    val addr = chat.peer?.address ?: chat.peerAddress
                                    if (addr != null) {
                                        scope.launch {
                                            Repository.unblockUser(addr).fold(
                                                onSuccess = { info = "Пользователь разблокирован" },
                                                onFailure = { error = Repository.humanError(it) },
                                            )
                                        }
                                    }
                                } else {
                                    confirmBlock = true
                                }
                            },
                        )
                    }
                    if (chat.is_group) {
                        DropdownMenuItem(
                            text = { Text("Участники") },
                            onClick = { menuOpen = false; membersOpen = true },
                        )
                        if (chat.owner != account?.username) {
                            DropdownMenuItem(
                                text = { Text("Выйти из группы") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        Repository.leaveChat(chat).fold(
                                            // выйдя из группы, закрываем панель чата
                                            onSuccess = { onClose?.invoke() },
                                            onFailure = { error = Repository.humanError(it) },
                                        )
                                    }
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Медиа и файлы чата") },
                        onClick = { menuOpen = false; mediaOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Очистить историю") },
                        onClick = { menuOpen = false; confirmClear = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить чат") },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            },
        )

        // Чат не найден (удалён/закрыт) — заглушка и выход из панели.
        if (chat == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("чат не найден")
            }
            return
        }

            if (conn != WsState.CONNECTED && !chat.is_p2p) {
                Text(
                    "нет связи — отправка недоступна (проверьте туннель)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (chat.is_p2p) {
                Text(
                    "чат без истории на сервере: сообщения идут напрямую, история — только на устройствах",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // v11: баннер «Вы заблокировали этого пользователя»
            val peerBlocked = !chat.is_group && chat.peerAddress != null &&
                blocks.any { it.address.equals(chat.peerAddress, ignoreCase = true) }
            if (peerBlocked) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Вы заблокировали этого пользователя.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Сообщения и звонки недоступны.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = {
                            val addr = chat.peer?.address ?: chat.peerAddress
                            if (addr != null) {
                                scope.launch {
                                    Repository.unblockUser(addr).fold(
                                        onSuccess = { info = "Пользователь разблокирован" },
                                        onFailure = { error = Repository.humanError(it) },
                                    )
                                }
                            }
                        }) { Text("Разблокировать") }
                    }
                }
            }

            if (searchMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("поиск по чату") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    HoverTooltip("Закрыть поиск") {
                        IconButton(onClick = { searchMode = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть поиск")
                        }
                    }
                }
            }

            val allMessages = messages
            val shown = if (searchQuery.isBlank()) {
                allMessages
            } else {
                allMessages.filter { m ->
                    (m.plain ?: "").contains(searchQuery, ignoreCase = true) ||
                        (m.file?.name ?: "").contains(searchQuery, ignoreCase = true) ||
                        m.sender.contains(searchQuery, ignoreCase = true)
                }
            }

            val listState = rememberLazyListState()
            // Автоскролл — только когда ПОЯВИЛОСЬ НОВОЕ ПОСЛЕДНЕЕ сообщение и
            // пользователь у нижнего края. Раньше: любой рост списка (в т.ч.
            // подгрузка старых сообщений вверх) насильно тащил список вниз.
            // ФИКС ПОЗИЦИИ ПРИ ВХОДЕ В ЧАТ. listState и
            // lastSeenKey жили «сквозь» смену чата (ChatPane переиспользуется
            // без key(chatId)), а глобальный Repository.messages ещё держит
            // сообщения ПРЕДЫДУЩЕГО чата первые кадры — lastSeenKey успевал
            // инициализироваться его последним сообщением, ветка «первый
            // показ» не срабатывала, позиция наследовалась от старого чата
            // («всегда разная»). Теперь: (1) lastSeenKey сбрасывается при
            // смене chatId; (2) эффект игнорирует сообщения чужого чата.
            var lastSeenKey by remember(chatId) { mutableStateOf<String?>(null) }
            LaunchedEffect(shown.size, shown.lastOrNull()?.mid, searchQuery.isBlank()) {
                val last = shown.lastOrNull() ?: return@LaunchedEffect
                if (searchQuery.isNotBlank()) return@LaunchedEffect
                // сообщения предыдущего чата ещё в общем flow — пропускаем
                if (last.chatId != chatId) return@LaunchedEffect
                val key = "${last.mid}:${last.clientId ?: ""}"
                if (lastSeenKey == null) {
                    listState.scrollToItem(shown.size - 1)
                } else if (key != lastSeenKey) {
                    val nearBottom = listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()?.let { it.index >= shown.size - 2 } ?: true
                    if (nearBottom) listState.animateScrollToItem(shown.size - 1)
                }
                lastSeenKey = key
            }

            // v8.2: перед сообщениями может быть кнопка «загрузить более старые» —
            // учитываем её при вычислении индекса последнего элемента списка
            val hasOlderBtn = chat.history && !chat.is_p2p && allMessages.size >= 50 && searchQuery.isBlank()
            val lastIndex = (shown.size - 1 + if (hasOlderBtn) 1 else 0).coerceAtLeast(0)

            // десктоп: авто-догрузка старых сообщений при скролле ВВЕРХ
            // (на Android требовался тап по кнопке; здесь кнопка остаётся,
            // но при достижении верха списка подгрузка срабатывает сама).
            var loadingOlder by remember { mutableStateOf(false) }
            val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
            LaunchedEffect(atTop, hasOlderBtn, chat.id) {
                if (atTop && hasOlderBtn && !loadingOlder) {
                    loadingOlder = true
                    runCatching { Repository.loadOlderMessages(chat) }
                    loadingOlder = false
                }
            }

            // v8.2: Box-обёртка — кнопка «вниз» лежит поверх списка в правом
            // нижнем углу, над полем ввода.
            // BoxWithConstraints — считаем 68% ширины панели для пузырей.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // ≤68% ширины панели — настольные пузыри не растягиваются на весь экран
                val bubbleMaxWidth = maxWidth * 0.68f
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hasOlderBtn) {
                        item {
                            TextButton(onClick = {
                                scope.launch { runCatching { Repository.loadOlderMessages(chat) } }
                            }) { Text("загрузить более старые сообщения") }
                        }
                    }
                    items(shown, key = { "${it.mid}:${it.clientId ?: ""}" }) { msg ->
                        MessageBubble(
                            msg = msg,
                            chat = chat,
                            bubbleMaxWidth = bubbleMaxWidth,
                            selected = selectedMsgIds.contains(msg.mid),
                            onClick = {
                                if (msgSelectionMode) {
                                    selectedMsgIds = if (selectedMsgIds.contains(msg.mid)) {
                                        selectedMsgIds - msg.mid
                                    } else {
                                        selectedMsgIds + msg.mid
                                    }
                                    // если сняли последний — выходим из режима выбора
                                    if (selectedMsgIds.isEmpty()) msgSelectionMode = false
                                } else {
                                    // вне режима выбора — обычный тап открывает
                                    // меню действий (реакции/копировать/удалить).
                                    // десктоп: клавиатуру/фокус гасить не нужно.
                                    menuFor = msg
                                }
                            },
                            onLongClick = {
                                if (msgSelectionMode) {
                                    // в режиме выбора long-тап тоже переключает
                                    selectedMsgIds = if (selectedMsgIds.contains(msg.mid)) {
                                        selectedMsgIds - msg.mid
                                    } else {
                                        selectedMsgIds + msg.mid
                                    }
                                    if (selectedMsgIds.isEmpty()) msgSelectionMode = false
                                } else {
                                    // long-тап = ТОЛЬКО вход в режим выбора (выделение),
                                    // без открытия меню действий — иначе два действия
                                    // происходят одновременно (выделение + меню).
                                    // Меню открывается обычным тапом.
                                    selectedMsgIds = setOf(msg.mid)
                                    msgSelectionMode = true
                                }
                            },
                            onFileAction = { m ->
                                if (m.localPath != null) {
                                    openLocalFile(m.localPath)
                                } else {
                                    // v8.x: скачивание не отменяется при выходе из чата
                                    Repository.launchSend {
                                        val res = Repository.downloadMessageFile(chat, m)
                                        res.fold(onSuccess = {}, onFailure = { error = Repository.humanError(it) })
                                    }
                                }
                            },
                            onSwipeReply = {
                                // v11: свайп вправо по сообщению → ответ на него.
                                // В режиме выбора свайп игнорируем (чтобы не ломать
                                // мультивыбор) — отвечать можно только вне режима.
                                if (!msgSelectionMode && msg.kind != "system" &&
                                    msg.type != "deleted" && msg.type != "system"
                                ) {
                                    replyTo = msg
                                    if (editing != null) { editing = null; input = "" }
                                }
                            },
                        )
                    }
                    if (searchQuery.isNotBlank() && shown.isEmpty()) {
                        item {
                            Text(
                                "ничего не найдено",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }

                // v8.2: быстрый возврат к последнему сообщению — видна, когда
                // список ещё можно прокрутить вниз (появление fade + scale).
                // Полный квалификатор обязателен: внутри Box, вложенного в Column,
                // короткое имя резолвится в ColumnScope.AnimatedVisibility.
                androidx.compose.animation.AnimatedVisibility(
                    visible = listState.canScrollForward,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(lastIndex) }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Вниз",
                        )
                    }
                }
            }

            sendProgress?.let { (done, total) ->
                if (total > 0) {
                    Text(
                        "отправка файла… ${formatBytes(done)} / ${formatBytes(total)} (${done * 100 / total} %)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // v11: краткие информационные сообщения (snackbar-стиль)
            info?.let {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // баннер режима правки с явной отменой (раньше выйти из правки
            // можно было только стерев текст и отправив пустоту).
            // v12 anim: плавное появление/уход; lastEditing держит контент
            // баннера во время анимации выхода (editing уже null).
            var lastEditing by remember { mutableStateOf<UiMsg?>(null) }
            if (editing != null) lastEditing = editing
            SmoothExpandFade(visible = editing != null) {
                if (lastEditing != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "правка сообщения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    HoverTooltip("Отменить правку") {
                        IconButton(
                            onClick = { editing = null; input = "" },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Отменить правку",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                }
            }

            // v11: баннер ответа на сообщение — quoted-превью над полем ввода,
            // отменяется кнопкой ✕ или отправкой/очисткой поля.
            // v12 anim: плавное появление/уход; lastReplyTo держит контент
            // баннера во время анимации выхода (replyTo уже null).
            var lastReplyTo by remember { mutableStateOf<UiMsg?>(null) }
            if (replyTo != null) lastReplyTo = replyTo
            SmoothExpandFade(visible = replyTo != null) {
                if (lastReplyTo != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ответ ${lastReplyTo?.sender ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            (lastReplyTo?.plain ?: lastReplyTo?.file?.name ?: "")
                                .ifBlank { "сообщение" }.take(60),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HoverTooltip("Отменить ответ") {
                        IconButton(
                            onClick = { replyTo = null },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Отменить ответ",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                }
            }

            // v12: панель эмодзи — плавно раскрывается над нижним баром
            // (та же анимация, что у баннеров ответа/правки). Тап по эмодзи
            // добавляет его в конец поля ввода.
            SmoothExpandFade(visible = emojiOpen) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        val emojiRows = remember {
                            listOf(
                                "😀 😁 😂 🤣 😊 😍 🥳 😎",
                                "🤔 😮 😢 😭 😡 🤯 😴 🤒",
                                "👍 👎 👏 🙏 💪 🤝 ✌️ 👌",
                                "❤️ 🧡 💛 💚 💙 💜 🖤 💔",
                                "🔥 ⭐ 🎉 🎊 🎁 🍕 ☕ 🍺",
                                "⚡ 🌈 ☀️ 🌙 🚀 💡 📎 ✅",
                            )
                        }
                        emojiRows.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                row.split(" ").forEach { e ->
                                    Box(
                                        Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                input += e
                                                Repository.sendTyping(chatId)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            e,
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // поле ввода: кнопки-иконки центрируются по вертикали относительно
            // поля (Alignment.Bottom прижимал их вниз, когда поле росло до 5 строк)
            if (recording) {
                // панель записи голосового: таймер, отмена, отправка
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    val secs = recTick * 300 / 1000
                    Text(
                        "запись %d:%02d".format(secs / 60, secs % 60),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HoverTooltip("Отменить запись") {
                        IconButton(
                            onClick = { recorder.cancel(); recording = false },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Отменить запись")
                        }
                    }
                    HoverTooltip("Отправить голосовое") {
                        IconButton(
                            onClick = {
                                val r = recorder.stop()
                                recording = false
                                val ch = chat
                                if (r != null && r.durationSec >= 1) {
                                    // v12: запись завершена — панель эмодзи больше не нужна
                                    emojiOpen = false
                                    busyFile = true
                                    // v8.x: отправка голосового переживает выход из чата
                                    Repository.launchSend {
                                        val res = Repository.sendVoice(ch, r.file, r.durationSec, r.wave) { _, _ -> }
                                        busyFile = false
                                        res.fold(onSuccess = {}, onFailure = { error = Repository.humanError(it) })
                                    }
                                } else {
                                    r?.file?.delete()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить голосовое")
                        }
                    }
                }
            } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v12: все кнопки бара — ровно 48dp, одинаковые отступы
                HoverTooltip("Отправить файл") {
                    IconButton(
                        onClick = { pickAndSendFiles() },
                        enabled = !busyFile,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Отправить файл")
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        Repository.sendTyping(chatId)
                    },
                    placeholder = {
                        Text(if (editing != null) "правка сообщения…" else "сообщение")
                    },
                    modifier = Modifier
                        .weight(1f)
                        // Enter — отправить, Shift+Enter — новая строка.
                        // Preview-фаза: перехватываем Enter ДО вставки переноса.
                        .onPreviewKeyEvent { e ->
                            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (enter && e.type == KeyEventType.KeyDown &&
                                !e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed
                            ) {
                                submitInput()
                                true
                            } else {
                                false
                            }
                        },
                    maxLines = 5,
                )
                // v12: кнопка эмодзи возле стрелки/микрофона — открывает/закрывает
                // панель эмодзи над баром (повторный тап закрывает панель)
                HoverTooltip("Эмодзи") {
                    IconButton(
                        onClick = { emojiOpen = !emojiOpen },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.EmojiEmotions,
                            contentDescription = "Эмодзи",
                            tint = if (emojiOpen) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // v12: одна хвостовая кнопка вместо двух. По умолчанию — стрелка
                // отправки; ДОЛГОЕ нажатие на стрелку превращает её в микрофон,
                // долгое нажатие на микрофон возвращает стрелку. Короткое нажатие
                // всегда выполняет действие текущего режима.
                // sendEnabled вынесен наверх — общий с Enter (submitInput).
                HoverTooltip(
                    if (voiceMode) "Голосовое (долгое нажатие — вернуться к отправке)"
                    else "Отправить (долгое нажатие — голосовое)",
                ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                if (voiceMode) {
                                    // микрофон: начать запись (как прежняя кнопка Mic);
                                    // десктоп: пермиссия не требуется
                                    if (!busyFile) {
                                        if (recorder.start()) {
                                            recording = true
                                            emojiOpen = false // v12: панель эмодзи не нужна при записи
                                        } else {
                                            info = "не удалось начать запись: микрофон недоступен"
                                        }
                                    }
                                } else {
                                    // отправка/правка — общий код с Enter (submitInput)
                                    submitInput()
                                }
                            },
                            onLongClick = {
                                // v12: свап режима стрелка ↔ микрофон
                                // (десктоп: вибро-отклик не требуется)
                                voiceMode = !voiceMode
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (voiceMode) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription =
                                "Записать голосовое (долгое нажатие — вернуть отправку)",
                        )
                    } else if (busyFile) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить (долгое нажатие — голосовое)",
                            // v12-n: оба состояния тематические: primary — активна.
                            tint = if (sendEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }
            }

        // меню сообщения
        val menuMsg = menuFor
        if (menuMsg != null) {
            AlertDialog(
                onDismissRequest = { menuFor = null },
                confirmButton = {},
                title = { Text("Сообщение", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column {
                        // v12: расширенный набор реакций (12 шт.)
                        val emojis = listOf(
                            "👍", "❤️", "🔥", "😮", "😂", "😢",
                            "🙏", "👏", "😡", "🎉", "🤔", "👎",
                        )
                        if (!chat.is_p2p && menuMsg.kind == "user" && menuMsg.type != "deleted") {
                            // 2 ряда по 3: один ряд из шести кнопок переполнял
                            // узкие окна
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                emojis.chunked(3).forEach { rowEmojis ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowEmojis.forEach { emoji ->
                                            TextButton(onClick = {
                                                scope.launch {
                                                    Repository.react(chat, menuMsg, emoji)
                                                }
                                                menuFor = null
                                            }) { Text(emoji) }
                                        }
                                    }
                                }
                            }
                        }
                        // v11: ответ на сообщение. Не показываем для системных
                        // и удалённых — отвечать на них бессмысленно.
                        if (menuMsg.kind != "system" && menuMsg.type != "deleted" && menuMsg.type != "system") {
                            TextButton(onClick = {
                                replyTo = menuMsg
                                // правка и ответ взаимоисключающие — выходим из правки
                                if (editing != null) { editing = null; input = "" }
                                menuFor = null
                            }) { Text("Ответить") }
                        }
                        TextButton(onClick = {
                            copyToClipboard(menuMsg.plain ?: "")
                            menuFor = null
                        }) { Text("Копировать текст") }
                        if (menuMsg.isMine && menuMsg.type == "text" && chat.history && !chat.is_p2p) {
                            TextButton(onClick = {
                                editing = menuMsg
                                input = menuMsg.plain ?: ""
                                // при входе в правку снимаем баннер ответа
                                replyTo = null
                                menuFor = null
                            }) { Text("Изменить") }
                        }
                        if (menuMsg.isMine && chat.history && !chat.is_p2p && menuMsg.type != "deleted") {
                            TextButton(onClick = {
                                scope.launch {
                                    val res = Repository.unsendMessage(chat, menuMsg)
                                    res.fold(onSuccess = {}, onFailure = { error = Repository.humanError(it) })
                                }
                                menuFor = null
                            }) { Text("Удалить у всех") }
                        }
                    }
                },
            )
        }

        // профиль собеседника
        profile?.let { p ->
            AlertDialog(
                onDismissRequest = { profile = null },
                confirmButton = {},
                title = { Text("Профиль") },
                text = {
                    Column {
                        Text(
                            p.nickname?.takeIf { it.isNotBlank() } ?: p.username ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "@${p.username ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!p.bio.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(p.bio)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (p.online == true) {
                                "в сети"
                            } else {
                                "был(а): " + formatFullTs(p.last_seen ?: 0.0)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        // участники группы (добавление/исключение/передача владения)
        if (membersOpen) {
            val amOwner = chat.owner == account?.username
            AlertDialog(
                onDismissRequest = { membersOpen = false },
                confirmButton = {},
                title = { Text("Участники (${chat.members.size}/64)") },
                text = {
                    Column {
                        Column(
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            chat.members.forEach { mem ->
                                val mname = mem.nickname?.takeIf { it.isNotBlank() }
                                    ?: mem.username ?: "?"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    Avatar(mname, 30.dp, online = mem.online)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(mname, style = MaterialTheme.typography.bodyMedium)
                                        if (mem.owner || mem.username == chat.owner) {
                                            Text(
                                                "владелец",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                    if (amOwner && mem.username != null && mem.username != chat.owner) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                Repository.transferOwnership(chat, mem.username!!)
                                            }
                                        }) { Text("владение") }
                                        TextButton(onClick = {
                                            scope.launch {
                                                Repository.removeGroupMember(chat, mem.username!!)
                                            }
                                        }) { Text("исключить") }
                                    }
                                }
                            }
                        }
                        if (amOwner) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = memberAddr,
                                    onValueChange = { memberAddr = it },
                                    label = { Text("адрес: bob или bob@srv") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                TextButton(
                                    onClick = {
                                        val a = memberAddr.trim()
                                        if (a.isNotBlank()) {
                                            scope.launch {
                                                val res = Repository.addGroupMember(chat, a)
                                                res.fold(
                                                    onSuccess = { memberAddr = "" },
                                                    onFailure = { error = Repository.humanError(it) },
                                                )
                                            }
                                        }
                                    },
                                    enabled = memberAddr.isNotBlank(),
                                ) { Text("добавить") }
                            }
                        }
                    }
                },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Очистить историю?") },
                text = { Text("Будет очищена только ваша копия истории (у собеседника останется своя).") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        scope.launch {
                            Repository.clearChat(chat).fold(
                                onSuccess = {},
                                onFailure = { error = Repository.humanError(it) },
                            )
                        }
                    }) { Text("Очистить") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
                },
            )
        }
        // v8: галерея чата — фото, видео, голосовые, файлы, ссылки
        if (mediaOpen) {
            MediaDialog(
                chat = chat,
                messages = messages,
                onDismiss = { mediaOpen = false },
                onDownload = { m ->
                    // v8.x: скачивание из медиа-галереи живёт в application-scope
                    Repository.launchSend {
                        val res = Repository.downloadMessageFile(chat, m)
                        res.fold(onSuccess = {}, onFailure = { error = Repository.humanError(it) })
                    }
                },
                onError = { error = it },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Удалить чат?") },
                text = {
                    Text(
                        if (chat.is_group && chat.owner == account?.username) {
                            "Группа будет удалена у ВСЕХ участников вместе с файлами на сервере."
                        } else if (chat.is_group) {
                            "Вы выйдете из группы."
                        } else {
                            "Чат будет удалён у ОБОИХ участников: история и файлы на сервере."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            Repository.leaveChat(chat).fold(
                                // чат удалён — закрываем панель (в узком режиме
                                // это возврат к списку, в master-detail — пустое состояние)
                                onSuccess = { onClose?.invoke() },
                                onFailure = { error = Repository.humanError(it) },
                            )
                        }
                    }) { Text("Удалить") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
                },
            )
        }

        // v11: подтверждение блокировки собеседника
        if (confirmBlock) {
            val peerAddr = chat.peer?.address ?: chat.peerAddress
            AlertDialog(
                onDismissRequest = { confirmBlock = false },
                title = { Text("Заблокировать?") },
                text = {
                    Text(
                        "Заблокировать ${peerAddr ?: "пользователя"}? После блокировки " +
                            "пользователь не сможет писать вам и звонить."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmBlock = false
                        val addr = peerAddr
                        if (addr != null) {
                            scope.launch {
                                Repository.blockUser(addr).fold(
                                    onSuccess = { info = "Пользователь заблокирован" },
                                    onFailure = { error = Repository.humanError(it) },
                                )
                            }
                        }
                    }) { Text("Заблокировать") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBlock = false }) { Text("Отмена") }
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: UiMsg,
    chat: Chat,
    bubbleMaxWidth: Dp = 320.dp,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFileAction: (UiMsg) -> Unit,
    onSwipeReply: () -> Unit = {},
) {
    val mine = msg.isMine
    val isSystem = msg.kind == "system" || msg.type == "system"
    val bubbleColor = when {
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        mine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    // v12: свайп вправо по пузырю → ответ на сообщение, с НАСТОЯЩЕЙ анимацией:
    // пузырь тянется за пальцем (translationX, за 120dp — резинка), слева от
    // его кромки проявляется иконка «Ответить», при пересечении порога 80dp —
    // цветовая отметка, при отпускании — ответ и пружинный возврат.
    // (десктоп: вибро-отклик не требуется — оставлен только визуальный фидбек)
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }
    val swipeMaxPx = with(density) { 120.dp.toPx() }
    val iconSizePx = with(density) { 24.dp.toPx() }
    val iconGapPx = with(density) { 8.dp.toPx() }
    var swipeX by remember(msg.mid) { mutableFloatStateOf(0f) }
    var crossed by remember(msg.mid) { mutableStateOf(false) }
    var rowWidthPx by remember(msg.mid) { mutableIntStateOf(0) }
    var bubbleWidthPx by remember(msg.mid) { mutableIntStateOf(0) }
    var springBack by remember(msg.mid) { mutableStateOf<Job?>(null) }
    // Левая кромка пузыря в покое: 0 у входящих, ширина-строки-ширина-пузыря у исходящих
    val bubbleLeftAtRest = if (mine) (rowWidthPx - bubbleWidthPx).coerceAtLeast(0).toFloat() else 0f

    if (isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // тап/правый клик — меню, long-press — выбор (как у обычных пузырей)
                .pointerInput(msg.mid) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                msg.plain ?: msg.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (chat.is_group && !mine) {
            Text(
                msg.sender,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, bottom = 1.dp),
            )
        }
        // v11: подсветка выбранного сообщения — рамка + лёгкое затемнение фона
        val selectedBorder = if (selected) {
            Modifier.border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (mine) 16.dp else 4.dp,
                    bottomEnd = if (mine) 4.dp else 16.dp,
                ),
            )
        } else Modifier
        // v12: строка сообщения = Box (ширина чата) c иконкой «Ответить» под
        // пузырём и самим пузырём. Пузырь сохраняет выравнивание
        // (Start у входящих / End у исходящих) как раньше.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width },
        ) {
            // иконка «Ответить» — едет слева от кромки пузыря, проявляется по мере свайпа
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null, // декоративная: действие озвучивается состоянием пузыря
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(24.dp)
                    .graphicsLayer {
                        // v12 audit: прогресс 0..1 считается внутри слоя (чтение
                        // swipeX здесь — это draw-фаза, БЕЗ recomposition)
                        val progress = (swipeX / swipeThresholdPx).coerceIn(0f, 1f)
                        translationX = bubbleLeftAtRest + swipeX - iconSizePx - iconGapPx
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    },
            )
            Surface(
                color = when {
                    selected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    crossed -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                    else -> bubbleColor
                },
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (mine) 16.dp else 4.dp,
                    bottomEnd = if (mine) 4.dp else 16.dp,
                ),
                modifier = Modifier
                    // v12 fix: именно 2D-константы (CenterEnd/CenterStart) — только
                    // они подходят BoxScope.align.
                    .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                    // не более 68% ширины панели (см. BoxWithConstraints выше)
                    .widthIn(max = bubbleMaxWidth)
                    .onSizeChanged { bubbleWidthPx = it.width }
                    // пузырь физически тянется за пальцем; чтение swipeX внутри
                    // graphicsLayer — перерисовка слоя без recomposition
                    .graphicsLayer { translationX = swipeX }
                    .then(selectedBorder)
                    .pointerInput(msg.mid) {
                        // v12: свайп вправо → ответ. Только вправо (dragAmount > 0).
                        // combinedClickable получает onClick/onLongClick, pointerInput
                        // перехватывает горизонтальные драги — вертикальный скролл
                        // списка при этом не ломается (жест горизонтальный).
                        detectHorizontalDragGestures(
                            onDragStart = {
                                springBack?.cancel() // прервать возврат, если тянут снова
                                springBack = null
                                crossed = false
                            },
                            onDragEnd = {
                                val trigger = swipeX >= swipeThresholdPx
                                val from = swipeX
                                springBack = scope.launch {
                                    animate(
                                        initialValue = from,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ) { v, _ -> swipeX = v }
                                    crossed = false
                                }
                                if (trigger) {
                                    onSwipeReply()
                                }
                            },
                            onDragCancel = {
                                val from = swipeX
                                springBack = scope.launch {
                                    animate(
                                        initialValue = from,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ) { v, _ -> swipeX = v }
                                    crossed = false
                                }
                            },
                        ) { _, dragAmount ->
                            springBack?.cancel()
                            // v12: движение влево (возврат пальца) откатывает
                            // пузырь к нулю и снимает порог.
                            val cur = swipeX
                            val next = when {
                                dragAmount < 0f -> (cur + dragAmount).coerceAtLeast(0f)
                                cur >= swipeMaxPx -> cur + dragAmount * 0.25f
                                else -> (cur + dragAmount).coerceAtMost(swipeMaxPx)
                            }
                            swipeX = next
                            if (!crossed && next >= swipeThresholdPx) {
                                crossed = true
                            } else if (crossed && next < swipeThresholdPx) {
                                crossed = false
                            }
                        }
                    }
                    // клик/правый клик — меню сообщения, long-press — режим
                    // выбора. detectTapGestures обрабатывает ЛЮБУЮ кнопку мыши:
                    // правый клик тоже открывает контекстное меню, long-press сохранён.
                    .pointerInput(msg.mid) {
                        detectTapGestures(
                            onTap = { onClick() },
                            onLongPress = { onLongClick() },
                        )
                    },
            ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // v11: quoted-превью оригинального сообщения над текстом/файлом.
                // Рисуется только если есть метаданные ответа (префикс был в plaintext).
                if (msg.replyToMid > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                msg.replyToSender,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accentTextColor(),
                            )
                            Text(
                                msg.replyToPreview.ifBlank { "сообщение" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                when {
                    msg.type == "deleted" -> Text(
                        "сообщение удалено",
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    msg.type == "file" && msg.file != null -> FileContent(msg, onFileAction)
                    else -> Text(msg.plain ?: "🔒 не удалось расшифровать")
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.editedAt != null) {
                        Text(
                            "изменено · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        formatFullTs(msg.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    if (mine) {
                        if (msg.status == "pending") {
                            // v12: вместо эмодзи часов — маленький вращающийся
                            // кружок (как индикатор синхронизации чатов, только меньше)
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                statusIcon(msg.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (msg.status) {
                                    "read" -> MaterialTheme.colorScheme.tertiary
                                    "failed" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                if (msg.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msg.reactions.forEach { r ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Text(
                                    "${r.emoji} ${r.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/** Файловое сообщение: голосовой плеер, миниатюра для изображений, иначе — строка с иконкой. */
@Composable
private fun FileContent(msg: UiMsg, onFileAction: (UiMsg) -> Unit) {
    Column {
        val voice = msg.file?.voice
        if (voice != null) {
            VoiceContent(msg, voice, onFileAction)
        } else {
            FileBody(msg, onFileAction)
        }
        TransferProgressRow(msg)
    }
}

@Composable
private fun FileBody(msg: UiMsg, onFileAction: (UiMsg) -> Unit) {
    val isImage = ImageThumb.isImage(msg.file?.name)
    // v8.x: асинхронное декодирование миниатюры — большое изображение
    // больше не блокирует UI-поток при первом показе; пока ImageBitmap не готов,
    // показываем лоадер (CircularProgressIndicator)
    val bmp = if (isImage) ImageThumb.getAsync(msg.localPath) else null

    if (isImage && bmp != null) {
        // картинка-миниатюра с именем и размером под ней; скругление углов —
        // иначе квадратные углы картинки торчат из-под скруглённого пузыря
        Image(
            bitmap = bmp,
            contentDescription = msg.file?.name ?: "изображение",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(4.dp))
        ImageCaption(msg, onFileAction)
    } else if (isImage && msg.localPath != null) {
        // v8.x: файл скачан, миниатюра ещё декодируется — лоадер
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        }
        Spacer(Modifier.height(4.dp))
        ImageCaption(msg, onFileAction)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📎", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    msg.file?.name ?: "файл",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatBytes(msg.file?.size ?: 0) +
                        if (msg.localPath != null) " · скачан" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v15: сохранить расшифрованный файл через системный диалог
            HoverTooltip("Сохранить как…") {
                SaveMediaAsButton(msg)
            }
            HoverTooltip(if (msg.localPath != null) "Открыть файл" else "Скачать файл") {
                IconButton(onClick = { onFileAction(msg) }) {
                    Icon(
                        if (msg.localPath != null) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.Download,
                        contentDescription = if (msg.localPath != null) "Открыть" else "Скачать",
                    )
                }
            }
        }
    }
}

/** v8.x: подпись под миниатюрой изображения (имя, размер, скачать/открыть). */
@Composable
private fun ImageCaption(msg: UiMsg, onFileAction: (UiMsg) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                msg.file?.name ?: "файл",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(msg.file?.size ?: 0) +
                    if (msg.localPath != null) " · скачан" else " · нажмите, чтобы скачать",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // v15: сохранить изображение через системный диалог
        HoverTooltip("Сохранить как…") {
            SaveMediaAsButton(msg, iconSize = 18.dp)
        }
        HoverTooltip(if (msg.localPath != null) "Открыть файл" else "Скачать файл") {
            IconButton(onClick = { onFileAction(msg) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (msg.localPath != null) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.Download,
                    contentDescription = if (msg.localPath != null) "Открыть" else "Скачать",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Голосовое сообщение: кнопка play, огибающая и длительность. */
@Composable
private fun VoiceContent(msg: UiMsg, voice: VoiceMeta, onFileAction: (UiMsg) -> Unit) {
    var playing by remember(msg.localPath) { mutableStateOf(false) }
    val canPlay = msg.localPath != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        /* v8: тап по голосовому без локального файла — СКАЧИВАЕТ его. */
        modifier = Modifier.clickable(enabled = !canPlay) { onFileAction(msg) },
    ) {
        HoverTooltip(
            when {
                playing -> "Остановить"
                canPlay -> "Воспроизвести"
                else -> "Скачать голосовое"
            },
        ) {
            IconButton(
                onClick = {
                    val path = msg.localPath
                    if (playing) {
                        VoicePlayer.stop()
                        playing = false
                    } else if (path != null) {
                        playing = true
                        VoicePlayer.play(File(path)) { playing = false }
                    } else {
                        onFileAction(msg)         // v8: нет файла — качаем
                    }
                },
                enabled = true,
            ) {
                Icon(
                    if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Остановить" else "Воспроизвести",
                    tint = if (playing || canPlay) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Waveform(
            wave = voice.wave,
            modifier = Modifier.weight(1f).height(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "%d:%02d".format(voice.dur / 60, voice.dur % 60),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v15: сохранить голосовое через системный диалог (.wav/.m4a)
        HoverTooltip("Сохранить как…") {
            SaveMediaAsButton(msg, iconSize = 18.dp)
        }
    }
    if (!canPlay) {
        Text(
            "нажмите, чтобы скачать голосовое",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Огибающая голосового: столбики из wave (или плоская полоска, если меты нет). */
@Composable
private fun Waveform(wave: List<Int>?, modifier: Modifier = Modifier) {
    val bars = wave?.takeIf { it.isNotEmpty() } ?: List(28) { 12 }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val n = bars.size
        val barW = size.width / (n * 1.6f)
        val gap = (size.width - barW * n) / (n - 1).coerceAtLeast(1)
        bars.forEachIndexed { i, v ->
            val h = (size.height * v.coerceIn(4, 100) / 100f).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (barW + gap), (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

/** Прогресс передачи файла для этого сообщения (отправка/скачивание), с процентами. */
@Composable
private fun TransferProgressRow(msg: UiMsg) {
    val transfers by Repository.transfers.collectAsState()
    val tr = transfers["${msg.chatId}:${msg.clientId ?: msg.mid}"] ?: return
    val frac = if (tr.total > 0) (tr.done.toDouble() / tr.total).coerceIn(0.0, 1.0) else 0.0
    val pct = (frac * 100).toInt()
    Column(Modifier.padding(top = 2.dp)) {
        LinearProgressIndicator(
            progress = { frac.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            (if (tr.phase == "download") "скачивание" else "отправка") +
                " ${formatBytes(tr.done)} / ${formatBytes(tr.total)} · $pct %",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusIcon(status: String): String = when (status) {
    "read" -> "✓✓"
    "delivered" -> "✓✓"
    "sent" -> "✓"
    "failed" -> "⚠"  // v8.x: не отправлено
    "incoming" -> ""
    // v12: "pending" рисуется отдельным компонентом — вращающийся кружок
    else -> ""
}

/** Открыть скачанный файл системным приложением (десктоп: java.awt.Desktop.open). */
private fun openLocalFile(path: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(File(path))
        }
    } catch (_: Exception) {
        // нет приложения для этого типа файла
    }
}

/**
 * тулбар панели чата — 56dp: аватар/имя/статус слева, действия справа
 * (звонок/файл/поиск/меню). Без back-стрелки: панель закрывается кнопкой «✕»
 * (onClose — сброс выбора чата в оболочке Main.kt).
 * В режиме мультивыбора сообщений вместо заголовка показывается контекстный
 * бар выбора («N выбрано», удалить у всех, копировать).
 */
@Composable
private fun ChatPaneToolbar(
    chat: Chat?,
    chatId: Long,
    typingMap: Map<Long, Pair<String, Long>>,
    msgSelectionMode: Boolean,
    selectedCount: Int,
    canDeleteAll: Boolean,
    onClose: (() -> Unit)?,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onCall: () -> Unit,
    onOpenFiles: () -> Unit,
    onToggleSearch: () -> Unit,
    searchMode: Boolean,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onMenuDismiss: () -> Unit,
    dropdownContent: @Composable () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (msgSelectionMode) {
                // v11: контекстный бар мультивыбора сообщений
                HoverTooltip("Отмена") {
                    IconButton(onClick = onExitSelection) {
                        Icon(Icons.Filled.Close, contentDescription = "Отмена")
                    }
                }
                Text(
                    "$selectedCount выбрано",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // «Удалить у всех»: активность считает вызывающий код
                HoverTooltip("Удалить у всех") {
                    IconButton(onClick = onDeleteSelected, enabled = canDeleteAll) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить у всех")
                    }
                }
                HoverTooltip("Копировать") {
                    IconButton(onClick = onCopySelected) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать")
                    }
                }
            } else {
                if (onClose != null) {
                    HoverTooltip("Закрыть чат") {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть чат")
                        }
                    }
                }
                if (chat != null) {
                    Avatar(chat.displayName, 36.dp, online = chat.peer?.online)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chat.displayName,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // fill=false: длинное имя сжимается и не выдавливает
                                // метку «без истории» и иконки действий за край
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (chat.is_p2p) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "без истории",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val isTyping = typingMap[chatId]?.let { (_, ts) ->
                            System.currentTimeMillis() - ts < 5000
                        } == true
                        val subtitle = when {
                            isTyping -> "печатает…"
                            chat.is_group -> "участников: ${chat.members.size}"
                            chat.peer?.online == true -> "в сети"
                            chat.peer == null -> ""
                            chat.peerAddress?.contains("@") == true -> "федерация"
                            else -> "не в сети"
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // v12: звонок доступен и в федеративных личных чатах
                    // (сигналинг и аудио идут через мост, см. ws.py/fed.py)
                    if (!chat.is_group && chat.peerAddress != null) {
                        HoverTooltip("Позвонить") {
                            IconButton(onClick = onCall) {
                                Icon(Icons.Filled.Call, contentDescription = "Позвонить")
                            }
                        }
                    }
                } else {
                    Text(
                        "чат",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                }
                // файл/поиск/меню — иконки прямо в тулбаре панели чата
                HoverTooltip("Отправить файл") {
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Отправить файл")
                    }
                }
                HoverTooltip(if (searchMode) "Закрыть поиск" else "Поиск по чату") {
                    IconButton(onClick = onToggleSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Поиск по чату")
                    }
                }
                Box {
                    HoverTooltip("Меню чата") {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню чата")
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                        dropdownContent()
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
