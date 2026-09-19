package com.tunnelmessenger.desktop.ui.theme

import java.util.concurrent.TimeUnit

/**
 * v15: определение СИСТЕМНОЙ темы (тёмная/светлая) для авто-режима темы.
 *
 * Windows: реестр HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\
 *   Personalize\AppsUseLightTheme (через `reg query`; 0x0 = тёмная,
 *   0x1 = светлая, отсутствие ключа = светлая — старые Windows).
 * Linux/прочие Unix: gsettings org.gnome.desktop.interface
 *   (color-scheme = 'prefer-dark'/'prefer-light'; иначе gtk-theme с "dark").
 *
 * Результат кэшируется на 10 секунд (запрос к реестру/gsettings — это
 * запуск внешнего процесса; дёргать его на каждую композицию нельзя).
 * Не удалось определить → тёмная (прежний вид приложения).
 */
object SystemTheme {

    @Volatile private var cachedDark: Boolean? = null
    @Volatile private var cachedAt: Long = 0L

    fun isSystemDark(): Boolean {
        val now = System.currentTimeMillis()
        val c = cachedDark
        if (c != null && now - cachedAt < 10_000L) return c
        val dark = detect()
        cachedDark = dark
        cachedAt = now
        return dark
    }

    private fun detect(): Boolean = runCatching {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) windowsDark() else linuxDark()
    }.getOrDefault(true)

    private fun runCmd(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = runCatching { p.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrNull()
        runCatching { p.waitFor(3, TimeUnit.SECONDS) }
        runCatching { p.destroy() }
        out
    }.getOrNull()

    private fun windowsDark(): Boolean {
        // AppsUseLightTheme: 0x1 = светлая, 0x0 = тёмная
        val out = runCmd(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ) ?: return true
        val m = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(out)
            ?: return true // ключ не найден — считаем тёмной (как выглядело приложение всегда)
        return m.groupValues[1].toIntOrNull(16) == 0
    }

    private fun linuxDark(): Boolean {
        // 1) явный color-scheme (GNOME 42+)
        runCmd("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.let { out ->
            when {
                out.contains("prefer-dark", ignoreCase = true) -> return true
                out.contains("prefer-light", ignoreCase = true) -> return false
            }
        }
        // 2) gtk-theme с "dark" в имени
        runCmd("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.let { out ->
            val t = out.trim().lowercase()
            if (t.contains("dark") && !t.contains("-light")) return true
            return false
        }
        return true // не определили — тёмная (прежний вид)
    }
}
