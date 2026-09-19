package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.model.UserShort
import com.tunnelmessenger.desktop.ui.components.Avatar
import com.tunnelmessenger.desktop.ui.components.HoverTooltip
import kotlinx.coroutines.launch

/**
 * Новый чат: адрес (bob или bob@домен), выбор режима (с историей на сервере /
 * без истории = P2P), создание группы и поиск пользователей (свой сервер и
 * user@домен — по мосту федерации). Контакты вынесены в отдельный экран
 * [ContactsScreen] (v12).
 *
 * СБОРКА 8: рисуется ЛЕВОЙ боковой панелью — шапка со стрелкой «Назад»
 * не нужна: закрытие кликом мимо панели или Esc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(onCreated: (Long) -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0 — личный чат, 1 — группа
    var address by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserShort>>(emptyList()) }
    var isP2p by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val contacts by Repository.contacts.collectAsState()

    LaunchedEffect(Unit) { Repository.loadContacts() }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            runCatching { Repository.searchUsers(query.trim()) }
                .onSuccess { results = it }
                .onFailure { error = Repository.humanError(it) }
        }
    }

    fun create() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            if (mode == 0) {
                Repository.createChat(address.trim(), isP2p).fold(
                    onSuccess = { c -> busy = false; onCreated(c.id) },
                    onFailure = { busy = false; error = Repository.humanError(it) },
                )
            } else {
                Repository.createGroup(title, selected).fold(
                    onSuccess = { c -> busy = false; onCreated(c.id) },
                    onFailure = { busy = false; error = Repository.humanError(it) },
                )
            }
        }
    }

    Scaffold { padding ->
        // СБОРКА 8: левая панель — без шапки «Назад»; отступ сверху взамен шапки.
        Column(Modifier.fillMaxSize().padding(padding)) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxSize()
                    // десктоп: imePadding не нужен — экранной клавиатуры нет
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Личный чат") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Группа") })
            }
            Spacer(Modifier.height(12.dp))

            // v12 anim: смена секции «личный чат»/«группа» — мягкий fade + slide.
            // v12 FIX: внутри AnimatedContent обязательна своя Column —
            // AnimatedContent укладывает детей как Box, и без Column все
            // элементы (поле адреса, чипы режима, кнопка) накладывались друг
            // на друга в одной точке.
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 })
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "newChatMode",
            ) { m ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m == 0) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Адрес: bob или bob@10.10.10.2") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "Режим чата (выбирается при создании):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        FilterChip(
                            selected = !isP2p, onClick = { isP2p = false },
                            label = { Text("с историей на сервере") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = isP2p, onClick = { isP2p = true },
                            label = { Text("без истории") },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { create() },
                            enabled = !busy && address.trim().length >= 2,
                        ) { Text("Создать / открыть") }
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    Repository.contactAdd(address.trim()).fold(
                                        onSuccess = {},
                                        onFailure = { error = Repository.humanError(it) },
                                    )
                                }
                            },
                            enabled = address.trim().length >= 2,
                        ) { Text("в контакты") }
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название группы") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (selected.isNotEmpty()) {
                        Text("Участники (до 64):", style = MaterialTheme.typography.labelMedium)
                        selected.forEach { u ->
                            AssistChip(
                                onClick = { selected -= u },
                                label = { Text(u) },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    Button(
                        onClick = { create() },
                        enabled = !busy && title.trim().isNotBlank(),
                    ) { Text("Создать группу") }
                }
                } // Column — конец фикса наложения
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ---------------------------------------------------------- поиск
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск пользователей по имени") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                HoverTooltip("Найти") {
                    IconButton(onClick = { search() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Найти")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // v12: «(подстрока)» убрано; добавлен поиск по федерации
                "Поиск — по username; для другого сервера введите user@домен",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            results.forEach { u ->
                val name = u.nickname?.takeIf { it.isNotBlank() } ?: u.username ?: u.address ?: "?"
                val uname = u.username ?: u.address
                val isContact = uname != null && contacts.any { it.address.equals(uname, ignoreCase = true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (mode == 1) {
                                u.username?.let { if (it !in selected) selected += it }
                            } else {
                                address = u.username ?: u.address ?: ""
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, 36.dp, online = u.online)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (u.username ?: u.address ?: "") +
                                if (u.online == true) " · в сети" else "",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mode == 1) {
                        TextButton(onClick = {
                            u.username?.let { if (it !in selected) selected += it }
                        }) { Text("добавить") }
                    } else {
                        TextButton(onClick = {
                            address = u.username ?: u.address ?: ""
                        }) { Text("выбрать") }
                    }
                    if (uname != null && mode == 0) {
                        if (isContact) {
                            Text(
                                "✓",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        } else {
                            TextButton(onClick = {
                                scope.launch {
                                    Repository.contactAdd(uname).fold(
                                        onSuccess = {},
                                        onFailure = { error = Repository.humanError(it) },
                                    )
                                }
                            }) { Text("в контакты") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        }
    }
}
