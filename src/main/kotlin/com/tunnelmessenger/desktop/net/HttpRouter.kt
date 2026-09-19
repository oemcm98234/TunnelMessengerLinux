package com.tunnelmessenger.desktop.net

import com.tunnelmessenger.desktop.tunnel.ProxySpec
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Единая фабрика OkHttpClient для REST и WebSocket.
 *
 * Пока туннель поднят, весь трафик мессенджера идёт через собственный
 * SOCKS5-клиент (Socks5SocketFactory) в локальный прокси движка
 * (tunnel-core) на 127.0.0.1 — так приложение «заворачивает само
 * себя» в туннель без системного VPN.
 *
 * ВАЖНО (v11): SOCKS5 ВСЕГДА даёт AWG-движок — и в режиме AmneziaWG,
 * и в режиме FreeTurn (движок запускается поверх UDP-релея FreeTurn).
 * UDP-релей FreeTurn сам по себе НЕ SOCKS5 — подключаться к нему напрямую
 * нельзя. Поэтому DNS тоже всегда через туннель (Socks5.dns): имена
 * передаются в SOCKS5 неразрешёнными и резолвятся внутри туннеля
 * (приватность, DNS не утекает к провайдеру).
 */
object HttpRouter {

    @Volatile private var cached: Pair<ProxySpec?, OkHttpClient>? = null

    fun client(): OkHttpClient {
        val spec = TunnelManager.currentProxy
        cached?.let { (spec0, client) -> if (spec0 == spec) return client }
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // heartbeat для WebSocket
            // v13-d: параллельная передача файлов (старт 8..12, разгон до
            // 24..32 потоков) — держим до 64 keep-alive соединений (дефолт 5
            // закрывал простаивающие, каждая новая передача открывала сокеты
            // через SOCKS5 заново) и страховочные лимиты Dispatcher.
            .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 64
            })
        // ВАЖНО (приватность): SOCKS-фабрика ставится ВСЕГДА. При выключенном
        // туннеле любое соединение немедленно падает с ошибкой «туннель не
        // подключён» — трафик НЕ уходит в прямую сеть, реальный IP не
        // раскрывается (мессенджер доступен только изнутри туннеля).
        builder
            .socketFactory(Socks5SocketFactory { TunnelManager.currentProxy })
        // DNS всегда через туннель: движок (SOCKS5) резолвит имена сам.
        // Dns.SYSTEM в FreeTurn-режиме ломал доступ по имени сервера —
        // порт 80/443 сервера отвечают только на адресах awg0.
        builder.dns(Socks5.dns)
        return builder.build().also { cached = spec to it }
    }

    /** Сбросить кэш (вызывается TunnelManager при изменении прокси). */
    fun invalidate() {
        // v12 audit: каждый client() строит НОВЫЙ OkHttpClient; старый при
        // смене прокси выбрасывался вместе с держимым пулом keep-alive сокетов
        // (висели до таймаута пула). Освобождаем idle-соединения явно —
        // активные (в т.ч. живой WS на старом клиенте) не трогаются.
        cached?.second?.let { old ->
            runCatching { old.connectionPool.evictAll() }
        }
        cached = null
    }
}
