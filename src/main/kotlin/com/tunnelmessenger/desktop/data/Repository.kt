package com.tunnelmessenger.desktop.data

import com.tunnelmessenger.desktop.call.CallManager
import com.tunnelmessenger.desktop.data.api.Api
import com.tunnelmessenger.desktop.data.api.ApiError
import com.tunnelmessenger.desktop.data.crypto.E2eCrypto
import com.tunnelmessenger.desktop.data.crypto.SecureStore
import com.tunnelmessenger.desktop.data.crypto.Tme1
import com.tunnelmessenger.desktop.data.local.AppConfig
import com.tunnelmessenger.desktop.tray.DesktopIntegrations
import com.tunnelmessenger.desktop.data.local.AppDirs
import com.tunnelmessenger.desktop.data.local.Db
import com.tunnelmessenger.desktop.data.local.MsgRow
import com.tunnelmessenger.desktop.data.model.BlockEntry
import com.tunnelmessenger.desktop.data.model.Chat
import com.tunnelmessenger.desktop.data.model.FileMeta
import com.tunnelmessenger.desktop.data.model.FileSecret
import com.tunnelmessenger.desktop.data.model.MeResp
import com.tunnelmessenger.desktop.data.model.Msg
import com.tunnelmessenger.desktop.data.model.P2pFileSecret
import com.tunnelmessenger.desktop.data.model.Reaction
import com.tunnelmessenger.desktop.data.model.StoredFile
import com.tunnelmessenger.desktop.data.model.UserShort
import com.tunnelmessenger.desktop.data.model.VoiceMeta
import com.tunnelmessenger.desktop.data.update.UpdateManager
import com.tunnelmessenger.desktop.data.ws.WsClient
import com.tunnelmessenger.desktop.data.ws.WsState
import com.tunnelmessenger.desktop.tunnel.TunnelHealth
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.ui.components.ImageThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Сообщение в UI-представлении (уже расшифрованное). */
data class UiMsg(
    val mid: Long,
    val clientId: String?,
    val chatId: Long,
    val sender: String,
    val kind: String,
    val type: String,
    val plain: String?,
    val file: FileMeta?,
    val localPath: String?,
    val createdAt: Double,
    val editedAt: Double?,
    val status: String,
    val isMine: Boolean,
    val reactions: List<Reaction>,
    val failed: Boolean,
    val p2p: Boolean,
    // v11: ответ на сообщение — метаданные внутри E2E-plaintext, см. packReply/unpackReply.
    // 0/"" — ответа нет (старые сообщения остаются совместимы: префикса нет → plain как есть).
    val replyToMid: Long = 0,
    val replyToSender: String = "",
    val replyToPreview: String = "",
)

data class Account(
    val baseUrl: String,
    val token: String,
    val username: String,
    val nickname: String?,
    val e2eSk: String,
    val e2ePk: String,
    val serverDomain: String?,
    val maxFileMb: Long,
)

/**
 * Ядро клиента: сессия, синхронизация (алгоритм из docs/API.md), WebSocket,
 * E2E-расшифровка, локальная история (включая P2P-чаты без серверной копии).
 *
 * Десктоп-порт Android Repository.kt. Замены:
 *  - SharedPreferences «session» → dataDir/config.json (AppConfig; секреты —
 *    только через SecureStore.sealStr, формат enc1: совпадает с Android);
 *  - ContentResolver/Uri → java.io.File;
 *  - системные уведомления → лента [notice] + stdout (Notify-интеграция —
 *    задача UI-слоя, ядру она не нужна).
 */
object Repository {

    private lateinit var db: Db
    private var api: Api? = null
    private var ws: WsClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // v11: префикс ответа в E2E-plaintext. Формат:
    //   "↪\u0001<json>\u0001<видимый текст>"
    // <json> = {"m":<mid:Long>,"s":"<sender>","p":"<preview>"}.
    // Если ответа нет — plaintext = просто текст (старые сообщения остаются
    // совместимы: префикса нет → unpackReply возвращает исходный текст без изменений).
    private const val REPLY_PREFIX = "↪\u0001"
    private const val REPLY_SEP = "\u0001"

    /** v11: результат unpackReply — 4 значения удобнее держать в классе, чем в Triple. */
    private data class ReplyParts(
        val text: String,
        val mid: Long,
        val sender: String,
        val preview: String,
    )

    /**
     * v11: упаковывает метаданные ответа + текст в E2E-plaintext.
     * Если replyTo == null или replyTo.mid <= 0 — возвращает текст как есть
     * (никакого префикса, обратная совместимость).
     */
    private fun packReply(text: String, replyTo: UiMsg?): String {
        if (replyTo == null || replyTo.mid <= 0) return text
        val preview = (replyTo.plain ?: "").ifBlank {
            if (replyTo.file != null) {
                if (replyTo.file.voice != null) "(голосовое)"
                else "📎 " + (replyTo.file.name ?: "файл")
            } else ""
        }.take(80)
        val json = buildJsonObject {
            put("m", replyTo.mid)
            put("s", replyTo.sender)
            put("p", preview)
        }
        return REPLY_PREFIX + json.toString() + REPLY_SEP + text
    }

    /**
     * v11: разбирает метаданные ответа из расшифрованного plaintext.
     * Возвращает (видимый текст, replyMid, replySender, replyPreview).
     * Если префикса нет — возвращает (plain, 0, "", "").
     */
    private fun unpackReply(plain: String): ReplyParts {
        if (!plain.startsWith(REPLY_PREFIX)) return ReplyParts(plain, 0, "", "")
        val rest = plain.substring(REPLY_PREFIX.length)
        val sepIdx = rest.indexOf(REPLY_SEP)
        if (sepIdx < 0) return ReplyParts(plain, 0, "", "")
        val jsonStr = rest.substring(0, sepIdx)
        val text = rest.substring(sepIdx + REPLY_SEP.length)
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            ReplyParts(
                text,
                obj["m"]?.jsonPrimitive?.longOrNull ?: 0L,
                obj["s"]?.jsonPrimitive?.contentOrNull ?: "",
                obj["p"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        } catch (_: Exception) {
            ReplyParts(text, 0, "", "")
        }
    }

    /** v11: то же что unpackReply, но для nullable plain (без реакции на null). */
    private fun unpackReplyOrNull(plain: String?): ReplyParts {
        if (plain == null) return ReplyParts("", 0, "", "")
        return unpackReply(plain)
    }

    /**
     * v8.x: запуск долгих операций ОТПРАВКИ/скачивания в application-scope.
     *
     * Раньше отправка файлов/голосовых шла через rememberCoroutineScope экрана
     * чата: при выходе из чата корутина отменялась — файл «не отправлялся»
     * (оптимистичное сообщение оставалось pending). Теперь отправка живёт
     * в скоупе Repository и переживает выход из экрана; прогресс/ошибки
     * пишутся в состояние экрана, если он ещё открыт.
     */
    fun launchSend(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** Экспорт аккаунта: человекочитаемый JSON с отступом 2 (как org.json.toString(2)). */
    private val exportJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * v11.3: базовый URL сервера для health-проб [TunnelHealth]
     * (null — аккаунт ещё не настроен, пробовать нечего). Запрос выполняется
     * ЧЕРЕЗ ТУННЕЛЬ (HttpRouter → SOCKS5 движка) — это и есть проверка туннеля.
     */
    fun baseForProbe(): String? = AppConfig.getString("base_url")

    // ------------------------------------------------------------------ state

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account

    private val _conn = MutableStateFlow(WsState.OFFLINE)
    val conn: StateFlow<WsState> = _conn

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats

    private val _activeChatId = MutableStateFlow<Long?>(null)

    private val _messages = MutableStateFlow<List<UiMsg>>(emptyList())
    val messages: StateFlow<List<UiMsg>> = _messages

    private val _typing = MutableStateFlow<Map<Long, Pair<String, Long>>>(emptyMap())
    val typing: StateFlow<Map<Long, Pair<String, Long>>> = _typing

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress

    private val _previews = MutableStateFlow<Map<Long, String>>(emptyMap())
    val previews: StateFlow<Map<Long, String>> = _previews

    private val _contacts = MutableStateFlow<List<UserShort>>(emptyList())
    val contacts: StateFlow<List<UserShort>> = _contacts

    // v11: блокировки текущего пользователя (List<BlockEntry>).
    private val _blocks = MutableStateFlow<List<BlockEntry>>(emptyList())
    val blocks: StateFlow<List<BlockEntry>> = _blocks

    // v11: менеджер обновлений приложения (обновления с сервера).
    lateinit var updateManager: UpdateManager
        private set

    private val _fedStatus = MutableStateFlow<String?>(null)
    val fedStatus: StateFlow<String?> = _fedStatus

    // v12: домен своего сервера (из hello) — для адресации боксов в
    // федеративных группах ('bob@домен'). Публичного доступа нет —
    // используется только внутри Repository (v13).
    private val _serverDomain = MutableStateFlow("")

    /**
     * Десктоп-замена Toast (Android): короткие предупреждения ядра —
     * смена ключа E2E, отсутствие ключа у участника группы, удаление
     * аккаунта собеседника. UI может показывать последнюю запись как
     * снекбар/диалог; [clearNotice] гасит её.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    fun clearNotice() { _notice.value = null }

    private fun showNotice(text: String) {
        println("[Repository] $text")
        _notice.value = text
    }

    /** v15: показать короткое уведомление-снекбар из любого места UI
     *  (например, результат сохранения медиа через диалог). */
    fun postNotice(text: String) { showNotice(text) }

    var notificationsEnabled: Boolean
        get() = AppConfig.getBoolean("notif_enabled", true)
        set(v) = AppConfig.putBoolean("notif_enabled", v)

    /**
     * v15: режим темы оформления — "auto" (по системе, по умолчанию) /
     * "dark" / "light". Хранится в config.json (theme_mode), смена
     * применяется сразу (Flow читает TunnelMessengerTheme).
     */
    private val THEME_MODES = listOf("auto", "dark", "light")
    private val _themeMode = MutableStateFlow(AppConfig.getString("theme_mode")?.takeIf { it in THEME_MODES } ?: "auto")
    val themeModeFlow: StateFlow<String> = _themeMode
    var themeMode: String
        get() = _themeMode.value
        set(v) {
            val mode = THEME_MODES.firstOrNull { it == v } ?: "auto"
            _themeMode.value = mode
            AppConfig.putString("theme_mode", mode)
        }

    private val _backgroundEnabled = MutableStateFlow(AppConfig.getBoolean("background_enabled", true))

    /**
     * СБОРКА 9: работать в фоне — закрытие окна сворачивает приложение в трей
     * (WebSocket остаётся активным, уведомления приходят). По умолчанию ВКЛ —
     * как foreground-служба на Android. Выключение убирает иконку трея, и
     * закрытие окна завершает приложение целиком. Flow — для трея в AppRoot.
     */
    val backgroundEnabledFlow: StateFlow<Boolean> = _backgroundEnabled
    var backgroundEnabled: Boolean
        get() = _backgroundEnabled.value
        set(v) {
            _backgroundEnabled.value = v
            AppConfig.putBoolean("background_enabled", v)
        }

    /** Приватность: показывать ли текст/отправителя в уведомлениях. */
    var notifPreviewEnabled: Boolean
        get() = AppConfig.getBoolean("notif_preview", true)
        set(v) = AppConfig.putBoolean("notif_preview", v)

    /** Автозагрузка небольших изображений для миниатюр. */
    var autoImagesEnabled: Boolean
        get() = AppConfig.getBoolean("auto_images", true)
        set(v) = AppConfig.putBoolean("auto_images", v)

    /**
     * v8.2: защита от скриншотов (Android FLAG_SECURE). На десктопе не
     * применима (нет эквивалента API) — флаг сохраняется для совместимости
     * конфига, UI кнопку скрыл (контракт 2.10).
     */
    var screenshotBlockEnabled: Boolean
        get() = AppConfig.getBoolean("screenshot_block", true)
        set(v) = AppConfig.putBoolean("screenshot_block", v)

    /**
     * «Приложение на экране». На десктопе окно всегда одно и «на экране»,
     * когда пользователь не свернул приложение целиком — сведений о фокусе
     * у ядра нет, поэтому считаем всегда активным: авто-«прочитано» в
     * открытом чате работает, как на Android с приложением на переднем плане.
     */
    private fun isForeground(): Boolean = true

    private const val IMAGE_AUTO_MAX = 15L * 1024 * 1024
    private val imageTries = ConcurrentHashMap<String, Long>()
    private val syncLock = Mutex()

    // локальный счётчик непрочитанных для P2P-чатов (сервер их не считает)
    private val p2pUnread = ConcurrentHashMap<Long, Int>()
    private val p2pMetas = ConcurrentHashMap<String, P2pFileSecret>()
    private val p2pTransfers = ConcurrentHashMap<String, P2pTransfer>()
    private val localIdCounter = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextLocalMid(): Long = localIdCounter.decrementAndGet()

    /**
     * v8.x: пометить оптимистичное сообщение как НЕ отправленное.
     * Раньше при сбое отправки (нет сети, отмена) запись навсегда оставалась
     * «pending» без всякой индикации; теперь у сообщения появляется пометка ⚠.
     */
    private fun markSendFailed(chat: Chat, clientId: String?) {
        val cid = clientId ?: return
        runCatching {
            if (chat.is_p2p) db.p2pStatus(chat.id, cid, "failed")
            else db.msgUpdateStatusByClientId(chat.id, cid, "failed")
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
        }
    }

    // ------------------------------------------------ прогресс передач файлов

    /** Прогресс передачи файла (отправка/скачивание) по ключу "chatId:clientId". */
    data class TransferState(val done: Long, val total: Long, val phase: String)

    private val _transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferState>> = _transfers

    private fun transferUpdate(chatId: Long, clientId: String?, phase: String, done: Long, total: Long) {
        if (total <= 0) return
        _transfers.value += ("$chatId:${clientId ?: "-"}" to TransferState(done, total, phase))
    }

    private fun transferDone(chatId: Long, clientId: String?) {
        _transfers.value -= ("$chatId:${clientId ?: "-"}")
    }

    data class P2pTransfer(
        val chatId: Long,
        val parts: MutableMap<Int, ByteArray> = mutableMapOf(),
        var total: Int = 0,
    )

    // ------------------------------------------------------------------ init

    /** Инициализация ядра (контракт 2.3: без аргументов — пути через AppDirs). */
    fun init() {
        AppDirs.ensureDirs()
        SecureStore.init(AppDirs.dataDir) // мастер-ключ (dataDir/master.key) — до любых чтений секретов
        db = Db(AppDirs.dbFile)
        // v11: менеджер обновлений (нужен до restoreAccount, т.к. проверка
        // обновления доступна даже без активной сессии — для случая, когда
        // старая версия клиента не может залогиниться на новый сервер).
        updateManager = UpdateManager { api }
        migrateSessionSecrets()
        // v13: восстановление туннеля из config.json (tunnel_conf/tunnel_last) —
        // TunnelManager.restorePersisted рассчитан на вызов отсюда (см. его доку);
        // без него сохранённый туннель не поднимался при старте приложения.
        TunnelManager.restorePersisted()
        // v12 audit: SQLite и последующая инициализация (чтение чатов/контактов,
        // restoreAccount) — вне вызывающего потока (на Android был Main).
        scope.launch {
            loadChatsFromDb()
            restoreAccount()
            if (_account.value != null) {
                loadContacts()
                loadBlocks()
            }
            observeTunnelForWs()
        }
        // v11.3: монитор здоровья сервера через туннель (идемпотентно)
        TunnelHealth.start { baseForProbe() }
        // v11: авто-проверка обновления при старте (тихо, без UI-всплеска)
        scope.launch { updateManager.checkForUpdates() }
    }

    // ------------------------------------------------------------ секреты

    /** Секретные значения config.json хранятся зашифрованными ("enc1:…"). */
    private fun secGet(key: String): String? =
        SecureStore.openStr(AppConfig.getString(key))

    private fun putSec(key: String, value: String?) = AppConfig.putSealed(key, value)

    /** Миграция старых открытых значений в зашифрованный вид. */
    private fun migrateSessionSecrets() {
        for (k in listOf("token", "e2e_sk", "e2e_pk")) {
            val raw = AppConfig.getString(k) ?: continue
            if (!SecureStore.isSealed(raw)) putSec(k, raw)
        }
    }

    /**
     * Смена состояния туннеля → WS мгновенно переподключается:
     * поднялся туннель — соединение уходит в SOCKS5-прокси движка,
     * отключён — передача ПАДАЕТ с ошибкой «туннель не подключён»
     * (v13, аудит: прямой сети нет — fail-closed, утечек мимо туннеля нет).
     */
    private fun observeTunnelForWs() {
        scope.launch {
            var last: String? = null
            TunnelManager.state.collect { t ->
                val changed = t.status != last
                last = t.status
                if (!changed) return@collect
                // любой переход статуса (off/connecting/up/error) меняет
                // доступность SOCKS5-прокси движка — WS перестартует и
                // переподключится уже через актуальный HttpRouter
                if (_account.value != null) ws?.restart()
            }
        }
    }

    private fun restoreAccount() {
        val token = secGet("token") ?: return
        val base = AppConfig.getString("base_url") ?: return
        val username = AppConfig.getString("username") ?: return
        val sk = secGet("e2e_sk") ?: return
        val pk = secGet("e2e_pk") ?: E2eCrypto.publicFromPrivate(sk) ?: return
        _account.value = Account(
            baseUrl = base, token = token, username = username,
            nickname = AppConfig.getString("nickname"),
            e2eSk = sk, e2ePk = pk,
            serverDomain = AppConfig.getString("server_domain"),
            maxFileMb = AppConfig.getLong("max_file_mb", 2048),
        )
        api = Api({ base }, { token })
        startWs()
    }

    // ---------------------------------------------------------- вход/выход

    suspend fun login(baseUrlRaw: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = normalizeBaseUrl(baseUrlRaw)
                val probe = Api({ baseUrl }, { null })
                val auth = probe.login(username, password)
                val token = auth.token ?: throw ApiError(500, "сервер не вернул токен")
                val authed = Api({ baseUrl }, { token })
                val me: MeResp = authed.me()
                // E2E-ключи: переиспользуем сохранённые или создаём новые
                var sk = secGet("e2e_sk")
                var pk = secGet("e2e_pk")
                if (sk == null || pk == null) {
                    val (newSk, newPk) = E2eCrypto.generateKeypair()
                    sk = newSk; pk = newPk
                    putSec("e2e_sk", sk); putSec("e2e_pk", pk)
                }
                Api({ baseUrl }, { token }).publishKey(pk)
                AppConfig.putString("base_url", baseUrl)
                putSec("token", token)
                AppConfig.putString("username", username)
                AppConfig.putString("nickname", me.user?.nickname)
                AppConfig.putString("server_domain", me.server?.domain)
                AppConfig.putLong("max_file_mb", me.server?.max_file_mb ?: 2048)
                _account.value = Account(
                    baseUrl = baseUrl, token = token, username = username,
                    nickname = me.user?.nickname, e2eSk = sk, e2ePk = pk,
                    serverDomain = me.server?.domain, maxFileMb = me.server?.max_file_mb ?: 2048,
                )
                api = Api({ baseUrl }, { token })
                startWs()
                launchSync()
                loadBlocks()  // v11: блокировки
                // v12: довлить pending-импорт (файл с мёртвым токеном) — история и контакты
                mergePendingImport(authed)
                Unit
            }
        }

    suspend fun register(baseUrlRaw: String, username: String, password: String, nickname: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = normalizeBaseUrl(baseUrlRaw)
                val probe = Api({ baseUrl }, { null })
                val auth = probe.register(username, password)
                val token = auth.token ?: throw ApiError(500, "сервер не вернул токен")
                val authed = Api({ baseUrl }, { token })
                val me: MeResp = authed.me()
                val (sk, pk) = E2eCrypto.generateKeypair()
                putSec("e2e_sk", sk); putSec("e2e_pk", pk)
                authed.publishKey(pk)
                if (!nickname.isNullOrBlank()) {
                    runCatching { authed.setNickname(nickname.trim()) }
                }
                AppConfig.putString("base_url", baseUrl)
                putSec("token", token)
                AppConfig.putString("username", username)
                AppConfig.putString("nickname", nickname?.trim()?.ifBlank { null })
                AppConfig.putString("server_domain", me.server?.domain)
                AppConfig.putLong("max_file_mb", me.server?.max_file_mb ?: 2048)
                _account.value = Account(
                    baseUrl = baseUrl, token = token, username = username,
                    nickname = nickname?.trim()?.ifBlank { null },
                    e2eSk = sk, e2ePk = pk,
                    serverDomain = me.server?.domain, maxFileMb = me.server?.max_file_mb ?: 2048,
                )
                api = Api({ baseUrl }, { token })
                startWs()
                launchSync()
                loadBlocks()  // v11: блокировки
                Unit
            }
        }

    fun updateNicknameLocal(nickname: String) {
        AppConfig.putString("nickname", nickname.ifBlank { null })
        _account.value = _account.value?.copy(nickname = nickname.ifBlank { null })
        val id = _activeChatId.value
        if (id != null) loadChatMessages(id)
    }

    /**
     * v8.2: смена пары ключей E2E («Настройки → Сменить ключи шифрования»).
     *
     * Сервер первый: при ошибке публикации локально НИЧЕГО не меняем
     * (безопасный дефолт). После успеха: новая пара в SecureStore/config.json
     * (e2e_sk/e2e_pk) и в _account; кэши чужих ключей (таблица keys +
     * callKeyCache) очищены — ключи перезапросятся при надобности.
     * Событие key_changed остальным клиентам разошлёт сервер.
     */
    suspend fun rotateKeys(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val acc = _account.value ?: throw IllegalStateException("нет аккаунта")
            val (sk, pk) = E2eCrypto.generateKeypair()
            Api({ acc.baseUrl }, { acc.token }).publishKey(pk)
            putSec("e2e_sk", sk); putSec("e2e_pk", pk)
            _account.value = acc.copy(e2eSk = sk, e2ePk = pk)
            db.keysClear()
            callKeyCache.clear()
            Unit
        }
    }

    /**
     * v8.2: безвозвратное удаление своего аккаунта (двойное подтверждение —
     * на экране настроек). Сервер сам разошлёт account_deleted и сотрёт
     * данные; после подтверждения — локальный выход с полной очисткой
     * (logout стирает сессию, БД и скачанные файлы).
     */
    suspend fun deleteAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val acc = _account.value ?: throw IllegalStateException("нет аккаунта")
            Api({ acc.baseUrl }, { acc.token }).accountDelete()
            logout()
            Unit
        }
    }

    suspend fun logout() {
        runCatching { api?.logout() }
        ws?.stop(); ws = null
        // чистим ТОЛЬКО ключи сессии: конфиг туннеля (tunnel_conf) и блоб
        // FreeTurn живут в том же config.json и переживают выход —
        // как на Android (там они в отдельных prefs-файлах)
        for (k in SESSION_KEYS) AppConfig.remove(k)
        _account.value = null
        api = null
        _chats.value = emptyList()
        _messages.value = emptyList()
        _activeChatId.value = null
        _conn.value = WsState.OFFLINE
        _contacts.value = emptyList()
        _blocks.value = emptyList()  // v11: сброс блокировок
        db.wipe()
        // скачанные файлы — тоже кэш: стираем при выходе
        runCatching { downloadsDir().deleteRecursively() }
        runCatching { tmpDir().deleteRecursively() }
        ImageThumb.evictAll()
    }

    /** Скачанные файлы/медиа живут в каталоге данных, а не в системных путях. */
    private fun downloadsDir(): File = File(AppDirs.dataDir, "downloads").apply { mkdirs() }

    /** Временный каталог для TME1-блобов и обменных файлов. */
    private fun tmpDir(): File = File(AppDirs.dataDir, "tmp").apply { mkdirs() }

    /** Сколько сейчас занимает локальный кэш (БД + медиа + служебные файлы), байт. */
    fun localCacheBytes(): Long = try {
        AppDirs.dataDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /**
     * Полная очистка локальной истории и медиа: БД + скачанные файлы +
     * миниатюры. Учётная запись остаётся; для обычных чатов история
     * подтянется с сервера при следующем синке, P2P и «без истории» теряются
     * навсегда.
     */
    suspend fun clearLocalHistory(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // останавливаем WS, чтобы никто не писал в БД во время пересоздания
            ws?.stop(); ws = null
            runCatching { db.close() }
            Db.deleteFiles(AppDirs.dbFile)
            downloadsDir().deleteRecursively()
            tmpDir().deleteRecursively()
            // v11 fix: очищаем и кэш обновлений (иначе он копится и
            // localCacheBytes показывает "33 МБ" даже после очистки истории).
            // ВАЖНО: сбрасываем state UpdateManager, чтобы UI не пытался
            // переустановить удалённый файл.
            AppDirs.updatesDir.deleteRecursively()
            runCatching { updateManager.resetAfterCacheClear() }
            ImageThumb.evictAll()
            db = Db(AppDirs.dbFile)
            p2pUnread.clear(); p2pMetas.clear(); p2pTransfers.clear(); imageTries.clear()
            _chats.value = emptyList()
            _messages.value = emptyList()
            _previews.value = emptyMap()
            _activeChatId.value = null
            _conn.value = WsState.OFFLINE
            _account.value?.let { acc ->
                api = Api({ acc.baseUrl }, { acc.token })
                startWs()
                launchSync()
            }
            Unit
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    /** Проверка доступности сервера (например, после поднятия туннеля). */
    suspend fun checkHealth(baseUrlRaw: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = Api({ normalizeBaseUrl(baseUrlRaw) }, { null }).health()
            resp.domain ?: "ok"
        }
    }

    // ------------------------------------------------------- пользователи

    suspend fun searchUsers(q: String): List<UserShort> =
        api?.searchUsers(q)?.users ?: emptyList()

    // ------------------------------------------------------------- контакты

    fun loadContacts() {
        if (_account.value == null) return
        scope.launch {
            runCatching { _contacts.value = api?.contacts()?.contacts ?: emptyList() }
        }
    }

    suspend fun contactAdd(address: String): Result<Unit> = runCatching {
        api!!.contactAdd(address.trim())
        _contacts.value = api!!.contacts().contacts
    }

    suspend fun contactRemove(address: String): Result<Unit> = runCatching {
        api!!.contactDelete(address.trim())
        _contacts.value = api!!.contacts().contacts
    }

    // ------------------------------------------------------ блокировки (v11)

    /** Загрузить список заблокированных адресов (вызывается после login/restoreAccount). */
    fun loadBlocks() {
        if (_account.value == null) return
        scope.launch {
            runCatching { _blocks.value = api?.blocksList() ?: emptyList() }
        }
    }

    /** Заблокировать пользователя по адресу (username или username@domain).
     *  После блокировки сервер отвергает сообщения/звонки/контакты в обе стороны. */
    suspend fun blockUser(address: String): Result<Unit> = runCatching {
        api!!.blockAdd(address.trim().lowercase())
        _blocks.value = api!!.blocksList()
    }

    /** Разблокировать пользователя. */
    suspend fun unblockUser(address: String): Result<Unit> = runCatching {
        api!!.blockDelete(address.trim().lowercase())
        _blocks.value = api!!.blocksList()
    }

    /** Профиль пользователя (для UI чата/профиля). */
    suspend fun userProfile(username: String): UserShort = api!!.userProfile(username)

    // ------------------------------------------------------------------ WS

    private fun startWs() {
        val acc = _account.value ?: return
        if (ws == null) {
            ws = WsClient(scope)
            // v8.2: call_frame идут мимо SharedFlow (не теряются при 50/с)
            ws!!.hotSink = CallManager.frameSink
            scope.launch {
                var prev: WsState? = null
                ws!!.state.collect { st ->
                    _conn.value = st
                    // после каждого восстановления связи дозабираем упущенное
                    if (st == WsState.CONNECTED && prev != null && prev != WsState.CONNECTED) {
                        launchSync()
                    }
                    prev = st
                }
            }
            scope.launch {
                ws!!.events.collect { handleEvent(it) }
            }
            ws!!.start(acc.baseUrl, acc.token)
        } else {
            ws!!.start(acc.baseUrl, acc.token)
        }
    }

    /** Синхронизация без параллельного дублирования. */
    private fun launchSync() {
        scope.launch {
            if (syncLock.tryLock()) {
                try {
                    runCatching { fullSync() }
                } finally {
                    syncLock.unlock()
                }
            }
        }
    }

    fun sendTyping(chatId: Long) {
        ws?.sendTyping(chatId)
    }

    /** Ручное обновление (иконка «Обновить»). */
    fun refresh() {
        if (_account.value == null) return
        launchSync()
    }

    // -------------------------------------------------------------- синк

    private fun loadChatsFromDb() {
        val rows = db.chatsAll()
        val list = rows.mapNotNull { (id, j) ->
            try {
                json.decodeFromString<Chat>(j)
            } catch (_: Exception) {
                null
            }
        }
        _chats.value = list.sortedByDescending { it.updated_at }
        val previews = mutableMapOf<Long, String>()
        for (c in list) {
            db.kvGet("preview:${c.id}")?.let { previews[c.id] = it }
        }
        _previews.value = previews
    }

    private fun setPreview(chatId: Long, plain: String?, type: String) {
        // v11: для text-сообщений убираем префикс ответа из preview списка чатов.
        val (visiblePlain, _, _, _) = unpackReplyOrNull(plain)
        val text = when {
            type == "file" -> parseFileSecret(plain)?.let { "📎 ${it.fn}" }
                ?: parseP2pFileSecret(plain)?.let { "📎 ${it.fn}" }
                ?: "📎 файл"
            type == "deleted" -> "сообщение удалено"
            plain != null -> visiblePlain.replace("\n", " ").take(80)
            else -> "🔒 зашифрованное сообщение"
        }
        db.kvSet("preview:$chatId", text)
        _previews.value += (chatId to text)
    }

    suspend fun fullSync() {
        if (_account.value == null) return
        try {
            _syncProgress.value = "обновление чатов…"
            val resp = api!!.chats()
            val merged = resp.chats.toMutableList()
            // локальные P2P-чаты, которых нет на сервере (peer ещё не видел)
            loadChatsFromDb()
            for (c in merged) db.chatPut(c.id, json.encodeToString(c))
            _chats.value = merged.sortedByDescending { it.updated_at }
            val historyChats = merged.filter { it.history && !it.is_p2p && !it.is_group }
            val groupChats = merged.filter { it.history && it.is_group }
            var i = 0
            val total = historyChats.size + groupChats.size
            for (chat in historyChats + groupChats) {
                i++
                _syncProgress.value = "история $i/$total: ${chat.displayName}"
                syncChatMessages(chat)
            }
            _syncProgress.value = null
        } catch (_: Exception) {
            _syncProgress.value = null
        }
    }

    private suspend fun syncChatMessages(chat: Chat) {
        var after = db.kvGet("cursor:${chat.id}")?.toLongOrNull() ?: 0L
        while (true) {
            val resp = api!!.messages(chat.id, afterId = after, limit = 200)
            if (resp.messages.isEmpty()) break
            for (m in resp.messages) storeServerMessage(chat.id, m)
            after = resp.messages.maxOf { it.id }
            db.kvSet("cursor:${chat.id}", "$after")
            if (resp.messages.size < 200) break
        }
        if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
    }

    private suspend fun storeServerMessage(chatId: Long, m: Msg) {
        val acc = _account.value ?: return
        val plain = if (m.kind == "system") {
            m.body
        } else {
            decryptBody(chatId, m.sender, m.body, fromSelf = m.sender == acc.username)
        }
        var fileJson: String? = null
        if (m.type == "file") {
            val secret = parseFileSecret(plain)
            if (secret != null) {
                fileJson = json.encodeToString(
                    StoredFile(id = m.file?.id, k = secret.k, sz = secret.sz, fn = secret.fn, c = secret.c, voice = secret.voice)
                )
            }
        }
        db.msgPut(
            chatId = chatId, mid = m.id, clientId = m.client_id, sender = m.sender,
            kind = m.kind, type = m.type, bodyEnc = m.body, bodyPlain = plain,
            fileJson = fileJson, status = m.status ?: "sent", createdAt = m.created_at,
            editedAt = m.edited_at,
            reactions = m.reactions?.let { json.encodeToString(it) },
        )
        if (m.kind != "system") setPreview(chatId, plain, m.type)
        // Сообщение могло прийти во время обрыва WS (дошло только через sync):
        // уведомляем, если оно совсем свежее и чат не открыт на экране.
        val isSelf = m.sender == acc.username
        if (!isSelf && m.kind != "system" && m.type != "deleted" &&
            m.created_at > System.currentTimeMillis() / 1000.0 - 120 &&
            _activeChatId.value != chatId
        ) {
            notifyMessage(chatId, m)
        }
    }

    private fun parseFileSecret(plain: String?): FileSecret? {
        if (plain == null || !plain.trim().startsWith("{")) return null
        return try {
            json.decodeFromString<FileSecret>(plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseP2pFileSecret(plain: String?): P2pFileSecret? {
        if (plain == null) return null
        return try {
            val s = json.decodeFromString<P2pFileSecret>(plain)
            if (s.p2pfile.isNotBlank()) s else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ расшифровка

    private suspend fun keyFor(username: String?): String? {
        if (username.isNullOrBlank()) return null
        val lower = username.lowercase()
        db.keyGet(lower)?.let { return it }
        return try {
            val pk = api?.userKey(username) ?: return null
            db.keyPut(lower, pk)
            pk
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun decryptBody(chatId: Long, sender: String?, body: String?, fromSelf: Boolean): String? {
        if (body == null) return null
        val acc = _account.value ?: return null
        return when {
            body.startsWith(E2eCrypto.PREFIX_TEXT) -> {
                val senderPk = if (fromSelf) acc.e2ePk else keyFor(sender)
                E2eCrypto.decryptText(body, senderPk, acc.e2eSk, fromSelf = fromSelf)
            }
            body.startsWith(E2eCrypto.PREFIX_GROUP) -> {
                val senderPk = keyFor(sender)
                // v12: запасная адресация бокса полным адресом (федеративные группы)
                val dom = _serverDomain.value
                val fedAddr = if (dom.isNotBlank()) "${acc.username}@$dom" else null
                E2eCrypto.decryptGroupText(body, senderPk, acc.username, acc.e2eSk, fedAddr)
            }
            else -> body
        }
    }

    private suspend fun encryptBody(chat: Chat, plaintext: String): String? {
        val acc = _account.value ?: return null
        return if (chat.is_group) {
            val members = chat.members.mapNotNull { it.username }
            val pks = mutableMapOf<String, String>()
            val missing = mutableListOf<String>()
            for (u in members) {
                val pk = if (u == acc.username) acc.e2ePk else keyFor(u)
                if (pk != null) pks[u] = pk else missing.add(u)
            }
            // v13 (аудит): НЕ отправляем с «молчаливым» пропуском участника без
            // ключа — раньше сообщение уходило остальным без уведомления.
            // Теперь отправка отменяется, отправитель видит, кому ключа нет.
            if (missing.isNotEmpty()) {
                showNotice("🔒 нет ключа E2E: ${missing.joinToString(", ")} — сообщение не отправлено")
                return null
            }
            E2eCrypto.encryptGroupText(plaintext, pks, acc.e2eSk)
        } else {
            val peer = chat.peerAddress ?: return null
            val pk = keyFor(peer) ?: return null
            E2eCrypto.encryptText(plaintext, pk, acc.e2eSk)
        }
    }

    // ------------------------------------------------------------ сообщения UI

    fun openChat(chatId: Long?) {
        _activeChatId.value = chatId
        if (chatId == null) return
        p2pUnread.remove(chatId)
        loadChatMessages(chatId)
        scope.launch {
            _chats.value.firstOrNull { it.id == chatId }?.let { chat ->
                markRead(chat)
                // дозагрузка новых сообщений при открытии
                if (chat.history && !chat.is_p2p) {
                    runCatching { syncChatMessages(chat) }
                    loadChatMessages(chatId)
                }
            }
        }
    }

    fun loadChatMessages(chatId: Long) {
        val acc = _account.value ?: return
        val chat = _chats.value.firstOrNull { it.id == chatId }
        val isP2p = chat?.is_p2p == true
        scope.launch {
            // v12 audit: чтение SQLite — вне UI-потока. Раньше rows/cursors
            // читались на потоке вызывающего: openChat() зовётся из Compose.
            val rows: List<MsgRow> = withContext(Dispatchers.IO) {
                if (isP2p) db.p2pMessages(chatId) else db.messagesRange(chatId)
            }
            val (peerRead, peerDelivered) = withContext(Dispatchers.IO) { db.chatCursors(chatId) }
            val out = mutableListOf<UiMsg>()
            for (r in rows) {
                var plain = r.bodyPlain
                var fileJson = r.fileJson
                if (plain == null && r.bodyEnc != null && r.type != "deleted") {
                    val fromSelf = r.sender == acc.username
                    plain = decryptBody(chatId, r.sender, r.bodyEnc, fromSelf)
                    if (plain != null) {
                        db.msgUpdateDecrypted(chatId, r.mid, plain)
                        if (r.type == "file") {
                            if (isP2p) {
                                parseP2pFileSecret(plain)?.let {
                                    fileJson = json.encodeToString(FileMeta(id = null, name = it.fn, size = it.sz, voice = it.voice))
                                    db.p2pFileJson(chatId, r.clientId ?: "", fileJson)
                                }
                            } else {
                                parseFileSecret(plain)?.let {
                                    fileJson = json.encodeToString(StoredFile(k = it.k, sz = it.sz, fn = it.fn, c = it.c, voice = it.voice))
                                    db.msgUpdateFileJson(chatId, r.mid, fileJson)
                                }
                            }
                        }
                    }
                }
                // Мета файла: НЕ расшифрованное поле name/size (раньше тут был
                // размер 0 — секрет {k,sz,fn} декодировали как FileMeta {id,name,size}).
                val fileMeta: FileMeta? = if (r.type != "file") null else when {
                    isP2p -> fileJson?.let { fj ->
                        runCatching { json.decodeFromString<FileMeta>(fj) }.getOrNull()
                    } ?: parseP2pFileSecret(plain)?.let { FileMeta(id = null, name = it.fn, size = it.sz) }
                    else -> {
                        val stored = fileJson?.let { fj ->
                            runCatching { json.decodeFromString<StoredFile>(fj) }.getOrNull()
                        } ?: parseFileSecret(plain)?.let {
                            StoredFile(k = it.k, sz = it.sz, fn = it.fn, c = it.c, voice = it.voice)
                        }
                        stored?.let { FileMeta(id = it.id, name = it.fn, size = it.sz, voice = it.voice) }
                            ?: fileJson?.let { fj ->
                                runCatching { json.decodeFromString<FileMeta>(fj) }.getOrNull()
                            }
                    }
                }
                val isMine = r.sender == acc.username
                var status = r.status
                if (!isP2p && isMine && r.mid > 0) {
                    status = when {
                        r.mid <= peerRead -> "read"
                        r.mid <= peerDelivered -> "delivered"
                        else -> "sent"
                    }
                }
                // v11: разбираем префикс ответа из расшифрованного plaintext.
                // Если префикса нет (старое сообщение или plain=null) — поля
                // ответа останутся нулями/пустыми, текст возвращается как есть.
                // unpackReplyOrNull безопасно обрабатывает null plain.
                val (replyText, replyMid, replySender, replyPreview) = unpackReplyOrNull(plain)
                out.add(
                    UiMsg(
                        mid = r.mid, clientId = r.clientId, chatId = chatId,
                        sender = r.sender ?: "?", kind = r.kind, type = r.type,
                        plain = replyText, file = fileMeta,
                        localPath = db.kvGet("file:${chatId}:${r.clientId ?: r.mid}"),
                        createdAt = r.createdAt, editedAt = r.editedAt, status = status,
                        isMine = isMine,
                        reactions = r.reactions?.let {
                            runCatching { json.decodeFromString<List<Reaction>>(it) }.getOrNull()
                        } ?: emptyList(),
                        // v8.x: статус «failed» (не отправлено) — из БД, не из курсоров
                        failed = r.status == "failed", p2p = isP2p,
                        // v11: метаданные ответа (если был префикс в plaintext).
                        replyToMid = replyMid,
                        replyToSender = replySender,
                        replyToPreview = replyPreview,
                    )
                )
            }
            _messages.value = out
            autoDownloadMedia(chatId, out)
        }
    }

    /**
     * v8: фоновая автозагрузка небольших изображений И голосовых сообщений —
     * чтобы миниатюры и кнопка «воспроизвести» работали сразу, без ручного
     * скачивания. P2P-файлы уже сохраняются при приёме.
     */
    private fun autoDownloadMedia(chatId: Long, msgs: List<UiMsg>) {
        if (!autoImagesEnabled) return
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        if (chat.is_p2p) return
        for (m in msgs) {
            if (m.type != "file" || m.file == null || m.localPath != null) continue
            val isVoice = m.file.voice != null
            if (!isVoice && !ImageThumb.isImage(m.file.name)) continue
            val size = m.file.size ?: 0
            if (size <= 0 || size > IMAGE_AUTO_MAX) continue
            val key = "$chatId:${m.mid}:${m.clientId ?: ""}"
            val last = imageTries[key] ?: 0L
            val now = System.currentTimeMillis()
            if (last != 0L && now - last < 10 * 60_000L) continue // кулдаун после ошибки
            imageTries[key] = now
            scope.launch {
                runCatching { downloadMessageFile(chat, m) }
            }
        }
    }

    suspend fun loadOlderMessages(chat: Chat) {
        if (chat.is_p2p || !chat.history) return
        // v12 audit: SQLite — вне UI-потока (экран зовёт через rememberCoroutineScope)
        val minMid = withContext(Dispatchers.IO) { db.messagesRange(chat.id, limit = 1) }
            .minOfOrNull { it.mid } ?: return
        if (minMid <= 1) return
        val resp = api!!.messages(chat.id, beforeId = minMid, limit = 200)
        for (m in resp.messages) storeServerMessage(chat.id, m)
        loadChatMessages(chat.id)
    }

    // --------------------------------------------------------------- отправка

    suspend fun sendText(chat: Chat, text: String, replyTo: UiMsg? = null): Result<Unit> {
        val acc = _account.value ?: return Result.failure(IllegalStateException("нет аккаунта"))
        if (text.isBlank()) return Result.failure(IllegalArgumentException("пустое сообщение"))
        // v11: упаковываем метаданные ответа в E2E-plaintext. Префикс живёт
        // внутри зашифрованного тела — сервер ничего про ответ не знает.
        // Храним упакованный plaintext в bodyPlain для оптимистичного сообщения:
        // тогда loadChatMessages (unpackReply) сразу рисует quoted-превью в UI
        // ДО эха сервера. Это покрывает и P2P (где серверного эха нет — иначе
        // метаданные ответа терялись бы после перезапуска). После эха сервера
        // storeServerMessage всё равно перезапишет bodyPlain расшифрованным
        // packed-plaintext — поведение идентично.
        val plaintext = packReply(text.trim(), replyTo)
        val envelope = encryptBody(chat, plaintext)
            ?: return Result.failure(IllegalStateException("🔒 нет ключа E2E собеседника"))
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000.0
        return if (chat.is_p2p) {
            db.p2pPut(chat.id, clientId, acc.username, "text", plaintext, null, "pending", now)
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            val ok = ws?.send(
                buildJsonObject {
                    put("t", "p2p_send")
                    put("chat_id", chat.id)
                    put("msg", buildJsonObject {
                        put("client_id", clientId)
                        put("type", "text")
                        put("body", envelope)
                        put("ts", now)
                    })
                }
            ) == true
            if (!ok) {
                markSendFailed(chat, clientId)
                Result.failure(IllegalStateException("нет связи — сообщение не отправлено"))
            }
            else Result.success(Unit)
        } else {
            db.msgPut(
                chat.id, nextLocalMid(), clientId, acc.username, "user",
                "text", envelope, plaintext, null, "pending", now, null, null,
            )
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            try {
                val resp = api!!.messageSend(chat.id, "text", envelope, clientId)
                resp.msg?.let { m ->
                    storeServerMessage(chat.id, m)
                    bumpChatByMessage(chat.id, m, mine = true)
                }
                if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
                Result.success(Unit)
            } catch (e: Exception) {
                markSendFailed(chat, clientId)
                Result.failure(e)
            }
        }
    }

    suspend fun editMessage(chat: Chat, msg: UiMsg, newText: String): Result<Unit> {
        if (chat.is_p2p || !chat.history) {
            return Result.failure(IllegalStateException("правка недоступна в этом чате"))
        }
        val envelope = encryptBody(chat, newText.trim())
            ?: return Result.failure(IllegalStateException("🔒 нет ключа E2E собеседника"))
        return try {
            api!!.messageEdit(chat.id, msg.mid, envelope)
            db.msgReplaceEdited(chat.id, msg.mid, envelope, newText.trim(), System.currentTimeMillis() / 1000.0)
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unsendMessage(chat: Chat, msg: UiMsg): Result<Unit> {
        if (chat.is_p2p || !chat.history) {
            return Result.failure(IllegalStateException("удаление недоступно в этом чате"))
        }
        return try {
            api!!.messageDelete(chat.id, msg.mid)
            db.msgMarkDeleted(chat.id, msg.mid)
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun react(chat: Chat, msg: UiMsg, emoji: String): Result<Unit> {
        if (chat.is_p2p) return Result.failure(IllegalStateException("реакции недоступны в чате без истории"))
        return try {
            val resp = api!!.messageReact(chat.id, msg.mid, emoji)
            db.msgUpdateReactions(chat.id, msg.mid, json.encodeToString(resp.reactions))
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markRead(chat: Chat) {
        val acc = _account.value ?: return
        if (chat.is_p2p) {
            val lastIncoming = db.p2pMessages(chat.id).lastOrNull { it.sender != acc.username }?.clientId
            if (lastIncoming != null) ws?.sendP2pRead(chat.id, lastIncoming)
            p2pUnread.remove(chat.id)
            refreshChatsFlow()
            return
        }
        val maxMid = db.messagesMaxMid(chat.id) ?: return
        if (maxMid > chat.last_read && maxMid > 0) {
            try {
                val isFed = chat.peerAddress?.contains("@") == true
                val clientId = if (isFed) {
                    db.messagesRange(chat.id).lastOrNull { it.mid == maxMid }?.clientId
                } else null
                api!!.read(chat.id, maxMid, clientId)
                val updated = chat.copy(unread = 0, last_read = maxMid)
                db.chatPut(chat.id, json.encodeToString(updated))
                refreshChatsFlow()
            } catch (_: Exception) {
                // не критично
            }
        } else if (chat.unread > 0) {
            db.chatPut(chat.id, json.encodeToString(chat.copy(unread = 0)))
            refreshChatsFlow()
        }
    }

    // ------------------------------------------------------------------ файлы

    /** v13: порог параллельной передачи файлов (ниже — обычный путь). */
    private const val PARALLEL_MIN_BYTES = 4L * 1024 * 1024

    /**
     * v12-o: зашифровать источник в TME1-блоб во временном файле —
     * для параллельной отгрузки частями (uploadFileParallel).
     */
    private fun tme1TempBlob(source: InputStream, key: String): File {
        val tmp = File.createTempFile("tmup", ".tme1", tmpDir())
        try {
            tmp.outputStream().buffered(256 * 1024).use { out ->
                Tme1.encryptStream(source, key, out)
            }
            return tmp
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    suspend fun sendFile(chat: Chat, file: File, onProgress: (Long, Long) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val acc = _account.value ?: return@withContext Result.failure(IllegalStateException("нет аккаунта"))
            var progressClientId: String? = null
            val res = runCatching {
                val name = file.name.ifBlank { "файл" }
                val size = file.length()
                if (size > acc.maxFileMb * 1024L * 1024L) {
                    throw IllegalStateException("файл больше лимита сервера (${acc.maxFileMb} МБ)")
                }
                val key = E2eCrypto.randomB64(32)
                val clientId = UUID.randomUUID().toString()
                progressClientId = clientId
                val now = System.currentTimeMillis() / 1000.0
                val plainSize = size

                // оптимистичное сообщение с файлом
                val fileMeta = FileMeta(id = null, name = name, size = plainSize)
                if (chat.is_p2p) {
                    db.p2pPut(chat.id, clientId, acc.username, "file", null, json.encodeToString(fileMeta), "pending", now)
                } else {
                    // храним StoredFile (секрет+размер): FileMeta-декод давал "0 Б"
                    db.msgPut(
                        chat.id, nextLocalMid(), clientId, acc.username, "user",
                        "file", null, null,
                        json.encodeToString(StoredFile(k = key, sz = plainSize, fn = name, c = "")),
                        "pending", now, null, null,
                    )
                }
                if (_activeChatId.value == chat.id) loadChatMessages(chat.id)

                // v13-c: прогресс-бар появляется СРАЗУ после вставки сообщения —
                // раньше до первого чанка/подтверждённой части бегал только
                // «pending»-кружок, и на медленном канале отправка выглядела
                // зависшей (трафик идёт, прогресса нет)
                transferUpdate(chat.id, clientId, "upload", 0L, maxOf(1L, Tme1.encryptedSize(plainSize)))

                if (chat.is_p2p) {
                    // v1: шифруем в память (как TUI), затем чанки по WS (v12-n: 256 КиБ)
                    val blob = file.readBytes()
                    val enc = Tme1.encryptBytes(blob, key)
                    val meta = P2pFileSecret(p2pfile = clientId, k = key, sz = blob.size.toLong(), fn = name, c = "")
                    val envelope = encryptBody(chat, json.encodeToString(meta))
                        ?: throw IllegalStateException("🔒 нет ключа E2E собеседника")
                    // мета-сообщение (client_id = transfer_id)
                    ws?.send(
                        buildJsonObject {
                            put("t", "p2p_send")
                            put("chat_id", chat.id)
                            put("msg", buildJsonObject {
                                put("client_id", clientId)
                                put("type", "text")
                                put("body", envelope)
                                put("ts", System.currentTimeMillis() / 1000.0)
                            })
                        }
                    )
                    // v12-n: чанки 256 КиБ — на 96 КиБ WS-сообщений в 2,7 раза больше,
                    // а каждый чанк тащит base64 (+33%) и JSON-обвязку
                    val chunkSize = 256 * 1024
                    val total = maxOf(1, (enc.size + chunkSize - 1) / chunkSize)
                    for (seq in 0 until total) {
                        val from = seq * chunkSize
                        val to = minOf(enc.size, from + chunkSize)
                        val chunk = enc.copyOfRange(from, to)
                        val sent = ws?.send(
                            buildJsonObject {
                                put("t", "p2p_file_chunk")
                                put("chat_id", chat.id)
                                put("transfer_id", clientId)
                                put("name", "e2e.tme1")
                                put("size", enc.size)
                                put("total", total)
                                put("seq", seq)
                                put("data", E2eCrypto.b64(chunk))
                            }
                        ) == true
                        if (!sent) throw IllegalStateException("нет связи — файл не отправлен")
                        onProgress(to.toLong(), enc.size.toLong())
                        transferUpdate(chat.id, clientId, "upload", to.toLong(), enc.size.toLong())
                    }
                    db.p2pStatus(chat.id, clientId, "sent")
                } else {
                    // v12-o: большие файлы — параллельная отгрузка (8-12 TCP-потоков
                    // агрегируют throughput туннеля; одна сессия через userspace-
                    // движок ограничена окном/RTT ≈ 200-300 КиБ/с). Старый сервер →
                    // возврат на обычный multipart.
                    val fid: String
                    var blob: File? = null
                    try {
                        blob = if (plainSize >= PARALLEL_MIN_BYTES) tme1TempBlob(file.inputStream(), key) else null
                        fid = blob?.let { b ->
                            api!!.uploadFileParallel(b, name) { d, t ->
                                transferUpdate(chat.id, clientId, "upload", d, t)
                                onProgress(d, t)
                            }
                        } ?: api!!.uploadEncryptedFile(
                            file.inputStream(), plainSize, key,
                        ) { d, t ->
                            transferUpdate(chat.id, clientId, "upload", d, t)
                            onProgress(d, t)
                        }
                    } finally {
                        blob?.delete()
                    }
                    val meta = FileSecret(k = key, sz = plainSize, fn = name, c = "")
                    val envelope = encryptBody(chat, json.encodeToString(meta))
                        ?: throw IllegalStateException("🔒 нет ключа E2E собеседника")
                    val resp = api!!.messageSend(chat.id, "file", envelope, clientId, fid)
                    resp.msg?.let { m ->
                        storeServerMessage(chat.id, m)
                        bumpChatByMessage(chat.id, m, mine = true)
                    }
                }
                if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            }
            if (res.isFailure) markSendFailed(chat, progressClientId)
            transferDone(chat.id, progressClientId)
            res.mapError { if (it is IllegalStateException || it is Tme1.Tme1Exception) it else RuntimeException(humanError(it)) }
        }

    /**
     * Отправить голосовое сообщение: тот же файловый конвейер, но мета
     * дополнена {"voice":{"dur","wave"}} — клиенты рисуют аудиоплеер.
     */
    suspend fun sendVoice(
        chat: Chat,
        file: File,
        durationSec: Int,
        wave: List<Int>,
        onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val acc = _account.value ?: return@withContext Result.failure(IllegalStateException("нет аккаунта"))
        var progressClientId: String? = null
        val res = runCatching {
            // на десктопе запись идёт в WAV (javax.sound), не в m4a
            val name = "voice-${System.currentTimeMillis() / 1000}.wav"
            val size = file.length()
            if (size > acc.maxFileMb * 1024L * 1024L) {
                throw IllegalStateException("файл больше лимита сервера (${acc.maxFileMb} МБ)")
            }
            val key = E2eCrypto.randomB64(32)
            val clientId = UUID.randomUUID().toString()
            progressClientId = clientId
            val now = System.currentTimeMillis() / 1000.0
            val voice = VoiceMeta(dur = durationSec, wave = wave)

            val fileMeta = FileMeta(id = null, name = name, size = size, voice = voice)
            if (chat.is_p2p) {
                db.p2pPut(chat.id, clientId, acc.username, "file", null, json.encodeToString(fileMeta), "pending", now)
            } else {
                db.msgPut(
                    chat.id, nextLocalMid(), clientId, acc.username, "user",
                    "file", null, null,
                    json.encodeToString(StoredFile(k = key, sz = size, fn = name, c = "", voice = voice)),
                    "pending", now, null, null,
                )
            }
            // v8: регистрируем записанный файл сразу — СВОЁ голосовое можно
            // прослушать не дожидаясь конца отправки (раньше кнопка play была
            // навсегда неактивна для собственных голосовых)
            db.kvSet("file:${chat.id}:${clientId}", file.absolutePath)
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)

            // v13-c: прогресс-бар голосового тоже сразу (см. sendFile)
            transferUpdate(chat.id, clientId, "upload", 0L, maxOf(1L, Tme1.encryptedSize(size)))

            if (chat.is_p2p) {
                val blob = file.readBytes()
                val enc = Tme1.encryptBytes(blob, key)
                val meta = P2pFileSecret(p2pfile = clientId, k = key, sz = blob.size.toLong(), fn = name, c = "", voice = voice)
                val envelope = encryptBody(chat, json.encodeToString(meta))
                    ?: throw IllegalStateException("🔒 нет ключа E2E собеседника")
                ws?.send(
                    buildJsonObject {
                        put("t", "p2p_send")
                        put("chat_id", chat.id)
                        put("msg", buildJsonObject {
                            put("client_id", clientId)
                            put("type", "text")
                            put("body", envelope)
                            put("ts", System.currentTimeMillis() / 1000.0)
                        })
                    }
                )
                val chunkSize = 256 * 1024   // v12-n: было 96 КиБ — см. sendFile
                val total = maxOf(1, (enc.size + chunkSize - 1) / chunkSize)
                for (seq in 0 until total) {
                    val from = seq * chunkSize
                    val to = minOf(enc.size, from + chunkSize)
                    val sent = ws?.send(
                        buildJsonObject {
                            put("t", "p2p_file_chunk")
                            put("chat_id", chat.id)
                            put("transfer_id", clientId)
                            put("name", "e2e.tme1")
                            put("size", enc.size)
                            put("total", total)
                            put("seq", seq)
                            put("data", E2eCrypto.b64(enc.copyOfRange(from, to)))
                        }
                    ) == true
                    if (!sent) throw IllegalStateException("нет связи — файл не отправлен")
                    onProgress(to.toLong(), enc.size.toLong())
                    transferUpdate(chat.id, clientId, "upload", to.toLong(), enc.size.toLong())
                }
                db.p2pStatus(chat.id, clientId, "sent")
            } else {
                // v12-o: параллельная отгрузка для больших файлов — см. sendFile
                val fid: String
                var blob: File? = null
                try {
                    blob = if (size >= PARALLEL_MIN_BYTES) tme1TempBlob(file.inputStream(), key) else null
                    fid = blob?.let { b ->
                        api!!.uploadFileParallel(b, name) { d, t ->
                            transferUpdate(chat.id, clientId, "upload", d, t)
                            onProgress(d, t)
                        }
                    } ?: api!!.uploadEncryptedFile(file.inputStream(), size, key) { d, t ->
                        transferUpdate(chat.id, clientId, "upload", d, t)
                        onProgress(d, t)
                    }
                } finally {
                    blob?.delete()
                }
                val meta = FileSecret(k = key, sz = size, fn = name, c = "", voice = voice)
                val envelope = encryptBody(chat, json.encodeToString(meta))
                    ?: throw IllegalStateException("🔒 нет ключа E2E собеседника")
                val resp = api!!.messageSend(chat.id, "file", envelope, clientId, fid)
                resp.msg?.let { m ->
                    storeServerMessage(chat.id, m)
                    bumpChatByMessage(chat.id, m, mine = true)
                }
            }
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
        }
        if (res.isFailure) markSendFailed(chat, progressClientId)
        transferDone(chat.id, progressClientId)
        res.mapError { if (it is IllegalStateException || it is Tme1.Tme1Exception) it else RuntimeException(humanError(it)) }
    }

    suspend fun downloadMessageFile(chat: Chat, msg: UiMsg): Result<File> = withContext(Dispatchers.IO) {
        val progressKey = msg.clientId ?: msg.mid.toString()
        val res = runCatching {
            val api = this@Repository.api ?: throw IllegalStateException("нет аккаунта")
            val dir = downloadsDir()
            val baseName = (msg.file?.name ?: "файл").replace(Regex("[/\\\\]"), "_")
            var dest = File(dir, baseName)
            var n = 1
            while (dest.exists()) dest = File(dir, "${baseName.removeSuffix(".tme1")} (${n++})")
            if (msg.p2p) {
                // P2P-файл уже собран и расшифрован при приёме
                throw IllegalStateException("файл ещё не получен")
            }
            // в file_json лежит StoredFile (секрет + серверный id файла);
            // старые строки содержат чистый FileSecret — тоже декодируется
            val stored = db.messagesRange(chat.id).firstOrNull { it.mid == msg.mid }?.fileJson?.let {
                runCatching { json.decodeFromString<StoredFile>(it) }.getOrNull()
            } ?: msg.file?.let {
                StoredFile(id = it.id, sz = it.size ?: 0, fn = it.name ?: "")
            } ?: throw IllegalStateException("нет данных для расшифрования")
            var fid = stored.id ?: msg.file?.id
            if (fid == null && msg.mid > 0) {
                // у старых сообщений id не сохранён — запросим это сообщение с сервера
                fid = api.messages(chat.id, afterId = msg.mid - 1, limit = 1)
                    .messages.firstOrNull { it.id == msg.mid }?.file?.id
            }
            fid ?: throw IllegalStateException("нет file_id")
            dest.outputStream().use { out ->
                api.downloadDecryptedFile(fid, stored.k, stored.sz, out, tempDir = tmpDir()) { d, t ->
                    transferUpdate(chat.id, progressKey, "download", d, t)
                }
            }
            db.kvSet("file:${chat.id}:${msg.clientId ?: msg.mid}", dest.absolutePath)
            if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
            dest
        }
        transferDone(chat.id, progressKey)
        res
    }

    // ------------------------------------------------------------------ звонки

    /** Отправить call_* событие через WS (false — нет связи). */
    fun sendCallEvent(payload: JsonObject): Boolean = ws?.send(payload) == true

    fun myE2eSk(): String = _account.value?.e2eSk.orEmpty()

    private val callKeyCache = ConcurrentHashMap<String, String>()

    /** Публичный ключ собеседника диалога (для шифрования фреймов звонка). */
    fun callPeerPkSync(chatId: Long): String? {
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return null
        val peer = chat.peerAddress ?: return null
        return callKeyCache[peer] ?: runBlocking { keyFor(peer)?.also { callKeyCache[peer] = it } }
    }

    /** Расшифровать фрейм звонка от from; null — битый/чужой. */
    fun decryptCallFrame(from: String, data: String): String? {
        val acc = _account.value ?: return null
        val pk = callKeyCache[from] ?: runBlocking { keyFor(from)?.also { callKeyCache[from] = it } } ?: return null
        return E2eCrypto.decryptFrame(data, pk, acc.e2eSk)
    }

    fun chatDisplayName(chatId: Long, fallback: String): String {
        val c = _chats.value.firstOrNull { it.id == chatId } ?: return fallback
        return c.peer?.nickname?.takeIf { it.isNotBlank() }
            ?: c.peer?.username?.takeIf { it.isNotBlank() }
            ?: c.title?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    // ------------------------------------------------------------------- чаты

    suspend fun createChat(address: String, isP2p: Boolean): Result<Chat> {
        return runCatching {
            val chat = api!!.chatCreate(address.trim(), isP2p)
            db.chatPut(chat.id, json.encodeToString(chat))
            refreshChatsFlow()
            chat
        }
    }

    suspend fun createGroup(title: String, members: List<String>): Result<Chat> {
        return runCatching {
            val chat = api!!.chatCreateGroup(title.trim(), members)
            db.chatPut(chat.id, json.encodeToString(chat))
            refreshChatsFlow()
            chat
        }
    }

    suspend fun addGroupMember(chat: Chat, address: String): Result<Unit> = runCatching {
        api!!.chatAddMember(chat.id, address)
    }

    suspend fun leaveChat(chat: Chat): Result<Unit> = runCatching {
        if (chat.is_group && chat.owner != _account.value?.username) {
            api!!.chatLeave(chat.id)
        } else {
            api!!.chatDelete(chat.id)
        }
        db.chatDelete(chat.id)
        refreshChatsFlow()
    }

    suspend fun deleteChat(chat: Chat): Result<Unit> = runCatching {
        api!!.chatDelete(chat.id)
        db.chatDelete(chat.id)
        refreshChatsFlow()
    }

    suspend fun reloadChat(chatId: Long) {
        try {
            val chat = api!!.chatGet(chatId)
            db.chatPut(chat.id, json.encodeToString(chat))
            refreshChatsFlow()
        } catch (_: Exception) {
        }
    }

    suspend fun removeGroupMember(chat: Chat, username: String): Result<Unit> = runCatching {
        api!!.chatRemoveMember(chat.id, username)
        reloadChat(chat.id)
    }

    suspend fun transferOwnership(chat: Chat, username: String): Result<Unit> = runCatching {
        api!!.chatSetOwner(chat.id, username)
        reloadChat(chat.id)
    }

    /** «Чат в другом режиме»: второй чат с тем же пиром (P2P ↔ с историей). */
    suspend fun switchChatMode(chat: Chat): Result<Chat> {
        val addr = chat.peerAddress
            ?: return Result.failure(IllegalStateException("нет адреса собеседника"))
        return createChat(addr, !chat.is_p2p)
    }

    suspend fun clearChat(chat: Chat): Result<Unit> = runCatching {
        if (!chat.is_p2p) api!!.chatClear(chat.id)
        db.chatClear(chat.id)
        if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
    }

    suspend fun setNickname(nickname: String): Result<Unit> = runCatching {
        api!!.setNickname(nickname)
        updateNicknameLocal(nickname)
    }

    /**
     * Экспорт аккаунта (v8: формат v4 — общий буфер переноса ВЕБ/ANDROID/TUI):
     * токен + приватный E2E-ключ + ник + ЛОКАЛЬНАЯ ИСТОРИЯ чатов «без истории»
     * (p2p) + контакты. Файл хранить как пароль!
     * Читатели формата v3 (старые версии клиентов) тоже понимают этот файл:
     * новые поля необязательны.
     */
    suspend fun exportAccount(outFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val acc = _account.value ?: throw IllegalStateException("нет аккаунта")
            val payload = buildJsonObject {
                put("kind", "tunnel-messenger-account")
                put("version", 4)
                put("exported_at", System.currentTimeMillis() / 1000.0)
                put("server", acc.baseUrl)
                put("username", acc.username)
                put("token", acc.token)
                put("nickname", acc.nickname ?: "")
                put("e2e_sk", acc.e2eSk)
                // v8: локальная история P2P-чатов (нейтральный вид, как в TUI/web)
                put("p2p", exportP2pHistory())
                // v8: контакты (адреса)
                put("contacts", JsonArray(_contacts.value.mapNotNull { c -> c.address?.let { JsonPrimitive(it) } }))
            }
            outFile.writeText(exportJson.encodeToString(JsonObject.serializer(), payload))
        }
    }

    /** История P2P-чатов в клиентонезависимом виде: chatId → {peer, messages[]} */
    private fun exportP2pHistory(): JsonObject {
        return buildJsonObject {
            for (c in _chats.value) {
                if (!c.is_p2p) continue
                val rows = db.p2pMessages(c.id, limit = 4000)
                if (rows.isEmpty()) continue
                val meta = buildJsonObject {
                    put("peer", c.peerAddress ?: "")
                    c.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                    put("messages", JsonArray(rows.map { r ->
                        buildJsonObject {
                            put("client_id", r.clientId ?: "")
                            put("sender", r.sender ?: "")
                            put("type", r.type)
                            // текст — как есть; файловое тело (TME1-мета) наружу не выносим:
                            // достаточно имени/размера, файлы не переносятся
                            put("body", if (r.type == "file") "" else (r.bodyPlain ?: ""))
                            put("created_at", r.createdAt)
                            r.editedAt?.let { put("edited_at", it) }
                            if (r.type == "file") {
                                val fm = r.fileJson?.let {
                                    runCatching { json.decodeFromString<FileMeta>(it) }.getOrNull()
                                }
                                if (fm != null && !fm.name.isNullOrBlank()) {
                                    put("file", buildJsonObject {
                                        put("name", fm.name)
                                        fm.size?.let { sz -> put("size", sz) }
                                    })
                                }
                            }
                        }
                    }))
                }
                put(c.id.toString(), meta)
            }
        }
    }

    /**
     * Импорт аккаунта (v8: понимает v3 И v4; v12: универсальный) — вход без
     * пароля на новом устройстве: токен + приватный E2E-ключ; локальная история
     * P2P-чатов и контакты из файла (v4) дедуплицируются и вливаются в локальный
     * кэш.
     *
     * v12 fix: если токен из файла НЕдействителен (выход на устройстве-доноре,
     * смена пароля, переустановка сервера) или сервер ещё недоступен — импорт
     * больше НЕ падает «в никуда»: приватный E2E-ключ, адрес сервера и имя
     * сохраняются сразу (парольный вход переиспользует ключ — история останется
     * читаемой), а история/контакты из файла запоминаются в pending_import
     * и доливаются после первого успешного входа. Так веб ведёт себя уже давно;
     * теперь оба клиента interchangeable в обе стороны.
     */
    suspend fun importAccount(inFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = if (inFile.isFile) inFile.readText(Charsets.UTF_8)
                else throw IllegalStateException("не удалось открыть файл")
            val obj = try {
                json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                throw IllegalStateException("это не файл аккаунта Tunnel Messenger")
            }
            fun str(k: String): String? =
                (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                    ?.content?.takeIf { it.isNotBlank() }

            val kind = str("kind") ?: ""
            if (kind != "tunnel-messenger-account" && kind != "tunnel-messager-account") {
                throw IllegalStateException("это не файл аккаунта Tunnel Messenger")
            }
            val server = str("server") ?: throw IllegalStateException("в файле нет адреса сервера")
            val token = str("token") ?: throw IllegalStateException("в файле нет токена")
            val username = str("username") ?: throw IllegalStateException("в файле нет имени пользователя")
            val sk = str("e2e_sk") ?: throw IllegalStateException("в файле нет приватного E2E-ключа")
            val nicknameRaw = str("nickname")
            val pk = E2eCrypto.publicFromPrivate(sk)
                ?: throw IllegalStateException("приватный E2E-ключ повреждён")
            val baseUrl = normalizeBaseUrl(server)
            val authed = Api({ baseUrl }, { token })
            val meRes = runCatching { authed.me() }   // проверка токена
            val me = meRes.getOrNull()
            if (me == null) {
                // v12: живого входа нет — сохраняем секреты и ждём парольный вход
                AppConfig.putString("base_url", baseUrl)
                AppConfig.putString("username", username)
                putSec("e2e_sk", sk)
                putSec("e2e_pk", pk)
                // v13 (аудит): в блобе есть token и e2e_sk — храним зашифрованным
                // (SecureStore), как остальные секреты; openStr читает и старые
                // незашифрованные записи (миграция при чтении).
                AppConfig.putString("pending_import", SecureStore.sealStr(obj.toString()))
                val meErr = meRes.exceptionOrNull()
                throw IllegalStateException(
                    if (meErr is ApiError && meErr.code == 401)
                        "токен из файла недействителен (выход на устройстве-доноре, " +
                            "смена пароля или переустановка сервера). Ключ E2E и локальная " +
                            "история уже сохранены — войдите паролем, и они подхватятся"
                    else
                        "сервер недоступен. Ключ E2E и данные из файла уже сохранены — " +
                            "поднимите туннель и войдите паролем, и они подхватятся"
                )
            }
            val nickname = me.user?.nickname ?: nicknameRaw
            AppConfig.putString("base_url", baseUrl)
            putSec("token", token)
            AppConfig.putString("username", username)
            AppConfig.putString("nickname", nickname)
            putSec("e2e_sk", sk)
            putSec("e2e_pk", pk)
            AppConfig.putString("server_domain", me.server?.domain)
            AppConfig.putLong("max_file_mb", me.server?.max_file_mb ?: 2048)
            _account.value = Account(
                baseUrl = baseUrl, token = token, username = username,
                nickname = nickname, e2eSk = sk, e2ePk = pk,
                serverDomain = me.server?.domain, maxFileMb = me.server?.max_file_mb ?: 2048,
            )
            api = authed
            loadChatsFromDb()
            loadContacts()
            loadBlocks()  // v11: блокировки
            startWs()
            launchSync()
            // v8: влить локальную историю P2P-чатов (дедуп по client_id)
            val merged = importP2pHistory(obj["p2p"] as? JsonObject)
            if (merged > 0) refreshChatsFlow()
            // v8: восстановить контакты (best-effort, по одному)
            val contacts = obj["contacts"] as? JsonArray
            if (contacts != null) {
                scope.launch {
                    for (el in contacts) {
                        val addr = (el as? JsonPrimitive)?.content?.trim() ?: continue
                        if (addr.isEmpty()) continue
                        runCatching { authed.contactAdd(addr) }
                    }
                }
            }
            merged
        }.map { }   // Result<Int> → Result<Unit>: наружу остаётся Result<Unit>
    }

    /**
     * v12: довлить отложенный импорт (pending_import — файл, чей токен оказался
     * недействителен при импорте) после успешного парольного входа: слить историю
     * P2P-чатов (дедуп по client_id) и восстановить контакты. Вызывается из login().
     */
    private fun mergePendingImport(authed: Api) {
        val sealed = AppConfig.getString("pending_import") ?: return
        AppConfig.remove("pending_import")
        // v13 (аудит): блоб теперь хранится зашифрованным; openStr понимает и старые открытые записи
        val raw = SecureStore.openStr(sealed) ?: return
        runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val merged = importP2pHistory(obj["p2p"] as? JsonObject)
            if (merged > 0) refreshChatsFlow()
            val contacts = obj["contacts"] as? JsonArray
            if (contacts != null) {
                scope.launch {
                    for (el in contacts) {
                        val addr = (el as? JsonPrimitive)?.content?.trim() ?: continue
                        if (addr.isEmpty()) continue
                        runCatching { authed.contactAdd(addr) }
                    }
                }
            }
        }
    }

    /**
     * v8: влить историю P2P-чатов из файла экспорта (v4) в локальный кэш.
     * Дедуп по client_id; недостающий чат создаётся минимальной записью.
     * → количество добавленных сообщений.
     */
    private fun importP2pHistory(p2p: JsonObject?): Int {
        if (p2p == null) return 0
        val me = _account.value?.username?.lowercase() ?: return 0
        var n = 0
        for ((key, metaEl) in p2p) {
            val chatId = key.toLongOrNull() ?: continue
            val meta = metaEl as? JsonObject ?: continue
            fun f(k: String): String =
                (meta[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
            val peer = f("peer")
            val title = f("title")
            val messages = meta["messages"] as? JsonArray ?: continue
            if (messages.isEmpty()) continue
            // чат должен существовать локально; если нет — создаём минимальную запись
            if (db.chatGet(chatId) == null) {
                val chat = Chat(
                    id = chatId, is_p2p = true, history = false,
                    peer = UserShort(address = peer.ifBlank { null }),
                    title = title.ifBlank { null },
                )
                db.chatPut(chatId, json.encodeToString(chat))
            }
            for (mEl in messages) {
                val m = mEl as? JsonObject ?: continue
                fun mf(k: String): String =
                    (m[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                val clientId = mf("client_id")
                if (clientId.isBlank() || db.p2pHas(chatId, clientId)) continue
                val sender = mf("sender")
                val type = mf("type").ifBlank { "text" }
                var body = mf("body")
                var fileJson: String? = null
                if (type == "file") {
                    val fo = m["file"] as? JsonObject
                    val fname = (fo?.get("name") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
                    if (fname.isNotBlank()) {
                        fileJson = json.encodeToString(
                            FileMeta(id = null, name = fname,
                                     size = (fo?.get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }),
                        )
                    }
                    body = ""
                }
                db.p2pPut(
                    chatId, clientId, sender, type, body, fileJson,
                    if (sender.lowercase() == me) "sent" else "incoming",
                    (m["created_at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                )
                n++
            }
        }
        return n
    }

    private fun refreshChatsFlow() {
        loadChatsFromDb()
    }

    private fun bumpChatByMessage(chatId: Long, m: Msg, mine: Boolean, incoming: Boolean = false) {
        val cur = _chats.value.firstOrNull { it.id == chatId } ?: return
        val updated = cur.copy(
            last_message = m,
            last_msg_id = maxOf(cur.last_msg_id, m.id),
            updated_at = m.created_at,
            unread = if (incoming && !mine) cur.unread + 1 else cur.unread,
        )
        db.chatPut(chatId, json.encodeToString(updated))
        _chats.value = _chats.value.map { if (it.id == chatId) updated else it }
            .sortedByDescending { it.updated_at }
    }

    // -------------------------------------------------------------- WS-события

    private fun handleEvent(ev: JsonObject) {
        val acc = _account.value ?: return
        val t = ev["t"]?.jsonPrimitive?.content ?: return
        scope.launch {
            try {
                when (t) {
                    "msg" -> onMsgEvent(ev)
                    "status" -> onStatusEvent(ev)
                    "typing" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val from = ev["from"]?.jsonPrimitive?.content ?: return@launch
                        val now = System.currentTimeMillis()
                        // протухшие индикаторы убираем сразу
                        _typing.value = _typing.value.filter { (_, v) -> now - v.second < 6000 } +
                            (chatId to (from to now))
                    }
                    "presence" -> {
                        val username = ev["username"]?.jsonPrimitive?.content ?: return@launch
                        val online = ev["online"]?.jsonPrimitive?.content == "true"
                        presenceUpdate(username, online)
                    }
                    "chat_updated" -> {
                        val chatEl = ev["chat"] ?: return@launch
                        val chat = json.decodeFromJsonElement(Chat.serializer(), chatEl.jsonObject)
                        db.chatPut(chat.id, json.encodeToString(chat))
                        refreshChatsFlow()
                        if (_activeChatId.value == chat.id) loadChatMessages(chat.id)
                    }
                    "chat_cleared" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        db.chatClear(chatId)
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "chat_deleted" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        db.chatDelete(chatId)
                        refreshChatsFlow()
                        if (_activeChatId.value == chatId) {
                            _activeChatId.value = null
                            _messages.value = emptyList()
                        }
                    }
                    "key_changed" -> {
                        // v8.2: у пользователя сменился pubkey E2E — кэш недействителен.
                        // v13 (аудит): ГРОМКОЕ предупреждение при ФАКТИЧЕСКОЙ смене ключа.
                        // Каталог ключей — trust-on-first-use: подмена ключа на сервере —
                        // единственный способ оператора сервера читать E2E, поэтому о
                        // каждой смене пользователь должен знать и подтвердить,
                        // что ключи менял именно владелец.
                        val username = ev["username"]?.jsonPrimitive?.content ?: return@launch
                        val u = username.lowercase()
                        val oldPk = db.keyGet(u)
                        db.keyDelete(u)
                        callKeyCache.remove(u)
                        if (oldPk != null) {
                            runCatching {
                                val newPk = api?.userKey(username)
                                if (newPk != null && newPk != oldPk) {
                                    showNotice(
                                        "⚠️ Ключ шифрования «$username» изменился. Если он не менял ключи — возможна подмена"
                                    )
                                    // локальная системная запись во все 1:1-чаты с этим пользователем
                                    val notice = "⚠️ Ключ шифрования пользователя $username изменился. " +
                                        "Если он не менял ключи в настройках — сверьте личность по другому каналу."
                                    val mid = -System.currentTimeMillis()
                                    for (c in _chats.value) {
                                        if (!c.is_group && (c.peerAddress ?: "").lowercase() == u) {
                                            db.msgPut(
                                                chatId = c.id, mid = mid, clientId = "keychg:$u:$mid",
                                                sender = "", kind = "system", type = "text",
                                                bodyEnc = null, bodyPlain = notice, fileJson = null,
                                                status = "sent", createdAt = System.currentTimeMillis() / 1000.0,
                                                editedAt = null, reactions = null,
                                            )
                                            if (_activeChatId.value == c.id) loadChatMessages(c.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "account_deleted" -> {
                        // v8.2: пользователь удалил аккаунт — чистим всё его
                        val username = ev["username"]?.jsonPrimitive?.content ?: return@launch
                        val u = username.lowercase()
                        if (u == acc.username.lowercase()) {
                            // аккаунт удалён на этом или другом устройстве — токен мёртв
                            logout()
                            showNotice("аккаунт удалён")
                            return@launch
                        }
                        val gone = _chats.value.filter {
                            !it.is_group && (it.peerAddress ?: "").lowercase() == u
                        }
                        for (c in gone) db.chatDelete(c.id)
                        _contacts.value = _contacts.value.filter {
                            (it.username ?: "").lowercase() != u &&
                                (it.address ?: "").lowercase() != u
                        }
                        refreshChatsFlow()
                        showNotice("пользователь $username удалил аккаунт")
                    }
                    "msg_del" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val msgId = ev["msg_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        db.msgMarkDeleted(chatId, msgId)
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "msg_edit" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val msgEl = ev["msg"] ?: return@launch
                        val m = json.decodeFromJsonElement(Msg.serializer(), msgEl.jsonObject)
                        storeServerMessage(chatId, m)
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "msg_react" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val msgId = ev["msg_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val reactions = ev["reactions"]?.let {
                            runCatching {
                                json.decodeFromJsonElement(
                                    ListSerializer(Reaction.serializer()),
                                    it
                                )
                            }.getOrNull()
                        } ?: return@launch
                        db.msgUpdateReactions(chatId, msgId, json.encodeToString(reactions))
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "p2p_msg" -> onP2pMsg(ev, acc.username)
                    "p2p_sent" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val clientId = ev["client_id"]?.jsonPrimitive?.content ?: return@launch
                        val delivered = ev["delivered"]?.jsonPrimitive?.content == "true"
                        db.p2pStatus(chatId, clientId, if (delivered) "sent" else "pending")
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "p2p_read" -> {
                        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return@launch
                        val lastClientId = ev["last_client_id"]?.jsonPrimitive?.content ?: return@launch
                        // пометим все мои сообщения до этого client_id как прочитанные
                        val rows = db.p2pMessages(chatId)
                        var seen = false
                        for (r in rows.asReversed()) {
                            if (r.clientId == lastClientId) seen = true
                            if (seen && r.sender == acc.username) {
                                db.p2pStatus(chatId, r.clientId ?: continue, "read")
                            }
                        }
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                    "p2p_file" -> onP2pFileChunk(ev)
                    "p2p_sys" -> onP2pSys(ev)
                    "call_invite", "call_accept", "call_decline", "call_end", "call_missed", "call_frame" ->
                        CallManager.onWsEvent(ev)
                    "fed_status" -> {
                        val domain = ev["domain"]?.jsonPrimitive?.content ?: return@launch
                        val st = ev["status"]?.jsonPrimitive?.content ?: return@launch
                        _fedStatus.value = "$domain: $st"
                    }
                    "hello" -> {
                        // v12: запоминаем домен своего сервера (для E2E групп)
                        _serverDomain.value =
                            ev["server_domain"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    "pong" -> Unit
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun onMsgEvent(ev: JsonObject) {
        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return
        val msgEl = ev["msg"] ?: return
        val m = json.decodeFromJsonElement(Msg.serializer(), msgEl.jsonObject)
        val self = ev["self"]?.jsonPrimitive?.content == "true"
        storeServerMessage(chatId, m)
        bumpChatByMessage(chatId, m, mine = self, incoming = !self)
        // «Прочитано» ставим только если чат РЕАЛЬНО открыт на экране
        // (чат открыт И приложение на переднем плане). Иначе — уведомление:
        // раньше activeChatId не сбрасывался при выходе в список чатов, и
        // сообщения «ложно прочитывались» без уведомлений.
        val inChatOpen = _activeChatId.value == chatId && isForeground()
        if (inChatOpen) {
            loadChatMessages(chatId)
            runCatching {
                val chat = _chats.value.firstOrNull { it.id == chatId }
                val isFed = chat?.peerAddress?.contains("@") == true
                if (isFed) {
                    api?.read(chatId, m.id, m.client_id)
                } else {
                    api?.read(chatId, m.id)
                }
                if (chat != null) {
                    val upd = chat.copy(unread = 0, last_read = maxOf(chat.last_read, m.id))
                    db.chatPut(chatId, json.encodeToString(upd))
                    refreshChatsFlow()
                }
            }
        } else if (!self) {
            notifyMessage(chatId, m)
        }
    }

    private fun onStatusEvent(ev: JsonObject) {
        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return
        val read = ev["last_read"]?.jsonPrimitive?.longOrNull
        val delivered = ev["last_delivered"]?.jsonPrimitive?.longOrNull
        val readClientId = ev["read_client_id"]?.jsonPrimitive?.content
        val deliveredClientId = ev["delivered_client_id"]?.jsonPrimitive?.content
        var r = read
        var d = delivered
        if (r == null && readClientId != null) {
            r = db.findMidByClientId(chatId, readClientId)
        }
        if (d == null && deliveredClientId != null) {
            d = db.findMidByClientId(chatId, deliveredClientId)
        }
        val (oldRead, oldDelivered) = db.chatCursors(chatId)
        db.chatCursorsSet(chatId, maxOf(r ?: 0, oldRead), maxOf(d ?: 0, oldDelivered))
        if (_activeChatId.value == chatId) loadChatMessages(chatId)
    }

    private fun presenceUpdate(username: String, online: Boolean) {
        val chats = _chats.value
        var changed = false
        val updated = chats.map { c ->
            if (!changed && !c.is_group && c.peerAddress == username && c.peer?.online != online) {
                changed = true
                c.copy(peer = c.peer?.copy(online = online))
            } else if (c.is_group) {
                val members = c.members.map {
                    if (it.username == username && it.online != online) it.copy(online = online) else it
                }
                if (members != c.members) {
                    changed = true
                    c.copy(members = members)
                } else c
            } else c
        }
        if (changed) {
            for (c in updated) db.chatPut(c.id, json.encodeToString(c))
            _chats.value = updated
        }
    }

    /**
     * v12: системное сообщение о звонке в чате без истории на сервере.
     * Сервер такие чаты не хранит — рассылает итог звонка живьём
     * (p2p_sys); храним локально, как остальные p2p-сообщения.
     */
    private fun onP2pSys(ev: JsonObject) {
        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return
        val clientId = ev["client_id"]?.jsonPrimitive?.content ?: return
        val text = ev["text"]?.jsonPrimitive?.content ?: return
        val ts = ev["ts"]?.jsonPrimitive?.doubleOrNull ?: (System.currentTimeMillis() / 1000.0)
        if (db.p2pHas(chatId, clientId)) return
        db.p2pPut(chatId, clientId, "", "system", text, null, "incoming", ts)
        setPreview(chatId, text, "system")
        if (_activeChatId.value == chatId) loadChatMessages(chatId)
        refreshChatsFlow()
    }

    private suspend fun onP2pMsg(ev: JsonObject, myUsername: String) {
        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return
        val from = ev["from"]?.jsonPrimitive?.content ?: "?"
        val msgEl = ev["msg"] ?: return
        val msgObj = msgEl.jsonObject
        val clientId = msgObj["client_id"]?.jsonPrimitive?.content ?: return
        val type = msgObj["type"]?.jsonPrimitive?.content ?: "text"
        val body = msgObj["body"]?.jsonPrimitive?.content
        val ts = msgObj["ts"]?.jsonPrimitive?.doubleOrNull ?: (System.currentTimeMillis() / 1000.0)
        if (db.p2pMessages(chatId).any { it.clientId == clientId }) return
        val plain = decryptBody(chatId, from, body, fromSelf = false)
        var fileJson: String? = null
        var storeType = type
        val p2pMeta = parseP2pFileSecret(plain)
        if (p2pMeta != null) {
            p2pMetas[p2pMeta.p2pfile] = p2pMeta
            storeType = "file"
            fileJson = json.encodeToString(FileMeta(id = null, name = p2pMeta.fn, size = p2pMeta.sz))
        }
        db.p2pPut(chatId, clientId, from, storeType, plain, fileJson, "incoming", ts)
        setPreview(chatId, plain, storeType)
        if (_activeChatId.value == chatId) {
            loadChatMessages(chatId)
        }
        if (_activeChatId.value == chatId && isForeground()) {
            val lastIncoming = db.p2pMessages(chatId).lastOrNull { it.sender != myUsername }?.clientId
            if (lastIncoming != null) ws?.sendP2pRead(chatId, lastIncoming)
        } else {
            p2pUnread.merge(chatId, 1, Int::plus)
            notifyP2p(chatId, from, plain)
        }
        refreshChatsFlow()
    }

    private fun onP2pFileChunk(ev: JsonObject) {
        val chatId = ev["chat_id"]?.jsonPrimitive?.longOrNull ?: return
        val tid = ev["transfer_id"]?.jsonPrimitive?.content ?: return
        val total = ev["total"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        val seq = ev["seq"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        val data = ev["data"]?.jsonPrimitive?.content?.let { E2eCrypto.unb64(it) } ?: return
        val tr = p2pTransfers.getOrPut(tid) { P2pTransfer(chatId) }
        tr.total = total
        tr.parts[seq] = data
        // прогресс приёма: приблизительно по собранным чанкам (size — весь шифротекст)
        if (tr.total > 0) {
            val encTotal = ev["size"]?.jsonPrimitive?.longOrNull ?: 0L
            if (encTotal > 0) {
                transferUpdate(chatId, tid, "download", tr.parts.size.toLong() * encTotal / tr.total, encTotal)
            }
        }
        if (tr.total > 0 && tr.parts.size >= tr.total) {
            p2pTransfers.remove(tid)
            val meta = p2pMetas.remove(tid)
            scope.launch {
                try {
                    // v12-n: раньше flatMap{toList()} боксировал КАЖДЫЙ байт в Byte-объект
                    // (100 МиБ — сотни миллионов объектов и шторм GC); теперь простая склейка
                    val blob = java.io.ByteArrayOutputStream(tr.total * 256 * 1024).use { sink ->
                        for (seq in 0 until tr.total) tr.parts[seq]?.let { sink.write(it) }
                        sink.toByteArray()
                    }
                    val acc = _account.value ?: return@launch
                    if (meta != null) {
                        val plain = Tme1.decryptBytes(blob, meta.k, meta.sz)
                        val dir = downloadsDir()
                        var dest = File(dir, meta.fn.replace(Regex("[/\\\\]"), "_"))
                        var n = 1
                        while (dest.exists()) dest = File(dir, "p2p_${meta.fn} (${n++})")
                        dest.writeBytes(plain)
                        db.p2pFileJson(chatId, tid, json.encodeToString(FileMeta(id = null, name = meta.fn, size = meta.sz, voice = meta.voice)))
                        db.kvSet("file:$chatId:$tid", dest.absolutePath)
                        transferDone(chatId, tid)
                        if (_activeChatId.value == chatId) loadChatMessages(chatId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    transferDone(chatId, tid)
                }
            }
        }
    }

    // ------------------------------------------------------------ уведомления

    /**
     * Уведомление о новом сообщении. На десктопе системных уведомлений в ядре
     * нет (Notify-интеграция — зона UI): флаги приватности соблюдаются,
     * событие дублируется в stdout.
     */
    private suspend fun notifyMessage(chatId: Long, m: Msg) {
        val acc = _account.value ?: return
        if (!notificationsEnabled) return
        if (m.kind == "system") return
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        val rawPlain = runCatching {
            decryptBody(chatId, m.sender, m.body, m.sender == acc.username)
        }.getOrNull() ?: "🔒 зашифрованное сообщение"
        // v11: в уведомлении не показываем префикс ответа — только видимый текст.
        val (visiblePlain, _, _, _) = unpackReplyOrNull(rawPlain)
        val isFile = m.type == "file"
        val text = if (isFile) (m.file?.name ?: "файл") else visiblePlain
        showNotification(chat.displayName, m.sender ?: "?", text, isFile)
    }

    private fun notifyP2p(chatId: Long, from: String, plain: String?) {
        if (!notificationsEnabled) return
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return
        // v11: убираем префикс ответа из уведомления.
        val (visiblePlain, _, _, _) = unpackReplyOrNull(plain)
        showNotification(chat.displayName, from, visiblePlain.ifBlank { "🔒 зашифрованное сообщение" }, false)
    }

    private fun showNotification(chatTitle: String, sender: String, text: String, isFile: Boolean) {
        // Приватность: при выключенном превью не раскрываем ни текст, ни
        // отправителя, ни имя чата — только сам факт сообщения.
        val body = if (notifPreviewEnabled) {
            "$chatTitle: $sender: ${if (isFile) "📎 $text" else text.take(120)}"
        } else {
            "новое сообщение"
        }
        // СБОРКА 9: настоящее системное уведомление (Windows — balloon трея,
        // Linux — notify-send с фолбэком на трей) — но ТОЛЬКО когда окно в фоне
        // (скрыто в трей/свернуто/без фокуса): на переднем плане пользователь
        // и так видит сообщение в чате.
        if (DesktopIntegrations.windowInBackground) {
            DesktopIntegrations.notify("Tunnel Messenger", body)
        } else {
            println("[Repository] уведомление (окно активно — не показываем): $body")
        }
    }

    fun unreadOf(chat: Chat): Int =
        chat.unread + (p2pUnread[chat.id] ?: 0)

    fun humanError(e: Throwable): String = when {
        e is ApiError -> e.message ?: "ошибка сервера"
        e.message?.contains("туннель не подключён", true) == true ->
            "туннель выключен — трафик не уходит напрямую, подключите туннель"
        e.message?.contains("Failed to connect", true) == true ||
            e.message?.contains("connect timed out", true) == true ||
            e.message?.contains("ECONNREFUSED", true) == true ||
            e.message?.contains("timeout", true) == true ->
            "нет связи с сервером — проверьте туннель"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Ключи сессии в config.json — стираются при logout (туннель не трогаем). */
    private val SESSION_KEYS = listOf(
        "base_url", "token", "username", "nickname", "server_domain", "max_file_mb",
        "e2e_sk", "e2e_pk", "pending_import",
        "notif_enabled", "notif_preview", "auto_images", "screenshot_block",
    )
}

/** Аналог mapError: преобразует ошибку, сохраняя успех (в stdlib Result нет mapError). */
private fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
