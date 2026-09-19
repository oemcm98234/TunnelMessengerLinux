package com.tunnelmessenger.desktop.tunnel

import com.tunnelmessenger.desktop.data.local.AppConfig
import com.tunnelmessenger.desktop.data.local.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер FreeTurn-туннеля (десктоп-порт).
 *
 * FreeTurn — upstream-проект samosvalishe/free-turn-proxy. Начиная со СБОРКИ 4
 * официальный CLI-клиент ВШИТ в поставку (app-resources/common) и автоматически
 * извлекается в {engineDir} — ручная укладка не нужна (см. AppDirs.resolveEngine).
 *
 * СБОРКА 8: ядро ПЕРЕСОБРАНО из исходников GitHub (HEAD после v3.4.0,
 * версия ядра v3.4.0-13.8, CGO_ENABLED=0, linux-amd64 + windows-amd64).
 * Попутно исправлен протокол запуска:
 *  - «-links» — ОДИН флаг со ссылками ЧЕРЕЗ ЗАПЯТУЮ. Go-флаг при повторе
 *    перезаписывает значение, поэтому старый подход «-links по одной на
 *    ссылку» доносил до ядра только ПОСЛЕДНЮЮ ссылку;
 *  - передаётся -manual-captcha (ручная VK-капча), если включена в конфиге.
 *
 * Ядро запускается как ПОДПРОЦЕСС {engineDir}/freeturn-client (+.exe на
 * Windows) с CLI-флагами Go `flag` package официального клиента:
 *
 *   -listen 127.0.0.1:9000
 *   -peer host:port
 *   -provider vk
 *   -links https://vk.ru/call/join/...   (повторяемый)
 *   -n 17
 *   -transport tcp|udp
 *   -mode udp|tcp
 *   -dns-servers <ip>
 *   -client-id / -obf-profile / -obf-key (из URI)
 *
 * FreeTurn в режиме udp — это UDP-релей (НЕ SOCKS5): SOCKS5-прокси после
 * этого поднимает AWG-движок (TunnelManager.startEngineForFreeTurn) с
 * Endpoint=127.0.0.1:<listen>.
 *
 * URI-формат: `freeturn://<base64url(json)>` — peer, mode, транспорт,
 * обф-ключи и т.д. (v:1 обязателен). VK-ссылки в URI НЕ лежат (per-client).
 *
 * Лог-префикс: [FREETURN] — журнал движка в TunnelScreen различает режимы.
 * Официальный клиент пишет логи через Go `log` (stderr, формат
 * «2026/… [INFO] …»); протокольных строк PROXY_READY| у него нет — готовность
 * распознаётся по позитивным строкам лога («TCP mode: listening on …»,
 * «[session N] connected…», «UDP relay listening…») либо фактом «процесс
 * жив спустя таймаут» (релей UDP может молчать до первой сессии).
 * Капча VK (СБОРКА 9): URL локального captcha-прокси ядра (127.0.0.1:8765)
 * публикуется в [captchaUrl] — UI показывает ВСТРОЕННЫЙ WebView (CaptchaDialog,
 * порт Android CaptchaWebViewDialog); успех ядра (строка «received success
 * token») гасит капчу. Системный браузер — ручной фолбэк из диалога.
 */
object FreeTurnManager {

    private const val CONF_KEY = "freeturn"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Жизненный цикл релея: idle/starting/relay/running/stopping или «error: …». */
    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /**
     * Конфигурация FreeTurn-туннеля.
     *
     * Пользовательские поля — vkLinks (ссылки VK Calls) и streams (1-25,
     * рекомендуемое 17). Остальное приходит из `freeturn://` URI, который
     * генерируется на сервере (`freeturn-install.sh client-add <name>`).
     */
    data class Config(
        val peer: String = "",
        val listen: String = "127.0.0.1:9000",
        val provider: String = "vk",
        val vkLinks: List<String> = emptyList(),
        val streams: Int = 17,
        val transport: String = "tcp",
        val mode: String = "udp",
        val dnsServers: String = "",
        val clientId: String = "",
        val obfProfile: String = "rtpopus3",
        val obfKey: String = "",
        val manualCaptcha: Boolean = false,
    )

    @Volatile var config: Config = Config()
        private set

    init {
        // СБОРКА 9 (фикс): загружаем сохранённый конфиг НА СТАРТЕ приложения —
        // как на Android (ViewModel читает prefs при создании). Раньше конфиг
        // читался только внутри startSaved(), а connect() проверял in-memory
        // config (пустой) и требовал «примените freeturn:// URI» даже при
        // сохранённом URI — до первого применения URI в этой сессии.
        runCatching { loadPersisted() }
    }

    /** Активен ли релей сейчас (процесс жив). */
    @Volatile private var running = false
    fun isRunning(): Boolean = running

    /** Локальный UDP-порт релея (из config.listen). */
    val relayPort: Int
        get() = config.listen.substringAfterLast(":").filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9000

    /** Последний URL капчи VK (null — капчи не требуется/пройдена). */
    @Volatile var lastCaptchaUrl: String? = null
        private set

    /**
     * СБОРКА 9: URL локального captcha-прокси ядра (http://localhost:8765/…) для
     * ВСТРОЕННОГО диалога капчи (CaptchaDialog). Не-null — нужна капча; ядро
     * само погасит её строкой «received success token from browser».
     */
    private val _captchaUrl = MutableStateFlow<String?>(null)
    val captchaUrl: StateFlow<String?> = _captchaUrl

    /** Пользователь скрыл диалог капчи («решу позже») — открыть снова можно из панели Туннеля. */
    private val _captchaHidden = MutableStateFlow(false)
    val captchaHidden: StateFlow<Boolean> = _captchaHidden

    fun hideCaptchaDialog() {
        _captchaHidden.value = true
    }

    fun showCaptchaDialog() {
        _captchaHidden.value = false
    }

    private var process: Process? = null

    /** v12: хэш конфига, С КОТОРЫМ запущено текущее ядро — при смене URI
     *  тёплый релей перезапускается с новым конфигом. */
    private var runningCfgHash: Int = 0

    /** Сериализует startSaved(). Guard `if (running)` ловит только УЖЕ
     *  поднятый релей, но не гонку «фоновый preconnect АВТО ещё в полёте,
     *  а FREELAY-start уже вызван». */
    private val startMutex = Mutex()

    /** Капча открывается в браузере не более одного раза за сессию релея. */
    private val captchaOpened = AtomicBoolean(false)

    fun appendLog(m: String) {
        _log.value = (_log.value + m).takeLast(200)
        TunnelManager.appendFreeTurnLog(m)
    }

    private fun setState(s: String) {
        _state.value = s
    }

    // ------------------------------------------------------------ конфиг

    /** Загрузить сохранённый конфиг (sealed-блоб в config.json). */
    fun loadPersisted() {
        val raw = AppConfig.getSealed(CONF_KEY) ?: run {
            config = Config(); return
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            config = Config(); return
        }
        fun str(k: String, def: String = "") = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: def
        fun int(k: String, def: Int) = (obj[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
        val links = (obj["vkLinks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        config = Config(
            peer = str("peer"),
            listen = safeListen(str("listen", "127.0.0.1:9000")),
            provider = str("provider", "vk").ifBlank { "vk" },
            vkLinks = links,
            streams = int("streams", 17).coerceIn(1, 25),
            transport = str("transport", "tcp").ifBlank { "tcp" },
            mode = str("mode", "udp").ifBlank { "udp" },
            dnsServers = str("dnsServers"),
            clientId = str("clientId"),
            obfProfile = str("obfProfile", "rtpopus3").ifBlank { "rtpopus3" },
            obfKey = str("obfKey"),
            manualCaptcha = str("manualCaptcha") == "true",
        )
    }

    /** Сохранить конфиг (обф-ключ и client_id — секреты → блоб sealed). */
    fun saveConfig(cfg: Config) {
        config = cfg.copy(
            listen = safeListen(cfg.listen),
            streams = cfg.streams.coerceIn(1, 25),
        )
        val blob = buildJsonObject {
            put("peer", config.peer)
            put("listen", config.listen)
            put("provider", config.provider)
            put("vkLinks", JsonArray(config.vkLinks.map { JsonPrimitive(it) }))
            put("streams", config.streams)
            put("transport", config.transport)
            put("mode", config.mode)
            put("dnsServers", config.dnsServers)
            put("clientId", config.clientId)
            put("obfProfile", config.obfProfile)
            put("obfKey", config.obfKey)
            put("manualCaptcha", config.manualCaptcha.toString())
        }.toString()
        AppConfig.putSealed(CONF_KEY, blob)
    }

    /** v13 (аудит): адрес релея приводится к loopback (хост игнорируется). */
    private fun safeListen(v: String?): String {
        val s = v?.trim().orEmpty().ifBlank { "127.0.0.1:9000" }
        val port = s.substringAfterLast(":", "9000").filter { it.isDigit() }.ifBlank { "9000" }
        return "127.0.0.1:$port"
    }

    // -------------------------------------------------------------- URI

    /**
     * Парсит `freeturn://<base64url(json)>` URI в [Config].
     * Wire-формат URI (короткие имена) — см. internal/uri/uri.go:
     *   v, provider, peer, transport, mode, obf, key, n, cid, listen,
     *   dnss, mcap, name. v:1 обязателен.
     */
    fun parseUri(uri: String): Config? {
        if (!uri.startsWith("freeturn://")) return null
        val payload = uri.removePrefix("freeturn://").substringBefore('#')
        if (payload.isBlank()) return null
        return runCatching {
            // base64 RawURLEncoding (URL-safe, без padding) — добавляем "=" до кратного 4
            val pad = (4 - payload.length % 4) % 4
            val padded = payload + "=".repeat(pad)
            val text = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            val o = json.parseToJsonElement(text).jsonObject
            fun str(k: String, def: String = "") =
                ((o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull })?.content ?: def
            val v = str("v").toIntOrNull() ?: 0
            if (v != 1) return@runCatching null
            Config(
                peer = str("peer"),
                listen = safeListen(str("listen", "127.0.0.1:9000")),
                provider = str("provider", "vk"),
                vkLinks = emptyList(), // per-client, не из URI — в отдельном поле формы
                streams = (str("n", "17").toIntOrNull() ?: 17).coerceIn(1, 25),
                transport = str("transport", "tcp"),
                mode = str("mode", "udp"),
                dnsServers = str("dnss"),
                clientId = str("cid"),
                obfProfile = str("obf", "rtpopus3"),
                obfKey = str("key"),
                // mcap из URI (сервер генерирует false = авто-капча)
                manualCaptcha = str("mcap") == "true",
            )
        }.getOrNull()
    }

    /**
     * Применить URI из формы TunnelScreen и запустить релей.
     * Синхронно проверяем URI/бинарник (понятная ошибка), сам запуск —
     * фоново (TURN-рукопожатие занимает до минуты).
     */
    fun startFromUri(uri: String): Result<Unit> {
        val cfg = parseUri(uri)
            ?: return Result.failure(IllegalArgumentException("не удалось разобрать URI freeturn:// (ожидался v:1)"))
        val merged = cfg.copy(
            vkLinks = config.vkLinks,
            streams = if (cfg.streams == 17) config.streams else cfg.streams,
        )
        saveConfig(merged)
        val bin = AppDirs.freeturnBinary()
        if (!bin.exists()) {
            return Result.failure(
                IllegalStateException(
                    "движок FreeTurn не найден в ${AppDirs.engineDir.absolutePath} — " +
                        "вшитый движок не извлёкся (проверьте права записи); " +
                        "либо положите freeturn-client вручную"
                )
            )
        }
        setState("starting")
        scope.launch {
            startSaved(preconnectOnly = false)
        }
        return Result.success(Unit)
    }

    /**
     * Запуск FreeTurn с сохранённым конфигом (используется TunnelManager
     * в режимах FREELAY/AUTO). Возвращает true, если релей готов.
     *
     * @param preconnectOnly поднять ТОЛЬКО UDP-релей (TURN-сессии), не
     *        запуская AWG-движок поверх него (АВТО-режим решает сам).
     */
    suspend fun startSaved(preconnectOnly: Boolean): Boolean = startMutex.withLock {
        loadPersisted()
        // v12: если релей уже работает (тёплый резерв АВТО) — переиспользуем
        if (running && process?.isAlive == true) {
            if (config.hashCode() != runningCfgHash) {
                appendLog("конфиг FreeTurn изменился — перезапуск релея с новым конфигом")
                stopCoreOnly()
            } else {
                appendLog("релей FreeTurn уже работает (${config.listen}) — переиспользуем")
                if (preconnectOnly) return@withLock true
                return@withLock TunnelManager.startEngineForFreeTurn(relayPort)
            }
        }

        val bin = AppDirs.freeturnBinary()
        if (!bin.exists()) {
            val msg = "движок FreeTurn не найден в ${AppDirs.engineDir.absolutePath} — " +
                "вшитый движок не извлёкся (проверьте права записи); либо положите freeturn-client вручную"
            TunnelManager.onFreeTurnError(msg)
            setState("error: $msg")
            return@withLock false
        }
        if (config.peer.isBlank()) {
            val msg = "не задан peer (сервер) FreeTurn — вставьте freeturn:// URI в настройках туннеля"
            TunnelManager.onFreeTurnError(msg)
            setState("error: $msg")
            return@withLock false
        }
        if (config.vkLinks.isEmpty()) {
            val msg = "не заданы VK-ссылки звонка — FreeTurn не может работать без них " +
                "(ссылки https://vk.ru/call/join/... — в настройках туннеля)"
            TunnelManager.onFreeTurnError(msg)
            setState("error: $msg")
            return@withLock false
        }

        TunnelManager.onFreeTurnConnecting()
        setState("starting")
        captchaOpened.set(false)

        val args = buildCliArgs(config)
        // в args входит -obf-key (секрет) — в журнале UI маскируем значение
        appendLog("запуск: ${redactArgs(args)}")

        val ok = runCatching {
            val cmd = mutableListOf(bin.absolutePath)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
            pb.directory(AppDirs.engineDir)
            val env = pb.environment()
            env["GODEBUG"] = "netdns=cgo"
            env["NO_PROXY"] = "*"
            env["HTTP_PROXY"] = ""
            env["HTTPS_PROXY"] = ""
            val proc = pb.start()
            process = proc
            scope.launch { readProcessStdout(proc) }
            scope.launch { readProcessStderr(proc) }

            // Ждём готовности событийно: PROXY_READY (stdout-ридер выставит
            // running) или смерть процесса. UDP-релей может молчать (без
            // PROXY_READY) — тогда живой процесс после таймаута считаем релеем.
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                if (running || !proc.isAlive) break
                delay(500)
            }
            val alive = proc.isAlive && (running || System.currentTimeMillis() >= deadline)
            if (alive) {
                running = true
                runningCfgHash = config.hashCode()
                appendLog("релей FreeTurn готов (${config.listen})")
                setState("relay")
                true
            } else {
                val msg = "FreeTurn-процесс завершился (проверьте VK-ссылки, peer, сервер)"
                TunnelManager.onFreeTurnError(msg)
                setState("error: $msg")
                false
            }
        }.getOrElse { e ->
            val msg = "ошибка запуска freeturn-client: ${e.message}"
            TunnelManager.onFreeTurnError(msg)
            setState("error: $msg")
            false
        }
        if (!ok) return@withLock false

        if (preconnectOnly) {
            appendLog("preconnect: релей готов (${config.listen}), движок запустит АВТО при выборе FreeTurn")
            return@withLock true
        }

        // Запускаем AWG-движок поверх работающего релея.
        // SOCKS5 теперь даёт ДВИЖОК — как в Amnezia-режиме.
        appendLog("UDP-релей ядра: ${config.listen} → движок шлёт на 127.0.0.1:$relayPort")
        val engineOk = TunnelManager.startEngineForFreeTurn(relayPort)
        if (!engineOk) {
            stopCoreOnly()
            return@withLock false
        }
        setState("running")
        true
    }

    // ----------------------------------------------------------- процесс

    private fun readProcessStdout(proc: Process) {
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                appendLog("OUT: $line")
                if (line.startsWith("PROXY_READY|")) {
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        running = true
                        setState("relay")
                    }
                    return@forEachLine
                }
                if (line.startsWith("PROXY_ERROR|")) {
                    val msg = line.substringAfter("PROXY_ERROR|")
                    TunnelManager.onFreeTurnError("FreeTurn: $msg")
                    setState("error: $msg")
                    runCatching { proc.destroy() }
                    running = false
                }
                maybeRelayReady(line)
                maybeOpenCaptcha(line)
            }
            if (running) {
                val msg = "FreeTurn-процесс завершился (проверьте VK-ссылки, peer, сервер)"
                TunnelManager.onFreeTurnError(msg)
                setState("error: $msg")
            }
            running = false
        } catch (e: Exception) {
            if (running) {
                TunnelManager.onFreeTurnError("ошибка чтения FreeTurn stdout: ${e.message}")
                setState("error: stdout: ${e.message}")
                running = false
            }
        }
    }

    private fun readProcessStderr(proc: Process) {
        try {
            proc.errorStream.bufferedReader().forEachLine { line ->
                appendLog("ERR: $line")
                if (line.contains("FATAL") || line.contains("fatal:")) {
                    if (!TunnelManager.isUp) {
                        TunnelManager.onFreeTurnError("FreeTurn FATAL: $line")
                        setState("error: FATAL: $line")
                    }
                }
                maybeRelayReady(line)
                maybeOpenCaptcha(line)
            }
        } catch (_: Exception) {
            // stderr закрыт — не критично
        }
    }

    /**
     * СБОРКА 4: официальный клиент пишет логи в stderr (Go `log`),
     * протокольных строк PROXY_READY| у него нет. Позитивные маркеры
     * готовности релея из его вывода:
     *   «TCP mode: listening on 127.0.0.1:9000 …»        (-mode tcp)
     *   «[session N] connected (active: M)»              (TURN-сессия поднята)
     *   «UDP relay listening on …» / «udprelay: listening»(-mode udp)
     * До появления маркера живой процесс после таймаута считается релеем
     * (UDP-релей молчит до первой сессии) — это уже было в v12.
     */
    private fun maybeRelayReady(line: String) {
        if (running) return
        val ready = line.contains("TCP mode: listening on") ||
            line.contains("UDP relay listening") ||
            line.contains("udprelay: listening") ||
            line.contains("connected (active:")
        if (ready) {
            running = true
            setState("relay")
        }
    }

    /**
     * СБОРКА 9: капча VK — показываем ВСТРОЕННЫЙ WebView (CaptchaDialog читает
     * captchaUrl). Механика ядра: локальный reverse-proxy на 127.0.0.1:8765
     * отдаёт страницу VK с переписанными URL; токен успеха страница шлёт
     * ОБРАТНО на этот сервер (fetch /local-captcha-result или перехват check),
     * поэтому страницу можно открыть любым браузером той же машины — в т.ч.
     * встроенным. В stdout ядра капча выглядит блоком:
     *     ACTION REQUIRED: MANUAL CAPTCHA SOLVING NEEDED
     *     If your browser didn't open automatically,
     *     manually open this URL: http://localhost:8765
     */
    private fun maybeOpenCaptcha(line: String) {
        // Успех: ядро получило токен решения (stderr-лог ядра).
        if (line.contains("received success token", true) ||
            line.contains("captcha solved", true)
        ) {
            if (_captchaUrl.value != null) {
                appendLog("[CAPTCHA] решена — продолжаю подключение")
            }
            _captchaUrl.value = null
            lastCaptchaUrl = null
            return
        }
        if (_captchaUrl.value != null) return // капча уже показана — не дублируем
        if (!line.contains("captcha", ignoreCase = true) && !line.contains("8765")) return
        // Локальный URL captcha-прокси ядра — только он открывается встроенным окном.
        // Голый «http://localhost:8765» тоже подходит: локальный сервер ядра сам
        // редиректит на полный путь с параметрами сессии.
        val target = Regex("https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):8765[^\\s\"']*")
            .find(line)?.value
            ?: if (line.contains("8765")) "http://localhost:8765" else null
            ?: return // внешний vk-URL в логах встроенному окну не нужен
        lastCaptchaUrl = target
        appendLog("[CAPTCHA] требуется ручное решение — открываю встроенный просмотр: $target")
        _captchaHidden.value = false
        _captchaUrl.value = target
    }

    /**
     * СБОРКА 9: фолбэк, если встроенный WebView не поднялся (нет GTK3 и т.п.) —
     * открыть капчу в системном браузере (один раз на сессию релея).
     */
    fun captchaFallbackToBrowser() {
        val url = _captchaUrl.value ?: lastCaptchaUrl ?: return
        if (captchaOpened.compareAndSet(false, true)) {
            appendLog("[CAPTCHA] встроенный просмотр недоступен — открываю системный браузер")
            runCatching {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
            }.onFailure { appendLog("[CAPTCHA] не удалось открыть браузер: ${it.message}") }
        }
    }

    /** Останавливает FreeTurn (релей + движок поверх него). */
    fun stop() {
        setState("stopping")
        running = false
        // сначала движок (он шлёт WG-пакеты в релей), затем ядро
        TunnelManager.stopFreeTurnEngine(keepError = false)
        stopCoreOnly()
        TunnelManager.onFreeTurnStopped()
        setState("idle")
    }

    /**
     * v12 (АВТО): остановить ТОЛЬКО ядро FreeTurn (UDP-релей/TURN-сессии),
     * НЕ трогая AWG-движок. Полезен при ПОЛНОЙ отмене подключения в АВТО
     * (пользователь нажал «Отключить» во время конкурентной проверки).
     */
    fun stopCoreOnly() {
        setState("stopping")
        running = false
        lastCaptchaUrl = null
        _captchaUrl.value = null
        _captchaHidden.value = false
        val proc = process
        process = null
        proc?.let { p ->
            runCatching { p.destroy() }
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
    }

    // ------------------------------------------------------------- CLI

    /**
     * Строит CLI-args из [Config] — массив строк для `flag` package Go.
     * Порядок: -listen, -peer, -provider, -links (один флаг, через запятую),
     * -n, -transport, -mode, -dns-servers, -client-id, -obf-profile,
     * -obf-key, [-manual-captcha].
     */
    private fun buildCliArgs(cfg: Config): List<String> {
        val args = mutableListOf<String>()
        args.add("-listen"); args.add(cfg.listen.ifBlank { "127.0.0.1:9000" })
        args.add("-peer"); args.add(cfg.peer)
        args.add("-provider"); args.add(cfg.provider.ifBlank { "vk" })
        // СБОРКА 8: -links — ОДИН флаг, ссылки через запятую. Повтор флага в Go
        // перезаписывает значение — раньше до ядра доезжала только последняя ссылка.
        if (cfg.vkLinks.isNotEmpty()) {
            val links = cfg.vkLinks.map { link ->
                if (link.startsWith("http") || link.contains("/call/join/")) {
                    link
                } else {
                    "https://vk.ru/call/join/$link"
                }
            }
            args.add("-links"); args.add(links.joinToString(","))
        }
        args.add("-n"); args.add(cfg.streams.coerceIn(1, 25).toString())
        // transport=tcp по умолчанию: UDP операторы часто режут DPI,
        // TCP-TURN проходит стабильно. URI может переопределить.
        args.add("-transport"); args.add(cfg.transport.ifBlank { "tcp" })
        args.add("-mode"); args.add(cfg.mode.ifBlank { "udp" })
        if (cfg.dnsServers.isNotBlank()) {
            args.add("-dns-servers"); args.add(cfg.dnsServers)
        }
        if (cfg.clientId.isNotBlank()) {
            args.add("-client-id"); args.add(cfg.clientId)
        }
        if (cfg.obfProfile.isNotBlank()) {
            args.add("-obf-profile"); args.add(cfg.obfProfile)
        }
        if (cfg.obfKey.isNotBlank()) {
            args.add("-obf-key"); args.add(cfg.obfKey)
        }
        // СБОРКА 8: ручная VK-капча (в браузере) — раньше флаг не передавался вовсе.
        if (cfg.manualCaptcha) {
            args.add("-manual-captcha=true")
        }
        return args
    }

    /** v12 audit: маскирует ЗНАЧЕНИЕ секретного флага в CLI-args перед записью
     *  в журнал UI (-obf-key, -client-id — секреты авторизации на FT-сервере). */
    private fun redactArgs(args: List<String>): String {
        val sb = StringBuilder()
        var hide = false
        for (a in args) {
            if (hide) {
                sb.append("<redacted> ")
                hide = false
                continue
            }
            sb.append(a).append(' ')
            if (a == "-obf-key" || a == "-client-id") hide = true
        }
        return sb.toString().trim()
    }
}
