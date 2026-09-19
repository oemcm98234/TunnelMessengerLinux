package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.model.BlockEntry
import com.tunnelmessenger.desktop.ui.components.Avatar
import com.tunnelmessenger.desktop.ui.components.formatTs
import kotlinx.coroutines.launch

/**
 * v11: экран «Заблокированные пользователи».
 *
 * Показывает Repository.blocks (List<BlockEntry>) — каждой строке соответствует
 * адрес (username или username@домен для федерации) и кнопка «Разблокировать»,
 * вызывающая Repository.unblockUser(address). Список живой: при изменении
 * _blocks в Repository (блокировка из чата, разблокировка отсюда) — UI
 * обновляется автоматически через collectAsState().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(onBack: () -> Unit) {
    val blocks by Repository.blocks.collectAsState()
    val scope = rememberCoroutineScope()
    // адрес, который сейчас разблокируется (чтобы показать спиннер на кнопке)
    var pendingAddr by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
                .widthIn(max = 720.dp),
        ) {
            if (error != null) {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (blocks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Список пуст", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Заблокированные пользователи не смогут писать вам\nи звонить. Блокировку можно включить в чате или из контактов.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp, vertical = 4.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(blocks, key = { it.address }) { entry ->
                        BlockedRow(
                            entry = entry,
                            // v12 anim: элементы списка плавно появляются/переезжают
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                fadeOutSpec = tween(180),
                                placementSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                ),
                            ),
                            busy = pendingAddr == entry.address,
                            onUnblock = {
                                if (pendingAddr != null) return@BlockedRow
                                pendingAddr = entry.address
                                error = null
                                scope.launch {
                                    Repository.unblockUser(entry.address).fold(
                                        onSuccess = { pendingAddr = null },
                                        onFailure = {
                                            pendingAddr = null
                                            error = "не удалось разблокировать: " + Repository.humanError(it)
                                        },
                                    )
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
        }
    }
}

@Composable
private fun BlockedRow(
    entry: BlockEntry,
    modifier: Modifier = Modifier,
    busy: Boolean,
    onUnblock: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(entry.address.ifBlank { "?" }, 40.dp, online = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.address.ifBlank { "—" },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val ts = formatTs(entry.created_at)
            if (ts.isNotEmpty()) {
                Text(
                    "заблокирован $ts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "заблокирован",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onUnblock, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text("Разблокировать")
        }
    }
}
