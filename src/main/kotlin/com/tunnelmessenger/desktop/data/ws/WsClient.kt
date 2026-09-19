package com.tunnelmessenger.desktop.data.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.tunnelmessenger.desktop.net.HttpRouter
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Состояние соединения (контракт 2.2). */
enum class WsState { CONNECTING, CONNECTED, RECONNECTING, OFFLINE }

/**
 * WebSocket-клиент сервера (GET /ws?token=...). Кадры — текстовые JSON.
 * Автопереподключение с нарастающей задержкой, встроенный heartbeat.
 */
class WsClient(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val events: SharedFlow<JsonObject> = _events

    /**
     * v8.2: прямой приёмник «горячих» событий (call_frame — ~50 шт/с).
     * SharedFlow с tryEmit терял кадры, когда общий коллектор временно
     * не успевал, — в звонке это звучало как тишина/пропуски. Горячие
     * события идут мимо SharedFlow в выделенный однопоточный исполнитель
     * CallManager (порядок сохраняется, ничего не теряется, WS-ридер
     * не блокируется).
     */
    @Volatile var hotSink: ((JsonObject) -> Unit)? = null

    private val _state = MutableStateFlow(WsState.OFFLINE)
    val state: StateFlow<WsState> = _state

    /** Клиент берётся на каждое подключение — чтобы подхватить прокси туннеля. */
    private val client get() = HttpRouter.client()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val running = AtomicBoolean(false)

    private var baseUrl: String = ""
    private var token: String = ""
    private var attempt = 0

    fun start(baseUrl: String, token: String) {
        this.baseUrl = baseUrl
        this.token = token
        if (running.getAndSet(true)) return
        scheduleConnect(0)
    }

    fun stop() {
        running.set(false)
        reconnectJob?.cancel()
        webSocket?.close(1000, "bye")
        webSocket = null
        _state.value = WsState.OFFLINE
    }

    /**
     * Перезапустить соединение с нуля (вызывается при смене туннеля:
     * прямое соединение ↔ SOCKS5-прокси движка). Базовый URL/токен —
     * сохранённые с последнего start().
     */
    fun restart() {
        if (!running.get()) {
            if (baseUrl.isNotBlank()) start(baseUrl, token)
            return
        }
        reconnectJob?.cancel()
        val old = webSocket
        webSocket = null // колбэки старого сокета игнорируются (guard в listener)
        old?.close(1000, "tunnel")
        attempt = 0
        scheduleConnect(0)
    }

    private fun scheduleConnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!running.get()) return@launch
            connect()
        }
    }

    private fun connect() {
        val wsUrl = baseUrl.trimEnd('/')
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/ws?token=" + URLEncoder.encode(token, "UTF-8")
        _state.value = WsState.CONNECTING
        // v13 (аудит): токен дублируем в заголовке Authorization — сервер v13
        // приоритизирует заголовок, и токен перестаёт зависеть от URL.
        // ?token= остаётся для совместимости со старыми серверами.
        val req = Request.Builder().url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        // Guard: события от уже заменённого/закрытого сокета игнорируем
        private fun isStale(ws: WebSocket) = ws !== this@WsClient.webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale(webSocket)) return
            attempt = 0
            _state.value = WsState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale(webSocket)) return
            val obj = try {
                json.decodeFromString(JsonObject.serializer(), text)
            } catch (_: Exception) {
                null
            } ?: return
            val sink = hotSink
            if (sink != null && obj["t"]?.jsonPrimitive?.contentOrNull == "call_frame") {
                sink(obj)
                return
            }
            _events.tryEmit(obj)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale(webSocket)) return
            _state.value = WsState.RECONNECTING
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isStale(webSocket)) return
            _state.value = WsState.RECONNECTING
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) {
            _state.value = WsState.OFFLINE
            return
        }
        val delayMs = minOf(30_000L, 1_500L * (1L shl minOf(attempt, 4)))
        attempt++
        scheduleConnect(delayMs)
    }

    /** Отправить JSON-событие на сервер. false — соединение закрыто. */
    fun send(event: JsonObject): Boolean {
        val ws = webSocket ?: return false
        return ws.send(event.toString())
    }

    fun sendTyping(chatId: Long) {
        send(buildJsonObject {
            put("t", "typing")
            put("chat_id", chatId)
        })
    }

    fun sendPing() {
        send(buildJsonObject {
            put("t", "ping")
            put("ts", System.currentTimeMillis() / 1000.0)
        })
    }

    fun sendP2pRead(chatId: Long, lastClientId: String) {
        send(buildJsonObject {
            put("t", "p2p_read")
            put("chat_id", chatId)
            put("last_client_id", lastClientId)
        })
    }
}
