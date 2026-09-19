package com.tunnelmessenger.desktop.tunnel

/**
 * Режим работы туннеля (v11 — FreeTurn).
 *
 * Tunnel Messenger поддерживает ДВА способа доставки трафика до сервера:
 *
 * 1. **AMNEZIA** — встроенный userspace AmneziaWG-движок (tunnel-core).
 *    Трафик идёт через локальный SOCKS5-прокси туннеля AmneziaWG.
 *    Самый быстрый путь — прямой UDP до сервера через WireGuard с обфускацией.
 *    https://github.com/amnezia-vpn/amneziawg-go
 *
 * 2. **FREELAY** (FreeTurn relay) — UDP/TCP поверх TURN через WebRTC-реле
 *    (VK Calls), серверный бэкенд AmneziaWG 3.1.
 *    Клиент слушает `127.0.0.1:9000`, трафик маскируется под VK-звонок
 *    (RTP/OPUS AEAD), релеается через TURN-сервера ВК в уже существующий
 *    AmneziaWG-туннель на VPS (UDP-Relay режим по умолчанию).
 *    Альтернативно — Direct AWG: freeturn-awg Docker-контейнер с портом
 *    51820/udp открыт, AmneziaWG 3.1 обфускация (Jc/Jmin/Jmax/S1-S4/H1-H4/HPK).
 *    https://github.com/samosvalishe/free-turn-proxy
 *
 * **AUTO** — v12: автоматический выбор и МГНОВЕННОЕ переключение:
 *   - Проверяются СРАЗУ ОБА пути: AmneziaWG-движок и FreeTurn-релей стартуют параллельно.
 *   - Если работают оба → используется AMNEZIA (приоритет — быстрее),
 *     а релей FreeTurn остаётся ТЁПЛЫМ РЕЗЕРВОМ (не гасится).
 *   - Если активный путь умирает → переключение за секунды: проба AmneziaWG
 *     ≤8с, затем движок сразу на тёплом релее (TURN уже поднят, капча уже
 *     пройдена ядром автоматически).
 *   - Пока туннель работает через FreeTurn — каждые 10 минут безболезненная
 *     проба AmneziaWG (релей не рвётся); заработал — мгновенное переключение.
 *   - Если НИ ОДИН протокол не работает — после нескольких подряд провальных
 *     циклов все движки останавливаются и туннель выключается (до ручного включения).
 *
 * Переключение происходит в [TunnelManager] при старте туннеля и при
 * изменении режима в настройках. UI — в TunnelScreen.
 */
enum class ProtocolMode(val displayName: String, val description: String) {
    // v12: краткие нейтральные описания — без механики переключения
    AUTO("Авто", "Оба протокола, выбирается рабочий"),
    AMNEZIA("AmneziaWG", "Прямой туннель AmneziaWG"),
    FREELAY("FreeTurn", "Через TURN-релей (VK Calls)");

    companion object {
        /** Ключ в config.json для хранения выбранного режима. */
        const val PREFS_KEY = "tunnel_protocol_mode"

        /**
         * Парсит строковое значение в [ProtocolMode].
         * Принимает как актуальное имя `FREELAY`, так и прежнее `FREETURN`
         * (case-insensitive); значение из предыдущей версии переписывается
         * в конфиг на актуальное. Неизвестные строки → [AUTO].
         */
        fun fromString(name: String): ProtocolMode =
            when (name.uppercase()) {
                "AUTO" -> AUTO
                "AMNEZIA" -> AMNEZIA
                "FREELAY", "FREETURN" -> FREELAY
                else -> AUTO
            }
    }
}
