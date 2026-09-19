package com.tunnelmessenger.desktop.tray

import com.tunnelmessenger.desktop.data.local.AppDirs
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * СБОРКА 9: интеграции десктопа — СИСТЕМНЫЙ ТРЕЙ, ФОН, УВЕДОМЛЕНИЯ,
 * АВТОЗАПУСК. Порт Android-модели «приложение живёт в фоне и присылает
 * уведомления» на Windows и Linux:
 *
 *  - ТРЕЙ: java.awt.SystemTray (Windows — нативный шелл; Linux — XEmbed-трей:
 *    KDE/XFCE/MATE/Cinnamon; в чистом GNOME иконки трея отключены — там
 *    используется notify-send для уведомлений, а окно закрывается по-старому).
 *  - ФОН: закрытие окна сворачивает приложение в трей — WebSocket живёт,
 *    уведомления приходят (аналог foreground-службы на Android).
 *  - УВЕДОМЛЕНИЯ: Linux — notify-send (DBus, работает и без трея), фолбэк —
 *    balloon трея; Windows — balloon/toast трея. Показываются, когда окно
 *    скрыто/свернуто/без фокуса (как на Android — когда приложение в фоне).
 *  - АВТОЗАПУСК: Windows — HKCU\...\Run (reg, без прав администратора);
 *    Linux — XDG-файл ~/.config/autostart/tunnelmessenger.desktop.
 *  - APPLOCK: одиночный экземпляр (FileLock) — автозапуск + ручной запуск
 *    не дают двух копий (SQLite этого не переживёт).
 */
object DesktopIntegrations {

    // ------------------------------------------------------------ window state

    /** Состояние окна для решения «показывать ли уведомление». */
    @Volatile var windowFocused: Boolean = true
    @Volatile var windowIconified: Boolean = false
    @Volatile var windowHidden: Boolean = false

    /** Окно в фоне (скрыто в трей / свернуто / без фокуса) — уведомления показываем. */
    val windowInBackground: Boolean
        get() = windowHidden || windowIconified || !windowFocused

    // ------------------------------------------------------------------- app lock

    private var lockChannel: java.nio.channels.FileChannel? = null

    /**
     * Одиночный экземпляр. Возвращает false, если копия уже запущена
     * (вторая копия не нужна — первая уже в трее/на экране).
     */
    fun acquireSingleInstanceLock(): Boolean {
        return runCatching {
            AppDirs.ensureDirs()
            val f = File(AppDirs.dataDir, "app.lock")
            val ch = java.io.RandomAccessFile(f, "rw").channel
            val lock = ch.tryLock()
            if (lock == null) {
                ch.close()
                false
            } else {
                lockChannel = ch
                true
            }
        }.getOrDefault(true) // не смогли заблокировать (FS не умеет) — работаем
    }

    // --------------------------------------------------------------------- tray

    private var trayIcon: TrayIcon? = null

    /** Доступен ли системный трей (Windows — да; Linux — зависит от DE). */
    val trayAvailable: Boolean
        get() = runCatching {
            !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
        }.getOrDefault(false)

    /**
     * СБОРКА 9: иконка трея РЕАЛЬНО добавлена (SystemTray.add прошёл).
     * Закрытие окна прячет приложение в трей только при этом условии —
     * иначе в DE без трея окно можно потерять навсегда.
     */
    val trayActive: Boolean
        get() = trayIcon != null

    private fun trayImage(): Image? = runCatching {
        val stream = DesktopIntegrations::class.java.classLoader
            .getResourceAsStream("icons/icon.png") ?: return@runCatching null
        val img = ImageIO.read(stream)
        val size = runCatching { SystemTray.getSystemTray().trayIconSize }.getOrDefault(null)
        val w = size?.width?.takeIf { it > 0 } ?: 24
        val h = size?.height?.takeIf { it > 0 } ?: 24
        img.getScaledInstance(w, h, Image.SCALE_SMOOTH)
    }.getOrNull()

    /**
     * Показать иконку в трее. Идемпотентно. Клик по иконке — открыть окно;
     * в меню — «Открыть» и «Выход».
     */
    fun ensureTray(onOpen: () -> Unit, onQuit: () -> Unit) {
        if (!trayAvailable) return
        if (trayIcon != null) return
        runCatching {
            val image = trayImage() ?: return
            val menu = PopupMenu()
            val open = MenuItem("Открыть Tunnel Messenger")
            open.addActionListener { SwingUtilities.invokeLater { onOpen() } }
            menu.add(open)
            menu.addSeparator()
            val quit = MenuItem("Выход")
            quit.addActionListener { SwingUtilities.invokeLater { onQuit() } }
            menu.add(quit)
            val icon = TrayIcon(image, "Tunnel Messenger", menu)
            icon.isImageAutoSize = true
            icon.addActionListener { SwingUtilities.invokeLater { onOpen() } }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            System.err.println("[tray] иконка добавлена в системный трей")
        }.onFailure {
            System.err.println("[tray] не удалось добавить иконку: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Убрать иконку трея (выключили «работать в фоне»). */
    fun removeTray() {
        runCatching {
            trayIcon?.let { SystemTray.getSystemTray().remove(it) }
            trayIcon = null
        }
    }

    // ------------------------------------------------------------- notifications

    private val isWindows: Boolean = AppDirs.isWindows

    /** Путь к PNG-иконке для notify-send/autostart (извлекается один раз). */
    fun notifyIconPath(): String? = runCatching {
        val dir = File(AppDirs.dataDir, "cache")
        if (!dir.isDirectory) dir.mkdirs()
        val target = File(dir, "icon.png")
        if (!target.isFile) {
            val stream = DesktopIntegrations::class.java.classLoader
                .getResourceAsStream("icons/icon.png") ?: return@runCatching null
            stream.use { it.copyTo(target.outputStream()) }
        }
        target.absolutePath
    }.getOrNull()

    /**
     * Системное уведомление. Linux: notify-send (перекрывает отсутствие трея
     * в GNOME), фолбэк — balloon трея. Windows: balloon/toast трея.
     * Вызывающий код (Repository) уже учёл флаги приватности и фоновость окна.
     */
    fun notify(title: String, body: String) {
        // 1) Linux: notify-send — работает без иконки в трее (GNOME и др.)
        if (!isWindows) {
            val sent = runCatching {
                val icon = notifyIconPath()
                val cmd = mutableListOf("notify-send", "-a", "Tunnel Messenger")
                if (icon != null) cmd += listOf("-i", icon)
                cmd += listOf("-t", "6000", title, body)
                val p = ProcessBuilder(cmd).start()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                p.exitValue() == 0
            }.getOrDefault(false)
            if (sent) return
        }
        // 2) balloon трея (Windows — основной путь; Linux — фолбэк)
        runCatching {
            trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
        }.onFailure {
            System.err.println("[notify] не удалось показать уведомление: ${it.message}")
        }
    }

    // ----------------------------------------------------------------- autostart

    private const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val REG_VALUE = "TunnelMessenger"

    private fun autostartFile(): File =
        File(File(System.getProperty("user.home"), ".config/autostart"), "tunnelmessenger.desktop")

    /**
     * Команда запуска текущего экземпляра: «<java> -jar <fat-jar>» (портативный
     * запуск) или сам exe (jpackage). null — не удалось определить (запуск из
     * IDE/gradle): переключатель автозапуска тогда недоступен.
     */
    fun launchCommand(): String? = runCatching {
        val codeSource = File(
            DesktopIntegrations::class.java.protectionDomain.codeSource.location.toURI(),
        )
        val selfBin = ProcessHandle.current().info().command().orElse(null)
        if (isWindows) {
            val jarPath = codeSource.absolutePath
            if (jarPath.endsWith(".exe", ignoreCase = true)) {
                return@runCatching "\"$jarPath\""
            }
            if (jarPath.endsWith(".jar", ignoreCase = true) && selfBin != null) {
                // javaw.exe — без консоли; лежит рядом с java.exe
                val javaw = File(File(selfBin).parentFile, "javaw.exe")
                val exe = if (javaw.isFile) javaw.absolutePath else selfBin
                return@runCatching "\"$exe\" -jar \"$jarPath\""
            }
            null
        } else {
            if (codeSource.absolutePath.endsWith(".jar") && selfBin != null) {
                "$selfBin -jar \"${codeSource.absolutePath}\""
            } else {
                null
            }
        }
    }.getOrNull()

    /** Автозапуск включён? (реальное состояние системы). */
    fun isAutostartEnabled(): Boolean = runCatching {
        if (isWindows) {
            val p = ProcessBuilder("reg", "query", REG_KEY, "/v", REG_VALUE).start()
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            p.exitValue() == 0
        } else {
            autostartFile().isFile
        }
    }.getOrDefault(false)

    /** Включить/выключить автозапуск. Возвращает false при неудаче. */
    fun setAutostartEnabled(on: Boolean): Boolean = runCatching {
        if (isWindows) {
            if (on) {
                val cmd = launchCommand() ?: return@runCatching false
                val p = ProcessBuilder(
                    "reg", "add", REG_KEY, "/v", REG_VALUE, "/t", "REG_SZ", "/d", cmd, "/f",
                ).start()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                val ok = p.exitValue() == 0
                System.err.println("[autostart] reg add → $ok ($cmd)")
                ok
            } else {
                val p = ProcessBuilder("reg", "delete", REG_KEY, "/v", REG_VALUE, "/f").start()
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                // отсутствие записи — тоже «выключено» (успех)
                true
            }
        } else {
            val f = autostartFile()
            if (on) {
                val cmd = launchCommand() ?: return@runCatching false
                if (!f.parentFile.isDirectory) f.parentFile.mkdirs()
                val icon = notifyIconPath()
                val content = buildString {
                    appendLine("[Desktop Entry]")
                    appendLine("Type=Application")
                    appendLine("Name=Tunnel Messenger")
                    appendLine("Comment=Мессенджер внутри VPN-туннеля")
                    appendLine("Exec=$cmd")
                    if (icon != null) appendLine("Icon=$icon")
                    appendLine("Terminal=false")
                    appendLine("Categories=Network;InstantMessaging;")
                    appendLine("X-GNOME-Autostart-enabled=true")
                }
                f.writeText(content)
                System.err.println("[autostart] записан ${f.path}: Exec=$cmd")
                true
            } else {
                if (f.isFile) f.delete()
                true
            }
        }
    }.getOrDefault(false)
}

/** Регистрация слушателей окна (фокус/сворачивание/скрытие) для уведомлений. */
fun bindWindowStateListeners(window: Window) {
    window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
        override fun windowGainedFocus(e: WindowEvent?) {
            DesktopIntegrations.windowFocused = true
        }

        override fun windowLostFocus(e: WindowEvent?) {
            DesktopIntegrations.windowFocused = false
        }
    })
    window.addWindowStateListener { e ->
        DesktopIntegrations.windowIconified =
            (e.newState and java.awt.Frame.ICONIFIED) != 0
    }
    window.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentHidden(e: java.awt.event.ComponentEvent?) {
            DesktopIntegrations.windowHidden = true
        }

        override fun componentShown(e: java.awt.event.ComponentEvent?) {
            DesktopIntegrations.windowHidden = false
        }
    })
}
