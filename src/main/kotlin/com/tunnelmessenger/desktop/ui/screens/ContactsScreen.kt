package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * Контакты (v12 — вынесены из «Нового чата» в отдельный экран):
 * список контактов с быстрым чатом/блокировкой/удалением, поиск
 * пользователей (username или user@домен) и добавление в контакты.
 *
 * СБОРКА 8: рисуется ЛЕВОЙ боковой панелью (как «Туннель»/«Настройки»
 * справа) — шапка со стрелкой «Назад» не нужна: закрытие кликом мимо
 * панели или Esc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onOpenChat: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserShort>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val contacts by Repository.contacts.collectAsState()
    val blocks by Repository.blocks.collectAsState()
    // v11: адрес, для которого открыт диалог подтверждения блокировки
    var confirmBlockAddr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { Repository.loadContacts() }

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            runCatching { Repository.searchUsers(query.trim()) }
                .onSuccess { results = it }
                .onFailure { error = Repository.humanError(it) }
        }
    }

    fun openQuickChat(addr: String) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            Repository.createChat(addr, false).fold(
                onSuccess = { c -> busy = false; onOpenChat(c.id) },
                onFailure = { busy = false; error = Repository.humanError(it) },
            )
        }
    }

    Scaffold { padding ->
        // СБОРКА 8: левая панель — без шапки «Назад» (панель закрывается
        // кликом мимо / Esc — как правые панели настроек).
        Column(Modifier.fillMaxSize().padding(padding)) {
            // небольшой отступ сверху взамен убранной шапки
            Spacer(Modifier.height(12.dp))
            // контент — центральная колонка не шире 720dp (настольный макет)
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                // десктоп: imePadding не нужен — экранной клавиатуры нет
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            // ------------------------------------------------------- контакты
            if (contacts.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "пока нет — найдите пользователя в поиске ниже и нажмите «в контакты»",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            contacts.forEach { c ->
                val addr = c.address ?: "?"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openQuickChat(addr) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(addr, 36.dp, online = c.online)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            addr,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                c.online == true -> "в сети"
                                addr.contains("@") -> "федерация"
                                else -> "не в сети"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { openQuickChat(addr) }) { Text("чат") }
                    // v11: блокировка из контактов
                    val isBlocked = blocks.any { it.address.equals(addr, ignoreCase = true) }
                    HoverTooltip(if (isBlocked) "Уже заблокирован" else "Заблокировать") {
                        IconButton(onClick = { confirmBlockAddr = addr }) {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = "Заблокировать",
                                tint = if (isBlocked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HoverTooltip("Удалить из контактов") {
                        IconButton(onClick = {
                            scope.launch {
                                Repository.contactRemove(addr).fold(
                                    onSuccess = {},
                                    onFailure = { error = Repository.humanError(it) },
                                )
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Удалить из контактов",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- поиск
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Найти пользователя") },
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
                    if (uname != null) {
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
                                        onSuccess = { Repository.loadContacts() },
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

    // v11: диалог подтверждения блокировки пользователя из контактов
    val blockAddr = confirmBlockAddr
    if (blockAddr != null) {
        AlertDialog(
            onDismissRequest = { confirmBlockAddr = null },
            title = { Text("Заблокировать?") },
            text = {
                Text(
                    "Заблокировать $blockAddr? После блокировки пользователь не сможет " +
                        "писать вам и звонить."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlockAddr = null
                    val addr = blockAddr
                    scope.launch {
                        Repository.blockUser(addr).fold(
                            onSuccess = {
                                // обновляем список контактов после блокировки
                                Repository.loadContacts()
                            },
                            onFailure = { error = Repository.humanError(it) },
                        )
                    }
                }) { Text("Заблокировать") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBlockAddr = null }) { Text("Отмена") }
            },
        )
    }
}
