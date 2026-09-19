package com.tunnelmessenger.desktop.tunnel

import com.tunnelmessenger.desktop.data.local.AppConfig
import com.tunnelmessenger.desktop.data.local.AppDirs
import com.tunnelmessenger.desktop.data.crypto.SecureStore
import com.tunnelmessenger.desktop.net.HttpRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/** Статистика туннеля (для TunnelState.lastHandshakeMs и внутреннего использования). */
data class TunnelStats(val rx: Long, val tx: Long, val lastHandshakeMs: Long)

/**
 * Снимок состояния туннеля для UI (контракт 2.4).
 * mode: auto | amnezia | freeturn | off; status: off | connecting | up | error.
 */
data class TunnelState(
    val mode: String = "off",
    val status: String = "off",
    val note: String? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    /** Unix-мс последнего WG/AWG-handshake (движок шлёт hs в unix-секундах; 0 = не было).
     *  СБОРКА 14: раньше сюда клали hs как есть и рисовали «рукопожатий: 1789675009» —
     *  это был сырой timestamp, а не счётчик. */
    val lastHandshakeMs: Long = 0,
)

/** Локальный SOCKS5-прокси движка: 127.0.0.1:port + случайные креды. */
data class ProxySpec(val port: Int, val user: String, val pass: String)

/**
 * Встроенный AmneziaWG-туннель в режиме «локальный прокси» (десктоп-порт).
 *
 * Движок — userspace-бинарник {engineDir}/tunnel-core (+.exe на Windows):
 * тот же stdin/stdout JSON-протокол, что у librtcore.so в Android:
 *   stdin  {"conf":"...","socks_user","socks_pass","log_level":"verbose"}
 *   stdout {"ev":"ready","port":N} / {"ev":"state","s":...,"m":?} /
 *          {"ev":"stats","rx","tx","hs"} / {"ev":"log","m"}
 *
 *  • Системный VPN НЕ поднимается: маршруты/адаптеры не трогаются — трафик
 *    мессенджера уходит только через локальный SOCKS5-прокси движка.
 *  • При каждом запуске генерируются случайные логин/пароль; SOCKS5 слушает
 *    только 127.0.0.1 и требует RFC1929-авторизацию — посторонние процессы
 *    воспользоваться прокси не могут.
 *  • Весь HTTP/WS-трафик мессенджера идёт через этот прокси (см. HttpRouter),
 *    DNS-запросы выполняются через туннель.
 */
object TunnelManager {

    /** Порог «застряло в CONNECTING» для watchdog-а (мс).
     *  Худший путь AUTO: проба Amnezia 8с + ожидание connected релея 60с +
     *  ожидание up движка 45с ≈ 115с. 180с — с запасом. */
    private const val STUCK_CONNECTING_MS = 180_000L

    /** v12 АВТО: окно пробы AmneziaWG. Рабочий прямой handshake завершается
     *  за доли секунды; если за 8с этого не случилось — путь считаем мёртвым. */
    private const val AUTO_AMNEZIA_PROBE_MS = 8_000L

    /** v12 АВТО: интервал плановой перепроверки AmneziaWG, пока туннель
     *  работает через FreeTurn. */
    private const val AUTO_RECHECK_MS = 10 * 60_000L

    /** v12 АВТО: сколько ПОДРЯД полностью провальных циклов пережидать,
     *  прежде чем остановить все движки и сам туннель. */
    private const val AUTO_MAX_FAIL_CYCLES = 3

    /** v12 АВТО: контроль здоровья АКТИВНОГО протокола: 2 пробы TunnelHealth
     *  (≈60с) подряд не прошли — БЫСТРОЕ переключение ([autoReelection]). */
    private const val AUTO_HEALTH_FAILS = 2

    private const val LOG_MAX = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var process: Process? = null
    private var engineJob: Job? = null

    /** Сериализует connect/disconnect/rotate — исключает одновременные запуски движка. */
    private val opMutex = Mutex()

    /** Желаемое состояние: true — пользователь/логика хочет активный туннель. */
    @Volatile private var desiredUp = false

    @Volatile private var stopping = false

    /** Поколение запуска движка: события устаревшего прогона игнорируются. */
    @Volatile private var gen = 0

    /** Последняя ошибка, о которой сообщил сам движок (событие state error). */
    @Volatile private var lastEngineErr: String? = null

    /** Момент перехода в CONNECTING (для watchdog-детекта зависания). */
    @Volatile private var connectingSince: Long = 0L

    /** v11.3 АВТО: идёт конкурентная проверка AmneziaWG+FreeTurn. */
    @Volatile private var autoProbeActive = false

    /** v11.3 АВТО: подряд идущие полностью провальные циклы (оба протокола). */
    @Volatile private var autoFailCycles = 0

    /** v11.3 АВТО: время последней плановой перепроверки AmneziaWG (epoch мс). */
    @Volatile private var lastAutoRecheckAt = 0L

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    /** Журнал движка (последние ~200 строк). */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /** Текущий локальный прокси. Не null с момента готовности SOCKS5. */
    @Volatile
    var currentProxy: ProxySpec? = null
        private set

    /** true если текущий прокси от FreeTurn-цепочки. */
    @Volatile var isFreeTurnActive: Boolean = false
        private set

    val isUp: Boolean get() = _state.value.status == STATUS_UP

    // ------------------------------------------------------- persist (config.json)

    private const val CONF_KEY = "tunnel_conf"
    private const val LAST_KEY = "tunnel_last"

    /** Текст сохранённого .conf (sealed в config.json). */
    fun confText(): String? = AppConfig.getSealed(CONF_KEY)

    private fun persistConf(text: String) {
        AppConfig.putSealed(CONF_KEY, text.trim())
    }

    private fun currentMode(): ProtocolMode =
        ProtocolMode.fromString(AppConfig.getString(ProtocolMode.PREFS_KEY) ?: ProtocolMode.AUTO.name)

    /**
     * СБОРКА 9 (фикс): сохранённый режим для UI. TunnelScreen раньше брал
     * режим из TunnelState.mode, а тот до первого подключения = "off" —
     * выбор FreeTurn/AmneziaWG «забывался» при каждом запуске приложения.
     */
    fun persistedMode(): ProtocolMode = currentMode()

    /**
     * СБОРКА 9 (фикс): строковое имя режима для TunnelState.mode. UI
     * (TunnelScreen) понимает "auto"|"amnezia"|"freeturn"; раньше
     * FREELAY.name.lowercase() давал "freelay", который ни с чем не
     * совпадал — сохранённый режим FreeTurn после перезапуска приложения
     * показывался как «Авто» и подключение шло не по тому пути.
     */
    private fun currentModeWire(): String = when (val m = currentMode()) {
        ProtocolMode.FREELAY -> "freeturn"
        else -> m.name.lowercase()
    }

    /**
     * Восстановить сохранённое состояние при старте приложения (вызывается
     * из Repository.init): если пользователь оставил туннель включённым —
     * поднимаем сразу, без ручного «Подключить».
     */
    fun restorePersisted() {
        val conf = confText()
        if (conf != null) {
            runCatching { AwgConfigParser.parse(conf) }
        }
        if (AppConfig.getString(LAST_KEY) == "on" && conf != null) {
            desiredUp = true
            scope.launch { opMutex.withLock { if (desiredUp) connectLocked() } }
        }
    }

    // ------------------------------------------------------------- публичный API

    /** Выбрать режим туннеля (AUTO/AMNEZIA/FREELAY); активный туннель перезапускается. */
    fun setMode(mode: ProtocolMode) {
        AppConfig.putString(ProtocolMode.PREFS_KEY, mode.name)
        scope.launch {
            opMutex.withLock {
                if (!desiredUp) return@withLock
                if (_state.value.status == STATUS_OFF) return@withLock
                // перезапуск с новым режимом
                stopEngineLocked()
                delay(150)
                if (desiredUp) connectLocked()
            }
        }
    }

    /** Проверить и сохранить конфиг, затем поднять туннель. */
    fun startWithConf(confText: String): Result<Unit> {
        return runCatching {
            val parsed = AwgConfigParser.parse(confText)
            persistConf(confText)
            parsed.summary() // валидация
            desiredUp = true
            autoFailCycles = 0
            AppConfig.putString(LAST_KEY, "on")
            scope.launch { opMutex.withLock { if (desiredUp) connectLocked() } }
        }
    }

    /** Сохранить конфиг без запуска; возвращает краткое описание. */
    fun saveConf(text: String): Result<String> = runCatching {
        val parsed = AwgConfigParser.parse(text)
        persistConf(text)
        parsed.summary()
    }

    /**
     * Полная остановка: SIGTERM → ожидание выхода → SIGKILL при зависании →
     * гарантированный перевод состояния в off.
     */
    fun stopEngine() {
        desiredUp = false
        // Ручное выключение — единственный способ отменить автозапуск.
        AppConfig.putString(LAST_KEY, "off")
        scope.launch {
            opMutex.withLock { stopEngineLocked() }
        }
    }

    /**
     * Перезапустить движок, если туннель активен (смена конфига/прокси,
     * «обновить креды»). Неактивный туннель не трогает.
     */
    fun restartIfActive() {
        scope.launch {
            opMutex.withLock {
                if (!desiredUp) return@withLock
                val st = _state.value.status
                if (st == STATUS_OFF || st == STATUS_ERROR) {
                    connectLocked()
                    return@withLock
                }
                stopEngineLocked()
                delay(150) // запас на освобождение порта
                if (desiredUp) connectLocked()
            }
        }
    }

    // -------------------------------------------------- автовосстановление

    /**
     * Watchdog: пока пользователь хочет туннель (desiredUp), движок не должен
     * остаться мёртвым: падение/kill → перезапуск с экспоненциальным бэкоффом.
     */
    private fun startWatchdog() {
        scope.launch {
            var attempts = 0
            while (true) {
                delay(if (attempts == 0) 5000L else minOf(60_000L, 5000L shl attempts.coerceAtMost(4)))
                val st = _state.value.status
                if (!desiredUp || st == STATUS_UP) {
                    attempts = 0
                    connectingSince = 0L
                    continue
                }
                if (st == STATUS_CONNECTING) {
                    // Зависание в CONNECTING: FreeTurn-рукопожатие занимает до
                    // 45с+ — порог 180с, чтобы не убивать легитимный коннект.
                    val since = connectingSince
                    if (since in 1..(System.currentTimeMillis() - STUCK_CONNECTING_MS)) {
                        appendEngineLog("CONNECTING дольше ${STUCK_CONNECTING_MS / 1000}с — перезапуск")
                        attempts = 0
                        opMutex.withLock {
                            if (desiredUp && _state.value.status == STATUS_CONNECTING) {
                                stopEngineLocked()
                                delay(300)
                                if (desiredUp) connectLocked()
                            }
                        }
                    }
                    continue
                }
                if (confText().isNullOrBlank()) continue
                attempts++
                opMutex.withLock {
                    if (desiredUp &&
                        _state.value.status != STATUS_UP &&
                        _state.value.status != STATUS_CONNECTING
                    ) {
                        connectLocked()
                    }
                }
            }
        }
    }

    init {
        startWatchdog()
        startAutoRecheckLoop()
    }

    // ------------------------------------------------------------- движок

    private suspend fun connectLocked() {
        if (!desiredUp) return // пользователь уже нажал «Отключить» — не поднимаем
        if (_state.value.status == STATUS_CONNECTING || _state.value.status == STATUS_UP) return

        // v11: выбор протокола туннеля (AUTO/AMNEZIA/FREELAY)
        when (currentMode()) {
            ProtocolMode.FREELAY -> {
                // Принудительный FreeTurn: релей + AWG-движок поверх него
                _state.update { it.copy(mode = "freeturn", status = STATUS_CONNECTING, note = null) }
                connectingSince = System.currentTimeMillis()
                val ok = FreeTurnManager.startSaved(preconnectOnly = false)
                if (!ok) {
                    onFreeTurnError("FreeTurn не запустился: ${_state.value.note ?: "ошибка"}")
                }
                return
            }
            ProtocolMode.AUTO -> {
                autoConnectLocked()
                return
            }
            ProtocolMode.AMNEZIA -> {
                // Принудительный Amnezia — стандартный путь (ниже)
            }
        }

        val confText = confText()
        if (confText.isNullOrBlank()) {
            fail("сначала импортируйте конфиг туннеля")
            return
        }
        val bin = AppDirs.engineBinary()
        if (!bin.exists()) {
            fail("движок туннеля не найден (${bin.path})")
            return
        }
        launchEngine(bin, confText)
    }

    /** Запуск AWG-движка и ожидание события up (используется и как проба). */
    private fun launchEngine(bin: File, confText: String) {
        stopping = false
        lastEngineErr = null
        val myGen = ++gen
        _state.update {
            it.copy(
                mode = currentModeWire(),
                status = STATUS_CONNECTING, note = null,
                rxBytes = 0, txBytes = 0, lastHandshakeMs = 0L,
            )
        }
        connectingSince = System.currentTimeMillis()
        engineJob = scope.launch {
            try {
                runEngine(bin.absolutePath, confText, myGen)
            } catch (e: Exception) {
                if (myGen == gen && !stopping && desiredUp) fail(humanize(e))
            }
        }
    }

    private suspend fun runEngine(binPath: String, confText: String, myGen: Int) {
        // Креды локальные для этого прогона: именно они уезжают в движок и
        // именно они попадают в currentProxy на «ready» — рассинхрона нет.
        val (user, pass) = generateCredentials()

        // Хостнеймы Endpoint резолвим средствами ОС/DoH (userspace-движок
        // не имеет доступа к системному DNS).
        val conf = withContext(Dispatchers.IO) { resolveEndpoints(confText) }

        val cfg: JsonObject = buildJsonObject {
            put("conf", conf)
            put("socks_user", user)
            put("socks_pass", pass)
            put("log_level", "verbose")
        }

        // stderr движка (паники Go, ошибки рантайма) вливаем в общий поток —
        // без этого крах движка не оставляет в журнале НИЧЕГО.
        val proc = ProcessBuilder(binPath).redirectErrorStream(true).start()
        process = proc
        try {
            proc.outputStream.use { os -> os.write(cfg.toString().toByteArray(Charsets.UTF_8)) }
            // outputStream закрыт: движок прочитал конфиг и работает,
            // остановка — через destroy() (SIGTERM).

            proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                // события устаревшего прогона не трогают состояние нового
                if (myGen == gen) handleEngineLine(line, user, pass)
            }
            val code = proc.waitFor()
            if (myGen != gen) return // запущен новый движок — состояние уже его
            if (stopping || !desiredUp) {
                setDown()
            } else {
                val real = lastEngineErr
                fail(
                    when {
                        real.isNullOrBlank() -> "движок остановлен (код $code) — смотрите журнал ниже"
                        else -> "движок остановлен (код $code): $real"
                    },
                )
            }
        } finally {
            runCatching { proc.destroy() }
            // чистим только СВОЙ прогон — не задеваем уже запущенный новый
            if (myGen == gen) {
                process = null
                currentProxy = null
                HttpRouter.invalidate()
            }
        }
    }

    private fun handleEngineLine(line: String, user: String, pass: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) {
            // Не-JSON строки (stderr движка: паники, ошибки рантайма) — в журнал,
            // чтобы крах движка всегда можно было увидеть глазами.
            if (trimmed.isNotEmpty()) appendEngineLog("· " + trimmed.take(220))
            return
        }
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return
        when (obj["ev"]?.jsonPrimitive?.content) {
            "ready" -> {
                val port = obj["port"]?.jsonPrimitive?.intOrNull ?: return
                currentProxy = ProxySpec(port, user, pass)
                HttpRouter.invalidate()
            }
            "state" -> {
                val s = obj["s"]?.jsonPrimitive?.content ?: return
                val m = obj["m"]?.jsonPrimitive?.content
                when (s) {
                    "up" -> _state.update { it.copy(status = STATUS_UP, note = null) }
                    "connecting" -> _state.update { it.copy(status = STATUS_CONNECTING, note = null) }
                    "down" -> setDown()
                    "error" -> {
                        if (!m.isNullOrBlank()) lastEngineErr = m
                        appendEngineLog(m ?: "ошибка туннеля")
                        _state.update { it.copy(status = STATUS_ERROR, note = m ?: "ошибка туннеля") }
                    }
                }
            }
            "stats" -> {
                if (_state.value.status != STATUS_UP) return
                val rx = obj["rx"]?.jsonPrimitive?.longOrNull ?: 0L
                val tx = obj["tx"]?.jsonPrimitive?.longOrNull ?: 0L
                val hs = obj["hs"]?.jsonPrimitive?.longOrNull ?: 0L
                _state.update { it.copy(rxBytes = rx, txBytes = tx, lastHandshakeMs = hs * 1000L) }
            }
            "log" -> {
                val m = obj["m"]?.jsonPrimitive?.content ?: return
                appendEngineLog(m)
            }
        }
    }

    private suspend fun stopEngineLocked() {
        stopping = true
        gen++
        // v11: если активен FreeTurn — останавливаем и его (в любом статусе,
        // не только UP — иначе застрявший в CONNECTING FreeTurn не остановить)
        if (FreeTurnManager.isRunning() || currentProxy != null) {
            runCatching { FreeTurnManager.stop() }
        }
        val proc = process
        if (proc != null) {
            runCatching { proc.destroy() }
            withTimeoutOrNull(2500) { engineJob?.join() }
            if (proc.isAlive) {
                runCatching { proc.destroyForcibly() }
                withTimeoutOrNull(1500) { engineJob?.join() }
            }
        }
        engineJob = null
        process = null
        setDown()
    }

    // ------------------------------------------------------------ АВТО-режим

    /**
     * v12: АВТО-режим — первичный выбор протокола.
     *
     *  1. ПРОВЕРЯЮТСЯ СРАЗУ ОБА пути: FreeTurn-релей стартует фоном
     *     (preconnectOnly: только релей, без движка), параллельно
     *     пробуется AmneziaWG (≤[AUTO_AMNEZIA_PROBE_MS]).
     *  2. Работают оба → остаётся AmneziaWG (приоритет: быстрее);
     *     релей FreeTurn НЕ гасится — остаётся «тёплым» резервом.
     *  3. AmneziaWG не поднялся — его движок гасится, на готовом релее
     *     запускается AWG-движок ([startEngineForFreeTurn]) → FreeTurn.
     *  4. Пока туннель работает через FreeTurn — каждые [AUTO_RECHECK_MS]
     *     плановая проба AmneziaWG ([autoAmneziaRecheck], без разрыва релея).
     *  5. НИ ОДИН не работает — после [AUTO_MAX_FAIL_CYCLES] подряд идущих
     *     провальных циклов останавливаем все движки и туннель ([onAutoBothFailed]).
     *  6. Выбранный протокол «тихо» умер — супервизор запускает
     *     БЫСТРОЕ переключение ([autoReelection]).
     */
    private suspend fun autoConnectLocked() {
        // ВАЖНО: autoFailCycles здесь НЕ сбрасывается — сброс только при
        // ручном включении (startWithConf) и при любом успехе.
        appendEngineLog("АВТО: конкурентная проверка AmneziaWG и FreeTurn…")
        _state.update { it.copy(mode = "auto", status = STATUS_CONNECTING, note = null) }
        connectingSince = System.currentTimeMillis()

        // FreeTurn-релей стартует фоново (до 60с: TURN-сессии + возможная капча).
        // preconnectOnly: ТОЛЬКО релей, без движка — решение о движке принимаем сами.
        autoProbeActive = true
        val ftRelay = scope.async { FreeTurnManager.startSaved(preconnectOnly = true) }

        // AmneziaWG решается быстро (≤8с): прямой UDP-handshake.
        val amneziaOk = tryAmnezia()

        if (amneziaOk) {
            // Работают оба → AmneziaWG (приоритет — быстрее). Релей НЕ гасим —
            // остаётся тёплым резервом.
            appendEngineLog("АВТО: AmneziaWG доступен — используется он (релей FreeTurn остаётся тёплым резервом)")
            autoProbeActive = false
            autoFailCycles = 0
            lastAutoRecheckAt = 0L
            return
        }

        // AmneziaWG не сработал — гасим ТОЛЬКО его движок (не трогая FreeTurn).
        stopFreeTurnEngine(keepError = true)
        val relayReady = ftRelay.await()
        autoProbeActive = false
        if (!desiredUp) {
            // пользователь успел нажать «Отключить» во время проверки —
            // ничего не запускаем, только гасим оставшийся релей
            FreeTurnManager.stopCoreOnly()
            return
        }
        if (relayReady) {
            appendEngineLog("АВТО: AmneziaWG недоступен — переключаюсь на FreeTurn")
            val engineOk = startEngineForFreeTurn(FreeTurnManager.relayPort)
            if (engineOk) {
                autoFailCycles = 0
                // первая плановая перепроверка AmneziaWG — через 10 минут
                lastAutoRecheckAt = System.currentTimeMillis()
                return
            }
            // релей жив, но движок поверх него не поднялся — гасим всё
            appendEngineLog("АВТО: AWG-движок не поднялся поверх релея FreeTurn")
            FreeTurnManager.stop()
        }
        onAutoBothFailed()
    }

    /**
     * v11.3: оба протокола не сработали. Первые [AUTO_MAX_FAIL_CYCLES]-1 раз
     * помечаем ошибку и даём watchdog-у повторить цикл; после N подряд —
     * останавливаем ВСЕ движки и сам туннель (desiredUp=false, автозапуск
     * снят). Включить обратно — только вручную.
     */
    private suspend fun onAutoBothFailed() {
        autoFailCycles++
        if (autoFailCycles >= AUTO_MAX_FAIL_CYCLES) {
            appendEngineLog("АВТО: оба протокола недоступны после $autoFailCycles циклов — останавливаю туннель")
            desiredUp = false
            AppConfig.putString(LAST_KEY, "off")
            stopEngineLocked()
            _state.update {
                it.copy(
                    status = STATUS_ERROR,
                    note = "АВТО: ни AmneziaWG, ни FreeTurn не подключились — туннель остановлен. " +
                        "Включите его вручную, когда появится связь.",
                )
            }
        } else {
            val left = AUTO_MAX_FAIL_CYCLES - autoFailCycles
            appendEngineLog("АВТО: оба протокола недоступны (осталось попыток: $left)")
            _state.update {
                it.copy(
                    status = STATUS_ERROR,
                    note = "АВТО: оба протокола недоступны (попытка $autoFailCycles из $AUTO_MAX_FAIL_CYCLES) — повтор…",
                )
            }
        }
    }

    /**
     * v12 АВТО: супервизор активного протокола, тик раз в 30с.
     *
     * 1) ЗДОРОВЬЕ: пока туннель UP (любой протокол), читаем пробы связи
     *    [TunnelHealth] (каждые 30с GET /api/health СТРОГО через туннель).
     *    AUTO_HEALTH_FAILS (=2, ≈60с) подряд неуспешных → [autoReelection].
     * 2) ПЛАНОВАЯ ПРОБА AmneziaWG раз в [AUTO_RECHECK_MS] (10 минут), пока
     *    туннель работает через FreeTurn ([autoAmneziaRecheck] — релей не рвётся).
     */
    private fun startAutoRecheckLoop() {
        scope.launch {
            while (true) {
                delay(30_000L)
                if (!desiredUp) continue
                if (autoProbeActive) continue
                if (_state.value.status != STATUS_UP) continue // DOWN/ERROR чинит watchdog
                if (currentMode() != ProtocolMode.AUTO) continue

                // 1) здоровье активного протокола → переперевыбор
                val h = TunnelHealth.state.value
                if (!h.noServer && h.lastCheckAt != 0L && h.failures >= AUTO_HEALTH_FAILS) {
                    runCatching { autoReelection("пробы связи не проходят подряд: ${h.failures}") }
                    continue
                }

                // 2) плановая перепроверка AmneziaWG при работе через FreeTurn
                if (!isFreeTurnActive) continue
                val now = System.currentTimeMillis()
                if (lastAutoRecheckAt != 0L && now - lastAutoRecheckAt < AUTO_RECHECK_MS) continue
                lastAutoRecheckAt = now
                runCatching { autoAmneziaRecheck() }
            }
        }
    }

    /**
     * v12 АВТО: БЫСТРОЕ переключение, когда активный путь умер «тихо»
     * (статус остаётся UP, движок жив, но связь через туннель потеряна).
     * Цепочка под мьютексом (от быстрого к медленному):
     *   1) проба AmneziaWG (≤8с);
     *   2) тёплый релей FreeTurn → движок поверх него — секунды;
     *   3) полный запуск FreeTurn (релей + движок) — минута, крайний случай;
     *   4) и это не взлетело — [onAutoBothFailed].
     */
    private suspend fun autoReelection(reason: String) {
        opMutex.withLock {
            if (!desiredUp) return@withLock
            if (_state.value.status != STATUS_UP) return@withLock
            if (currentMode() != ProtocolMode.AUTO) return@withLock
            appendEngineLog("АВТО: активный путь потерял связь ($reason) — быстрое переключение")
            _state.update { it.copy(status = STATUS_CONNECTING, note = null) }
            connectingSince = System.currentTimeMillis()
            autoProbeActive = true

            // Гасим ТОЛЬКО движок: тёплый релей FreeTurn (если был) переживает.
            stopFreeTurnEngine(keepError = true)
            delay(250) // запас на освобождение SOCKS-порта старого движка

            // 1) AmneziaWG ещё жив? (короткая проба, ≤8с)
            if (tryAmnezia()) {
                autoProbeActive = false
                autoFailCycles = 0
                lastAutoRecheckAt = 0L
                appendEngineLog("АВТО: AmneziaWG снова отвечает — переключились на него")
                return@withLock
            }
            // 2) тёплый релей есть → движок поверх релея (секунды)
            if (FreeTurnManager.isRunning() && FreeTurnManager.relayPort > 0) {
                appendEngineLog("АВТО: AmneziaWG недоступен — движок на тёплом релее FreeTurn")
                if (startEngineForFreeTurn(FreeTurnManager.relayPort)) {
                    autoProbeActive = false
                    autoFailCycles = 0
                    lastAutoRecheckAt = System.currentTimeMillis()
                    appendEngineLog("АВТО: FreeTurn поднят на тёплом релее — связь восстановлена")
                    return@withLock
                }
                appendEngineLog("АВТО: движок не поднялся на тёплом релее — полный запуск FreeTurn")
            }
            // 3) релея нет / протух — полный запуск FreeTurn (релей + движок)
            runCatching { FreeTurnManager.stop() } // гасит релей (и движок), чистит состояние
            if (!desiredUp) return@withLock // пользователь нажал «Отключить» во время переключения
            if (FreeTurnManager.startSaved(preconnectOnly = false)) {
                autoProbeActive = false
                autoFailCycles = 0
                lastAutoRecheckAt = System.currentTimeMillis()
                appendEngineLog("АВТО: FreeTurn поднят (полный запуск) — связь восстановлена")
                return@withLock
            }
            // 4) ни тот ни другой — счётчик провальных циклов
            autoProbeActive = false
            onAutoBothFailed()
        }
    }

    /**
     * v12 АВТО: плановая проба AmneziaWG во время работы на FreeTurn.
     * Под мьютексом: гасим ТОЛЬКО движок FreeTurn (релей остаётся тёплым)
     * → проба AmneziaWG (≤8с) →
     *   заработал: остаёмся на нём (релей — тёплый резерв);
     *   нет: мгновенный возврат — движок заново на тёплом релее.
     */
    private suspend fun autoAmneziaRecheck() {
        opMutex.withLock {
            if (!desiredUp || !isFreeTurnActive) return@withLock
            if (_state.value.status != STATUS_UP) return@withLock
            if (currentMode() != ProtocolMode.AUTO) return@withLock
            appendEngineLog("АВТО: плановая проба AmneziaWG (раз в 10 минут)…")
            _state.update { it.copy(status = STATUS_CONNECTING, note = null) }
            autoProbeActive = true
            // Гасим ТОЛЬКО движок FreeTurn — релей остаётся тёплым.
            stopFreeTurnEngine(keepError = true)
            delay(250) // запас на освобождение SOCKS-порта старого движка
            val awgOk = tryAmnezia()
            if (awgOk) {
                autoProbeActive = false
                autoFailCycles = 0
                appendEngineLog("АВТО: AmneziaWG заработал — переключились с FreeTurn (релей остаётся тёплым резервом)")
                return@withLock
            }
            // не заработал — мгновенный возврат на тёплый релей
            appendEngineLog("АВТО: AmneziaWG недоступен — мгновенный возврат на FreeTurn")
            var ok = false
            if (FreeTurnManager.isRunning() && FreeTurnManager.relayPort > 0) {
                ok = startEngineForFreeTurn(FreeTurnManager.relayPort)
            }
            if (!ok) {
                appendEngineLog("АВТО: тёплый релей не отвечает — полный запуск FreeTurn")
                runCatching { FreeTurnManager.stop() }
                if (desiredUp) {
                    ok = FreeTurnManager.startSaved(preconnectOnly = false)
                }
            }
            autoProbeActive = false
            if (ok) {
                autoFailCycles = 0
                appendEngineLog("АВТО: FreeTurn восстановлен")
            } else {
                appendEngineLog("АВТО: FreeTurn не восстановился — повтор через watchdog")
            }
        }
    }

    /**
     * Попытка поднять AmneziaWG-туннель (используется в AUTO-режиме).
     * Запускает движок и ПОЛЛИНГОМ (300мс) ждёт до [AUTO_AMNEZIA_PROBE_MS] (8с)
     * статуса UP — при успехе выходим сразу.
     */
    private suspend fun tryAmnezia(): Boolean {
        val confText = confText() ?: return false
        val bin = AppDirs.engineBinary()
        if (!bin.exists()) return false
        stopping = false
        lastEngineErr = null
        val myGen = ++gen
        engineJob = scope.launch {
            try {
                runEngine(bin.absolutePath, confText, myGen)
            } catch (e: Exception) {
                if (myGen == gen && !stopping && desiredUp) fail(humanize(e))
            }
        }
        // Поллинг с ранним выходом при успехе
        val deadline = System.currentTimeMillis() + AUTO_AMNEZIA_PROBE_MS
        while (System.currentTimeMillis() < deadline) {
            if (stopping || !desiredUp) return false
            when (_state.value.status) {
                STATUS_UP -> return true
                STATUS_ERROR -> return false
                else -> { /* ещё CONNECTING */ }
            }
            delay(300)
        }
        val up = _state.value.status == STATUS_UP
        if (!up) {
            // v12 audit: проба не взлетела — гасим движок пробы. Вызывающий код
            // ПЕРЕЗАПИСЫВАЕТ engineJob: процесс пробы оставался бы жить
            // «сиротой» (держал SOCKS/UDP и стучался в сервер).
            stopping = true
            gen++
            process?.let { runCatching { it.destroy() } }
            process = null
            currentProxy = null
            HttpRouter.invalidate()
        }
        return up
    }

    /**
     * v11: запуск AWG-движка (tunnel-core) ПОВЕРХ работающего FreeTurn-релея.
     * Движок получает .conf с Endpoint=127.0.0.1:listenPort (UDP-релей
     * FreeTurn) и MTU 1280, открывает локальный SOCKS5 — его использует
     * HttpRouter.
     *
     * @param listenPort локальный UDP-порт релея FreeTurn (из config.listen).
     * @return true — движок поднялся (статус UP получен от события "state up").
     */
    suspend fun startEngineForFreeTurn(listenPort: Int): Boolean {
        val confText = confText()
        if (confText.isNullOrBlank()) {
            onFreeTurnError("для FreeTurn-режима нужен конфиг AWG — импортируйте .conf в настройках туннеля")
            return false
        }
        val bin = AppDirs.engineBinary()
        if (!bin.exists()) {
            onFreeTurnError("движок туннеля не найден (${bin.path})")
            return false
        }
        val ftConf = overrideConfForFreeTurn(confText, listenPort)
        if (ftConf.isNullOrBlank()) {
            onFreeTurnError("в конфиге AWG нет секции [Peer]/Endpoint — некуда подставлять 127.0.0.1:$listenPort")
            return false
        }
        stopping = false
        lastEngineErr = null
        val myGen = ++gen
        _state.update {
            it.copy(mode = "freeturn", status = STATUS_CONNECTING, note = null)
        }
        connectingSince = System.currentTimeMillis()
        var engineUp = false
        engineJob = scope.launch {
            try {
                runEngine(bin.absolutePath, ftConf, myGen)
            } catch (e: Exception) {
                if (myGen == gen && !stopping && desiredUp) fail(humanize(e))
            }
        }
        // ждём «state up» от движка до 45с (WG-handshake через TURN небыстрый)
        val deadline = System.currentTimeMillis() + 45_000L
        while (System.currentTimeMillis() < deadline) {
            if (!FreeTurnManager.isRunning()) return false // остановлено извне
            when (_state.value.status) {
                STATUS_UP -> { engineUp = true; break }
                STATUS_ERROR, STATUS_OFF -> return false
                else -> { /* ещё CONNECTING */ }
            }
            delay(300)
        }
        if (!engineUp) {
            appendEngineLog("AWG-движок не поднялся за 45с через FreeTurn-релей")
            appendEngineLog("проверьте: бэкенд awg0 на VPS жив, Client ID в clients.json, obf-key совпадает")
            return false
        }
        isFreeTurnActive = true // прокси — от движка, но путь — через FreeTurn
        return true
    }

    /**
     * Готовит .conf для запуска движка поверх FreeTurn:
     * Endpoint в секции [Peer] заменяется на 127.0.0.1:listenPort (локальный
     * UDP-релей FreeTurn), MTU прижимается к 1280.
     */
    private fun overrideConfForFreeTurn(confText: String, listenPort: Int): String? {
        if (listenPort !in 1..65535) return null
        var hasPeer = false
        var anyEndpoint = false
        val out = StringBuilder(confText.length + 64)
        var inPeer = false
        for (raw in confText.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inPeer = line.lowercase().startsWith("[peer")
                if (inPeer) hasPeer = true
                out.appendLine(raw)
                continue
            }
            if (inPeer && line.lowercase().startsWith("endpoint")) {
                anyEndpoint = true
                out.appendLine("Endpoint = 127.0.0.1:$listenPort")
                continue
            }
            if (line.lowercase().startsWith("mtu")) {
                out.appendLine("MTU = 1280")
                continue
            }
            out.appendLine(raw)
        }
        if (!hasPeer || !anyEndpoint) {
            // нет Endpoint — добавим в конец секции [Peer]
            if (!hasPeer) return null
            out.appendLine("Endpoint = 127.0.0.1:$listenPort")
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * v11: остановка движка, поднятого поверх FreeTurn (вызывается из
     * [FreeTurnManager.stop]). keepError=true — не затирать текущую ошибку UI.
     */
    fun stopFreeTurnEngine(keepError: Boolean) {
        // stopping + gen++: умирающий runEngine-корутин увидит myGen != gen
        // и НЕ тронет состояние (иначе после destroy он вызвал бы fail()
        // и статус прыгал off → error → «мигание» состояния)
        stopping = true
        gen++
        val proc = process ?: run {
            if (!keepError) {
                currentProxy = null
                HttpRouter.invalidate()
            }
            return
        }
        runCatching { proc.destroy() }
        engineJob = null
        process = null
        currentProxy = null
        isFreeTurnActive = false
        HttpRouter.invalidate()
        if (!keepError) {
            _state.update { it.copy(status = STATUS_OFF, note = null) }
        }
    }

    // ------------------------------------------- события от FreeTurnManager

    /** v11: вызывается FreeTurnManager при готовности релея — статус/режим UI. */
    fun onFreeTurnConnecting() {
        connectingSince = System.currentTimeMillis()
        _state.update { it.copy(mode = "freeturn", status = STATUS_CONNECTING, note = null) }
    }

    /** v11: вызывается FreeTurnManager при ошибке запуска.
     *  АВТО: во время конкурентной проверки (autoProbeActive) или когда
     *  уже работает AmneziaWG ошибки FreeTurn не роняют статус туннеля. */
    fun onFreeTurnError(msg: String) {
        if (autoProbeActive) {
            appendFreeTurnLog("FreeTurn: $msg (в процессе выбора протокола — игнорируется)")
            return
        }
        if (_state.value.status == STATUS_UP && !isFreeTurnActive) {
            appendFreeTurnLog("FreeTurn: $msg (активен AmneziaWG — игнорируется)")
            return
        }
        _state.update { it.copy(status = STATUS_ERROR, note = msg) }
    }

    /** v11: вызывается FreeTurnManager при остановке. */
    fun onFreeTurnStopped() {
        currentProxy = null
        isFreeTurnActive = false
        HttpRouter.invalidate()
        _state.update { it.copy(status = STATUS_OFF, note = null) }
    }

    // ------------------------------------------------------------- журнал

    private fun appendEngineLog(m: String) {
        // update{} атомарен: журнал пишется из нескольких потоков (движок,
        // FreeTurn, health) — plain read-copy-write терял строки.
        _log.update { (it + m).takeLast(LOG_MAX) }
    }

    /** v11: добавление логов FreeTurn в общий журнал (видно в TunnelScreen). */
    fun appendFreeTurnLog(m: String) {
        _log.update { (it + "[FREETURN] $m").takeLast(LOG_MAX) }
    }

    /** v11.3: журнал health-проб связи ([TunnelHealth]). */
    fun appendHealthLog(m: String) {
        _log.update { (it + "[HEALTH] $m").takeLast(LOG_MAX) }
    }

    private fun setDown() {
        currentProxy = null
        isFreeTurnActive = false
        HttpRouter.invalidate()
        _state.update {
            it.copy(mode = "off", status = STATUS_OFF, note = null, rxBytes = 0, txBytes = 0, lastHandshakeMs = 0L)
        }
    }

    private fun fail(message: String) {
        currentProxy = null
        isFreeTurnActive = false
        HttpRouter.invalidate()
        _state.update { it.copy(status = STATUS_ERROR, note = message) }
    }

    // ----------------------------------------------------------- утилиты

    /** Случайные креды прокси: новый набор при каждом запуске туннеля. */
    private fun generateCredentials(): Pair<String, String> {
        val rnd = SecureRandom()
        fun token(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(rnd::nextBytes))
        return token(9) to token(18) // ~16 и ~24 символа
    }

    /**
     * Заменяет хостнеймы в Endpoint= на IP-адреса (резолв через DoH, затем
     * системный DNS). IP-литералы и неразрешимые хосты остаются как есть.
     */
    private fun resolveEndpoints(confText: String): String {
        val out = StringBuilder(confText.length + 64)
        var inPeer = false
        for (raw in confText.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inPeer = line.lowercase().startsWith("[peer")
                out.appendLine(raw)
                continue
            }
            if (inPeer && line.lowercase().startsWith("endpoint")) {
                val value = line.substringAfter('=').trim()
                out.appendLine("Endpoint = ${resolveHost(value)}")
                continue
            }
            out.appendLine(raw)
        }
        return out.toString().trimEnd('\n')
    }

    private fun resolveHost(endpoint: String): String {
        // формы: host:port | [v6]:port | v6-литерал | хост без порта
        val (host, portSuffix) = when {
            endpoint.startsWith("[") ->
                endpoint.substringAfter('[').substringBefore(']') to endpoint.substringAfter(']', "")
            endpoint.count { it == ':' } == 1 ->
                endpoint.substringBefore(':') to endpoint.substringAfter(':', "")
            else -> endpoint to ""
        }
        if (host.isBlank() || isIpLiteral(host)) return endpoint
        // Приватность (DNS-утечки): домен туннеля НЕ уходит в открытый
        // системный DNS провайдера — сначала DoH поверх TLS к публичным
        // резолверам (ISP видит лишь соединение к 1.1.1.1:443), системный
        // DNS — только крайний фолбэк, иначе туннель не поднимется.
        val addr = dohResolve(host)
            ?: runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
            ?: return endpoint
        return if (addr.contains(':')) "[$addr]$portSuffix" else addr + portSuffix
    }

    private fun isIpLiteral(host: String): Boolean = when {
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) -> true
        host.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(host) -> true
        else -> false
    }

    /**
     * DNS-over-HTTPS (JSON API) к IP-литеральным резолверам — домен самого
     * резолвера не нужен, значит никакого предварительного DNS-запроса.
     * Возвращает первый A/AAAA-ответ или null (оба недоступны/заблокированы).
     */
    private fun dohResolve(host: String): String? {
        val enc = URLEncoder.encode(host, "UTF-8")
        for (base in listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")) {
            for ((type, num) in listOf("A" to 1, "AAAA" to 28)) {
                val ip = runCatching {
                    val conn = (URL("$base?name=$enc&type=$type").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("accept", "application/dns-json")
                    }
                    conn.inputStream.use { stream ->
                        val obj = runCatching {
                            json.parseToJsonElement(stream.bufferedReader().readText()) as? JsonObject
                        }.getOrNull() ?: return@runCatching null
                        val ans = obj["Answer"] as? JsonArray ?: return@runCatching null
                        for (el in ans) {
                            val rec = el as? JsonObject ?: continue
                            if ((rec["type"] as? JsonPrimitive)?.content?.toIntOrNull() == num) {
                                val data = (rec["data"] as? JsonPrimitive)?.content
                                if (!data.isNullOrEmpty()) return@runCatching data
                            }
                        }
                        null
                    }
                }.getOrNull()
                if (ip != null) return ip
            }
        }
        return null
    }

    private fun humanize(e: Exception): String = when {
        e.message?.contains("Permission denied", true) == true ->
            "не удалось запустить движок (Permission denied) — проверьте права на файл ${AppDirs.engineBinary().path}"
        else -> e.message ?: e.javaClass.simpleName
    }

    // строки статусов TunnelState (контракт 2.4)
    const val STATUS_OFF = "off"
    const val STATUS_CONNECTING = "connecting"
    const val STATUS_UP = "up"
    const val STATUS_ERROR = "error"
}
