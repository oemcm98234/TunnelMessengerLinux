package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.model.Chat
import com.tunnelmessenger.desktop.data.ws.WsState
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.ui.components.Avatar
import com.tunnelmessenger.desktop.ui.components.HoverTooltip
import com.tunnelmessenger.desktop.ui.components.SmoothExpandFade
import com.tunnelmessenger.desktop.ui.components.pressScale
import com.tunnelmessenger.desktop.ui.components.formatBytes
import com.tunnelmessenger.desktop.ui.components.formatTs
import com.tunnelmessenger.desktop.ui.theme.AppFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v15 (сборка 5): панель списка чатов.
 *
 * СБОРКА 8 (по фидбеку — верхний бар приложения УБРАН):
 *  - в шапке панели, рядом с поиском — компактный статус связи
 *    («на связи»/«подключение…»/«нет связи», цвет как был в топбаре);
 *  - ВНИЗУ панели ДВЕ колонки кнопок в стиле Android-FAB:
 *      слева   — «Туннель» (щит, мятный при активном туннеле) и «Настройки»
 *                (шестерёнка) — icon-only, одна НАД другой;
 *      справа  — «Контакты» и «Новый чат» (как раньше);
 *    ряды выровнены: Туннель ↔ Контакты, Настройки ↔ Новый чат.
 *
 * Фон панели — единый background темы (как у Android Scaffold), карточки/шапки —
 * surface. Вся логика списка сохранена: поиск, статус синка, мультивыбор
 * с блокировкой/удалением, pull-to-refresh, баннер обновления, удаление чатов.
 *
 * @param selectedChatId открытый сейчас чат (master-detail) — подсветка строки
 * @param onChat         выбрать чат (вызывающий код делает Repository.openChat)
 * @param onNewChat      открыть «Новый чат» (ЛЕВАЯ панель оболочки)
 * @param onOpenContacts открыть «Контакты» (ЛЕВАЯ панель оболочки)
 * @param onOpenTunnel   открыть «Туннель» (правая панель)
 * @param onOpenSettings открыть «Настройки» (правая панель)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListPane(
    selectedChatId: Long?,
    onChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onOpenContacts: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenTunnel: () -> Unit = {},
) {
    val chats by Repository.chats.collectAsState()
    val previews by Repository.previews.collectAsState()
    val syncProgress by Repository.syncProgress.collectAsState()
    val account by Repository.account.collectAsState()
    val updateState by Repository.updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    // v11: мультивыбор чатов
    var selectedChatIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    // v11: баннер обновления можно смахнуть (до следующей переустановки состояния)
    var updateBannerDismissed by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var bulkInfo by remember { mutableStateOf<String?>(null) }
    // поиск по чатам (имя, превью, последнее сообщение)
    var query by remember { mutableStateOf("") }

    Box(
        // СБОРКА 6 FIX: применяем внешний modifier (раньше он игнорировался —
        // в master-detail панель списка width(320.dp) не работала: список
        // занимал всю ширину, а разделитель с правой частью вытеснялись за экран)
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            // v11: шапка мультивыбора чатов (вместо поиска)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoverTooltip("Отмена") {
                    IconButton(onClick = {
                        selectionMode = false
                        selectedChatIds = emptySet()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Отмена")
                    }
                }
                Text(
                    "${selectedChatIds.size} выбрано",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val selectedChats = chats.filter { it.id in selectedChatIds }
                // «Заблокировать»: только если все выбранные — личные чаты (не группы)
                val canBlockAll = selectedChats.isNotEmpty() &&
                    selectedChats.all { !it.is_group && !it.peerAddress.isNullOrBlank() }
                HoverTooltip("Заблокировать") {
                    IconButton(onClick = {
                        scope.launch {
                            val toBlock = selectedChats.mapNotNull { it.peerAddress }
                            var done = 0
                            var lastError: Throwable? = null
                            for (addr in toBlock) {
                                Repository.blockUser(addr).fold(
                                    onSuccess = { done++ },
                                    onFailure = { lastError = it },
                                )
                            }
                            bulkInfo = if (lastError != null && done < toBlock.size) {
                                "заблокировано $done из ${toBlock.size}: " +
                                    Repository.humanError(lastError!!)
                            } else {
                                "заблокировано: $done"
                            }
                            selectedChatIds = emptySet()
                            selectionMode = false
                        }
                    }, enabled = canBlockAll) {
                        Icon(Icons.Filled.Block, contentDescription = "Заблокировать")
                    }
                }
                HoverTooltip("Удалить") {
                    IconButton(onClick = { confirmBulkDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                    }
                }
            }
        } else {
            // Шапка панели: компактный поиск + статус связи (топбар приложения
            // убран в сборке 8 — статус живёт здесь, как в Android-списке).
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ChatStatusCaption()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // v12 anim: строка синхронизации плавно появляется/исчезает (fade + expand).
        // Последний непустой текст держим в состоянии: в момент скрытия
        // syncProgress уже null, и без этого содержимое схлопнулось бы в ноль
        // до конца exit-анимации.
        var lastSyncText by remember { mutableStateOf<String?>(null) }
        syncProgress?.let { lastSyncText = it }
        SmoothExpandFade(visible = syncProgress != null) {
            lastSyncText?.let { syncText ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(syncText, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // v11: баннер обновления приложения (доступна новая версия)
        // v12 anim: баннер плавно раскрывается/скрывается (fade + expand по вертикали)
        SmoothExpandFade(visible = updateState.updateAvailable && !updateBannerDismissed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Доступна новая версия v${updateState.latestVersion}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (updateState.apkSize > 0) {
                            Text(
                                "Размер: ${formatBytes(updateState.apkSize)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    TextButton(onClick = onOpenSettings) { Text("Обновить") }
                    HoverTooltip("Скрыть") {
                        IconButton(onClick = { updateBannerDismissed = true }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Скрыть",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // v11: краткое сообщение о результате массовой операции
        bulkInfo?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пока нет чатов", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Нажмите «Новый чат» внизу — найдите пользователя по имени\n" +
                            "или введите адрес вида bob@10.10.10.2.\n" +
                            "Контакты — кнопка рядом",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // поиск — фильтр по имени чата, превью и телу последнего сообщения
            val q = query.trim()
            val visibleChats = if (q.isEmpty()) chats else chats.filter { c ->
                c.displayName.contains(q, ignoreCase = true) ||
                    (previews[c.id] ?: "").contains(q, ignoreCase = true) ||
                    (c.last_message?.body ?: "").contains(q, ignoreCase = true)
            }

            if (visibleChats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "ничего не найдено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // v8.x: PULL-TO-REFRESH — тянем список вниз (на тачпаде/мыши —
                // жест перетаскивания) в самом верху → пересинк истории.
                val listState = rememberLazyListState()
                var pullPx by remember { mutableFloatStateOf(0f) }
                var pulling by remember { mutableStateOf(false) }
                var refreshing by remember { mutableStateOf(false) }
                val triggerPx = with(LocalDensity.current) { 72.dp.toPx() }

                LaunchedEffect(refreshing) {
                    if (refreshing) {
                        delay(1200)
                        refreshing = false
                    }
                }

                val pullConn = remember {
                    object : NestedScrollConnection {
                        // тянем вниз, когда список уже на самом верху (overscroll)
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (available.y > 0 &&
                                listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset == 0
                            ) {
                                pulling = true
                                pullPx += available.y
                                return Offset(0f, available.y)
                            }
                            return Offset.Zero
                        }

                        // жест отпущен — если тянули достаточно, запускаем ресинк
                        override suspend fun onPreFling(consumed: Velocity): Velocity {
                            if (pullPx > triggerPx && !refreshing) {
                                refreshing = true
                                Repository.refresh()
                            }
                            pulling = false
                            pullPx = 0f
                            return Velocity.Zero
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(pullConn),
                ) {
                    LazyColumn(
                        state = listState,
                        // v8.x: список слегка «тянется» за пальцем, как в нативном
                        // pull-to-refresh (половина от величины свайпа)
                        modifier = Modifier.graphicsLayer {
                            translationY = if (pulling) pullPx / 2f else 0f
                        },
                    ) {
                        items(visibleChats, key = { it.id }) { chat ->
                            ChatRow(
                                chat = chat,
                                // v12 anim: элементы списка плавно появляются и
                                // переезжают при изменениях (fade + placement spring)
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    fadeOutSpec = tween(180),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                                ),
                                preview = previews[chat.id],
                                unread = Repository.unreadOf(chat),
                                selected = selectedChatIds.contains(chat.id),
                                // подсветка открытого чата в master-detail
                                active = chat.id == selectedChatId && !selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        selectedChatIds = if (selectedChatIds.contains(chat.id)) {
                                            selectedChatIds - chat.id
                                        } else {
                                            selectedChatIds + chat.id
                                        }
                                        if (selectedChatIds.isEmpty()) selectionMode = false
                                    } else {
                                        onChat(chat.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                    }
                                    selectedChatIds = if (selectedChatIds.contains(chat.id)) {
                                        selectedChatIds - chat.id
                                    } else {
                                        selectedChatIds + chat.id
                                    }
                                    if (selectedChatIds.isEmpty()) selectionMode = false
                                },
                            )
                        }
                        // СБОРКА 5: отступ под FAB-кнопки (не перекрывают последнюю строку)
                        item { Spacer(Modifier.height(160.dp)) }
                    }
                    // индикатор: раскручивается по мере свайпа, крутится во время синка
                    val progress = if (refreshing) null else (pullPx / triggerPx).coerceIn(0f, 1f)
                    if (refreshing || (progress != null && progress > 0.02f)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    progress = { progress ?: 0f },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        // СБОРКА 8: нижний ряд кнопок в стиле Android-FAB, ДВЕ колонки:
        //  - СЛЕВА: «Туннель» (щит; мятный, когда туннель активен) и «Настройки»
        //    (шестерёнка) — icon-only (по фидбеку «оставь их просто иконками»);
        //  - СПРАВА: «Контакты» (secondaryContainer) НАД «Новый чат» — как раньше.
        //  Ряды выровнены напротив друг друга:
        //      Туннель ↔ Контакты, Настройки ↔ Новый чат.
        // pressScale даёт пружинное сжатие при нажатии (как в Android-клиенте).
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // левая колонка: туннель + настройки
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val tunnelState by TunnelManager.state.collectAsState()
                val tunnelInteraction = remember { MutableInteractionSource() }
                HoverTooltip("Настройки туннеля") {
                    ExtendedFloatingActionButton(
                        onClick = onOpenTunnel,
                        modifier = Modifier.pressScale(tunnelInteraction),
                        interactionSource = tunnelInteraction,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = "Настройки туннеля",
                            tint = if (tunnelState.status == "up") MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                val settingsInteraction = remember { MutableInteractionSource() }
                HoverTooltip("Настройки") {
                    ExtendedFloatingActionButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.pressScale(settingsInteraction),
                        interactionSource = settingsInteraction,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // правая колонка: контакты + новый чат
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val contactsInteraction = remember { MutableInteractionSource() }
                ExtendedFloatingActionButton(
                    onClick = onOpenContacts,
                    modifier = Modifier.pressScale(contactsInteraction),
                    interactionSource = contactsInteraction,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Filled.People, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Контакты")
                }
                val fabInteraction = remember { MutableInteractionSource() }
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    modifier = Modifier.pressScale(fabInteraction),
                    interactionSource = fabInteraction,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Новый чат")
                }
            }
        }
    }

    // v11: подтверждение массового удаления чатов
    if (confirmBulkDelete) {
        val count = selectedChatIds.size
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Удалить чаты?") },
            text = {
                Text(
                    "Удалить $count чатов? Личные чаты будут удалены у ОБОИХ участников " +
                        "вместе с историей и файлами на сервере. Группы (если вы владелец) " +
                        "будут удалены у ВСЕХ участников. Это действие необратимо."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    val toDelete = chats.filter { it.id in selectedChatIds }
                    scope.launch {
                        var done = 0
                        var lastError: Throwable? = null
                        for (c in toDelete) {
                            // Группа, где пользователь НЕ владелец → leaveChat
                            // (на сервере: владелец удаляет, остальные выходят).
                            val res = if (c.is_group && c.owner != account?.username) {
                                Repository.leaveChat(c)
                            } else {
                                Repository.deleteChat(c)
                            }
                            res.fold(
                                onSuccess = { done++ },
                                onFailure = { lastError = it },
                            )
                        }
                        bulkInfo = if (lastError != null && done < toDelete.size) {
                            "удалено $done из ${toDelete.size}: " + Repository.humanError(lastError!!)
                        } else {
                            "удалено: $done"
                        }
                        selectedChatIds = emptySet()
                        selectionMode = false
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Отмена") }
            },
        )
    }
}

/** Строка чата: аватар, имя, метки группы/P2P, время, превью и счётчик непрочитанных. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    modifier: Modifier = Modifier,
    chat: Chat,
    preview: String?,
    unread: Int,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rowBg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        // открытый чат (правая панель master-detail) подсвечен заметнее
        active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Avatar(chat.displayName, 44.dp, online = chat.peer?.online)
            // v11: галочка выбора поверх аватара
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Выбрано",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill=true: имя забирает всё лишнее место (с многоточием при
                    // переполнении), а чипы и дата прижаты к ПРАВОМУ краю.
                    modifier = Modifier.weight(1f),
                )
                if (chat.is_group) {
                    Spacer(Modifier.width(6.dp))
                    Text("👥", style = MaterialTheme.typography.labelSmall)
                }
                if (chat.is_p2p) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "без истории",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val ts = chat.last_message?.created_at ?: chat.updated_at
                Text(
                    formatTs(ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fallback = when {
                    chat.last_message?.type == "file" -> "📎 файл"
                    chat.last_message?.type == "deleted" -> "сообщение удалено"
                    chat.last_message?.kind == "system" -> chat.last_message?.body ?: ""
                    chat.last_message != null -> "🔒 зашифрованное сообщение"
                    else -> ""
                }
                Text(
                    preview ?: fallback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge { Text("$unread") }
                }
            }
        }
    }
}

/** Компактный статус связи (шапка панели списка; топбар приложения убран). */
@Composable
private fun ChatStatusCaption() {
    val conn by Repository.conn.collectAsState()
    val text = when (conn) {
        WsState.CONNECTED -> "на связи"
        WsState.CONNECTING, WsState.RECONNECTING -> "подключение…"
        else -> "нет связи"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (conn == WsState.CONNECTED) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Компактное поле поиска по чатам (панель 320dp — стандартный TextField слишком высок). */
@Composable
private fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(36.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Box {
                if (query.isEmpty()) {
                    Text(
                        "поиск чатов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    // СБОРКА 7: собственный TextStyle ПОЛНОСТЬЮ заменяет стиль темы —
                    // без явного fontFamily терялась цепочка фолбэков, и эмодзи в
                    // поиске показывались квадратами. fontFamily обязателен.
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontFamily = AppFonts.family,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * v15 (сборка 5): прежний экран чатов — тонкая обёртка над [ChatListPane]
 * для УЗКОГО режима (<1000dp): панель списка на всю ширину содержимого,
 * открытый чат рисуется поверх неё самой оболочкой (Main.kt).
 * «Туннель»/«Настройки» открылись бы правой панелью оболочки, поэтому
 * onOpenTunnel/onOpenSettings оставлены только для совместимости сигнатуры.
 */
@Composable
fun ChatsScreen(
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onOpenContacts: () -> Unit = {},
    onOpenTunnel: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    ChatListPane(
        selectedChatId = null,
        onChat = onOpenChat,
        onNewChat = onNewChat,
        onOpenContacts = onOpenContacts,
        modifier = Modifier.fillMaxSize(),
        onOpenSettings = onOpenSettings,
        onOpenTunnel = onOpenTunnel,
    )
}
