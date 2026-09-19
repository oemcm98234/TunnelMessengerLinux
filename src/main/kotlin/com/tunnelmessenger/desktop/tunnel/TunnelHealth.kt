package com.tunnelmessenger.desktop.tunnel

import com.tunnelmessenger.desktop.net.HttpRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v11.3: HEALTH-ТЕСТ ТУННЕЛЯ — как в AmneziaVPN.
 *
 * Пока туннель запущен (любой режим — AmneziaWG, FreeTurn, АВТО),
 * [TunnelHealth] раз в [PROBE_INTERVAL_MS] выполняет ЛЁГКИЙ HTTP-запрос к
 * `/api/health` сервера СТРОГО ЧЕРЕЗ ТУННЕЛЬ (HttpRouter → SOCKS5 движка →
 * туннель → сервер).
 *
 *  • запрос OK за отведённое время → туннель работает (failures сбрасываются);
 *  • запрос не прошёл → failures++; 2+ подряд — «проблемы со связью»
 *    (супервизор АВТО в TunnelManager запускает быстрое переключение).
 *
 * Проба намеренно дешёвая (пара сотен байт, раз в 30с) и не мешает трафику
 * мессенджера: отдельный OkHttpClient с короткими таймаутами.
 */
object TunnelHealth {

    /** Интервал активной пробы, пока туннель UP (мс). */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** Пауза между проверками «туннель поднят?», пока он off (мс). */
    private const val IDLE_POLL_MS = 2_000L

    /** Состояние последней проверки — для UI экрана туннеля. */
    data class HealthState(
        /** epoch мс последней завершённой пробы (0 — ещё не было). */
        val lastCheckAt: Long = 0L,
        /** Последняя проба прошла через туннель успешно? */
        val lastOk: Boolean = false,
        /** Задержка последней успешной пробы, мс (−1 — пробы не было/неуспешна). */
        val lastLatencyMs: Long = 0L,
        /** Сколько проб подряд не прошло (0 — связь есть). */
        val failures: Int = 0,
        /** Проба выполняется прямо сейчас. */
        val checking: Boolean = false,
        /** Аккаунт не настроен (нет сервера) — пробовать нечем, это НЕ сбой. */
        val noServer: Boolean = false,
    ) {
        /** Проблемы со связью: 2+ пробы подряд не прошли. */
        val degraded: Boolean get() = failures >= 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(HealthState())
    val state: StateFlow<HealthState> = _state

    @Volatile private var running = false
    private var job: Job? = null

    /** Поставщик базового URL сервера (Repository.baseForProbe). */
    @Volatile private var baseUrlProvider: (() -> String?)? = null

    /** Запустить монитор (идемпотентно). Вызывается из Repository.init. */
    fun start(baseUrl: () -> String?) {
        baseUrlProvider = baseUrl
        if (running) return
        running = true
        job = scope.launch {
            while (running) {
                if (!TunnelManager.isUp) {
                    // туннель не запущен — состояние сбрасываем, чтобы в UI
                    // не висело «5с назад» от прошлого прогона
                    if (_state.value != HealthState()) _state.value = HealthState()
                    delay(IDLE_POLL_MS)
                    continue
                }
                // Аккаунт не настроен — пробы через туннель бессмысленны
                // (некуда ходить); это НЕ сбой связи, помечаем отдельно
                val base = baseUrlProvider?.invoke()?.trimEnd('/')?.takeIf { it.isNotBlank() }
                if (base == null) {
                    if (!_state.value.noServer) _state.value = HealthState(noServer = true)
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                if (_state.value.noServer) _state.value = HealthState()
                probeOnce(base)
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /** Остановить монитор и сбросить состояние. */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        _state.value = HealthState()
    }

    /** Одна проба через туннель: GET {base}/api/health. */
    private suspend fun probeOnce(base: String) {
        val prevFailures = _state.value.failures
        _state.value = _state.value.copy(checking = true)
        val t0 = System.currentTimeMillis()
        val ok = runCatching { probe(base) }.getOrDefault(false)
        val latency = System.currentTimeMillis() - t0
        _state.value = _state.value.copy(
            checking = false,
            lastCheckAt = System.currentTimeMillis(),
            lastOk = ok,
            lastLatencyMs = if (ok) latency else -1L,
            failures = if (ok) 0 else (_state.value.failures + 1).coerceAtMost(99),
        )
        // v11.3: результат пробы — ТОЛЬКО в журнал туннеля. Пишем первую
        // неудачу, отметки на 2/3 подряд (порог «нет связи» и порог
        // переперевыбора в АВТО), далее каждую 5-ю подряд, и восстановление.
        // Успехи «в ритме» журнал не засоряют.
        val f = _state.value.failures
        when {
            !ok && (f == 1 || f == 2 || f == 3 || f % 5 == 0) ->
                TunnelManager.appendHealthLog("проба связи не прошла ($f подряд)")
            ok && prevFailures > 0 ->
                TunnelManager.appendHealthLog("связь восстановлена ($latency мс)")
        }
    }

    /**
     * Сам запрос. ВАЖНО: клиент строится от [HttpRouter.client] — значит
     * трафик идёт через SOCKS5 движка (туннель), а DNS резолвится внутри
     * туннеля. Если туннель мёртв — соединение не откроется и проба
     * провалится по callTimeout (именно это и есть «нет связи»).
     */
    private fun probe(base: String): Boolean {
        val client: OkHttpClient = HttpRouter.client().newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("$base/api/health").get().build()
        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    /** Человекочитаемая строка «сколько секунд назад была проверка». */
    fun secondsAgo(state: HealthState = _state.value): Int {
        if (state.lastCheckAt == 0L) return -1
        return ((System.currentTimeMillis() - state.lastCheckAt) / 1000L).toInt()
    }
}
