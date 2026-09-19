package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.ui.components.FilePickers
import com.tunnelmessenger.desktop.ui.theme.Teal600
import com.tunnelmessenger.desktop.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.launch

/**
 * Вход/регистрация. Сервер доступен ТОЛЬКО изнутри туннеля — на экране есть
 * меню туннеля (статус/подключение/импорт конфига) для первого подключения.
 *
 * Порт Android LoginScreen.kt. Отличия на десктопе:
 *  - импорт аккаунта — через javax.swing.JFileChooser (FilePickers.pickFile);
 *  - нет imePadding (нет экранной клавиатуры);
 *  - подключение туннеля выполняется на экране «Туннель» — там лежит редактор
 *    конфига (TunnelManager.startWithConf требует текст .conf).
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onOpenTunnel: () -> Unit = {}) {
    // v8.2: дефолтный адрес сервера внутри VPN (первый адрес подсети туннеля)
    var server by remember { mutableStateOf("http://10.10.10.1:80") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hintTunnel by remember { mutableStateOf(false) }
    var healthText by remember { mutableStateOf<String?>(null) }
    var healthOk by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val tunnelUi by TunnelManager.state.collectAsState()

    // v12 anim: одноразовое появление контента при входе — fade + мягкий подъём (350мс).
    // Значение читается в graphicsLayer: alpha/translationY применяются в draw-фазе,
    // без рекомпозиций на каждый кадр.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(350),
        label = "loginEntrance",
    )

    fun submit() {
        if (busy) return
        busy = true; error = null; hintTunnel = false
        scope.launch {
            val res = if (isRegister) {
                Repository.register(server, username.trim(), password, nickname.trim().ifBlank { null })
            } else {
                Repository.login(server, username.trim(), password)
            }
            busy = false
            res.fold(
                onSuccess = {
                    onLoggedIn()
                },
                onFailure = { e ->
                    val msg = Repository.humanError(e)
                    error = msg
                    hintTunnel = msg.contains("нет связи", true)
                },
            )
        }
    }

    // Контент центрируется weight-спейсерами, а НЕ Arrangement.Center:
    // Center + verticalScroll обрезает верх экрана, когда контент выше вьюпорта.
    // Surface обязателен: он задаёт LocalContentColor — без него все тексты
    // без явного цвета рисовались ЧЁРНЫМ и были не видны на тёмном фоне.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // СБОРКА 5: форма входа центрирована и ограничена 440dp — на широком
        // окне больше не растягивается на всю ширину (десктоп-обликовка).
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // v12 anim: entrance-анимация (fade + подъём) — только draw-фаза
                .graphicsLayer {
                    alpha = entrance
                    translationY = (1f - entrance) * 24f
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Spacer(Modifier.weight(1f))
        // Фирменный знак — глифы ▚▞ (квадрантные блоки) с изумрудным свечением
        // и разрядкой. v15: в тёмной теме — тот же --em #34d399, что и раньше;
        // в светлой — Teal600 (тот же акцентный зелёный, читаемый на светлом).
        val logoColor = if (isAppInDarkTheme()) Color(0xFF34D399) else Teal600
        Text(
            "▚▞",
            color = logoColor,
            fontSize = 34.sp,
            letterSpacing = 6.8.sp, // .2em от 34sp — как letter-spacing:.2em в вебе
            style = TextStyle(
                shadow = Shadow(
                    color = logoColor.copy(alpha = 0.45f),
                    blurRadius = 18f,
                ),
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text("Tunnel Messenger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Мессенджер внутри вашего туннеля",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        // ------- меню туннеля: видно при первом подключении к серверу
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v12 anim: цвет статуса туннеля меняется плавно (tween 250)
                    // На десктопе TunnelManager.state.status — строка
                    // ("off" | "connecting" | "up" | "error").
                    val dotColor by animateColorAsState(
                        targetValue = when (tunnelUi.status) {
                            "up" -> MaterialTheme.colorScheme.tertiary
                            "connecting" -> MaterialTheme.colorScheme.secondary
                            "error" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(250),
                        label = "tunnelDotColor",
                    )
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(dotColor, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (tunnelUi.status) {
                            "up" -> "Туннель подключён"
                            "connecting" -> "Туннель: подключение…"
                            "error" -> "Туннель: ошибка"
                            else -> "Туннель отключён"
                        },
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                tunnelUi.note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (tunnelUi.status == "off") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Для входа нужен конфиг туннеля (.conf). " +
                            "Импортируйте его и подключите туннель.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // На десктопе текст .conf доступен на экране «Туннель»
                // (TunnelManager.startWithConf принимает текст конфига),
                // поэтому «Подключить» открывает тот экран.
                if (tunnelUi.status == "up") {
                    OutlinedButton(
                        onClick = { TunnelManager.stopEngine() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отключить") }
                } else {
                    Button(
                        onClick = onOpenTunnel,
                        enabled = tunnelUi.status != "connecting",
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Подключить туннель") }
                }
                TextButton(onClick = onOpenTunnel, modifier = Modifier.fillMaxWidth()) {
                    Text("Конфиг туннеля")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = server, onValueChange = { server = it },
            label = { Text("Адрес сервера (внутри VPN)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Имя пользователя") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Пароль") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        if (isRegister) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it },
                label = { Text("Никнейм (необязательно)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && username.isNotBlank() && password.isNotBlank() && server.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isRegister) "Зарегистрироваться" else "Войти")
        }
        TextButton(onClick = { isRegister = !isRegister; error = null }) {
            Text(
                if (isRegister) "У меня уже есть аккаунт — войти"
                else "Нет аккаунта — зарегистрироваться"
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (hintTunnel) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Сервер отвечает только изнутри туннеля. Подключите туннель выше " +
                    "или импортируйте конфиг туннеля в разделе «Туннель».",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (tunnelUi.status != "up") {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenTunnel) {
                    Text("Открыть «Туннель»")
                }
            }
        }

        healthText?.let {
            Spacer(Modifier.height(12.dp))
            // результат проверки — цветной: успех зелёный, ошибка красная
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
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    healthText = "проверяем…"
                    val res = Repository.checkHealth(server)
                    healthOk = res.isSuccess
                    healthText = res.fold(
                        onSuccess = { d -> "✓ сервер доступен (домен: $d)" },
                        onFailure = { "✗ сервер недоступен: ${Repository.humanError(it)}" },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Проверить связь с сервером") }

        // Импорт аккаунта: вместо ActivityResultContracts.OpenDocument — JFileChooser
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val file = FilePickers.pickFile("Импорт аккаунта", "json")
                    if (file != null) {
                        busy = true; error = null
                        Repository.importAccount(file).fold(
                            onSuccess = { busy = false; onLoggedIn() },
                            onFailure = { busy = false; error = Repository.humanError(it) },
                        )
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Импортировать аккаунт из файла") }

        Spacer(Modifier.weight(1f))
        }
        }
    }
}
